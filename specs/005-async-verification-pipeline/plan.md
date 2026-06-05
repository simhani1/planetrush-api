# Implementation Plan: Async Verification Pipeline — 챌린지 인증 비동기 처리 노출

**Branch**: `005-async-verification-pipeline` | **Date**: 2026-06-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-async-verification-pipeline/spec.md`

## Summary

기존 동기 챌린지 인증 컨트롤러를 비동기 표면으로 전환해 컨슈머(이미지 유사도 추론) 가용성에 응답이 묶이지 않게 하고, 컨슈머 결과 callback 을 받아 결과를 저장하는 내부 엔드포인트와 클라이언트 폴링 엔드포인트를 추가한다.

발행 경로는 **이미 존재하는 Outbox 인프라**(Spec 001 안전망 + Spec 002 republisher) 를 그대로 재사용한다 — 헌법 IV(메시지 발행은 Outbox 경유 강제)에 따라 컨트롤러가 Redis Stream 을 직접 호출하지 않는다. 발행 흐름:

```
POST /api/v1/verify/planets/{planet-id}
  → @Transactional 안에서 (a) VerificationRequest(PENDING) INSERT
                       + (b) OutboxEvent(payload=컨슈머 계약) INSERT
  → 트랜잭션 커밋
  → AFTER_COMMIT 리스너 → VerificationRedisStreamPublisher 가 verify:requests Stream 에 XADD
  → 발행 실패 시 OutboxRepublisher 폴러가 자동 재발행 (Spec 002)
→ 즉시 202 Accepted + { requestId, status: "PENDING" }
```

컨슈머 callback 흐름:

```
POST /api/v1/internal/verification-results { requestId, similarityScore?, verified?, error?, message? }
  → @Transactional 안에서 (락 없음 — R-002/R-003):
     1. UPDATE verification_request SET status=..., ... WHERE id=? AND status='PENDING'
        - 영향 행 수 0: 이미 종착 또는 없는 ID → 200 멱등 흡수 (FR-004a + FR-011)
        - 영향 행 수 1: 정상 전이, 2단계 진행
     2. SUCCESS/FAIL: VerificationRecord INSERT 시도
        - 정상: 저장 1건
        - DataIntegrityViolationException (uniq_member_planet_date): 다른 PENDING 의 callback 이 먼저 저장 → skip (FR-004b 흡수)
     3. ERROR: VerificationRecord 저장 단계 건너뛰기 (clarify Q2)
  → 200 OK
```

클라이언트 폴링: `GET /api/v1/verify/{requestId}` → 현재 상태 + 결과.

신규 entity 1개(`VerificationRequest`), 신규 컨트롤러 1개(`/internal/verification-results`), 기존 컨트롤러 수정 1개(202 응답으로 전환), 기존 발행 인프라 0건 수정(payload 빌더만 컨슈머 계약 충족하도록 보강).

## Technical Context

**Language/Version**: Java 21, Spring Boot 3.2.7 (변경 없음)

**Primary Dependencies**: 신규 의존성 **0건**. 사용 요소 전부 기존 보유 —
- Spring Web (REST 컨트롤러)
- Spring Data JPA (`VerificationRequestRepository` 신규)
- 기존 Outbox 인프라: `VerificationExternalEventRecorder` / `OutboxEvent` / AFTER_COMMIT 리스너 / `VerificationRedisStreamPublisher`
- 기존 Spec 002 `OutboxRepublisher` (장애 복구 — 본 스펙은 수정 없음)
- Jackson `ObjectMapper` (callback 페이로드 역직렬화, outbox payload 직렬화)
- JUnit 5 + Testcontainers + Awaitility (이미 Spec 001 의존성)

**Storage**:
- MySQL — 신규 테이블 `verification_request` (별도 entity), 기존 `verification_record` 에 unique 인덱스 `(member_id, planet_id, DATE(upload_date))` 추가, `outbox_event` 변경 없음.
- Redis Stream — 기존 `verify:requests` 스트림(컨슈머 계약 §3-1) 재사용.

**Testing**: JUnit 5 + Testcontainers (MySQL 8 + Redis 7). Spec 001 의 `IntegrationTest` 베이스 상속. Awaitility 로 비동기 종착 대기.

**Target Platform**: Spring Boot 단일 모듈 웹 서비스. 컨슈머는 별도 컨테이너(Python + PyTorch — `planetrush_consumer/`).

**Project Type**: 단일 모듈. 신규 코드는 `verification` 도메인의 신규 하위 패키지(`verification/request`, `verification/result`).

**Performance Goals**: 본 스펙은 **단대단 SLO 정량값을 두지 않는다**(clarify Q1). 결과 처리가 비동기(컨슈머 추론)라 p95 가 신뢰성 측정에 의미를 주지 않는다. 인증 요청 응답 경로 안에서 컨슈머 호출이 없음을 절차(코드 경로) 단계 검증으로 갈음.

**Constraints**:
- 메시지 발행 Outbox 경유 강제 (헌법 IV).
- callback 핸들러는 두 종류 멱등 모두 보장 (FR-004 — `requestId` 단위 + 사용자·챌린지·날짜 단위).
- ERROR 종착 시 `VerificationRecord` 미저장 (clarify Q2).
- 시크릿 로그 금지 (헌법 V).
- `verification_record` 에 unique 인덱스 추가 시 기존 데이터의 중복(같은 사용자가 같은 챌린지에 같은 날짜로 2건 이상 record 보유) 가능성 점검 필요 — research R-003.

**Scale/Scope**:
- 신규 파일 ~8개 (entity 1, repo 1, 신규 service 1, 컨트롤러 1, request DTO 1, 응답 DTO 2 — 폴링·callback, 예외 1).
- 수정 파일 ~5개 (`VerificationController`, `VerificationServiceImpl`, `VerificationExternalEventRecorder` payload 빌더, `application*.yml` callback URL 외부화, `VerificationRecord` unique 인덱스).
- 통합 테스트 ~4개 + 슬라이스 테스트 ~2개.

## Constitution Check

*GATE: Phase 0 진입 전 통과 필수. Phase 1 후 재검토.*

| 원칙 | 적용 | 본 스펙에서의 의미 | 판정 |
|---|---|---|---|
| **I. Testcontainers (NON-NEGOTIABLE)** | 적용 | US1/US2/US3 통합 테스트가 `IntegrationTest`(MySQL+Redis Testcontainers) 베이스 상속. chaos test(컨슈머 다운/복구 시뮬레이션), callback 멱등, unique 제약 충돌은 실제 MySQL/Redis 필수. | ✅ Pass |
| **II. 외부 의존 어댑터 격리** | 적용 | Redis 발행은 기존 `VerificationRedisStreamPublisher`(infra/publisher 어댑터) 재사용. 도메인/서비스 레이어가 `RedisTemplate` 직접 호출 0. callback 컨트롤러는 외부 시스템→Spring 인입이므로 HTTP 어댑터 역할만 수행하고 도메인 로직은 서비스에 위임. | ✅ Pass |
| **III. QueryDSL Projections** | 적용 | `GET /api/v1/verify/{requestId}` 응답 DTO는 QueryDSL `Projections.constructor`로 `VerificationRequest`에서 직접 매핑(서비스에서 entity getter 호출 0). | ✅ Pass |
| **IV. Outbox 강제 (NON-NEGOTIABLE)** | 적용 | 본 스펙의 핵심 적용 항목. 컨트롤러/서비스가 `RedisTemplate` 직접 호출 금지. 발행은 `VerificationExternalEventRecorder` 경유 → `OutboxEvent` INSERT → AFTER_COMMIT 리스너 → publisher. BRIEF 의 "Spring 컨트롤러가 직접 XADD" 패턴은 채택하지 않음. | ✅ Pass |
| **V. 시크릿 로그 금지 (NON-NEGOTIABLE)** | 적용 | 이미지 서명 URL 토큰·callback 페이로드·requestId 평문 로그 금지. Spec 001 마스킹 컨버터(PatternConverter)가 1차 방어, 본 스펙 신규 로그는 의도적으로 시크릿/토큰 키워드 미사용. `verifySecretLogScan` 게이트 통과. | ✅ Pass |
| **VI. 듀얼 AI 리뷰** | 적용 | PR 단계에서 Claude + Codex 리뷰 첨부. | ✅ Pass (PR 단계) |
| **VII. 인수 기준 자동 테스트** | 적용 | SC-001~007 전부 자동 테스트로 검증, 매핑은 tasks.md 에 명시. 수동 검증 0. | ✅ Pass |

**결과**: 전 게이트 통과. NON-NEGOTIABLE 3종(I·IV·V) 위반 0. Phase 0 진입 가능.

**라이브러리 도입 규칙**: 신규 의존성 0건 — 본 항목 해당 없음.

## Project Structure

### Documentation (this feature)

```text
specs/005-async-verification-pipeline/
├── spec.md                       # 작성 완료
├── plan.md                       # 본 파일
├── research.md                   # Phase 0 산출물 (R-001~R-006)
├── data-model.md                 # Phase 1 산출물 (VerificationRequest entity + lifecycle)
├── quickstart.md                 # Phase 1 산출물 (로컬 실행/chaos 재현 가이드)
├── contracts/                    # Phase 1 산출물 (REST + Stream 메시지 계약)
│   ├── rest-api.md
│   └── stream-message.md
├── checklists/requirements.md    # /speckit-specify 산출
└── tasks.md                      # /speckit-tasks 단계 생성
```

### Source Code (repository root)

```text
src/main/java/com/planetrush/planetrush/
├── verification/
│   ├── controller/
│   │   ├── VerificationController.java         # 수정: 202 응답으로 전환, 인증 요청 발행 호출
│   │   ├── internal/
│   │   │   ├── InternalVerificationResultController.java  # 신규: POST /api/v1/internal/verification-results
│   │   │   └── req/VerificationCallbackReq.java           # 신규: callback 페이로드 DTO + Validation
│   │   ├── VerificationStatusController.java   # 신규: GET /api/v1/verify/{requestId}
│   │   └── res/VerificationStatusRes.java      # 신규: 폴링 응답 DTO
│   ├── domain/
│   │   ├── VerificationRecord.java             # 수정: (member_id, planet_id, DATE(upload_date)) unique 인덱스
│   │   ├── VerificationRequest.java            # 신규: entity (UUID PK, status, 결과 필드)
│   │   └── VerificationRequestStatus.java      # 신규: enum (PENDING/SUCCESS/FAIL/ERROR)
│   ├── repository/
│   │   ├── VerificationRequestRepository.java          # 신규: JPA 인터페이스
│   │   └── custom/
│   │       └── VerificationRequestRepositoryCustom.java # 신규: QueryDSL Projection 조회
│   ├── service/
│   │   ├── VerificationServiceImpl.java        # 수정: PENDING request INSERT + Outbox 기록
│   │   ├── VerificationResultService.java      # 신규: callback 멱등 처리 진입점
│   │   ├── VerificationStatusService.java      # 신규: 폴링 응답 조회
│   │   └── dto/
│   │       ├── VerificationCallbackCommand.java # 신규: 서비스 인입 DTO
│   │       └── VerificationStatusDto.java       # 신규: 폴링 응답 도메인 DTO
│   └── exception/
│       └── VerificationRequestNotFoundException.java  # 신규
└── outbox/
    ├── VerificationExternalEventRecorder.java  # 수정: payload 에 requestId/callbackUrl/threshold 추가
    └── dto/OutboxRecordCommand.java            # 수정: requestId 필드 추가

src/main/resources/
├── application.yml                # 수정: app.verification.* (callback-url, threshold, stream-name)
├── application-dev.yml / application-prod.yml  # 수정: 환경별 callback URL
└── application-test.yml           # 수정: 테스트 callback URL (Testcontainers MockMvc)

src/test/java/com/planetrush/planetrush/verification/
├── VerificationAsyncFlowIntegrationTest.java       # SC-001 단대단(정상 흐름)
├── VerificationConsumerOutageIntegrationTest.java  # SC-003 chaos: 컨슈머 미가용 시 PENDING 보존 + 복구 후 종착
├── VerificationCallbackIdempotencyTest.java        # SC-005a: requestId 단위 멱등
├── VerificationDailyIdempotencyTest.java           # SC-005b: 사용자·챌린지·날짜 단위 멱등
├── VerificationOutboxRepublisherIntegrationTest.java  # SC-004: Stream 발행 실패→Republisher 복구 (Spec 002 정합)
├── VerificationControllerSliceTest.java            # SC-002: 응답 경로에 컨슈머 호출 0 (@WebMvcTest)
└── InternalVerificationResultControllerSliceTest.java  # callback 페이로드 검증·400 응답
```

**Structure Decision**:
- `verification/` 도메인 안에서 신규 코드를 분류한다. 컨트롤러는 사용자용(`controller/`)과 내부용(`controller/internal/`)을 경로로 구분해 헌법 IV(외부 직접 발행 차단) 정신을 디렉토리에 반영한다.
- `VerificationRequest` 는 별도 entity 로 분리(`VerificationRecord` 확장 X). 이유: 라이프사이클(PENDING→종착)·식별자 체계(UUID vs IDENTITY long)·ERROR 시 미저장 정책이 두 모델을 분리해야 깔끔하게 표현됨 — research R-001.
- callback 핸들러 멱등은 **SQL 제약 두 개로 끝낸다** — 요청 ID 단위는 `UPDATE ... WHERE id=? AND status='PENDING'` optimistic update (영향 행 수 0 → 멱등 흡수), 사용자·챌린지·날짜 단위는 `(member_id, planet_id, DATE(upload_date))` unique 제약 (DuplicateKeyException catch → record skip). 비관적 락·낙관적 락(`@Version`)·1차 SELECT 가드 모두 미사용 — research R-002·R-003.

## Constitution Alignment (Governance §1)

- **원칙 IV(NON-NEGOTIABLE)**: 본 스펙의 가장 무거운 적용 항목. BRIEF 가 제시한 "컨트롤러 직접 XADD" 패턴은 의도적으로 채택하지 않고 기존 Outbox 인프라(Spec 002 republisher 포함)를 재사용. 컨트롤러/서비스 어디서도 `RedisTemplate.opsForStream().add()` 직접 호출 0.
- **원칙 I(NON-NEGOTIABLE)**: chaos·멱등·unique 충돌 검증은 실제 MySQL/Redis 필수 — Testcontainers 당위성. H2/Mock 대체 불가.
- **원칙 II**: callback HTTP 컨트롤러는 외부→Spring 인입의 표면 어댑터. 도메인 로직은 `VerificationResultService` 에 위임하고 컨트롤러는 페이로드 검증·DTO 매핑만 담당.
- **원칙 III**: 폴링 응답·callback 후속 조회 모두 QueryDSL `Projections.constructor` 로 entity → DTO 매핑. 서비스/컨트롤러에서 entity getter 호출 0.
- **원칙 V(NON-NEGOTIABLE)**: 이미지 서명 URL/callback payload/requestId 평문 로그 금지. Spec 001 마스킹 컨버터 활성. 신규 로그는 outbox event id·request id의 멱등 도달 사실 정도만 INFO 로 출력.
- **원칙 VI·VII**: PR/테스트 단계에서 점검.

## Complexity Tracking

> 위반 없음. 표 비움.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| (해당 없음) | — | — |

## Post-Design Constitution Re-Check

Phase 0·1 산출물(research.md, data-model.md, contracts/, quickstart.md) 작성 후 갱신:

- 신규 위반 없음. data-model 의 `VerificationRecord` unique 인덱스 추가는 기존 도메인 무결성 보강이며 헌법 어느 원칙과도 충돌하지 않음. ✅
- contracts/rest-api.md 의 `/api/v1/internal/*` 경로 분리는 헌법 IV 정신을 직접 인코딩 — 외부 호출은 `/api/v1/verify/*` 표면, 내부는 `/api/v1/internal/*` 표면. ✅
- 전 게이트 재통과. Phase 2(`/speckit-tasks`) 진입 가능.

## Post-Implementation Constitution Re-Check

구현 완료(tasks.md 전부 통과) 후 갱신:

| 원칙 | 구현 결과 |
|---|---|
| **I. Testcontainers (NON-NEGOTIABLE)** | (tasks 완료 후 기입) |
| **II. 외부 의존 어댑터 격리** | (tasks 완료 후 기입) |
| **III. QueryDSL Projections** | (tasks 완료 후 기입) |
| **IV. Outbox 강제 (NON-NEGOTIABLE)** | (tasks 완료 후 기입) |
| **V. 시크릿 로그 금지 (NON-NEGOTIABLE)** | (tasks 완료 후 기입) |
| **VI. 듀얼 AI 리뷰** | (PR 단계) |
| **VII. 인수 기준 자동 테스트** | (tasks 완료 후 기입) |

**Complexity Tracking** (구현 후 갱신): (작성 예정)

**구현 중 발견·결정 사항**: (작성 예정)

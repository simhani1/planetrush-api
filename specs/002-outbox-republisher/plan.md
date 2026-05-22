# Implementation Plan: Outbox Republisher Worker (At-Least-Once 보장 1/2)

**Branch**: `002-outbox-republisher` | **Date**: 2026-05-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-outbox-republisher/spec.md`

## Summary

`PENDING` 상태로 남은 `OutboxEvent`를 주기적으로 재발행하는 스케줄러 폴러를 도입한다.

현재 `VerificationRedisStreamPublisher.publish()`의 발행이 실패하면 `catch (RuntimeException)`이 로그만 찍어 `OutboxEvent`가 `PENDING`으로 영영 남고, `@TransactionalEventListener(AFTER_COMMIT)` 실행 전 앱이 죽어도 마찬가지다. 본 스펙은 이 누락분을 자동 복구하는 폴러를 추가해 **At-Least-Once 발행을 보장**한다.

컨슈머 멱등성(Spec 003)이 중복을 무해화한다는 전제로 "최소 1번"만 보장하면 충분하므로 — 재시도 카운터·지수 백오프·`FAILED` 상태를 **도입하지 않는다**. 무한 재시도는 `createdAt` 기반 시간 컷오프로 차단한다. **`OutboxEvent`/`OutboxStatus` 스키마 변경 0.**

## Technical Context

**Language/Version**: Java 21, Spring Boot 3.2.7 (변경 없음)

**Primary Dependencies**: 신규 의존성 **없음**. 사용 요소 전부 기존 보유 —
- Spring `@Scheduled` (스케줄링은 `PlanetrushApplication`에 `@EnableScheduling` 이미 활성)
- Spring Data JPA (`OutboxRepository`)
- Jackson `ObjectMapper` (`OutboxEvent.payload` JSON → `MessageCommand` 역직렬화)
- 기존 `VerificationMessagePublisher` / `VerificationRedisStreamPublisher` 재사용

**Storage**: MySQL `outbox_event` 테이블 (기존), Redis Stream (기존 발행 대상). 스키마 변경 없음.

**Testing**: JUnit 5 + Testcontainers. Spec 001의 `IntegrationTest` 베이스(MySQL 8.0.36 + Redis 7-alpine) 상속.

**Target Platform**: Spring Boot 단일 모듈 웹 서비스, 다중 인스턴스(HA) 운영 가정.

**Project Type**: 단일 모듈. 폴러는 `outbox/republisher` 신규 패키지.

**Performance Goals**:
- 정상 경로 평균 발행 지연 ≤ 폴링 주기 + 1초 (기본 5초 주기 → ≤ 6초, SC-005).
- 폴러 1사이클 처리량 상한 = 배치 크기(기본 100), 설정 가능.

**Constraints**:
- `OutboxEvent`/`OutboxStatus` 스키마 변경 금지 (FR-008).
- 폴러는 `test` 프로필에서 비활성 (FR-006) — 통합 테스트는 폴러 컴포넌트를 직접 호출해 검증.
- 다중 폴러 인스턴스 간 동일 outbox 이중 처리 0 (FR-002).

**Scale/Scope**: 신규 파일 ~3개(폴러 컴포넌트, 설정 properties, 락 쿼리), 수정 ~4개(`OutboxRepository`, `application*.yml`). 통합 테스트 ~3개 + 단위 테스트 ~2개.

## Constitution Check

*GATE: Phase 0 진입 전 통과 필수. Phase 1 후 재검토.*

| 원칙 | 적용 | 본 스펙에서의 의미 | 판정 |
|---|---|---|---|
| **I. Testcontainers (NON-NEGOTIABLE)** | 적용 | 카오스(SC-001)·동시성(SC-002)·컷오프(SC-003) 통합 테스트가 `IntegrationTest` 베이스 상속. SKIP LOCKED는 실제 MySQL 필수라 H2 불가 — Testcontainers 당위성 자체. | ✅ Pass |
| **II. 외부 의존 어댑터 격리** | 적용 | Redis 발행은 기존 `VerificationRedisStreamPublisher`(infra/publisher 어댑터) 재사용. 폴러는 도메인이 아닌 `outbox/republisher` 레이어. 도메인 코드가 외부 클라이언트 직접 호출 0. | ✅ Pass |
| **III. QueryDSL Projections** | N/A | 폴링은 `OutboxEvent` 엔티티 조회(DTO 매핑 아님). 락 쿼리는 native 또는 `@Lock` — 본 원칙은 Entity→DTO 매핑 규칙이라 해당 없음. | ✅ Pass (해당 없음) |
| **IV. Outbox 강제 (NON-NEGOTIABLE)** | 적용 | 본 스펙이 Outbox 인프라 자체를 강화. 발행은 전부 OutboxEvent 경유. 위반 불가능. | ✅ Pass |
| **V. 시크릿 로그 금지 (NON-NEGOTIABLE)** | 적용 | 폴러 신규 로그에 시크릿 키워드 미사용. `payload`(이미지 URL)는 시크릿 아님. Spec 001 마스킹 컨버터가 2차 방어. | ✅ Pass |
| **VI. 듀얼 AI 리뷰** | 적용 | PR 단계에서 Claude + Codex 리뷰 첨부. | ✅ Pass (PR 단계) |
| **VII. 인수 기준 자동 테스트** | 적용 | SC-001~005 전부 자동 테스트로 검증. tasks.md에 SC↔Test 매핑. | ✅ Pass |

**결과**: 전 게이트 통과. NON-NEGOTIABLE 3종 위반 0. Phase 0 진입 가능.

**라이브러리 도입 규칙**: 신규 의존성 0건 — 본 항목 해당 없음.

## Project Structure

### Documentation (this feature)

```text
specs/002-outbox-republisher/
├── spec.md                       # 작성 완료 (단순 버전)
├── plan.md                       # 본 파일
├── research.md                   # Phase 0 산출물 (R-001~)
├── quickstart.md                 # Phase 1 산출물 (폴러 운영·튜닝 가이드)
├── checklists/requirements.md    # /speckit-specify 산출
└── tasks.md                      # /speckit-tasks 단계 생성

# data-model.md — 스키마 변경 0 → 아래 plan에 "변경 없음" 명시 후 생략
# contracts/    — 신규 외부 API/계약 0 (내부 컴포넌트만) → 생략
```

### Source Code (repository root)

```text
src/main/java/com/planetrush/planetrush/
├── outbox/
│   ├── domain/OutboxEvent.java          # 변경 없음 (스키마 동결)
│   ├── repository/OutboxRepository.java # 수정: SKIP LOCKED 폴링 쿼리 메서드 추가
│   └── republisher/                     # 신규 패키지
│       ├── OutboxRepublisher.java        # @Scheduled 폴러 컴포넌트
│       └── OutboxRepublisherProperties.java  # @ConfigurationProperties (컷오프·주기·배치)
└── infra/publisher/
    └── VerificationRedisStreamPublisher.java  # 재사용 (변경 없음 — 폴러가 publish() 호출)

src/main/resources/
├── application.yml                       # 수정: outbox.republisher.* 기본값
├── application-dev.yml / application-prod.yml  # 수정: outbox.republisher.enabled=true
└── application-test.yml                  # 수정: outbox.republisher.enabled=false

src/test/java/com/planetrush/planetrush/outbox/republisher/
├── OutboxRepublisherIntegrationTest.java   # 카오스(SC-001) + 컷오프(SC-003)
├── OutboxRepublisherConcurrencyTest.java   # 동시성 RepeatedTest (SC-002)
└── OutboxRepublisherProfileTest.java       # 폴러 비활성 검증 (SC-004)
```

**Structure Decision**: 폴러는 `outbox/republisher` 신규 하위 패키지에 둔다 — Outbox 도메인의 일부이되 `domain`/`repository`와 분리해 "재발행 메커니즘"임을 명확히. `scheduler/ScheduledTasks`에 메서드를 추가하지 않는 이유: `ScheduledTasks`는 행성·멤버 도메인 배치이고 outbox 폴러는 인프라 신뢰성 관심사 — 응집도 분리.

## Constitution Alignment (Governance §1)

- **원칙 IV(NON-NEGOTIABLE)**: 본 스펙은 Outbox 패턴의 신뢰성을 강화하는 작업으로 원칙 IV의 정신을 직접 구현.
- **원칙 I(NON-NEGOTIABLE)**: SKIP LOCKED 동시성 검증은 실제 MySQL이 필수 — Testcontainers의 존재 이유 그 자체. H2로는 검증 불가.
- **원칙 II**: 폴러는 발행을 직접 하지 않고 기존 `VerificationMessagePublisher` 어댑터에 위임.
- **원칙 III·V·VI·VII**: 위 Constitution Check 표 참조.

## Complexity Tracking

> 위반 없음. 표 비움.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| (해당 없음) | — | — |

## Post-Design Constitution Re-Check

Phase 0·1 산출물(research.md, quickstart.md) 작성 후 갱신:

- 신규 위반 없음. `data-model.md`/`contracts/` 생략은 스키마·외부 계약 변경 0에 따른 의도된 N/A이며 본 plan Structure에 명시. ✅
- research.md의 락 쿼리 결정(native `FOR UPDATE SKIP LOCKED`)이 원칙 III와 무관함 재확인 — 엔티티 조회이지 DTO 매핑 아님. ✅
- 전 게이트 재통과. **Phase 2(`/speckit-tasks`) 진입 가능.**

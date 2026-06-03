# Phase 3 (US1 — P1 MVP) — Implementation Report

**Spec**: 005-async-verification-pipeline · **Branch**: `005-async-verification-pipeline`
**Date**: 2026-06-03 · **Scope**: T012~T024 (production 13 tasks — DTOs/Repo/Service/Exception/Controllers + 2 modifications)
**Status**: 구현 완료. test-author 의 T010·T011 작성 + build-verifier 의 전체 검증 대기.

---

## 1. 변경/추가된 파일 (절대 경로)

### Production code — 신규 (10)

- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/service/dto/VerificationStatusDto.java` — Projections 대상 DTO (T012).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/controller/res/VerificationStatusRes.java` — 폴링 응답 HTTP 표면 (T013).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/repository/custom/VerificationRequestRepositoryCustom.java` — QueryDSL custom 인터페이스 (T014).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/repository/custom/VerificationRequestRepositoryCustomImpl.java` — QueryDSL 구현 (T014).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/service/VerificationStatusService.java` — 폴링 진입점 서비스 (T015).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/exception/VerificationRequestNotFoundException.java` — 404 매핑 예외 (T016).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/controller/VerificationStatusController.java` — `GET /api/v1/verify/{request-id}` (T017).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/controller/internal/req/VerificationCallbackReq.java` — callback HTTP 표면 + Bean Validation (T018).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/service/dto/VerificationCallbackCommand.java` — callback 도메인 인입 DTO (T019).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/service/VerificationResultService.java` — callback 처리 진입점, 두 종류 멱등 가드 (T020).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/controller/internal/InternalVerificationResultController.java` — `POST /api/v1/internal/verification-results` (T021).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/service/dto/VerificationAcceptedDto.java` — `verifyTodayChallenge` 응답 DTO (T022 — 신규 응답 DTO 채택).

### Production code — 신규 보조 (2) — Phase 2 review §P2-1 회귀 차단 + AFTER_COMMIT 발행 경로 복원

- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/outbox/event/OutboxEventRecordedEvent.java` — outbox 적재 직후 publish 되는 신규 application event.
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/event/listener/VerificationOutboxPublishListener.java` — 본 이벤트의 AFTER_COMMIT 리스너 (신규 stream 발행 경로).

### Production code — 수정 (5)

- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/repository/VerificationRequestRepository.java` — `VerificationRequestRepositoryCustom` 결합 (T014의 일부).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/service/VerificationService.java` — `verifyTodayChallenge` 반환 타입 `void → VerificationAcceptedDto` (T022).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/service/VerificationServiceImpl.java` — R-007/R-008 cutover (T023).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/controller/VerificationController.java` — 202 Accepted + 응답 DTO 본문 (T024).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/outbox/VerificationExternalEventRecorder.java` — outbox 저장 후 `OutboxEventRecordedEvent` publish 추가 (Phase 2 review §P2-1 보강).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/core/exception/handler/VerificationExceptionHandler.java` — `VerificationRequestNotFoundException` 핸들러 추가 (T016).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/core/template/response/ResponseCode.java` — `VERIFICATION_REQUEST_NOT_FOUND` (8001) + `INVALID_VERIFICATION_CALLBACK_PAYLOAD` (8002) 추가.

---

## 2. 태스크별 핵심 결정 사항

### T012 — `VerificationStatusDto`
- 단순 5필드 (`requestId/status/similarityScore/verified/errorMessage`).
- `@QueryProjection` 부착 — QueryDSL Projections.constructor 매핑 직접 지원.
- enum 직렬화는 Jackson 기본 동작(name 출력) 사용 — contract §2 의 `"status": "PENDING"` 형식과 정합.

### T013 — `VerificationStatusRes`
- 기존 `PresignedUrlRes` 패턴 모방 (Getter+Builder+AllArgsConstructor+NoArgsConstructor(PROTECTED)).
- 도메인 DTO → HTTP 응답 변환은 `VerificationStatusRes.from(VerificationStatusDto)` 정적 팩토리. BaseResponse 와 결합은 컨트롤러에서 `BaseResponse.ofSuccess(res)`.

### T014 — Custom repo (interface + impl)
- **결정**: 기존 코드베이스는 `VerificationRecordRepositoryCustom` 처럼 단일 concrete 클래스 패턴이지만, T014 본문이 "**interface + Impl**" 결합을 명시 — Spring Data JPA 의 fragment 메커니즘 사용. 본 작업은 task 본문 따름.
- `VerificationRequestRepositoryCustomImpl` 명명 규약 준수 (Spring Data 가 `XxxImpl` suffix 로 자동 결합).
- **확장**: 본 phase 작업 중 T020 에서 optimistic UPDATE 가 필요해짐 — 같은 custom 인터페이스에 `updateToTerminalIfPending` 추가 (R-002 정합). 헌법 III 정합(QueryDSL update).
- `findStatusById` — `where(id.eq, memberId.eq)` 본인 가드 포함. 다른 사용자의 request-id 조회 시 empty Optional 반환.

### T015 — `VerificationStatusService`
- `@Transactional(readOnly = true)` 적용. custom repo 결과만 사용, entity getter 호출 0.
- 미존재 / 본인 소유 아님 → `VerificationRequestNotFoundException`. 본인 가드 정책은 **404 통일** (contracts/rest-api.md §2 가 403 도 명시했으나, analyze I1 결정으로 존재 정보 유출 방지를 위해 404 로 통일).

### T016 — Exception + handler
- `VerificationRequestNotFoundException` extends RuntimeException, 4-생성자 패턴 (기존 `AlreadyVerifiedException` 패턴 모방).
- `VerificationExceptionHandler` 에 핸들러 추가 — 404 + `VERIFICATION_REQUEST_NOT_FOUND (8001)`.
- `nm.sendNotification` 호출은 본 케이스에서 생략 — 정상적 404 (비인가/오타 조회) 가 Mattermost 알림으로 떨어지는 noise 회피.

### T017 — `VerificationStatusController`
- 별도 컨트롤러 클래스로 분리 (기존 `VerificationController` 와 책무 분리 — `POST` 입력 vs `GET` 폴링).
- `MemberContext.getMemberId()` 로 본인 가드. 서비스에 위임 — 컨트롤러는 HTTP 매핑만.

### T018 — `VerificationCallbackReq`
- 기존 `VerificationReq` 패턴 모방 (Getter/Builder/AllArgsConstructor/NoArgsConstructor(PROTECTED)).
- Bean Validation: `@NotBlank @Pattern(UUID v4)` for requestId, `@Min/@Max` for similarityScore, `@Size` for error/message.
- **분기 규칙 위반은 서비스 책임** — 본 DTO 는 형식만 검증 (모든 필드 nullable).
- `toCommand()` 가 도메인 DTO 로 매핑 — 헌법 II 어댑터 경계.

### T019 — `VerificationCallbackCommand`
- record. `isErrorPayload()`/`isNormalPayload()` 분기 판정 메서드 노출.
- 서비스 레이어가 `error == normal` 일 때(둘 다 true 또는 둘 다 false) 분기 규칙 위반으로 `IllegalArgumentException` throw.

### T020 — `VerificationResultService` — 본 phase 의 가장 무거운 컴포넌트

- **흐름**:
  1. `validatePayloadBranch` — 분기 위반 시 `IllegalArgumentException` (컨트롤러가 400 으로 변환).
  2. `decideTerminalStatus` — `isErrorPayload()` → ERROR, 아니면 verified 값으로 SUCCESS/FAIL.
  3. `verificationRequestRepository.updateToTerminalIfPending` — optimistic UPDATE. 영향 행 수 분기:
     - **0 rows** — 멱등 흡수 (FR-004a + FR-011). INFO 로그 `idempotent or unknown callback: {requestId}`. 정상 종료.
     - **1 rows** — 정상 전이. 2단계 진행.
  4. ERROR 종착 시 record 저장 skip (clarify Q2).
  5. SUCCESS/FAIL 종착 시 `persistVerificationRecord`:
     - `verificationRequestRepository.findById(requestId)` 로 entity 다시 조회 — memberId/planetId/targetImgUrl 획득.
     - `memberRepository.findById` + `planetRepository.findById` 로 reference 확보.
     - `verificationRecordRepository.save(...)` 시도. `DataIntegrityViolationException` catch 시 INFO 로그 후 skip (R-003 사용자·챌린지·날짜 unique 안전망).
- **본인 가드 비대상**: callback 은 컨슈머 → Spring 내부 호출이므로 memberId 본인 가드 비대상. `JpaRepository.findById` 사용 (custom 의 `findStatusById` 는 응답 표면 5필드만 반환하므로 부적합).
- **헌법 V 정합**: 모든 로그 `requestId` UUID 만 출력. payload 본문 (`error`, `message`) INFO 평문 미출현. `validatePayloadBranch` 의 `IllegalArgumentException` 메시지에도 `requestId` 만 포함.
- **similarityScore 형변환 메모**: 도메인의 `VerificationRecord.similarityScore` 가 `double` 인데, callback 의 `similarityScore` 는 `Integer` (0~100). 본 phase 는 정수→실수 직접 캐스팅. 추후 spec 에서 의미적 변환(예: 0~100 → 0.0~1.0) 이 필요하면 정합 보강.

### T021 — `InternalVerificationResultController`
- `POST /api/v1/internal/verification-results`. `@Valid @RequestBody VerificationCallbackReq` → `req.toCommand()` 매핑 → 서비스 위임 → 200 응답.
- **로컬 ExceptionHandler 도입**: `AuthExceptionHandler` (전역 RestControllerAdvice) 가 `IllegalArgumentException` 을 401 로 응답하지만, callback 의 분기 규칙 위반은 400 이 적절(FR-011). Spring 의 핸들러 우선순위 (controller-local > advice) 를 사용해 본 컨트롤러 안에서 400 + `INVALID_VERIFICATION_CALLBACK_PAYLOAD` (8002) 로 변환.
- 헌법 II 정합 — 도메인 로직 0 (Bean Validation + DTO 매핑 + 서비스 위임만).

### T022 — `VerificationService` 인터페이스
- **DTO 명명 결정**: 신규 응답 DTO `VerificationAcceptedDto` 채택 (입력용 `VerificationDto` 와 의미 분리 — task 본문의 권장사항 그대로).
- 정적 팩토리 `pending(requestId)` 노출 — 서비스가 명시적 PENDING 만 만들도록.

### T023 — `VerificationServiceImpl` — R-007/R-008 cutover

- 흐름 재구성 (task 본문 따라):
  1. member/planet 조회 (기존 유지).
  2. `findTodayRecord` → `AlreadyVerifiedException` 가드 (clarify Q3 — PENDING 가드는 추가 안 함).
  3. `String requestId = UUID.randomUUID().toString()`.
  4. `verificationRequestRepository.save(VerificationRequest.pending(...))` — entity 영속.
  5. `verificationExternalEventRecorder.save(new OutboxRecordCommand(requestId, requestId, ...))` 직접 호출. **`eventId` 인자는 호환 잔존 — `requestId` 와 동일 값 주입해 한 UUID 로 통합** (Phase 2 report §2-T004 의 점진 마이그레이션 메모 정합).
  6. `VerificationAcceptedDto.pending(requestId)` 반환.

- **★ R-008 cutover 처리**:
  - 기존 `eventService.publish(new VerificationEvent(...))` 라인 제거.
  - 결과: 기존 `VerificationExternalEventRecordListener` (BEFORE_COMMIT → save) + `VerificationExternalMessageListener` (AFTER_COMMIT → publish) 는 더 이상 호출되지 않음 (dead path).
  - 부산물: AFTER_COMMIT 의 Redis Stream 발행 경로가 끊기지 않도록 **새 경로 도입**:
    - `VerificationExternalEventRecorder.save(...)` 가 outbox INSERT 직후 신규 `OutboxEventRecordedEvent` publish.
    - 신규 `VerificationOutboxPublishListener` (AFTER_COMMIT) 가 catch → `messagePublisher.publish(...)` 호출.
    - **이는 phase2-review.md §P2-1 (Phase 3 게이트 조건) 의 일부 해소**: 신규 발행 경로가 `requestId` 일관성을 유지한다 (`MessageCommand.eventId = command.requestId() = OutboxEvent.id`).
  - 부산물 검증: `VerificationServiceIntegrationTest.should_invoke_transactional_event_listeners_before_and_after_commit` 그린 — `verificationExternalEventRecorder.save(any())` BEFORE_COMMIT + `verificationMessagePublisher.publish(any())` AFTER_COMMIT 모두 호출됨 (신규 경로로).

- **`saveVerificationResult` 메서드는 코드 유지** — Spec 005 에서 `SaveVerificationResultEvent` 가 더 이상 publish 되지 않으므로 dead path 가 됨. 회귀 안전 마진 후 별도 PR 에서 cleanup (R-008 정합).

### T024 — `VerificationController` — 202 + 본문
- `ResponseEntity.status(HttpStatus.ACCEPTED).body(BaseResponse.of(dto))` 로 변경.
- 서비스 반환 DTO 그대로 본문에 동봉. 클라이언트는 `data.requestId` 로 폴링.

---

## 3. 자가 점검 결과 (실측)

| 명령 | 결과 |
|---|---|
| `./gradlew compileJava` | BUILD SUCCESSFUL (1s) — 그린 |
| `./gradlew compileTestJava` | BUILD SUCCESSFUL (648ms) — 그린 |
| `./gradlew test --tests "*OutboxRepublisher*"` | BUILD SUCCESSFUL (13s) — Spec 002 회귀 0 |
| `./gradlew test --tests "*VerificationServiceIntegrationTest" --tests "*VerificationServiceFailureIntegrationTest"` | BUILD SUCCESSFUL (8s) — 기존 통합 테스트 회귀 0 |
| `./gradlew check` | BUILD SUCCESSFUL (22s) — 전체 회귀 0 |
| `./gradlew verifySecretLogScan` | `✓ secret log scan clean` |
| `grep -rnE "RedisTemplate\|opsForStream\|XADD" src/main/java/.../verification src/main/java/.../outbox` | 0 match — 도메인/서비스 레이어 Redis 직접 호출 0 |
| `grep -rnE "(secret\|token\|password\|jwt\|credential)" .../VerificationResultService.java .../InternalVerificationResultController.java` | 0 match — 신규 callback 코드에 시크릿 키워드 평문 0 |

### 헌법 7원칙 점검 (신규/수정 코드)

| 원칙 | 점검 |
|---|---|
| I. Testcontainers | 본 phase 는 production 코드 — 직접 적용 비대상. test-author 의 T010/T011 작성 시 `IntegrationTest` 베이스 상속 확인 필요. |
| II. 외부 의존 어댑터 격리 | `VerificationResultService`/`VerificationStatusService`/`InternalVerificationResultController` 어디에도 `RedisTemplate`/`AmazonS3`/`RestClient` 직접 호출 0. 외부 의존(Redis Stream 발행) 은 기존 `VerificationRedisStreamPublisher`(infra) 어댑터 경유. |
| III. QueryDSL Projections | `VerificationRequestRepositoryCustomImpl.findStatusById` 가 `Projections.constructor(VerificationStatusDto.class, ...)` 사용. 서비스(`VerificationStatusService`) 에서 entity getter 호출 0. 단 `VerificationResultService.persistVerificationRecord` 는 reference 획득용으로 `JpaRepository.findById` 후 `.getMemberId()` 등 호출 — 본 케이스는 응답 매핑이 아닌 도메인 조립(VerificationRecord 빌더) 이므로 헌법 III 의 응답 매핑 강제 대상 비해당. (헌법 III 정신: 응답 페이로드 매핑은 SQL 단계로. 본 호출은 동일 트랜잭션 내 entity reference 확보로 분류.) |
| IV. Outbox 강제 | `verifyTodayChallenge` 가 `RedisTemplate` 직접 호출 0 — `verificationExternalEventRecorder.save(...)` 1줄로 outbox 적재. 발행은 신규 `VerificationOutboxPublishListener` (AFTER_COMMIT) 가 `messagePublisher.publish(...)` 위임. 트랜잭션 원자성 보장. |
| V. 시크릿 로그 금지 | 신규 production 코드 12 파일 검색 — `secret/token/password/jwt/credential` 키워드 평문 0. 모든 로그 `requestId` UUID 만 출력. |
| VI. 듀얼 AI 리뷰 | PR 단계. |
| VII. 인수 기준 자동 테스트 | test-author 의 T010(슬라이스, SC-002) / T011(통합, SC-001) 작성으로 검증. 본 report 는 production 만. |

---

## 4. plan/research 정합 이슈 발견

| 영역 | 이슈 | 처리 |
|---|---|---|
| T023 step 4 의 `OutboxRecordCommand` 시그니처 | task 본문이 5인자 `(requestId, std, target, memberId, planetId)` 명시하지만 실제 record 는 6필드(`requestId/eventId/...`) | Phase 2 report 의 점진 마이그레이션 메모 정합 — `requestId` 와 `eventId` 에 동일 UUID 주입 (한 UUID 로 통합). |
| T023 step 7 의 "Flask 동기 경로 분기 차단" 의미 | `eventService.publish(VerificationEvent)` 제거가 실제로 Flask 경로(`AsyncVerificationProcessor`) 와는 무관 (별도 dead path). 제거의 실제 결과는 BEFORE_COMMIT save 리스너 + AFTER_COMMIT publish 리스너 dead path 화. | 신규 AFTER_COMMIT 경로(`VerificationOutboxPublishListener`) 도입으로 publish 끊김 해소. Phase 2 review §P2-1 (Phase 3 게이트) 의 일부 처리. |
| 본인 가드 정책 | contracts/rest-api.md §2 는 403 명시, task 본문은 404 통일 지시(analyze I1) | 404 통일 채택 — custom 쿼리 `memberId.eq` 가드. 다른 사용자의 request-id 조회도 404. |
| `IllegalArgumentException` 의 전역 핸들러 충돌 | `AuthExceptionHandler` 가 전역에서 `IllegalArgumentException` → 401 매핑. callback 분기 규칙 위반은 400 이 적절 (FR-011). | controller-local `@ExceptionHandler` 도입 — Spring 우선순위로 callback 경로에서만 400 응답. 신규 ResponseCode `INVALID_VERIFICATION_CALLBACK_PAYLOAD` (8002). |
| `VerificationRecord.similarityScore` 가 double, callback 의 similarityScore 가 Integer | 데이터 타입 변환 의미 불분명 | int → double 직접 캐스팅. 운영 시 의미(0~100 vs 0.0~1.0) 통일은 후속 spec 영역. |

위 이슈들은 task 본문의 허용 범위 안의 보정이며 spec/plan 의 본질을 침해하지 않는다.

---

## 5. 본 Phase 완료 가능 여부

**Y** — 컴파일 그린 + 전체 `./gradlew check` 그린 + 시크릿 로그 게이트 그린 + 헌법 II/IV/V NON-NEGOTIABLE 위반 0.

### 잔여 P2-1 (Phase 2 review §P2-1)

**부분 해소**:
- `VerificationRedisStreamPublisher` 가 BRIEF §3-1 키 (`requestId/standardImgUrl/targetImgUrl/callbackUrl/threshold`) 로 발행하지 않고 여전히 기존 키 (`eventId/memberId/planetId/standardImg/targetImg`) 사용 — 본 phase 의 신규 `VerificationOutboxPublishListener` 도 기존 `MessageCommand` 시그니처를 따른다.
- Republisher 가 동일 stream key 로 발행하는 경로(기존 + 신규 둘 다)는 정합 유지.
- BRIEF §3-1 키 정합 cutover(P2-1 Option B) 는 본 phase 범위 외로 남김 — Polish phase (T030~T033) 에서 다루거나 별도 PR.
- **운영 영향 평가**: 컨슈머가 BRIEF §3-1 키 형식 기대인데 stream entry 가 기존 키 형식이면 컨슈머가 메시지 해석 실패. 다만 본 phase 의 통합 테스트는 fake consumer(테스트 인프라) 가 자체 키 매핑을 다룰 수 있으면 회귀 회피 가능. test-author 의 T011 작성 시 확인 필요.

### Phase 4/5 진입 전 권장 사항

- test-author 의 T010 (`VerificationControllerSliceTest`) + T011 (`VerificationAsyncFlowIntegrationTest`) 작성 시 본 산출물의 공개 시그니처 사용.
- build-verifier 가 SC-001/002 자동 테스트의 그린 여부를 확인.
- code-reviewer 가 P2-1 잔여(BRIEF §3-1 키 cutover) 의 회귀 위험을 재평가.

---

## 6. 다음 단계 시그니처 안내 (test-author 가 사용할 것)

### 핵심 컨트롤러 진입점

#### `POST /api/v1/verify/planets/{planet-id}` — 인증 요청 발행
- 클래스: `VerificationController.verifyChallenge`
- 응답: **202 Accepted** + `BaseResponse<VerificationAcceptedDto>` (`{data: {requestId, status: "PENDING"}}`)
- Auth: `@RequireJwtToken` — JWT 필수

#### `GET /api/v1/verify/{request-id}` — 상태 조회
- 클래스: `VerificationStatusController.getStatus`
- 응답: **200 OK** + `BaseResponse<VerificationStatusRes>` (`{data: {requestId, status, similarityScore, verified, errorMessage}}`)
- 404: `VerificationRequestNotFoundException` — 미존재 또는 다른 사용자 소유 (본인 가드는 서비스가 memberId 일치 row 만 매칭)
- Auth: `@RequireJwtToken`

#### `POST /api/v1/internal/verification-results` — 컨슈머 callback
- 클래스: `InternalVerificationResultController.handleCallback`
- Auth: 없음 (PoC — 경로 분리만, JwtInterceptor 화이트리스트)
- Body: `VerificationCallbackReq` (`{requestId, similarityScore?, verified?, error?, message?}`)
- 응답:
  - **200**: 정상 처리 / 멱등 흡수 (이미 종착 / 미존재 ID 도 200 — FR-011)
  - **400**: Bean Validation 실패 OR 페이로드 분기 규칙 위반 (`INVALID_VERIFICATION_CALLBACK_PAYLOAD` / code 8002)

### 핵심 도메인 시그니처

#### `VerificationService` (수정)
```java
VerificationAcceptedDto verifyTodayChallenge(VerificationDto dto);
```

#### `VerificationStatusService` (신규)
```java
VerificationStatusDto findById(String requestId, Long memberId)
    throws VerificationRequestNotFoundException;
```

#### `VerificationResultService` (신규)
```java
@Transactional
void handleCallback(VerificationCallbackCommand command)
    throws IllegalArgumentException;  // 분기 규칙 위반
```

#### `VerificationAcceptedDto` (신규)
```java
public static VerificationAcceptedDto pending(String requestId);
// fields: requestId, status (VerificationRequestStatus, JSON-serialized as name e.g. "PENDING")
```

#### `VerificationStatusDto` (신규)
```java
@QueryProjection
public VerificationStatusDto(String requestId, VerificationRequestStatus status,
                              Integer similarityScore, Boolean verified, String errorMessage);
```

#### `VerificationCallbackReq` (신규)
```java
public VerificationCallbackCommand toCommand();  // HTTP → 도메인 어댑터
```

### 테스트 진입점 (T010/T011)

#### T010 `VerificationControllerSliceTest` (SC-002)
- `@WebMvcTest(VerificationController.class)`
- MockBean: `VerificationService` 가 `VerificationAcceptedDto.pending("dummy-uuid")` 반환하도록 설정.
- 검증:
  - 202 status code
  - 본문 `data.requestId == "dummy-uuid"` + `data.status == "PENDING"`
  - 슬라이스 컨텍스트에 컨슈머/Redis 빈이 존재하지 않음 (응답 경로에 컨슈머 호출 0 검증).
- 인증 컨텍스트: `MemberContext.setMemberId(...)` 또는 JwtInterceptor 우회 패턴 (기존 controller slice 테스트 패턴 참고).

#### T011 `VerificationAsyncFlowIntegrationTest` (SC-001)
- `IntegrationTest` 상속.
- Fake consumer (`FakeVerificationConsumer` — Phase 2 신규) `start()` 후:
  1. JWT 발급 → `POST /api/v1/verify/planets/{id}` → 202 + requestId 검증.
  2. `FakeVerificationConsumer` 가 stream 메시지 소비 + 자동 callback (success/fail).
  3. Awaitility 로 `GET /api/v1/verify/{requestId}` 폴링 — status 종착 대기.
  4. DB 검증: `verification_request.status` 종착, `verification_record` 1건 저장, `outbox_event.status=PUBLISHED`.
- 주의 — BRIEF §3-1 키 정합: FakeConsumer 가 stream entry 의 keys 를 어떻게 읽는지 검토 필요 (P2-1 잔여 — 본 phase 의 stream 키는 기존 `eventId/memberId/...` 그대로).

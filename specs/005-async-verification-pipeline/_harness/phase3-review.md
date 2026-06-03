# Phase 3 (US1 — P1 MVP) — Code Review (Claude)

**Spec**: 005-async-verification-pipeline · **Branch**: `005-async-verification-pipeline`
**Date**: 2026-06-03 · **Reviewer**: code-reviewer (Claude)
**Scope**: T010·T011 (테스트 2) + T012~T024 (production 13) + 신규 보조 컴포넌트 2 (`OutboxEventRecordedEvent` + `VerificationOutboxPublishListener`) + `VerificationExternalEventRecorder.save()` 보강
**Build signal**: `./gradlew check` BUILD SUCCESSFUL (25s) + `verifySecretLogScan` clean — build-verifier 그린 신호 수신.

---

## Verdict

**CHANGES_REQUESTED** — **P1 발견 1건** (P1-1: stream entry key 정합성 위반 — BRIEF §3-1 컨슈머 계약 깨짐).

본 P1 은 **운영 컨슈머(Python)가 Spring 발행 메시지를 파싱할 수 없는** 정합성 결함으로, Spec 005 단대단 흐름이 운영에서 작동하지 않을 위험이 있다. 통합 테스트는 `FakeVerificationConsumer` 가 폴백 키 매핑(`requestId` 미존재 시 `eventId` 폴백, line 239-243)으로 살아남았기 때문에 그린이지만, 실 컨슈머는 BRIEF §3-1 만 안다.

다만 P1-1 은 phase2-review.md §P2-1 의 잔여 이슈로 **이미 추적되고 있다** — Phase 3 범위는 R-008 cutover (publish 호출 경로 교체) 까지이고, BRIEF §3-1 키 cutover 는 Polish/별도 PR 로 의도적 분리됨이 impl-report 와 progress.md 양쪽에 명시. 본 리뷰는 그 분리 결정을 **존중하되**, Phase 4 진입 전 P1 로 격상해 가시화한다(머지 차단 사유는 아니나 PR 본문에 명확히 표면화 필요).

---

## 헌법 7원칙 점검 결과

| 원칙 | 결과 | 근거 |
|---|---|---|
| **I. Testcontainers** | ✅ | T011 `VerificationAsyncFlowIntegrationTest extends IntegrationTest` (line 72). H2/임베디드 검색 0. T010 은 `@WebMvcTest` 슬라이스로 컨테이너 비대상. |
| **II. 어댑터 격리** | ✅ | `VerificationResultService`/`VerificationStatusService`/`VerificationServiceImpl`/`InternalVerificationResultController` 모두 `RedisTemplate`/`AmazonS3`/`RestClient`/`WebClient` 직접 import 0. Redis Stream 발행은 `VerificationRedisStreamPublisher`(infra 어댑터) 경유. `VerificationOutboxPublishListener` 도 `VerificationMessagePublisher` 인터페이스 호출(line 47). |
| **III. QueryDSL Projections** | ✅ | `VerificationRequestRepositoryCustomImpl.findStatusById` (line 30-47) 가 `Projections.constructor(VerificationStatusDto.class, ...)` 사용. `VerificationStatusService.findById` 는 entity getter 호출 0(line 43-47). ⚠️ 예외 1건: `VerificationResultService.persistVerificationRecord` (line 128-157)에서 `JpaRepository.findById().getMemberId/getPlanetId/getTargetImgUrl` 호출. 이는 응답 표면 매핑이 아닌 **도메인 entity 조립(VerificationRecord 빌더)** 용 reference 확보 — 헌법 III 의 정신("응답 매핑은 SQL 단계") 비대상으로 분류 가능. impl-report §3 의 분류 정합. |
| **IV. Outbox 경유** | ✅ | `VerificationServiceImpl.verifyTodayChallenge` (line 80-121) 가 `RedisTemplate` 직접 호출 0. `verificationExternalEventRecorder.save(...)` 1줄로 outbox INSERT 만 수행. 발행은 AFTER_COMMIT 리스너 위임. `VerificationOutboxPublishListener.publishAfterCommit` 가 `@TransactionalEventListener(phase=AFTER_COMMIT)` 로 분리 — 메인 트랜잭션 커밋 후 발행. |
| **V. 시크릿 로그 금지** | ✅ | `grep -nE "secret\|token\|password\|jwt\|credential"` on 4 new files → 0 match. 모든 신규 로그는 `requestId` UUID 만 출력 (`VerificationResultService` line 94/155, `InternalVerificationResultController` line 63, `VerificationOutboxPublishListener` line 46, `VerificationExternalEventRecorder` line 67). callback payload 본문 (`error`/`message`) 평문 출력 0. `verifySecretLogScan` clean. |
| **VI. 듀얼 AI 리뷰** | (이 리뷰가 집행) | PR 단계에서 Codex 리뷰와 짝. |
| **VII. 인수 기준=테스트** | ✅ | SC-001 ↔ T011 (`VerificationAsyncFlowIntegrationTest.endToEndPendingToSuccess`), SC-002 ↔ T010 (`VerificationControllerSliceTest.respondsAcceptedWithoutInvokingConsumerOrPublisher`). 1:1 매핑 + Javadoc 표시. SC-003~005 는 Phase 4·5 범위. |

**NON-NEGOTIABLE (I/IV/V)** 위반 0건.

---

## P1 (머지 차단 후보)

### P1-1. stream entry key 가 BRIEF §3-1 컨슈머 계약과 불일치 — 운영 컨슈머 파싱 실패 위험

- **파일·라인**:
  - `src/main/java/com/planetrush/planetrush/infra/publisher/VerificationRedisStreamPublisher.java:36-41`
  - `docs/SPRING_INTEGRATION_BRIEF.md` §3-1
  - `src/main/java/com/planetrush/planetrush/outbox/VerificationExternalEventRecorder.java:77-90` (payload 빌더는 BRIEF 정합)
- **정합 깨짐 위치(양쪽 동시 비교)**:
  - **생산자(publisher)** stream entry 키:
    ```java
    fields.put("eventId", command.eventId());          // ← 미정합
    fields.put("memberId", String.valueOf(command.memberId()));
    fields.put("planetId", String.valueOf(command.planetId()));
    fields.put("standardImg", command.standardImg());  // ← 키명 미정합
    fields.put("targetImg", command.targetImg());      // ← 키명 미정합
    ```
  - **소비자(BRIEF §3-1 컨슈머 계약)** 기대 키 set:
    `requestId / standardImgUrl / targetImgUrl / callbackUrl / threshold` (5개, payload 직렬화 정합).
  - **Outbox `payload` 컬럼** (BRIEF §3-1 정합) 은 `VerificationExternalEventRecorder.buildPayload` (line 79-84) 가 정확히 위 5개 키로 직렬화 — payload 와 stream entry 가 **서로 다른 계약**으로 발행됨.
- **위반 원칙/이유**: 통합 정합성 결함(헌법 II 어댑터 계약 — 외부 계약 불이행). 운영 컨슈머는 stream entry 의 keys 를 직접 읽어 처리하므로 `requestId`/`standardImgUrl`/`targetImgUrl`/`callbackUrl`/`threshold` 가 보이지 않으면 메시지 해석 실패. Spec 005 의 SC-001 (정상 흐름) 이 운영에서 작동하지 않는다.
- **테스트가 못 잡은 이유**: `FakeVerificationConsumer.handleRecord` (`src/test/java/.../FakeVerificationConsumer.java:239-243`) 가 신/구 키 폴백을 가지고 있어 통합 테스트는 그린:
  ```java
  String requestId = stringValue(raw.get("requestId"));
  if (requestId == null) {
    requestId = stringValue(raw.get("eventId"));  // 폴백
  }
  ```
  실 컨슈머는 이 폴백을 갖지 않는다.
- **현 분류**: phase2-review.md §P2-1 + Phase 3 impl-report §5 "잔여 P2-1" 이 본 사안을 **이미 추적 중**. impl-report 는 "Polish phase (T030~T033) 에서 다루거나 별도 PR" 로 의도 분리. 본 리뷰는 그 분리를 존중하되 **격상**하여 PR 단계 가시화 필수:
  1. PR 본문에 "운영 컨슈머와의 stream key 정합은 본 PR 범위 외 — 후속 PR 에서 cutover" 를 명시.
  2. Polish (Phase 6, T030~T033) 또는 별도 PR 에서 `VerificationRedisStreamPublisher.publish()` 가 `MessageCommand` → BRIEF §3-1 key set 으로 매핑하도록 수정.
- **수정 방향**(후속 PR):
  ```java
  // VerificationRedisStreamPublisher.publish — BRIEF §3-1 cutover
  fields.put("requestId", command.eventId());                  // 이름 변경
  fields.put("standardImgUrl", command.standardImg());         // 키명 정합
  fields.put("targetImgUrl", command.targetImg());             // 키명 정합
  fields.put("callbackUrl", /* @Value 주입 */);
  fields.put("threshold", /* @Value 주입 */);
  // memberId/planetId 는 BRIEF §3-1 외라면 제거 또는 보조 키로
  ```
  `MessageCommand` record 도 BRIEF §3-1 필드(`requestId`/`standardImgUrl`/`targetImgUrl`/`callbackUrl`/`threshold`) 로 재정의 권장 — payload 와 stream key set 이 동일 record 에서 흘러야 정합 회귀 위험 0.
- **머지 차단 여부**: **Phase 3 자체는 통과** (R-008 cutover 의 정의된 범위는 publish 호출 경로 교체까지). PR 머지 전엔 별도 PR 또는 Polish phase 에 위 cutover 가 반드시 들어가야 한다 — 본 PR 만으로는 운영에서 컨슈머가 메시지를 받지 못한다.

---

## P2 (권장 수정)

### P2-A. outbox status PUBLISHED 전이 미관측 — 결함 진단

- **파일·라인**:
  - `src/main/java/com/planetrush/planetrush/infra/publisher/VerificationRedisStreamPublisher.java:33-56`
  - `src/main/java/com/planetrush/planetrush/outbox/VerificationExternalEventRecorder.java:92-96`
  - `src/main/java/com/planetrush/planetrush/outbox/domain/OutboxEvent.java:67-69`
- **관측 사실(test-author 보고)**:
  - 통합 환경에서 `[Redis Stream] published streamKey=verify:requests, recordId=..., eventId=<requestId>` 로그 정상 출력.
  - `outbox_event.status` 가 PENDING 잔존(10초 Awaitility 폴링에도 변환 안 됨).
- **가설 진단(코드 라인 기반)**:
  1. **호출 흐름**: `verifyTodayChallenge` (메인 TX) → `VerificationExternalEventRecorder.save()` (REQUIRED, 같은 TX) → `eventPublisher.publishEvent(OutboxEventRecordedEvent)` → 메인 TX commit → `VerificationOutboxPublishListener.publishAfterCommit` (`@TransactionalEventListener(AFTER_COMMIT)`) → `VerificationMessagePublisher.publish(MessageCommand)` → `VerificationRedisStreamPublisher.publish` (`@Transactional`, line 33).
  2. **publish() 트랜잭션**: AFTER_COMMIT 컨텍스트에서 호출되므로 outer TX 없음. `@Transactional` 기본 `Propagation.REQUIRED` → 새 TX 시작. 정상 활성화 가정 시 dirty checking 가능.
  3. **entity 로드**: `eventRecorder.findById(command.eventId())` (line 51) → `@Transactional(readOnly=true)` (line 92). nested 호출이므로 outer TX (publish 의 readOnly=false) 가 우선 — entity 는 managed 상태로 로드.
  4. **status 전이**: `outboxEvent.published()` (`OutboxEvent.java:67-69`) → `this.status = PUBLISHED`. dirty checking 으로 commit 시 UPDATE 가 flush 되어야 함.
  5. **publish() try-catch (line 43-55)**: `redisTemplate.opsForStream().add()` 만 catch 안에 있고, `outboxEvent.published()` 는 try 블록 안의 `add` 다음 라인이지만 같은 try. RuntimeException 발생 시 published() 호출 안 됨 — 그러나 로그상 published 가 정상 출력되므로 try 내부 실행 완료가 확정.

- **가장 유력한 근본 원인 후보**:
  - **(a)** `VerificationRedisStreamPublisher.publish` 의 `@Transactional` 이 AFTER_COMMIT 컨텍스트에서 **새 트랜잭션이 정상 활성화되지 않을** 가능성. Spring 의 `TransactionalEventListener` 가 AFTER_COMMIT phase 를 처리할 때 이미 outer TX 의 SynchronizationManager 가 cleanup 되어, REQUIRED 가 의도대로 새 TX 를 열지 못하고 트랜잭션 없이 실행될 수 있다. 그 경우 `outboxEvent.published()` 호출은 detached entity 의 setter 호출에 그쳐 flush 0.
  - **(b)** `eventRecorder.findById(eventId)` 가 새 영속성 컨텍스트를 열고 entity 를 반환한 직후 영속성 컨텍스트가 닫히는 경우(트랜잭션 경계 분리) → detached entity → published() flush 0.
- **즉시 검증 가능한 진단법**(Phase 4 진입 시 test-author·code-implementer 가 수행):
  1. `VerificationRedisStreamPublisher.publish()` 에 `TransactionSynchronizationManager.isActualTransactionActive()` 로그 추가 → 실제로 TX 활성화되는지 확인.
  2. publish() `@Transactional(propagation = Propagation.REQUIRES_NEW)` 로 명시 변경 후 재현 — REQUIRED 의 모호한 활성화를 회피.
  3. 또는 `@TransactionalEventListener(phase=AFTER_COMMIT)` 대신 `@TransactionalEventListener(phase=AFTER_COMMIT, fallbackExecution=true)` 와 함께 publish() 안에서 직접 `outboxRepository.save(outboxEvent.markPublished())` 호출(명시적 save). dirty checking 의존을 제거.
- **운영 영향 평가** (test-author 보고 정합):
  - **현재**: stream 발행 자체는 성공 → 컨슈머가 메시지 수신·처리 → callback 정상 도착 → `VerificationRequest.status` 종착. 사용자 가시 동작 정상.
  - **잠재 위험**: `OutboxRepublisher` (Spec 002) 가 PENDING 잔존 outbox row 를 폴링 사이클에 재발행 → 컨슈머가 중복 메시지 수신 → 컨슈머 멱등(SC-005a) 안전망으로 흡수되나 불필요한 추론 부하·중복 callback 발생.
- **수정 방향**: 본 결함은 **Phase 4 (SC-004 작성) 진입 시 test-author 가 우선 진단** 권장. impl-report §결함 후보 및 test-report §7.1 의 결정과 정합 — Phase 3 머지 차단 아님. **PR 본문에 known issue 로 명시 필수**.

### P2-B. `VerificationServiceImpl.saveVerificationResult` dead path 와 `VerificationExternalMessageListener`·`VerificationExternalEventRecordListener` dead listeners 의 코드 잔존

- **파일·라인**:
  - `src/main/java/com/planetrush/planetrush/verification/service/VerificationServiceImpl.java:129-145` (saveVerificationResult)
  - `src/main/java/com/planetrush/planetrush/verification/event/listener/VerificationExternalMessageListener.java` (전체)
  - `src/main/java/com/planetrush/planetrush/verification/event/listener/VerificationExternalEventRecordListener.java` (전체)
  - `src/main/java/com/planetrush/planetrush/verification/service/dto/VerificationEvent.java` (전체)
  - `src/main/java/com/planetrush/planetrush/verification/service/VerificationEventService.java` (전체)
- **이유**: R-008 cutover 후 `eventService.publish(VerificationEvent)` 호출지점 grep → 0 match (production 코드 0). 그러나 위 5개 컴포넌트가 모두 코드에 남아 있다 — 호출되지 않는 dead path. impl-report §T023 가 "회귀 안전 마진 후 별도 PR 에서 cleanup" 으로 명시 — 정당한 분리.
- **헌법 II/IV 정합**: dead path 가 잘못된 호출지점을 만들 위험은 없으나, **신규 개발자가 어느 경로를 써야 하는지 혼동할 위험**(코드 가독성). 특히 `VerificationExternalMessageListener` 와 신규 `VerificationOutboxPublishListener` 가 거의 동일한 시그니처(AFTER_COMMIT + `messagePublisher.publish`)를 가져 — 한 OutboxEvent 가 두 리스너로 인해 중복 발행될 가능성을 의심하게 만든다(실제로는 dead path 라 안전).
- **중복 발행 가능성 검증**: `VerificationExternalMessageListener` 는 `VerificationEvent` 를 listen. production 에서 `VerificationEvent` 가 publish 되지 않으므로 호출 0. `VerificationOutboxPublishListener` 는 `OutboxEventRecordedEvent` 를 listen — 둘은 listener target type 이 다르므로 **중복 발행 0**. ✅
- **수정 방향**: Phase 6 (Polish, T030~T033) 또는 별도 PR 에서 위 5개 dead path 컴포넌트 일괄 제거. PR 본문에 명시.

### P2-C. `VerificationRedisStreamPublisher.publish()` 의 `try-catch(RuntimeException)` 가 모든 발행 실패를 silently 흡수 — 헌법 IV at-least-once 의존성

- **파일·라인**: `src/main/java/com/planetrush/planetrush/infra/publisher/VerificationRedisStreamPublisher.java:43-55`
- **현재 동작**: `add()` 실패 시 ERROR 로그만 출력하고 RuntimeException 흡수 → 트랜잭션 정상 commit → outbox row 는 PENDING 잔존(`published()` 호출 안 됨). `OutboxRepublisher` 가 다음 사이클에 재발행 시도 — 의도된 at-least-once 동작.
- **위험**: try 블록의 다음 라인 `eventRecorder.findById()` 또는 `outboxEvent.published()` 가 던지는 RuntimeException 도 같이 흡수되어, **stream 발행은 성공했는데 outbox status 가 PENDING 잔존하는 race window** 가 발생. P2-A 의 가설과 정합 — 실제로 status 가 PENDING 잔존 중.
- **수정 방향**: try-catch 를 `add()` 라인에만 좁히고, `findById` + `published()` 는 try 밖에서. 또는 catch 블록을 IOException/RedisConnectionException 등 구체 예외로 좁혀 silent 흡수 범위 축소.
- **머지 차단 여부**: 본 PR 의 신규 코드 아님 (Spec 002 잔재). **Phase 4 진입 후 결함 진단과 함께 처리** 권장.

### P2-D. `AuthExceptionHandler.illegalArgumentException` 의 401 매핑은 본 PR 의 동기 — 다른 컨트롤러의 부수효과 검토

- **파일·라인**:
  - `src/main/java/com/planetrush/planetrush/core/exception/handler/AuthExceptionHandler.java:45-51` (전역 `IllegalArgumentException` → 401)
  - `src/main/java/com/planetrush/planetrush/verification/controller/internal/InternalVerificationResultController.java:60-66` (controller-local `IllegalArgumentException` → 400)
- **현재 동작**: Spring 의 controller-local `@ExceptionHandler` 우선순위가 RestControllerAdvice 보다 높다 — `InternalVerificationResultController` 에서 발생한 `IllegalArgumentException` 만 400 으로 변환, 다른 컨트롤러는 여전히 401. impl-report §T021 의 의도 정합.
- **검증**: `grep -rn "IllegalArgumentException" src/main/java/com/planetrush/planetrush/.../controller/` 로 다른 컨트롤러에서 본 예외가 401 응답되는 케이스의 부수효과 없음 확인 권장.
- **헌법 II 정합**: 컨트롤러는 도메인 로직 0, HTTP 표면 매핑만. ✅
- **권장**: 본 패턴은 임시 해결책 — 장기적으로 `IllegalArgumentException` 대신 도메인 전용 예외(`InvalidCallbackPayloadException` 등) 도입 후 전역 핸들러 회피가 안전. P3 제안 영역.

### P2-E. `VerificationResultService.persistVerificationRecord` 의 entity reload 패턴 — N+1 위험

- **파일·라인**: `src/main/java/com/planetrush/planetrush/verification/service/VerificationResultService.java:128-157`
- **현재 동작**: `updateToTerminalIfPending` UPDATE 후 record 저장을 위해 `findById(requestId)` 로 entity 다시 조회 + `memberRepository.findById` + `planetRepository.findById`. 총 3개 SELECT.
- **이유**: comment(line 129-131)에서 명시 — custom `findStatusById` 는 본인 가드 + 응답 5필드만 반환하므로 부적합. `JpaRepository.findById` 사용.
- **개선 여지**: `VerificationRequest` 가 이미 memberId/planetId/targetImgUrl 을 들고 있어 `findById` 하나로 충분. `memberRepository.findById` + `planetRepository.findById` 는 `VerificationRecord` 의 `@ManyToOne` reference 가 필요해 사실상 불가피.
- **헌법 III 정합**: 응답 매핑이 아닌 도메인 entity 조립 — 적용 비대상. ✅
- **권장**: 현 구현 유지. 단, callback 처리 빈도가 높아지면 `getReference()` (proxy) 로 대체해 round-trip 절감 가능 — P3 제안.

### P2-F. `MessageCommand.eventId` 필드명이 신규 흐름의 `requestId` 의미와 불일치 — 의미적 혼동

- **파일·라인**: `src/main/java/com/planetrush/planetrush/verification/service/dto/MessageCommand.java:4`
- **현재 동작**: `VerificationOutboxPublishListener.publishAfterCommit` (line 47-53) 가 `command.requestId()` 를 `MessageCommand.eventId` 인자로 주입. `VerificationRedisStreamPublisher.publish` (line 37) 가 stream entry 의 `eventId` 키로 발행.
- **이유**: R-004 정합으로 한 UUID 가 흐르지만, record 필드명이 의미적으로 모호 — 신규 흐름에서는 `requestId` 가 의미상 정확하지만 record 가 `eventId` 라는 옛 이름을 유지.
- **헌법 IV 정합**: 정합성 자체는 깨지지 않음(같은 UUID). 의미적 혼동 위험만.
- **권장**: `MessageCommand` 도 BRIEF §3-1 정합 cutover (P1-1) 시 함께 `requestId/standardImgUrl/targetImgUrl/callbackUrl/threshold` 로 재정의. P1-1 수정과 함께 묶음 처리.

---

## P3 (제안)

- **P3-1.** `OutboxRecordCommand` record 의 `eventId` 필드를 deprecate 처리 또는 제거 — impl-report §2-T023 가 "한 UUID 로 통합" 결정. 잔존 호출지점은 `VerificationEvent.toRecordCommand` (dead path) 1곳 뿐. Polish phase 에서 일괄 정리.
- **P3-2.** `VerificationCallbackCommand.isErrorPayload`/`isNormalPayload` 분기 메서드가 `IllegalArgumentException` 거절 로직과 결합되어 있어 — record 안에 `validateBranchOrThrow()` 같은 자체 검증 메서드를 두면 서비스 코드가 더 깔끔(현재는 `VerificationResultService.validatePayloadBranch` 가 별도 private). 단순 가독성 제안.
- **P3-3.** `VerificationRequestRepositoryCustomImpl.updateToTerminalIfPending` (line 49-70) 의 `set` 체이닝이 5줄. 향후 SC 추가로 필드가 늘어나면 누락 위험 — `VerificationRequest` 도메인 메서드 `markTerminal(status, score, verified, error)` 로 캡슐화하고 영속 메서드는 `saveAndFlush` 로 단순화하는 대안. 단, 멱등 `WHERE status='PENDING'` 가드는 SQL 단계가 필수라 현 구조 합리적 — 제안 수준.
- **P3-4.** `VerificationAcceptedDto` 와 `VerificationStatusDto` 모두 `@Getter` 만 — record 변환 권장(Spec 005 의 신규 DTO 패턴 통일). 단 `@QueryProjection` 호환성 검토 필요.

---

## production 결함 후보 (P2-A) 진단 정리

| 항목 | 결론 |
|---|---|
| 결함 위치 | `VerificationRedisStreamPublisher.publish()` (`infra/publisher/...:33-56`) 의 트랜잭션·dirty checking 경로 |
| 가설 1 (유력) | AFTER_COMMIT 컨텍스트에서 `@Transactional`(REQUIRED) 가 새 TX 를 정상 활성화하지 못함 → entity detached → `published()` flush 0 |
| 가설 2 | `try-catch(RuntimeException)` 범위가 너무 넓어 `findById`/`published()` 의 silent 흡수 → status 전이 누락 |
| 즉시 검증법 | `TransactionSynchronizationManager.isActualTransactionActive()` 로그 추가, 또는 publish() 에 `Propagation.REQUIRES_NEW` 명시 |
| Phase 3 차단 여부 | **차단 아님** — 사용자 가시 동작 정상(stream 발행 → callback → status 종착). PENDING 잔존은 Republisher 중복 발행 위험만 — 컨슈머 멱등(SC-005a) 안전망 존재 |
| 처리 권장 | Phase 4 진입 시 test-author 가 SC-004 작성 중 우선 진단 → code-implementer 가 fix |

---

## BRIEF §3-1 stream key 정합 (P2-B 검증) 결과

**실제 코드 stream entry key set** (`VerificationRedisStreamPublisher.publish` line 36-41):
```
eventId, memberId, planetId, standardImg, targetImg
```

**BRIEF §3-1 컨슈머 계약 key set**:
```
requestId, standardImgUrl, targetImgUrl, callbackUrl, threshold
```

**Outbox `payload` 컬럼 key set** (`VerificationExternalEventRecorder.buildPayload` line 79-84):
```
requestId, standardImgUrl, targetImgUrl, callbackUrl, threshold  ← BRIEF §3-1 정합
```

**정합 결과**:
- **payload ↔ BRIEF**: ✅ 완전 정합 (Phase 2 T005 의 결정).
- **stream entry ↔ BRIEF**: ❌ 5개 key 모두 불일치 (이름·집합 모두).
- **stream entry ↔ payload**: ❌ 불일치 — 동일 outbox event 가 두 다른 계약으로 노출됨.

**분류**: **P1-1 로 격상**. impl-report 의 "Polish phase 별도 PR" 의도 분리는 존중하나, PR 본문 가시화는 필수. 운영 컨슈머가 메시지 파싱 실패 시 Spec 005 단대단 흐름이 운영에서 작동하지 않음.

---

## 인수 기준 정합성

| SC | 검증 테스트 | SC 동작을 실제로 단언하는가 |
|----|-----------|----------------------|
| SC-001 (정상 흐름) | `VerificationAsyncFlowIntegrationTest.endToEndPendingToSuccess` | ✅ 단대단 — 202 + PENDING (line 151-156) / FakeConsumer stream 소비 + callback (line 134-135, FakeConsumer line 261-287) / Awaitility SUCCESS 폴링 (line 160-167) / DB row 검증 — `VerificationRequest.status=SUCCESS` + `similarityScore` + `verified` + `completedAt` (line 171-178) + `VerificationRecord` 1건 (line 188-194) + `OutboxEvent` row 존재(R-004 4면 ID 동일 검증 line 184-185). ⚠️ outbox status PUBLISHED 미검증은 의도된 분리(line 181-183 코멘트 + SC-004 영역). |
| SC-002 (즉시 응답) | `VerificationControllerSliceTest.respondsAcceptedWithoutInvokingConsumerOrPublisher` | ✅ analyze A1 보강 정합 — `verifyNoInteractions(flaskApiClient/verificationMessagePublisher/asyncVerificationProcessor)` (line 150-152) 로 응답 경로의 컨슈머 호출 0 직접 단언. 202 + `requestId="dummy-uuid"` + status="PENDING" (line 138-142). `VerificationService.verifyTodayChallenge` 1회 호출 검증(line 145). 슬라이스 한계 명시 — service mock 이 production 동작을 완전 모사하지 않음, 그러나 컨트롤러 응답 경로의 절차적 함수는 충분 검증. |
| SC-003 (컨슈머 장애 흡수) | (Phase 4 — T025) | Pending — Phase 3 범위 외 |
| SC-004 (Outbox 재발행) | (Phase 4 — T026) | Pending — P2-A 결함 진단과 함께 처리 권장 |
| SC-005 (멱등) | (Phase 5 — T027~T029) | Pending — Phase 5 |
| SC-006 (인수 기준 ↔ 테스트 매핑) | (tasks.md 자체) | ✅ T010/T011 클래스 Javadoc 에 SC 매핑 명시 |
| SC-007 (시크릿 미노출) | `./gradlew verifySecretLogScan` | ✅ Phase 3 clean — 신규 코드 0 match |

---

## 잘된 점

- **R-002 멱등 구현의 정확성** (`VerificationResultService.handleCallback` line 75-105 + custom `updateToTerminalIfPending` line 49-70): SQL 단계 optimistic compare-and-set + 영향 행 수 분기 + INFO 로그(`requestId` 만). FR-004a + FR-011 + 헌법 V 가 한 코드 경로에 정확히 표현됨. ERROR 케이스 record 저장 skip(clarify Q2) 도 명시적 분기로 코드화.
- **R-008 cutover 의 부산물 처리** — `eventService.publish(VerificationEvent)` 제거가 AFTER_COMMIT 발행 경로를 끊을 위험을 신규 `OutboxEventRecordedEvent` + `VerificationOutboxPublishListener` 도입으로 복원. dead listener (`VerificationExternalMessageListener`) 가 listen 하는 event type 이 달라 **중복 발행 0** 으로 안전 분리됨.
- **헌법 V 정합의 일관성**: 신규 4개 production 파일(`VerificationResultService` / `InternalVerificationResultController` / `VerificationExternalEventRecorder` / `VerificationOutboxPublishListener`) 의 모든 로그가 `requestId` UUID 만 출력. callback payload 본문 평문 0. `IllegalArgumentException` 의 message 까지 `requestId` 만 포함하도록 통제.

---

## 다음 단계 권장 (Phase 4 진입 시)

1. **P2-A 결함 진단 우선**: SC-004 작성 전 `VerificationRedisStreamPublisher.publish()` 의 트랜잭션·dirty checking 경로를 진단. `Propagation.REQUIRES_NEW` 명시 또는 명시적 `outboxRepository.save()` 호출로 dirty checking 의존 제거 검토.
2. **P1-1 cutover 일정 확정**: BRIEF §3-1 stream key 정합 cutover 는 Polish (T030~T033) 또는 별도 PR — 둘 중 어느 일정에 들어가는지 PR 본문에 명시.
3. **Phase 3 PR 본문 known issues**: (a) outbox status PUBLISHED 전이 미관측 — Phase 4 진단 예정, (b) stream entry key set 이 BRIEF §3-1 미정합 — Polish/별도 PR 처리, (c) dead path 컴포넌트 5건 — Polish 일괄 cleanup.

---

## Phase 3 최종 정리

**Phase 3 commit 가능 여부**: **Y (conditional)** — P1-1 은 impl-report·progress.md 가 이미 분리 추적 중이며 본 PR 의 신규 결함 아님(Spec 002 잔재). Phase 3 의 정의된 범위(R-008 cutover + US1 MVP) 는 모두 충족. PR 본문에 known issues 3건이 가시화되어야 함.

**헌법 NON-NEGOTIABLE (I/IV/V)** 위반 0건 — 모든 게이트 통과.

**P1**: 1건 (P1-1, 분리 추적 중 — Polish/별도 PR 일정 확정 필수).
**P2**: 6건 (A·B·C·D·E·F — Phase 4/Polish 처리).
**P3**: 4건 (제안).

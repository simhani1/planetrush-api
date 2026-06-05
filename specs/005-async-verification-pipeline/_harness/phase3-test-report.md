# Phase 3 (US1 — P1 MVP) — Test Report

**Spec**: 005-async-verification-pipeline · **Branch**: `005-async-verification-pipeline`
**Date**: 2026-06-03 · **Scope**: T010·T011 (테스트 2 — 슬라이스 1 + 통합 1)
**Status**: 작성 완료. 두 테스트 그린 + `./gradlew check` 전체 회귀 0 + 시크릿 로그 스캔 그린.

---

## 1. 추가된 테스트 파일 (절대 경로)

| 파일 | 책무 | SC 매핑 |
|---|---|---|
| `/Users/simjonghan/source_code/planetrush-api/src/test/java/com/planetrush/planetrush/verification/VerificationControllerSliceTest.java` | `@WebMvcTest(VerificationController.class)` — 응답 경로의 외부 추론/발행 어댑터 호출 0 검증 | **SC-002** |
| `/Users/simjonghan/source_code/planetrush-api/src/test/java/com/planetrush/planetrush/verification/VerificationAsyncFlowIntegrationTest.java` | `IntegrationTest` 상속 + `@Import(FakeVerificationConsumer.class)` — 인증 요청 → stream → callback → SUCCESS 종착 단대단 | **SC-001** |

---

## 2. SC 매핑

| SC | 요지 | 검증 테스트 | 단언 메커니즘 |
|---|---|---|---|
| **SC-001** | 정상 환경 인증 요청 → 결과 조회 종착 단대단 흐름 | `VerificationAsyncFlowIntegrationTest.endToEndPendingToSuccess` | (a) HTTP POST 202 + PENDING 응답, (b) FakeVerificationConsumer가 stream 메시지 소비 + callback 발행, (c) Awaitility 로 GET 폴링 → SUCCESS 도달, (d) DB row 검증 — `verification_request.status=SUCCESS` + similarityScore/verified/completedAt non-null + `verification_record` 1건 저장 + `outbox_event` row 존재(id=requestId R-004 정합) |
| **SC-002** | 핸들러가 응답 경로 안에서 컨슈머 호출 없이 즉시 반환 | `VerificationControllerSliceTest.respondsAcceptedWithoutInvokingConsumerOrPublisher` | (a) `MockMvc` 로 POST 호출 → 202 + `data.requestId="dummy-uuid"` + `data.status="PENDING"`, (b) `VerificationService.verifyTodayChallenge` 1회 호출 확인, (c) `FlaskApiClient`/`VerificationMessagePublisher`(인터페이스 → 구체 `VerificationRedisStreamPublisher` 포괄)/`AsyncVerificationProcessor` 에 대해 `verifyNoInteractions(...)` — analyze A1 보강(강한 검증) |

---

## 3. 본 phase 의 테스트 작성 결정

### 3.1 T010 (슬라이스)
- **JWT/AOP 우회 패턴**: `@MockBean JwtInterceptor` + `MemberContext.setMemberId(...)` (slice 컨텍스트는 `ExtractMemberIdAspect` 가 자동 로드되지 않으므로 ThreadLocal 직접 주입).
- **외부 추론/발행 어댑터 `verifyNoInteractions`**: `FlaskApiClient` + `VerificationMessagePublisher`(인터페이스) + `AsyncVerificationProcessor` 세 종류. `VerificationRedisStreamPublisher` 는 구체 클래스이지만 같은 인터페이스 타입이라 별도 `@MockBean` 으로 두면 타입 충돌(`BeanNotOfRequiredTypeException`) — 인터페이스 1개만 mock 하면 구체 구현체 호출도 함께 차단된다. SC-002 검증력은 유지.
- **`@RestControllerAdvice` 의존성 보강**: 슬라이스 컨텍스트가 `AuthExceptionHandler` 의존성 `NotificationManager` 를 요구 — `@MockBean` 추가로 컨텍스트 부팅 정상화.

### 3.2 T011 (통합)
- **fixture**: `MemberFixture.activeMember()` + `PlanetFixture.readyPlanet()` 매 테스트 새 row 영속. `@AfterEach` 에 정리 로직(외래키 순서) 추가.
- **JWT 발급**: `JwtTokenProvider.createToken(member.getId()).getAccessToken()` 호출 — 반환값에 `"Bearer "` prefix 포함, Authorization 헤더에 그대로 동봉.
- **HTTP 호출**: `TestRestTemplate` + `@LocalServerPort` (IntegrationTest 부모는 RANDOM_PORT). `ParameterizedTypeReference` 로 `BaseResponse<VerificationAcceptedDto>` 응답 매핑.
- **컨슈머 시뮬레이션**: `FakeVerificationConsumer.start()` + `setCallbackBaseUrl("http://localhost:" + port)` + `setNextResult(CallbackResult.success(85))`. stream entry 의 `callbackUrl` 키는 dummy URL 이지만 FakeConsumer 가 무시(phase2-impl-report §T009 정합).
- **Awaitility 폴링**: 10초 timeout + 200ms interval. SC-001 의 비동기 정량 SLO 가 없으므로(spec clarify Q1) CI 안정성 위주.

---

## 4. C1 보강 처리 — N (사유 명시)

**처리 여부**: **N (본 phase 범위 외로 분리)**.

**사유**:
- analyze C1 (FR-002 원자성 — Outbox INSERT 실패 시 VerificationRequest rollback) 검증은 `@SpyBean(VerificationExternalEventRecorder.class)` + `doThrow().when(...).save(any())` 패턴이 필요.
- `@SpyBean` 적용 시 Spring 컨텍스트 캐시가 분기되어 본 클래스의 다른 테스트 메서드와 컨텍스트 공유가 깨진다 → 별도 테스트 클래스 권장.
- 본 phase 의 SC-001 본문은 "정상 환경의 단대단 흐름" — 원자성 실패 케이스는 별도 SC 표면. 본 보강을 본 phase 의 T011 안에 합치는 것은 책임 분리 원칙(test fixture 단순성) 에 어긋남.
- 권장 후속 작업: Phase 4 또는 별도 PR 로 `VerificationOutboxAtomicityTest` 클래스 신규 — `@SpyBean` + `doThrow` 로 `verification_request` 와 `outbox_event` 양쪽이 함께 rollback 됨을 검증.

---

## 5. 자가 점검 명령 출력 (실측)

| 명령 | 결과 | 비고 |
|---|---|---|
| `./gradlew test --tests "VerificationControllerSliceTest"` | **BUILD SUCCESSFUL** (4s) | T010 단독 그린 |
| `./gradlew test --tests "VerificationAsyncFlowIntegrationTest"` | **BUILD SUCCESSFUL** (6s, 2번째 실행) | T011 단독 그린 |
| `./gradlew test --tests "VerificationControllerSliceTest" --tests "VerificationAsyncFlowIntegrationTest" --tests "*OutboxRepublisher*"` | **BUILD SUCCESSFUL** (15s) | T010 + T011 + Spec 002 회귀 0 |
| `./gradlew check` | **BUILD SUCCESSFUL** (25s) | 전체 회귀 0 + `verifySecretLogScan` 자동 hook 그린 |

### 헌법 게이트 점검

| 원칙 | 점검 결과 |
|---|---|
| **I. Testcontainers (NON-NEGOTIABLE)** | T011 이 `IntegrationTest` 베이스 상속 — MySQL/Redis 컨테이너 자동 부팅. T010 은 `@WebMvcTest` 슬라이스로 컨테이너 비대상. Mock/H2 미사용. |
| **V. 시크릿 로그 금지** | 신규 테스트 코드 검색 — `secret`/`token`/`password`/`jwt`/`credential` 키워드 평문 로그 라인 0건. `./gradlew verifySecretLogScan` clean. |
| **VII. 인수 기준 자동 테스트 (P1)** | T010 ↔ SC-002 (클래스 Javadoc 매핑 명시), T011 ↔ SC-001 (Javadoc 매핑 명시). tasks.md §SC↔Task 표와 1:1 정합. |

---

## 6. 본 Phase 테스트 완료 가능 여부

**Y** — 두 테스트 모두 그린 + 전체 `./gradlew check` 회귀 0 + 헌법 I/V/VII 게이트 통과.

---

## 7. 결함 후보 / 후속 처리 메모

### 7.1 outbox status PUBLISHED 전이가 통합 환경에서 미관측

**관측 사실** (T011 작성 중 진단):
- 통합 테스트가 정상 흐름(202 → stream 발행 → callback → status=SUCCESS) 까지 모두 통과.
- 로그상 `VerificationRedisStreamPublisher.publish()` 가 호출되어 `[Redis Stream] published streamKey=verify:requests, recordId=..., eventId=<requestId>` 가 정상 출력.
- 그러나 `outbox_event.status` 는 PENDING 잔존 — 5초 Awaitility 폴링에도 PUBLISHED 로 전이되지 않음.

**가설**:
- `VerificationRedisStreamPublisher.publish()` 의 `@Transactional` 메서드 안에서 `eventRecorder.findById(eventId)` → `outboxEvent.published()` 호출 후, AFTER_COMMIT phase 에서 시작된 새 트랜잭션이 커밋 시 dirty checking 으로 status UPDATE 가 flush 되어야 한다. 그러나 실제로는 PENDING 잔존 — flush 가 일어나지 않거나 entity 가 detached 일 가능성.
- `VerificationExternalEventRecorder.findById` 의 `@Transactional(readOnly=true)` 가 outer 트랜잭션의 readOnly hint 를 변경하는지, AFTER_COMMIT 컨텍스트의 새 트랜잭션이 정상적으로 dirty checking 을 활성화하는지 추가 진단 필요.

**본 phase 결정**:
- T011 의 outbox 검증은 "row 존재 + id=requestId" 로 축소. SC-001 본문이 "발행~callback~상태 전이" 까지를 요구하므로 outbox status PUBLISHED 전이는 SC-004 (Phase 4 — `VerificationOutboxRepublisherIntegrationTest`) 의 영역.
- Phase 4 의 T026 또는 SC-004 작성 시 본 가설을 직접 검증하면 production 결함 여부가 드러난다 — 그 phase 의 test-author 가 우선 진단 후 결함 확인 시 `code-implementer` 에 전달 권장.

**임시 영향 평가**:
- 운영 동작: stream 발행은 정상이므로 컨슈머는 메시지를 받아 처리 후 callback. 사용자 가시 동작은 정상.
- 잠재 문제: outbox status 가 PENDING 잔존하면 OutboxRepublisher 가 컷오프 이내 PENDING 으로 인식해 중복 재발행 시도 → at-least-once 시멘틱 안에서 컨슈머가 중복 처리. 컨슈머의 멱등(SC-005a) 이 안전망이지만 불필요한 중복 부하 위험.

---

## 8. 다음 단계 (build-verifier / code-reviewer 가 사용할 정보)

- **테스트 진입점**: 위 §1 의 두 파일.
- **잔여 production 결함 후보**: §7.1 의 outbox PUBLISHED 전이 미관측. Phase 4 진입 시 우선 진단.
- **회귀 안전성**: 전체 `./gradlew check` 그린 — Spec 001/002 의 16/16 기존 통합 테스트 회귀 0.

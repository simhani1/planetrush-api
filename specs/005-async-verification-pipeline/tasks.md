---
description: "Tasks for spec 005-async-verification-pipeline"
---

# Tasks: Async Verification Pipeline — 챌린지 인증 비동기 처리 노출

**Input**: Design documents from `/specs/005-async-verification-pipeline/`

**Prerequisites**: plan.md ✓ · spec.md ✓ · research.md ✓ · data-model.md ✓ · contracts/ ✓ · quickstart.md ✓

**Tests**: 헌법 VII(인수 기준은 자동 테스트로 검증)에 따라 모든 SC가 자동 테스트 태스크와 1:1 매핑된다. 본 tasks.md 의 가장 아래 §SC↔Task 매핑 표 참고.

**Organization**: User stories 별 독립 implementation·test. US1 = MVP.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·미완료 의존 0 — 병렬 가능
- **[Story]**: US1 / US2 / US3 (Setup·Foundational·Polish 단계는 라벨 없음)

## Path Conventions

Spring Boot 단일 모듈. 모든 경로는 repo root 기준.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 환경 설정값 외부화 — 도메인 코드 작성 전 yml 키만 확정.

- [ ] T001 `application.yml` / `application-dev.yml` / `application-prod.yml` / `application-test.yml` 에 `app.verification.*` 설정 키 추가 — `callback-url`, `threshold` (기본 `"0.088"`), `stream-name` (기본 `verify:requests`). 환경별 callback URL 값은 quickstart.md §6-3 표 기준.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 US 가 의존하는 entity·repository·Outbox payload 정합·security·테스트 인프라.

**⚠️ CRITICAL**: 본 단계 완료 전 어떤 US 작업도 시작 금지.

- [ ] T002 [P] `src/main/java/com/planetrush/planetrush/verification/domain/VerificationRequestStatus.java` 신규 — enum `PENDING`/`SUCCESS`/`FAIL`/`ERROR`.
- [ ] T003 [P] `src/main/java/com/planetrush/planetrush/verification/domain/VerificationRequest.java` 신규 — data-model.md §VerificationRequest 그대로. `@Id String(36)`(UUID), `@Enumerated(EnumType.STRING) status`, `@CreationTimestamp createdAt`, nullable 결과 필드. `@Table` 의 `indexes` 에 `idx_verification_request_member_status`.
- [ ] T004 [P] `src/main/java/com/planetrush/planetrush/outbox/dto/OutboxRecordCommand.java` 수정 — `requestId` 필드 추가(record 시그니처 확장). 기존 호출처는 신규 필드 명시적 주입 필요.
- [ ] T005 `src/main/java/com/planetrush/planetrush/outbox/VerificationExternalEventRecorder.java` 수정 — `save(OutboxRecordCommand)` 가 `command.requestId()` 를 `OutboxEvent.id` 로 사용(R-004). `buildPayload` 가 BRIEF §3-1 키(`requestId`, `standardImgUrl`, `targetImgUrl`, `callbackUrl`, `threshold`)를 출력하도록 변경. `callbackUrl`·`threshold` 는 `@Value("${app.verification.callback-url}")`·`@Value("${app.verification.threshold:0.088}")` 주입.
- [ ] T006 [P] `src/main/java/com/planetrush/planetrush/verification/repository/VerificationRequestRepository.java` 신규 — `JpaRepository<VerificationRequest, String>` 확장 + custom 인터페이스 결합. 기본 메서드만 우선.
- [ ] T007 [P] `src/main/java/com/planetrush/planetrush/verification/domain/VerificationRecord.java` 수정 — `@Table(uniqueConstraints = @UniqueConstraint(name = "uniq_verification_record_member_planet_date", columnNames = {"member_id", "planet_id", "upload_date_only"}))` 추가. Generated column `upload_date_only DATE GENERATED ALWAYS AS (DATE(upload_date)) STORED` 는 ddl-auto 신뢰 어려우므로 `src/main/resources/schema-mysql-uniq.sql` 에 명시하고 `application.yml` 의 `spring.sql.init.mode=never` 유지(운영은 수동 DDL — quickstart §6-2). 테스트 프로필은 `application-test.yml` 의 `spring.sql.init.mode=always` + `schema.sql` 자동 적용으로 해결.
- [ ] T008 [P] `src/main/java/com/planetrush/planetrush/core/config/SecurityConfig.java` (또는 동등 위치) 수정 — `/api/v1/internal/**` 경로를 `JwtAuthenticationFilter` 인증 화이트리스트에 추가(R-005). 통과 시 정상 컨트롤러 진입.
- [ ] T009 `src/test/java/com/planetrush/planetrush/verification/testsupport/FakeVerificationConsumer.java` 신규 — XREADGROUP 폴링 + MockMvc 또는 RestClient callback 호출. `start()`/`stop()` API. 통합 테스트 안에서 컨슈머 다운/복구 모의용(R-006). 테스트 라이프사이클은 `@TestComponent` + `@Autowired` 주입.

**Checkpoint**: T001~T009 완료 시 모든 US 동시 진입 가능.

---

## Phase 3: User Story 1 — 인증 요청은 즉시 접수되고 결과는 폴링으로 도착한다 (Priority: P1) 🎯 MVP

**Goal**: 클라이언트가 인증 이미지를 업로드하면 즉시 202 응답 + requestId 반환, 백그라운드 처리 후 폴링으로 결과 수신.

**Independent Test (SC-001/002 매핑)**: 컨슈머·Spring·Redis 정상 기동에서 인증 요청 1건 → 즉시 202 응답 → 폴링 → 수 초 내 SUCCESS/FAIL 결과 수신. 슬라이스 테스트로 응답 경로 안에 컨슈머 호출 0 확인.

### Tests for User Story 1 (헌법 VII — 인수 기준 자동 테스트)

- [ ] T010 [P] [US1] `src/test/java/com/planetrush/planetrush/verification/VerificationControllerSliceTest.java` 신규 — `@WebMvcTest(VerificationController.class)`. mocked `VerificationService` 가 더미 `VerificationDto(requestId, PENDING)` 반환. 검증: (a) 202 응답, (b) 본문에 `requestId` + `status:"PENDING"`, (c) 핸들러가 외부 추론/Redis publisher 빈을 호출하지 않음(MockBean 검증). **SC-002 매핑.**
- [ ] T011 [P] [US1] `src/test/java/com/planetrush/planetrush/verification/VerificationAsyncFlowIntegrationTest.java` 신규 — `IntegrationTest` 베이스(Spec 001) 상속. 시나리오: (1) `POST /api/v1/verify/planets/{id}` → 202 + requestId, (2) `FakeVerificationConsumer.start()` 로 stream 메시지 소비 + 자동 callback 호출, (3) Awaitility 로 polling endpoint 가 SUCCESS/FAIL 반환할 때까지 대기, (4) `verification_request`/`verification_record`/`outbox_event` row 검증. **SC-001 매핑.**

### Implementation for User Story 1

- [ ] T012 [P] [US1] `src/main/java/com/planetrush/planetrush/verification/service/dto/VerificationStatusDto.java` 신규 — Projections 대상 DTO(필드: `requestId`, `status`, `similarityScore`, `verified`, `errorMessage`).
- [ ] T013 [P] [US1] `src/main/java/com/planetrush/planetrush/verification/controller/res/VerificationStatusRes.java` 신규 — 컨트롤러 응답 래퍼(`BaseResponse` 와 결합 또는 정적 팩토리). status enum 은 String 직렬화.
- [ ] T014 [US1] `src/main/java/com/planetrush/planetrush/verification/repository/custom/VerificationRequestRepositoryCustom.java` + `VerificationRequestRepositoryCustomImpl.java` 신규 — QueryDSL `Projections.constructor(VerificationStatusDto.class, ...)` 로 `findStatusById(String requestId, Long memberId)` 구현(memberId 일치 가드 포함, 헌법 III 정합). T006 의 JPA 인터페이스에 `VerificationRequestRepositoryCustom` 결합.
- [ ] T015 [P] [US1] `src/main/java/com/planetrush/planetrush/verification/service/VerificationStatusService.java` 신규 — `findById(String requestId, Long memberId)` 메서드. entity getter 호출 0, custom repo 의 DTO 반환만 사용. 미존재 시 `VerificationRequestNotFoundException` throw.
- [ ] T016 [P] [US1] `src/main/java/com/planetrush/planetrush/verification/exception/VerificationRequestNotFoundException.java` 신규 + `src/main/java/com/planetrush/planetrush/core/exception/handler/VerificationExceptionHandler.java` 에 404 핸들러 추가.
- [ ] T017 [US1] `src/main/java/com/planetrush/planetrush/verification/controller/VerificationStatusController.java` 신규 — `GET /api/v1/verify/{request-id}`. `@RequireJwtToken`, `MemberContext.getMemberId()` 로 본인 가드. Service 호출 후 `VerificationStatusRes` 반환.
- [ ] T018 [P] [US1] `src/main/java/com/planetrush/planetrush/verification/controller/internal/req/VerificationCallbackReq.java` 신규 — Bean Validation: `requestId @NotBlank @Pattern(UUID)`, `similarityScore` `@Min(0) @Max(100)`, `verified` boolean, `error` `@Size(max=100)`, `message` `@Size(max=500)`. 페이로드 분기 규칙은 서비스 레이어 책임이라 DTO 자체는 모든 필드 nullable 로 둠(분기 규칙 위반은 서비스에서 400 발생).
- [ ] T019 [P] [US1] `src/main/java/com/planetrush/planetrush/verification/service/dto/VerificationCallbackCommand.java` 신규 — 내부 도메인 DTO(컨트롤러 → 서비스 인입 어댑터).
- [ ] T020 [US1] `src/main/java/com/planetrush/planetrush/verification/service/VerificationResultService.java` 신규 — callback 처리 진입점. 흐름(R-002·R-003):
  1. `UPDATE verification_request SET status=?, similarity_score=?, verified=?, error_message=?, completed_at=NOW() WHERE id=? AND status='PENDING'` 의 영향 행 수 확인. 0이면 멱등 흡수(`log.info("idempotent callback hit: {}", requestId)`, 200).
  2. SUCCESS/FAIL 인 경우 `verificationRecordRepository.save(...)` 시도. `DataIntegrityViolationException` catch 시 record 추가 저장 skip.
  3. ERROR 인 경우 record 저장 단계 건너뛰기(Q2).
  4. `@Transactional` 적용. 락 사용 없음.
  - 페이로드 분기 규칙 위반(`verified`/`similarityScore` 와 `error` 가 동시에 누락 또는 동시에 존재) 은 400 throw — 헌법 V 준수해 `requestId` 외 페이로드 내용 INFO 미출력.
- [ ] T021 [US1] `src/main/java/com/planetrush/planetrush/verification/controller/internal/InternalVerificationResultController.java` 신규 — `POST /api/v1/internal/verification-results`. `@Valid` 페이로드 → `VerificationCallbackCommand` 매핑 → 서비스 호출 → 200 응답. 의심 호출(존재하지 않는 requestId 등) 은 서비스 영향 행 수 0 분기에서 INFO 로그 후 200 흡수(FR-011).
- [ ] T022 [US1] `src/main/java/com/planetrush/planetrush/verification/service/VerificationService.java` 인터페이스 수정 — `verifyTodayChallenge(VerificationDto)` 반환 타입 `void` → `VerificationDto`(또는 신규 응답 DTO `VerificationAcceptedDto { requestId, status }`). 호출처(컨트롤러) 업데이트 가능.
- [ ] T023 [US1] `src/main/java/com/planetrush/planetrush/verification/service/VerificationServiceImpl.java` 수정 — `verifyTodayChallenge` 흐름 재구성(R-007):
  1. 기존 `AlreadyVerifiedException` 가드 유지(완료된 `VerificationRecord` 기준; PENDING 가드는 추가 안 함 — Q3).
  2. `String requestId = UUID.randomUUID().toString()`.
  3. `VerificationRequest(requestId, memberId, planetId, targetImgUrl, standardImgUrl, status=PENDING, createdAt=now)` 영속.
  4. `verificationExternalEventRecorder.save(new OutboxRecordCommand(requestId, planet.getStandardVerificationImg(), dto.getVerificationImgUrl(), memberId, planetId))` 호출.
  5. `requestId, "PENDING"` DTO 반환.
  6. `@Transactional` 안에서 (3)·(4) 가 동일 트랜잭션이어야 함을 확인.
  7. 기존 `eventService.publish(VerificationEvent ...)` 호출 라인은 제거(R-008 — Flask 동기 경로 분기 차단).
- [ ] T024 [US1] `src/main/java/com/planetrush/planetrush/verification/controller/VerificationController.java` 수정 — 응답을 `ResponseEntity.status(HttpStatus.ACCEPTED).body(BaseResponse.of(dto))` 로 변경. 서비스 반환 DTO 그대로 본문에 동봉.

**Checkpoint**: US1 완료 시 `/speckit-implement` 또는 부분 머지로 MVP 출시 가능. 컨슈머가 정상 가동하는 환경에서 단대단 동작.

---

## Phase 4: User Story 2 — 컨슈머가 죽어 있어도 사용자 요청은 실패하지 않는다 (Priority: P1)

**Goal**: 컨슈머 미가용 시 인증 요청이 5xx 가 아닌 PENDING 으로 보관 → 복구 후 자동 종착.

**Independent Test (SC-003/004 매핑)**: chaos test — 인증 N건 발행 도중 컨슈머 강제 종료 → PENDING 보존 → 컨슈머 재기동 → 모두 종착.

본 단계는 신규 구현이 거의 없다(US1 의 발행 경로 + Spec 002 Outbox Republisher 의 정합 검증 위주). 신규 테스트만 추가.

### Tests for User Story 2

- [ ] T025 [P] [US2] `src/test/java/com/planetrush/planetrush/verification/VerificationConsumerOutageIntegrationTest.java` 신규 — `IntegrationTest` 상속. 시나리오: (1) `FakeVerificationConsumer.start()`, (2) 인증 요청 10건 발행, (3) ~5건 처리됐을 때 `FakeVerificationConsumer.stop()`(컨슈머 다운 모의), (4) 남은 요청들의 status 가 PENDING 임을 폴링으로 확인 + 실패율 0%, (5) `FakeVerificationConsumer.start()` 재기동, (6) Awaitility 로 모두 종착 상태로 전환됨을 확인. **SC-003 매핑.**
- [ ] T026 [P] [US2] `src/test/java/com/planetrush/planetrush/verification/VerificationOutboxRepublisherIntegrationTest.java` 신규 — `IntegrationTest` 상속. 시나리오: (1) Stream 발행을 일시 실패시키는 `VerificationMessagePublisher` 페이크(예: 첫 호출 throw)를 `@TestConfiguration` 으로 주입, (2) 인증 요청 1건 발행 → `OutboxEvent.status=PENDING` 잔존 확인, (3) 페이크 발행을 정상으로 전환 + `OutboxRepublisher` 사이클 수동 호출(Spec 002 의 통합 테스트 패턴 재사용), (4) outbox 재발행 → stream 메시지 도달 → `FakeVerificationConsumer` callback → status 종착 확인. **SC-004 매핑.**

---

## Phase 5: User Story 3 — 컨슈머 callback 중복은 도메인에 한 번만 반영된다 (Priority: P2)

**Goal**: 동일 `requestId` 중복 callback + 같은 사용자·챌린지·날짜의 서로 다른 `requestId` 중복 callback 모두 도메인 효과 1회만 발생.

**Independent Test (SC-005 매핑)**: 두 종류 멱등을 각각 검증. 구현 자체는 US1 의 callback handler 에 이미 포함됨(T020).

### Tests for User Story 3

- [ ] T027 [P] [US3] `src/test/java/com/planetrush/planetrush/verification/VerificationCallbackIdempotencyTest.java` 신규 — `IntegrationTest` 상속. 시나리오: (1) 인증 요청 1건 발행, (2) 정상 callback 1회 호출 → `VerificationRequest.status=SUCCESS`, `VerificationRecord` 1건 저장 확인, (3) 동일 payload callback 2회 추가 호출 → 두 번 모두 200, (4) DB 상태 불변(status·record 카운트 동일) 확인. 추가: ERROR 종착 후 SUCCESS callback 도착 시 상태 역전 금지 검증(spec US3 Acceptance Scenario 2). **SC-005a 매핑.**
- [ ] T028 [P] [US3] `src/test/java/com/planetrush/planetrush/verification/VerificationDailyIdempotencyTest.java` 신규 — `IntegrationTest` 상속. 시나리오: (1) 같은 사용자·planet 으로 인증 요청 2건 발행(서로 다른 requestId), (2) 두 PENDING 모두 SUCCESS callback 도착(직렬 실행), (3) 두 `VerificationRequest` 모두 SUCCESS 종착 + `VerificationRecord` 정확히 1건만 저장 확인 — 저장 전 `existsTodayRecord`(verified 무관, unique 제약과 동일 키) 조회-후-저장 멱등. callback 동시 도착(race) 은 미발생 전제(US3) 라 직렬 시나리오만 검증한다. **SC-005b 매핑.**
- [ ] T029 [P] [US3] `src/test/java/com/planetrush/planetrush/verification/InternalVerificationResultControllerSliceTest.java` 신규 — `@WebMvcTest(InternalVerificationResultController.class)`. 페이로드 분기 검증: (a) 정상 payload → 서비스 호출 1회, (b) 오류 payload(`error` 필드) → 서비스 호출 1회(분기 인자 다름), (c) 두 패턴 모두 어긋남(필수 필드 누락) → 400, (d) UUID 형식 위반 → 400, (e) 존재하지 않는 requestId 도 200(FR-011 흡수 — mocked 서비스 영향 행 수 0 모의). 부수: 헌법 V 정합 — 페이로드 내용이 INFO 로그에 평문 노출되지 않음 (log capture 검증).

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 헌법 게이트 통과 + R-008 cutover + plan 사후 갱신.

- [ ] T030 시크릿 로그 스캔 게이트 통과 — 로컬에서 `./gradlew verifySecretLogScan` 실행해 clean. 신규 로그(특히 `VerificationResultService` 와 `InternalVerificationResultController`)에 `secret`/`token`/`password`/`jwt`/`credential` 키워드 평문 미출현 확인. **SC-007 매핑.**
- [ ] T031 기존 동기 Flask 호출 경로(R-008) 차단 — `VerificationServiceImpl` 의 `eventService.publish(VerificationEvent ...)` 호출 라인 제거(T023 의 일부지만 본 태스크에서 명시적 확인). `AsyncVerificationProcessor`/`FlaskApiClient` 자체 삭제는 회귀 안전 마진 확보 후 별도 PR. 본 PR 의 머지 시점에 신규 흐름만 트리거됨을 통합 테스트로 보장.
- [ ] T032 `plan.md` 의 §Post-Implementation Constitution Re-Check 표를 채운다 — 원칙 I~VII 별 구현 결과 + 구현 중 발견·결정 사항. Spec 002 plan §Post-Implementation 패턴 참고.
- [ ] T033 PR 본문 체크리스트 점검 (헌법 VI) — Claude 리뷰 + Codex 리뷰 결과 첨부, 각 코멘트에 대해 "채택" 또는 "기각 + 사유" 명시. 본 태스크는 PR 작성 시점.

---

## Dependencies / Story Completion Order

```
Phase 1 (Setup, T001)
   │
   ▼
Phase 2 (Foundational, T002~T009) ─ 차단 단계, 전 US 의 전제
   │
   ▼
Phase 3 (US1, T010~T024) ─ MVP. 단독 머지 가능.
   │
   ├──► Phase 4 (US2, T025~T026) ─ US1 의 발행 경로 + Spec 002 인프라 의존. US1 완료 후 진입.
   │
   └──► Phase 5 (US3, T027~T029) ─ US1 의 callback handler 멱등 로직 의존. US1 완료 후 진입.
                                    Phase 4 와 병렬 가능.
   │
   ▼
Phase 6 (Polish, T030~T033) ─ 모든 US 완료 후 PR/머지 준비 단계.
```

**Parallel execution windows**:
- Phase 2: T002·T003·T004·T006·T007·T008 [P] (서로 다른 파일). T005·T009 는 의존성 있음.
- Phase 3 (테스트): T010·T011 [P] (서로 다른 테스트 파일).
- Phase 3 (구현): T012·T013·T015·T016·T018·T019 [P] (서로 다른 신규 파일). T014·T017·T020·T021·T022·T023·T024 는 순차.
- Phase 4: T025·T026 [P].
- Phase 5: T027·T028·T029 [P].

---

## Implementation Strategy

1. **MVP first**: Phase 1 → Phase 2 → Phase 3 (US1) 까지만으로 정상 환경 단대단이 통합 테스트로 검증된다. 머지 가능 시점.
2. **신뢰성 카드 (US2)**: Phase 4 진입. chaos test 가 자동 회귀로 들어가면 향후 컨슈머/인프라 교체에도 안전망이 됨. 포트폴리오 narrative 의 핵심 증거.
3. **at-least-once 의 보강 (US3)**: Phase 5 진입. 두 종류 멱등 검증. US1 의 callback handler 로직 이미 완성됐으므로 본 단계는 테스트만.
4. **PR 준비 (Phase 6)**: 헌법 게이트(SC-007) + plan 사후 갱신 + 듀얼 AI 리뷰 첨부.

---

## SC ↔ Task Mapping (헌법 VII — 인수 기준은 자동 테스트로 검증)

| SC | 인수 기준 요지 | 검증 Task | 검증 메커니즘 |
|---|---|---|---|
| SC-001 | 정상 환경에서 인증 요청 → 결과 조회 종착까지 단대단 흐름 통과 | **T011** | `VerificationAsyncFlowIntegrationTest` |
| SC-002 | 핸들러가 컨슈머 추론 시간과 무관하게 즉시 반환, 응답 경로에 컨슈머 호출 0 | **T010** | `VerificationControllerSliceTest` (`@WebMvcTest` + MockBean 검증) |
| SC-003 | 컨슈머 미가용 시 인증 10건 PENDING 보존, 실패율 0%, 복구 후 종착 전환 | **T025** | `VerificationConsumerOutageIntegrationTest` |
| SC-004 | Stream 발행 실패 시 Republisher 가 자동 재발행해 결국 종착 | **T026** | `VerificationOutboxRepublisherIntegrationTest` |
| SC-005a | 동일 requestId callback 2회 호출 시 상태 전이 1회, record 저장 1건 | **T027** | `VerificationCallbackIdempotencyTest` |
| SC-005b | 같은 사용자·챌린지·날짜 다중 requestId callback 모두 SUCCESS 도 record 1건만 보존(직렬, 동시 race 미발생 전제) | **T028** | `VerificationDailyIdempotencyTest` |
| SC-006 | 인수 기준 ↔ 테스트 매핑이 spec ↔ tasks 양방향으로 명시 | **본 표 자체** | 본 tasks.md 의 §SC↔Task Mapping |
| SC-007 | 인증 처리/callback 경로에 시크릿 평문 로그 미출현 | **T030** | `./gradlew verifySecretLogScan` 게이트 |

추가 보조 검증:
- **헌법 IV (Outbox 강제)**: T023 의 `eventService.publish(...)` 제거 + T005 의 Outbox payload 정합. `RedisTemplate.opsForStream().add(...)` 직접 호출이 도메인/서비스 레이어에 없음을 코드 리뷰에서 확인.
- **헌법 III (QueryDSL Projections)**: T014 의 `Projections.constructor` 사용. 서비스에서 entity getter 호출 0 — `VerificationStatusService` 코드 리뷰에서 확인.
- **헌법 II (어댑터 격리)**: callback 컨트롤러는 페이로드 검증·DTO 매핑만 담당, 도메인 로직은 `VerificationResultService` 위임 — T020·T021 의 책임 분리.
- **헌법 I (Testcontainers)**: T011·T025·T026·T027·T028 모두 `IntegrationTest` 베이스 상속 — Mock/H2 미사용 확인.

---

## 산출물 위치 요약

- 신규 production 코드: 13개 파일 (entity 2, repo 2 [interface+impl], service 2, dto 4, controller 2, exception 1)
- 수정 production 코드: 6개 파일 (`VerificationController`, `VerificationService(인터페이스)`, `VerificationServiceImpl`, `VerificationRecord`, `OutboxRecordCommand`, `VerificationExternalEventRecorder`)
- 설정: 4개 `application*.yml` + 1개 `schema.sql`(테스트 전용)
- 보안: `SecurityConfig` 1개 수정
- 신규 테스트 코드: 7개 파일 (슬라이스 2 + 통합 4 + 테스트 인프라 `FakeVerificationConsumer` 1)
- 게이트 명령: `./gradlew check`, `./gradlew verifySecretLogScan`

총 태스크 수: **33** (T001~T033)

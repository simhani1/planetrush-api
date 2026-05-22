---

description: "Task list for Spec 002 — Outbox Republisher Worker (simplified)"
---

# Tasks: Outbox Republisher Worker (At-Least-Once 보장 1/2)

**Input**: Design documents from `/specs/002-outbox-republisher/`

**Prerequisites**: plan.md (✅), spec.md (✅, 단순 버전), research.md (✅), quickstart.md (✅)

**Tests**: 인수 기준(SC-001~005)이 자동 테스트로 검증되어야 한다(Constitution 원칙 VII). 통합 테스트는 Spec 001의 `IntegrationTest`(Testcontainers) 베이스를 상속한다.

**Organization**: Tasks are grouped by user story. 단순 버전 특성상 폴러 핵심 구현은 Foundational + US1에 모이고, US2·US3는 그 위에 동작을 검증하는 테스트 중심 phase다.

## Format: `[ID] [P?] [Story?] Description with file path`

- **[P]**: 다른 파일, 선행 미완 의존 없음 → 병렬 가능
- **[Story]**: US1/US2/US3 — Phase 3+ 에만 부착

## Path Conventions

단일 Spring Boot 모듈:
- Production: `src/main/java/com/planetrush/planetrush/...`
- Resources: `src/main/resources/...`
- Tests: `src/test/java/com/planetrush/planetrush/...`

## 설계 전제 (plan/research 반영)

- 신규 의존성 0. `OutboxEvent`/`OutboxStatus` 스키마 변경 0.
- 폴러는 `outbox/republisher` 신규 패키지. 발행은 기존 `VerificationMessagePublisher` 재사용.
- 락: `OutboxRepository` native query `FOR UPDATE SKIP LOCKED` (research R-001).
- 트랜잭션: 폴러 사이클당 1개 (research R-002).
- 컷오프/주기/배치: `OutboxRepublisherProperties` 설정 외부화 (research R-006).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 설정 외부화 + 스케줄러 종료 정책.

- [X] T001 [P] Create `src/main/java/com/planetrush/planetrush/outbox/republisher/OutboxRepublisherProperties.java` — `@ConfigurationProperties(prefix = "outbox.republisher")` record. 필드: `enabled`(boolean, default true), `pollingIntervalMs`(long, 5000), `cutoffMinutes`(long, 5), `batchSize`(int, 100). `@Validated` + 양수 제약.
- [X] T002 [P] Add `outbox.republisher.*` 기본값 to `src/main/resources/application.yml` (`enabled: true`, `polling-interval-ms: 5000`, `cutoff-minutes: 5`, `batch-size: 100`) and override `outbox.republisher.enabled: false` in `src/main/resources/application-test.yml`.
- [X] T003 [P] Add `spring.task.scheduling.shutdown.await-termination: true` + `await-termination-period: 20s` to `src/main/resources/application.yml` (research R-004, graceful shutdown).

**Checkpoint**: 설정 바인딩 가능. `@ConfigurationProperties` 스캔 등록(`@ConfigurationPropertiesScan` 또는 `@EnableConfigurationProperties`)은 T006에서 폴러와 함께 처리.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 폴러가 의존하는 쿼리·변환 유틸. 모든 User Story의 전제.

**⚠️ CRITICAL**: 이 Phase가 끝나야 US1/US2/US3 진입 가능.

- [X] T004 Add SKIP LOCKED 폴링 쿼리 to `src/main/java/com/planetrush/planetrush/outbox/repository/OutboxRepository.java` — native query 메서드 `findRepublishableForUpdateSkipLocked(Instant cutoff, int batchSize)`: `SELECT * FROM outbox_event WHERE status='PENDING' AND created_at > :cutoff ORDER BY created_at LIMIT :batchSize FOR UPDATE SKIP LOCKED` (research R-001).
- [X] T005 [P] Create `src/main/java/com/planetrush/planetrush/outbox/republisher/VerificationOutboxPayload.java` — `OutboxEvent.payload` JSON 역직렬화용 record(`standardImgUrl`, `verificationImgUrl`, `memberId`, `planetId`) + `OutboxEvent` → `MessageCommand` 변환 로직(매핑: eventId=id, standardImg=standardImgUrl, targetImg=verificationImgUrl). research R-003.

**Checkpoint**: 폴링 쿼리 + payload 변환 준비 완료. 폴러 컴포넌트 작성 가능.

---

## Phase 3: User Story 1 - 발행 누락분 자동 재발행 (Priority: P1) 🎯 MVP

**Goal**: `PENDING`으로 남은 `OutboxEvent`를 폴러가 주기적으로 발견해 재발행한다.

**Independent Test**: Publisher에 일시 장애를 주입해 `OutboxEvent`를 `PENDING`으로 만든 뒤, 장애 해소 후 폴러 사이클을 직접 호출하면 재발행되어 `PUBLISHED`로 전환됨.

### Implementation for User Story 1

- [X] T006 [US1] Create `src/main/java/com/planetrush/planetrush/outbox/republisher/OutboxRepublisher.java` 골격 — `@Component`, `@ConditionalOnProperty(name="outbox.republisher.enabled", havingValue="true")`, 생성자 주입(`OutboxRepository`, `VerificationMessagePublisher`, `ObjectMapper`, `OutboxRepublisherProperties`). `@EnableConfigurationProperties(OutboxRepublisherProperties.class)`는 본 클래스 또는 별도 config에 부착.
- [X] T007 [US1] Implement `republishPending()` in `OutboxRepublisher.java` — `@Scheduled(fixedDelayString="${outbox.republisher.polling-interval-ms:5000}")` + `@Transactional`. 로직: cutoff 계산(`now - cutoffMinutes`) → `findRepublishableForUpdateSkipLocked` 조회 → 각 건 `VerificationOutboxPayload` 역직렬화 → `MessageCommand` 변환 → `messagePublisher.publish()` 호출. `publish()` 성공 시 내부에서 `published()` 전환됨(기존 동작 재사용). research R-002 트랜잭션 경계.
- [X] T008 [US1] Add `src/test/java/com/planetrush/planetrush/outbox/republisher/OutboxRepublisherIntegrationTest.java` — `extends IntegrationTest`. 카오스 시나리오(SC-001): `PENDING` OutboxEvent 저장 → `republishPending()` 직접 호출 → `status == PUBLISHED` 검증. 발행 1차 실패 후 재시도 케이스 포함(Mock publisher 또는 Redis 일시 장애 시뮬레이션).

**Checkpoint**: SC-001 통과. 폴러가 누락분을 재발행. US1 MVP 완성 — 단독으로 At-Least-Once 핵심 가치 제공.

---

## Phase 4: User Story 2 - 다중 인스턴스 이중 처리 0건 (Priority: P1)

**Goal**: 폴러 N개 동시 실행에서 동일 outbox가 정확히 1회만 발행된다.

**Independent Test**: `PENDING` 100건 + 폴러 2개 동시 실행 → 각 outbox 발행 1회 + `PUBLISHED` 100건.

### Implementation for User Story 2

> 동시성 보장 메커니즘(`FOR UPDATE SKIP LOCKED`)은 T004에서 이미 구현됨. 본 Phase는 그 동작을 검증한다.

- [X] T009 [US2] Add `src/test/java/com/planetrush/planetrush/outbox/republisher/OutboxRepublisherConcurrencyTest.java` — `extends IntegrationTest`. SC-002: `PENDING` OutboxEvent 100건 저장 → 폴러 사이클을 스레드 2개로 동시 실행(`ExecutorService` 또는 병렬 호출) → 각 outbox의 발행 횟수 정확히 1, `PUBLISHED` 카운트 100 검증. `@RepeatedTest(10)`으로 안정성 확인.

**Checkpoint**: SC-002 통과. 다중 인스턴스 안전 검증.

---

## Phase 5: User Story 3 - 오래된 PENDING 컷오프 제외 (Priority: P2)

**Goal**: `createdAt`이 컷오프 초과한 `PENDING`은 자동 재발행 대상에서 빠진다.

**Independent Test**: 컷오프 초과 `PENDING`과 컷오프 이내 `PENDING`을 섞어두고 폴러 실행 → 이내 건만 재발행.

### Implementation for User Story 3

> 컷오프 제외 메커니즘(`created_at > :cutoff`)은 T004 쿼리 + T001 properties에 이미 포함. 본 Phase는 그 동작을 검증한다.

- [X] T010 [US3] Add 컷오프 검증 테스트 to `src/test/java/com/planetrush/planetrush/outbox/republisher/OutboxRepublisherIntegrationTest.java` — SC-003: `createdAt`을 컷오프보다 오래되게 설정한 `PENDING` 1건 + 컷오프 이내 `PENDING` 1건 저장 → `republishPending()` 호출 → 이내 건만 `PUBLISHED`, 오래된 건은 `PENDING` 잔존 검증. `createdAt`은 `@CreationTimestamp`라 테스트에서 native update로 과거 시각 주입.

**Checkpoint**: SC-003 통과. poison message가 폴러 사이클을 영구 점유하지 않음.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 잔여 인수 기준 + 머지 준비.

- [ ] T011 [P] Add `src/test/java/com/planetrush/planetrush/outbox/republisher/OutboxRepublisherProfileTest.java` — SC-004: `test` 프로필 부팅 시 `OutboxRepublisher` 빈이 컨텍스트에 부재함 검증(`assertThatThrownBy(() -> context.getBean(OutboxRepublisher.class))` 또는 `ObjectProvider` 부재 확인).
- [ ] T012 [US1] Add 발행 지연 측정 to `OutboxRepublisherIntegrationTest.java` — SC-005: `PENDING` 저장 시각 ~ `PUBLISHED` 전환 시각 차이가 폴링 주기 + 1초 이내임을 측정(테스트에서는 폴러 직접 호출이므로 "주기 내 1회 호출로 발행됨"을 확인하는 형태로 검증).
- [ ] T013 Run full local validation:
  - `./gradlew test --tests "*OutboxRepublisher*"` 전 통과
  - `./gradlew verifySecretLogScan` clean (Spec 001 게이트)
  - `./gradlew check` 통과
- [ ] T014 Update `specs/002-outbox-republisher/plan.md` "Post-Design Constitution Re-Check" 섹션을 구현 완료 결과로 갱신 + 발견 사항 기록.

**Checkpoint**: 전 SC 통과. PR 머지 준비 완료.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)** → **Phase 2 (Foundational)** → **Phase 3·4·5 (User Stories)** → **Phase 6 (Polish)**
- US1(Phase 3)이 폴러 본체를 구현. US2(Phase 4)·US3(Phase 5)는 US1 완료 후 그 위에서 동작을 검증 — 사실상 US1 의존.

### Task-Level Dependencies

| Task | 차단 | 비고 |
|---|---|---|
| T001·T002·T003 | — | 서로 병렬 |
| T004 | — | Phase 1과 병렬 가능(다른 파일) |
| T005 | — | T004와 병렬 |
| T006 | T001, T004, T005 | 폴러 골격은 properties·repo·payload 필요 |
| T007 | T006 | 폴러 핵심 로직 |
| T008 | T007 | 카오스 통합 테스트 |
| T009 | T007 | 동시성 테스트 (폴러 동작 필요) |
| T010 | T007, T008 | 컷오프 검증 (T008 파일에 추가) |
| T011 | T006 | 프로필 비활성 검증 |
| T012 | T008 | T008 파일에 추가 |
| T013 | T008~T012 | 최종 검증 |
| T014 | T013 | 문서 갱신 |

### Within Each User Story

- US1: T006(골격) → T007(로직) → T008(검증). 구현 후 검증.
- US2: T009 — 단독(폴러 동작에 의존).
- US3: T010 — T008 파일에 케이스 추가.

---

## Parallel Execution Examples

### Phase 1+2 병렬

```
T001 [P] OutboxRepublisherProperties
T002 [P] application.yml 설정
T003 [P] scheduler shutdown 설정
T004     OutboxRepository SKIP LOCKED 쿼리   ┐ Phase 1과 병렬
T005 [P] VerificationOutboxPayload 변환      ┘
```

### Phase 6 병렬

```
T011 [P] 프로필 비활성 테스트
T012     발행 지연 측정 (T008 파일)
```

### 이도류 배치 제안

- **Claude (메인)**: T004~T008 (폴러 본체 + 카오스 테스트 — 설계 판단 집중)
- **Codex (워크트리)**: T001~T003 (설정 보일러플레이트) + T009·T011 (검증 테스트)

---

## Implementation Strategy

### MVP First

**US1(Phase 1·2·3)만으로 At-Least-Once 핵심이 완성**된다. 시간 압박 시 US1까지 머지하고 US2·US3 검증 테스트는 후속 커밋으로 분리 가능(권장은 단일 PR).

### Incremental Delivery (단일 PR 내부 커밋 단위)

- commit 1: Phase 1·2 (설정 + 쿼리 + 변환)
- commit 2: Phase 3 US1 (폴러 본체 + 카오스 테스트)
- commit 3: Phase 4·5 US2·US3 (동시성·컷오프 검증)
- commit 4: Phase 6 (polish + 최종 검증)

---

## Acceptance Criteria ↔ Task Mapping (Constitution VII)

| SC | 검증 Task | 검증 방식 |
|---|---|---|
| SC-001 (카오스 재발행) | T007·T008 | 일시 장애 주입 → 폴러 재발행 → PUBLISHED |
| SC-002 (동시성 이중 처리 0) | T004·T009 | SKIP LOCKED + 폴러 2개 RepeatedTest |
| SC-003 (컷오프 제외) | T004·T010 | 오래된 PENDING이 폴링에서 제외 |
| SC-004 (폴러 비활성) | T011 | test 프로필에서 빈 부재 |
| SC-005 (발행 지연) | T012 | PENDING→PUBLISHED 지연 측정 |

---

## Notes

- [P] = 다른 파일, 의존 없음
- 단순 버전: `retryCount`/`FAILED`/백오프/Mattermost 관련 task 전부 없음 (full 버전 38 task → 14 task)
- TDD를 강제하지는 않으나, T008 카오스 테스트를 T007 직후 빠르게 작성해 회귀 안전망 확보 권장
- 매 Phase 완료 시 git commit (squash-merge 가정)
- 시크릿 키워드를 포함한 신규 로그 금지 (Spec 001 `verifySecretLogScan` 게이트가 차단)

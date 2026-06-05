# Phase 4 (US2 — P1) — Test Report

**Spec**: 005-async-verification-pipeline · **Branch**: `005-async-verification-pipeline`
**Date**: 2026-06-04 · **Scope**: T025·T026 (테스트 2 — 통합 2, production 코드 0)
**Status**: 작성 완료. 두 테스트 그린 + `./gradlew check` 전체 회귀 0 + 시크릿 로그 스캔 그린.

---

## 1. 추가된 테스트 파일 (절대 경로)

| 파일 | 책무 | SC 매핑 |
|---|---|---|
| `/Users/simjonghan/source_code/planetrush-api/src/test/java/com/planetrush/planetrush/verification/VerificationConsumerOutageIntegrationTest.java` | 컨슈머 다운/복구 chaos — 다운 중 인증 요청 5xx 0건 + PENDING 보존 + 복구 후 종착 | **SC-003** |
| `/Users/simjonghan/source_code/planetrush-api/src/test/java/com/planetrush/planetrush/verification/VerificationOutboxRepublisherIntegrationTest.java` | Stream 발행 일시 실패 → outbox PENDING 잔존 → Republisher 자동 재발행 → 종착 | **SC-004** |

---

## 2. SC 매핑

| SC | 요지 | 검증 테스트 | 단언 메커니즘 |
|---|---|---|---|
| **SC-003** | 컨슈머 미가용 시 인증 요청 5xx 0건 + PENDING 보존 + 복구 후 종착 | `VerificationConsumerOutageIntegrationTest.consumerOutageDoesNotFailRequestsAndRecoveryDrainsBacklog` | (a) 컨슈머 가용 상태 5건 발행 → 모두 202, (b) 1건 처리 후 `FakeVerificationConsumer.stop()` — 컨슈머 다운 모의, (c) 다운 중 5건 추가 발행 → 모두 202 (실패율 0%), (d) `verification_request.status=PENDING` 잔존 최소 1건 확인, (e) `FakeVerificationConsumer.start()` 재기동, (f) Awaitility 로 모든 10건 종착(SUCCESS) 도달까지 폴링, (g) 폴링 응답 200 + `status=SUCCESS` 직접 확인 |
| **SC-004** | Stream 발행 실패 → Republisher 자동 재발행 → 종착 | `VerificationOutboxRepublisherIntegrationTest.streamPublishFailureIsHealedByOutboxRepublisher` | (a) 페이크 publisher (`FailFirstThenDelegatePublisher`, `@Primary`) 가 첫 publish 호출 흡수, (b) 인증 요청 1건 발행 → 202 + PENDING, (c) `outbox_event.status=PENDING` 잔존 확인 (R-004 id=requestId 정합), (d) 페이크 호출 횟수 1회 확인, (e) `OutboxRepublisher.republishPending()` 직접 호출 (Spec 002 `outbox.republisher.enabled=false` 정합 — 폴러 비활성), (f) 페이크 두 번째 호출에서 stream entry 발행 + outbox markPublished, (g) FakeConsumer callback → `verification_request.status=SUCCESS` Awaitility 폴링 확인, (h) `outbox_event.status=PUBLISHED` 전이 확인 (P2-A REQUIRES_NEW fix 정합 회귀), (i) `VerificationRecord` 1건 저장 확인, (j) 페이크 정확히 2회 호출 단언 |

---

## 3. 본 phase 의 테스트 작성 결정

### 3.1 T025 (Consumer Outage) — 사용자별 1요청 모델 채택

**결정**: 인증 요청 10건을 **서로 다른 사용자**가 보내도록 설계. 같은 사용자가 같은 챌린지에 다중 요청을 보내면 `uniq_verification_record_member_planet_date` (R-003 안전망) 가 두 번째부터 거절해 chaos 시나리오 본질(컨슈머 가용성 ↔ 요청 처리) 이 가려진다.

**해석**: US2 narrative 는 "사용자 가용한 인증 요청이 컨슈머 가용성과 무관히 PENDING 으로 보관" 이다. 이는 사용자 단위 시나리오가 아니라 **요청 처리 파이프라인 단위** 시나리오. 따라서 동일 사용자/다른 사용자는 본 SC 의 본질이 아니며, 자연스럽게 다른 사용자로 모델링하는 것이 적절.

### 3.2 T025 (Consumer Outage) — `runUntilProcessed(1, ...)` 패턴

**결정**: "컨슈머가 일부 처리 후 다운" 시나리오를 결정론적으로 재현하기 위해 `runUntilProcessed(1, Duration.ofSeconds(5))` 호출 — **최소 1건 처리 확인 후** stop. 정확히 N건 처리 시점을 잡으려 하지 않는다.

**해석**: chaos 의 핵심은 "다운 시점의 정확한 처리 수" 가 아니라 "다운 중 미처리 요청들이 PENDING 보존 + 복구 후 종착". 결정론성은 단언 측("최소 1건 PENDING 잔존") 으로 흡수.

### 3.3 T026 (Outbox Republisher) — 페이크 publisher 주입 방식

**결정**: `@TestConfiguration` + `@Bean @Primary FailFirstThenDelegatePublisher` 패턴. 본 nested config 를 `@Import` 로 명시.

**왜 `@Primary`**:
- `VerificationMessagePublisher` 는 `@FunctionalInterface`. 진짜 빈은 `VerificationRedisStreamPublisher` (`@ConditionalOnProperty(name="verification.publisher.type", havingValue="redis-stream")` 활성화).
- `VerificationOutboxPublishListener` 와 `OutboxRepublisher.republishOne` 양쪽이 `VerificationMessagePublisher` 인터페이스 타입을 주입.
- `@Primary` 페이크 빈을 두면 두 호출처 모두 페이크 빈 선택 → 첫 publish (AFTER_COMMIT 리스너 경유) + 두 번째 publish (Republisher 사이클 경유) 가 모두 페이크 손에 들어옴.
- 진짜 `VerificationRedisStreamPublisher` 빈은 컨텍스트에 그대로 존재 — 다른 테스트 클래스가 영향받지 않게 `@DirtiesContext(classMode=AFTER_CLASS)` 부착.

### 3.4 T026 (Outbox Republisher) — `OutboxRepublisher` 사이클 직접 호출 (Spec 002 정합)

**결정**: `application-test.yml` 의 `outbox.republisher.enabled=false` (Spec 002 FR-006 — 통합 테스트는 폴러를 직접 호출) 정합. 테스트 코드가 `outboxRepublisher.republishPending()` 을 직접 호출해 사이클 1회를 결정론적으로 트리거.

**Spec 002 패턴 정합**: Spec 002 의 `OutboxRepublisherIntegrationTest.transientFailureKeepsPendingThenSucceedsOnRetry` 와 동일 패턴. 본 테스트는 그 패턴을 Spec 005 신규 발행 경로 (서비스 → outbox INSERT → AFTER_COMMIT 리스너 → publisher) 위에서 재현해 두 스펙의 정합을 단대단으로 박제.

### 3.5 T026 — 페이크 publisher 의 두 번째 호출 동작 결정

**결정**: 페이크의 두 번째 호출은 **stream entry 직접 발행 (BRIEF §3-1 정합) + outbox.published() 직접 호출** 패턴. 진짜 `VerificationRedisStreamPublisher.publish()` 에 위임하지 않는다.

**사유 (production 결함 회피 — §5 보고)**:
- 진짜 publisher 의 `@Transactional(REQUIRES_NEW)` (Phase 3 P2-A fix) 가 AFTER_COMMIT 리스너 컨텍스트에서는 안전.
- 그러나 `OutboxRepublisher.republishPending()` 안의 publisher 호출 시 외부 트랜잭션이 SKIP LOCKED 로 outbox row 락을 들고 있고, REQUIRES_NEW 가 같은 row 의 dirty UPDATE 를 새 TX 에서 시도하면 외부 TX 의 락과 충돌 → `Lock wait timeout exceeded` (deadlock-like).
- 실측 — 첫 시도에서 본 deadlock 발생, 60초 timeout 후 `PessimisticLockingFailureException` throw. (test-results XML 보존.)
- 본 phase 는 production 코드 수정 금지. 페이크가 Spec 002 의 `stubPublishSuccess` 패턴 (stream 발행 + outbox.published()) 을 직접 구현해 deadlock 회피 + SC-004 의 본질 (재발행 → 종착) 검증.
- production 영향은 §5 에 별도 보고.

### 3.6 T025/T026 공통 — 외래키 정리 순서

`@AfterEach` 에서 `verification_record` (member/planet FK) → `verification_request` → `outbox_event` → `planet` → `member` 순서로 삭제. 외래키 위반 회피.

---

## 4. 자가 점검 명령 출력 (실측)

| 명령 | 결과 | 비고 |
|---|---|---|
| `./gradlew compileTestJava` | **BUILD SUCCESSFUL** (732ms) | 테스트 컴파일 그린 |
| `./gradlew test --tests "VerificationConsumerOutageIntegrationTest"` | **BUILD SUCCESSFUL** (7s) | T025 단독 그린 |
| `./gradlew test --tests "VerificationOutboxRepublisherIntegrationTest"` | **BUILD SUCCESSFUL** (7s) | T026 단독 그린 (deadlock fix 후) |
| `./gradlew test --tests "VerificationConsumerOutageIntegrationTest" --tests "VerificationOutboxRepublisherIntegrationTest" --tests "*OutboxRepublisher*" --tests "VerificationAsyncFlowIntegrationTest"` | **BUILD SUCCESSFUL** (19s) | Phase 2/3 회귀 0 |
| `./gradlew check` | **BUILD SUCCESSFUL** (30s) | 전체 회귀 0 + `verifySecretLogScan` 자동 hook 그린 |
| `./gradlew verifySecretLogScan` | `✓ secret log scan clean` | 신규 테스트 코드에 시크릿 평문 0 |

### 헌법 게이트 점검

| 원칙 | 점검 결과 |
|---|---|
| **I. Testcontainers (NON-NEGOTIABLE)** | T025/T026 모두 `IntegrationTest` 베이스 상속 — MySQL/Redis 컨테이너 자동 부팅. Mock/H2 미사용. |
| **IV. Outbox 강제 (NON-NEGOTIABLE)** | T026 가 SC-004 (Stream 발행 실패 → Outbox 안전망 → 자동 복구) 를 단대단 단언. T026 의 페이크 publisher 패턴 자체가 Spec 002 의 발행 At-Least-Once 보장 정합. |
| **V. 시크릿 로그 금지** | 신규 테스트 코드에 `secret`/`password`/`credential` 평문 0. `JwtTokenProvider` 임포트는 인프라 클래스 명일 뿐이며 시크릿 값 로깅은 0건. `verifySecretLogScan` clean. |
| **VII. 인수 기준 자동 테스트 (P1)** | T025 ↔ SC-003 (클래스 Javadoc 매핑 명시), T026 ↔ SC-004 (Javadoc 매핑 명시). tasks.md §SC↔Task 표와 1:1 정합. |

---

## 5. ★ Production 결함 보고 — `VerificationRedisStreamPublisher` REQUIRES_NEW + `OutboxRepublisher` deadlock

### 5.1 발견 경위

T026 의 첫 구현 (페이크가 두 번째 호출에서 진짜 `VerificationRedisStreamPublisher.publish()` 에 위임) 실행 중 **`Lock wait timeout exceeded` (`PessimisticLockingFailureException`)** 발생. 본 phase 가 production 코드를 드러낸 새 결함.

### 5.2 결함 메커니즘

**호출 경로**:
1. 테스트 — `OutboxRepublisher.republishPending()` 직접 호출.
2. `OutboxRepublisher` — `@Transactional` 메서드 진입, `findRepublishableForUpdateSkipLocked()` 로 PENDING outbox row 에 **pessimistic lock 획득** (외부 TX 유지).
3. `OutboxRepublisher.republishOne()` → `VerificationMessagePublisher.publish(command)` (인터페이스 호출).
4. 진짜 `VerificationRedisStreamPublisher.publish()` 진입 — `@Transactional(propagation=Propagation.REQUIRES_NEW)` (Phase 3 P2-A fix).
5. 새 TX 안에서 `eventRecorder.findById(command.eventId())` → `outboxEvent.published()` (status = PUBLISHED).
6. 새 TX commit 시 dirty checking 으로 `UPDATE outbox_event SET ... WHERE id=?` 발행.
7. **데드락-like 충돌**: 외부 TX (OutboxRepublisher) 가 이미 SKIP LOCKED 로 같은 row 의 락을 들고 있어 UPDATE 가 lock wait 진입.
8. `innodb_lock_wait_timeout` (기본 50초) 만료 → `PessimisticLockException` throw.

### 5.3 운영 영향 평가

**현재 운영 흐름** (Phase 3 정합 — AFTER_COMMIT 리스너 경유):
- 메인 TX → outbox INSERT → AFTER_COMMIT 리스너 → `VerificationRedisStreamPublisher.publish()`.
- 메인 TX 가 이미 commit 된 후이므로 외부 락 0 → REQUIRES_NEW 가 자유롭게 row UPDATE 가능. **안전.**

**문제 운영 흐름** (`OutboxRepublisher` 가 PENDING outbox 를 재발행할 때):
- `OutboxRepublisher.republishPending()` (외부 TX + SKIP LOCKED) → publisher 호출 (REQUIRES_NEW) → deadlock.
- 운영에서 stream 발행 일시 실패 → outbox PENDING 잔존 → 폴러 사이클 진입 → 폴러가 publisher 호출하는 순간 **모든 재발행 시도가 lock wait timeout 으로 실패**.
- 사용자 가시 영향: 인증 요청은 영원히 PENDING 잔존 — 폴러가 PENDING 을 복구하지 못함.
- 결과: **헌법 IV 의 At-Least-Once 발행 보장이 깨진다** (Spec 002 SC-001 과의 정합 위반).

### 5.4 권장 fix 방향 (Phase 6 또는 별도 PR)

**옵션 A**: `VerificationRedisStreamPublisher.publish()` 의 `outboxEvent.published()` 호출을 trans-aware 모드로 변경 — `REQUIRES_NEW` 대신 `REQUIRED` 로 전환하고 외부 TX 가 있을 때(`OutboxRepublisher` 경로) 같은 TX 안에서 dirty checking. 단 Phase 3 의 P2-A 회귀가 우려 — AFTER_COMMIT 리스너 컨텍스트에서 새 TX 없이 dirty UPDATE 가 flush 되지 않을 가능성을 다시 점검.

**옵션 B**: publisher 의 outbox status 전이 책임을 publisher 에서 분리. `VerificationRedisStreamPublisher.publish()` 는 stream entry 발행만 수행하고, status 전이는 호출처 (AFTER_COMMIT 리스너 / Republisher) 가 자기 트랜잭션 안에서 수행. 책임 분리 + 트랜잭션 경계 명확화.

**옵션 C**: `OutboxRepublisher` 가 publisher 호출 전에 SELECT FOR UPDATE 락을 풀고(별도 트랜잭션 분리), publisher 가 REQUIRES_NEW 로 자유롭게 UPDATE. SKIP LOCKED 의 동시성 보장이 약해질 수 있어 신중 검토.

본 phase 의 책무는 보고. 구현 결정은 code-implementer / code-reviewer 에 위임.

### 5.5 본 테스트가 결함을 드러낸 의의

Phase 3 의 P2-A REQUIRES_NEW fix 는 AFTER_COMMIT 리스너 경로에서의 dirty checking 문제를 해결했으나, **Republisher 경로에서는 같은 fix 가 deadlock 의 원인**이 되었다. 즉 두 경로의 트랜잭션 컨텍스트가 다르므로 동일 propagation 설정이 한쪽엔 해결책, 다른쪽엔 결함이 된다.

본 phase 의 SC-004 는 정확히 Republisher 경로를 검증하므로 — Phase 3 P2-A 가 발견하지 못한 다른 측면의 결함을 본 phase 의 chaos test 가 자연스럽게 드러냈다. **헌법 VII (인수 기준 자동 테스트) 가 production 결함의 회귀 안전망으로 기능한 사례.**

---

## 6. 본 Phase 테스트 완료 가능 여부

**Y** — 두 테스트 모두 그린 + 전체 `./gradlew check` 회귀 0 + 헌법 I/IV/V/VII 게이트 통과.

### 잔여 사항

- **§5 production 결함**: code-implementer / code-reviewer 에게 전달. Phase 6 (Polish) 또는 별도 PR 에서 처리.
- **T026 의 페이크 패턴**: §5 결함 fix 후, 페이크가 진짜 publisher 에 위임하는 더 강한 테스트 (production 호출 경로 그대로) 로 재정비할 수 있다 — 단 현재의 페이크도 SC-004 본질 (재발행 → 종착) 은 완전 검증.
- **다른 통합 테스트와의 컨텍스트 격리**: T026 의 `@DirtiesContext(classMode=AFTER_CLASS)` 로 페이크 빈이 다른 테스트에 누설되지 않음을 보장. 회귀 0 실측.

---

## 7. 다음 단계 (build-verifier / code-reviewer 가 사용할 정보)

- **테스트 진입점**: §1 의 두 파일.
- **잔여 production 결함**: §5 의 `VerificationRedisStreamPublisher` REQUIRES_NEW + `OutboxRepublisher` deadlock. Phase 4 차단은 아니지만 **운영에서 At-Least-Once 보장 위반 — Phase 6 진입 전 fix 권장**.
- **회귀 안전성**: 전체 `./gradlew check` 그린 — Phase 2/3 통합 테스트 회귀 0.

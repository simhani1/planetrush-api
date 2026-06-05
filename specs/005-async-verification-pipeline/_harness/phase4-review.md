# Phase 4 옵션 C-2 production fix — Code Review (Claude)

**Spec**: 005-async-verification-pipeline · **Branch**: `005-async-verification-pipeline`
**Date**: 2026-06-04 · **Reviewer**: code-reviewer
**Build signal**: `./gradlew clean check` BUILD SUCCESSFUL (32s, 170/170 tests) + `verifySecretLogScan` clean — `phase4-verify.md` PASS 수신.

---

## §0. Verdict

**PASS** — P1 0건. self-deadlock 결함 근본 원인이 코드/구조 수준에서 제거됐고, 책임 분리(publisher 어댑터 / helper / listener / republisher) 가 자연스럽다. P2 권고 3건, P3 제안 2건 — 모두 Phase 5/Polish 처리 가능. **Phase 5 진입 가능.**

한 줄: publisher 의 `@Transactional` 책임을 호출자에게 위임하고 dirty checking 을 한 트랜잭션 경계로 모은 fix 는 헌법 II·IV 정합을 강화하며, 170/170 회귀 + grep 정합 확인으로 self-deadlock 재발 0 을 입증한다.

---

## §1. 헌법 7원칙 점검

| 원칙 | 결과 | 근거 (파일:라인) |
|---|---|---|
| **I. Testcontainers (NON-NEGOTIABLE)** | PASS | `VerificationOutboxRepublisherIntegrationTest:109` `extends IntegrationTest` — MySQL/Redis 컨테이너 부팅. Spec 002 회귀 `OutboxRepublisherIntegrationTest:36` 동일. `VerificationRedisStreamPublisherTest` 는 단위 슬라이스 — Mockito 만 사용, H2 0. |
| **II. 외부 의존 어댑터 격리** | PASS (강화) | `VerificationRedisStreamPublisher.java:42-59` — `RedisTemplate.opsForStream().add()` 호출만 보유. 도메인 상태 전이 호출 0, `OutboxRepository` import 0. 본 fix 로 어댑터 격리가 **더 깨끗해짐** (Phase 3 P2-A fix 에 섞여 있던 outbox 상태 전이를 분리). 헬퍼는 `outbox/OutboxPublishingHelper.java` — `infra/` 가 아닌 도메인 패키지 위치 정합. |
| **III. QueryDSL Projections** | PASS (비대상) | 본 fix 는 응답 매핑 비대상. |
| **IV. Outbox 강제 (NON-NEGOTIABLE)** | PASS (강화) | 정상 흐름: `VerificationServiceImpl` → `VerificationExternalEventRecorder.save` → `OutboxEventRecordedEvent` → `VerificationOutboxPublishListener:50-69` (`@TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW)`) → 헬퍼 → publisher. 재시도 흐름: `OutboxRepublisher.republishPending:53` (`@Transactional` + SKIP LOCKED) → `republishOne:82` → 헬퍼. **두 흐름 모두 status PUBLISHED 전이를 통합 테스트로 입증** — `VerificationAsyncFlowIntegrationTest` + `VerificationOutboxRepublisherIntegrationTest:240-245`. self-deadlock 0 으로 At-Least-Once 흐름이 운영 양 경로에서 동작 가능. |
| **V. 시크릿 로그 금지 (NON-NEGOTIABLE)** | PASS | 본 fix 가 추가/수정한 로그 라인 5건 모두 `requestId`/UUID/엔트리 메타만 출력. payload/이미지URL/threshold/콜백URL 평문 출현 0. `verifySecretLogScan` clean. |
| **VI. 듀얼 AI 리뷰** | (집행 중) | 본 리뷰가 Claude 측. PR 단계에서 Codex 와 짝. |
| **VII. 인수 기준=테스트** | PASS | SC-004 ↔ `VerificationOutboxRepublisherIntegrationTest:170-256`. SC-001 회귀 ↔ `VerificationAsyncFlowIntegrationTest`. Spec 002 SC-001 회귀 ↔ `OutboxRepublisherIntegrationTest.transientFailureKeepsPendingThenSucceedsOnRetry` (doThrow 정합). |

NON-NEGOTIABLE (I/IV/V) 위반 0건.

---

## §2. 코드 품질

### Strengths

1. **책임 분리가 본질적으로 옳다**. publisher = 어댑터(stateless Redis XADD) / helper = 도메인 행위 묶음(stateless, 호출자 트랜잭션 상속) / listener·republisher = 트랜잭션 경계 정의자. 트랜잭션 propagation 결정이 호출 경로별로 자유 — Phase 3 P2-A 의 함정이 publisher 가 두 경로 공통 propagation 하나만 들 수 있다는 강제에서 비롯됨.
2. **헬퍼의 Javadoc 이 트랜잭션 어노테이션 부재의 의도를 명시**. 호출자 트랜잭션 상속 + 호출자가 적절한 propagation 부착 책임 명시.
3. **에러 흐름 명시적**. `VerificationOutboxPublishListener:66-69` 가 publisher 예외 catch → outbox PENDING 잔존 → Republisher 자동 복구 흐름이 try-catch + Javadoc 양쪽에 박제.
4. **헬퍼 위치 (`outbox/`) 가 적절**. `infra/publisher/` 에 두지 않은 결정이 옳다 — outbox status 전이는 도메인 행위.
5. **AFTER_COMMIT 리스너의 `REQUIRES_NEW` 부착이 정확**. 회색지대에서 새 EM + 새 TX 강제 시작 — 유일하게 dirty checking 정합 보장하는 선택지.

### Concerns

| ID | 위치 | 우려 |
|---|---|---|
| C-1 (P2) | `VerificationOutboxPublishListener:66-69` | `RuntimeException` 흡수 광범위 — `RedisConnectionFailureException` 류만 잡고 진짜 의도치 않은 예외는 전파하는 게 안전. |
| C-2 (P2) | `OutboxPublishingHelper:42-46` | `findById().ifPresent(...)` — eventId 부재 시 silent. R-004 동일 UUID 정합 깨지면 status 영원히 PENDING. `orElseThrow` 또는 WARN 로그 권장. |
| C-3 (P3) | `VerificationOutboxPublishListener:67` | `log.error` 가 Republisher 자동 복구 흐름의 일부라 과도. WARN 격하 + 메트릭 카운터 권장. |

---

## §3. 통합 정합성

### 3.1 두 발행 경로의 트랜잭션 경계 교차

| 경로 | 트랜잭션 시작 | publisher 호출 시 락 | findById 결과 | flush 시점 |
|---|---|---|---|---|
| 정상 (AFTER_COMMIT) | 리스너의 `REQUIRES_NEW` — 새 EM + 새 TX | 메인 TX commit → row 락 0 | managed entity | 리스너 TX commit |
| 재시도 (Republisher) | `republishPending` 의 `@Transactional` — outer TX | SKIP LOCKED 본인 락 | already-managed entity | Republisher TX commit |

두 경로 모두 helper 가 호출자 TX 를 상속 → self-deadlock 0. ✅

### 3.2 ★ Race condition 인지 — "AFTER_COMMIT 리스너 + Republisher 동시 진입"

본 fix 가 새로 만들어내는 race 가 아니라 At-Least-Once 의 의도된 부산물. AFTER_COMMIT 발행은 메인 TX commit 직후 ms 단위, Republisher 컷오프는 5분 단위라 현실적 발생 확률 극히 낮음. 컨슈머 멱등 (Spec 005 FR-004 callback 멱등 + `uniq_verification_record_member_planet_date`) 으로 흡수.

**Polish 권고**: OutboxRepublisher 컷오프를 짧지 않게 (≥AFTER_COMMIT 발행 평균 지연 + safety margin) 유지하도록 spec/plan 명문화.

### 3.3 AFTER_COMMIT 리스너의 REQUIRES_NEW 적정성

- `REQUIRED`: 회색지대 dirty checking 실패 (P2-A 입증).
- `NOT_SUPPORTED`/`NEVER`: 트랜잭션 0 → dirty checking 의존 깨짐.
- `REQUIRES_NEW`: 유일하게 dirty checking 정상.

`OutboxPublishingHelper` Javadoc 이 호출자별 propagation 책임 명시 → 정확.

### 3.4 Spec 002 `OutboxRepublisherIntegrationTest` 의 `doThrow` 정합

- 신규 publisher 계약: 성공=void / 실패=RuntimeException.
- 변경 전 `doNothing()`: mock 침묵 → helper 가 성공으로 간주 → PUBLISHED 전이 → 일시 장애 시뮬레이션 실패.
- 변경 후 `doThrow(RuntimeException)`: 운영 실제 실패 모드(RedisConnectionFailureException 류) 와 동일 모델링 — 의도 강화 ✅.

### 3.5 페이크 publisher 정합

`VerificationOutboxRepublisherIntegrationTest:294-332` `FailFirstThenDelegatePublisher` 가 신규 계약(예외=실패) + 두 번째 호출에서 진짜 publisher 와 동일한 BRIEF §3-1 5키 발행. status 갱신은 helper 의 dirty checking 으로 처리되므로 페이크가 status 를 건드릴 필요 0 — 정합 ✅.

### 3.6 self-deadlock 결함 제거 grep

| grep | 결과 |
|---|---|
| `@Transactional` in `VerificationRedisStreamPublisher.java` | 0 match — 원인 제거 ✅ |
| `@Transactional` in `OutboxPublishingHelper.java` | 0 match (Javadoc 참조만) — 호출자 TX 상속 보장 ✅ |
| `messagePublisher` in `OutboxRepublisher.java` | 0 match — 헬퍼 교체 완료 ✅ |
| `@Transactional` in `OutboxRepublisher.republishPending` | line 53 — 외부 TX 보유 ✅ |
| `@Transactional(REQUIRES_NEW)` in `VerificationOutboxPublishListener` | line 51 — 회색지대 우회 ✅ |

---

## §4. P1/P2/P3 Findings

**P1: 0건.** 머지 차단 없음.

### P2 (권장 수정 — Phase 5/Polish)

| ID | 위치 | 내용 | 권장 |
|---|---|---|---|
| **P2-1** | `VerificationOutboxPublishListener:66-69` | `RuntimeException` 흡수 광범위 — NPE 류 코드 버그까지 흡수, 알람 누락. | `RedisConnectionFailureException` 류만 catch, 그 외 전파 또는 별도 ERROR + 메트릭. |
| **P2-2** | `OutboxPublishingHelper:44-45` | `findById().ifPresent(...)` — 부재 silent. R-004 보장 깨지면 status 영원 PENDING. | `orElseThrow` 또는 ERROR 로그. |
| **P2-3** | `VerificationOutboxPublishListener:67` | publish failure `log.error` 가 자동 복구 흐름엔 과도. | `log.warn` 격하 + 메트릭 카운터. |

### P3 (제안)

| ID | 위치 | 내용 |
|---|---|---|
| **P3-1** | `OutboxPublishingHelper` 메서드명 | 향후 EventType 별 다른 status 전이 필요 시 일반화 부담. 현 단계는 적정. |
| **P3-2** | dead listener 5종 | Polish T030~T033 일괄 정리 권장 (본 fix 무관). |

---

## §5. Phase 4 종료 권고

### 종료 조건 충족

| 조건 | 결과 |
|---|---|
| T025 그린 | ✅ |
| T026 가 신규 production 흐름 검증 (페이크가 진짜 publisher 동등) | ✅ |
| self-deadlock 결함 제거 (grep) | ✅ |
| `./gradlew clean check` 170/170 그린 | ✅ |
| Phase 2/3 회귀 0 | ✅ |
| 헌법 NON-NEGOTIABLE (I/IV/V) 위반 0 | ✅ |

### 권고

**Phase 4 commit + Phase 5 진입 가능 (Y).**

PR known issues 명시 권장:
1. P2-1/P2-2/P2-3 — Phase 5/Polish 처리.
2. §3.2 race condition — 본 fix 무관, At-Least-Once 의도된 부산물, 컨슈머 멱등으로 흡수, Polish 단계에서 컷오프 명문화 권고.
3. AFTER_COMMIT REQUIRES_NEW + helper 패턴 (`CLAUDE.md` 운영 함정 박제 정합) — 향후 다른 도메인 outbox 발행에서 재사용 가능 자산.

### Phase 5 진입 시 주의사항

- T027~T029 의 멱등 테스트가 본 fix 의 race(§3.2) 시나리오를 자연스럽게 흡수하면 헌법 VII 의 회귀 안전망 정신이 한 번 더 박제됨.
- T026 의 페이크 `FailFirstThenDelegatePublisher` 패턴은 향후 chaos 시나리오에서 재사용 가능 — `verification/testsupport/` 격상 검토.

---

## 부록 — 관련 파일

**Production (5)**:
- `src/main/java/com/planetrush/planetrush/infra/publisher/VerificationRedisStreamPublisher.java`
- `src/main/java/com/planetrush/planetrush/outbox/OutboxPublishingHelper.java`
- `src/main/java/com/planetrush/planetrush/verification/event/listener/VerificationOutboxPublishListener.java`
- `src/main/java/com/planetrush/planetrush/outbox/republisher/OutboxRepublisher.java`
- `src/main/java/com/planetrush/planetrush/outbox/VerificationExternalEventRecorder.java` (호출처)

**Test (2 수정 + 2 신규)**:
- `src/test/java/com/planetrush/planetrush/outbox/republisher/OutboxRepublisherIntegrationTest.java` (doThrow 정합)
- `src/test/java/com/planetrush/planetrush/infra/publisher/VerificationRedisStreamPublisherTest.java` (단위 슬라이스 축소)
- `src/test/java/com/planetrush/planetrush/verification/VerificationOutboxRepublisherIntegrationTest.java` (T026)
- `src/test/java/com/planetrush/planetrush/verification/VerificationConsumerOutageIntegrationTest.java` (T025)

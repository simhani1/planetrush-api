# Phase 4 — 옵션 C-2 production fix 회귀 검증 보고

**스펙**: 005-async-verification-pipeline
**Phase**: 4 (post-test-author production fix)
**브랜치**: 005-async-verification-pipeline
**작성일**: 2026-06-04
**작성자**: build-verifier

---

## §1. 게이트 항목별 PASS/FAIL

### 1.1 빌드·테스트 — `./gradlew clean check` ✅ PASS

**명령**:
```
./gradlew clean check --info
```

**핵심 출력 발췌** (`/tmp/gradle-check.log`):
```
> Task :clean
> Task :compileJava
> Task :compileTestJava
> Task :testClasses
> Task :test
> Task :verifySecretLogScan
✓ secret log scan clean
> Task :check

BUILD SUCCESSFUL in 32s
6 actionable tasks: 6 executed
```

**testsuite 결과** (`build/test-results/test/TEST-*.xml`, 17 suite 합산):

| Suite | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| PlanetrushApplicationTests | 1 | 0 | 0 | 0 |
| ProdProfileBootTest | 2 | 0 | 0 | 0 |
| JwtTokenProviderSecretLogTest | 2 | 0 | 0 | 0 |
| MaskingPatternConverterTest | 28 | 0 | 0 | 0 |
| **VerificationRedisStreamPublisherTest** | **1** | **0** | **0** | **0** |
| ImageTypeTest | 8 | 0 | 0 | 0 |
| MemberIntegrationTest | 2 | 0 | 0 | 0 |
| **OutboxRepublisherConcurrencyTest** | **10** | **0** | **0** | **0** |
| **OutboxRepublisherIntegrationTest** | **4** | **0** | **0** | **0** |
| OutboxRepublisherProfileTest | 2 | 0 | 0 | 0 |
| PlanetIntegrationTest | 104 | 0 | 0 | 0 |
| **VerificationAsyncFlowIntegrationTest** | **1** | **0** | **0** | **0** |
| **VerificationConsumerOutageIntegrationTest** | **1** | **0** | **0** | **0** |
| VerificationControllerSliceTest | 1 | 0 | 0 | 0 |
| **VerificationOutboxRepublisherIntegrationTest** | **1** | **0** | **0** | **0** |
| VerificationServiceFailureIntegrationTest | 1 | 0 | 0 | 0 |
| VerificationServiceIntegrationTest | 1 | 0 | 0 | 0 |
| **합계** | **170** | **0** | **0** | **0** |

판정: ✅ PASS — 전체 170개 테스트가 그린. 실패·에러·스킵 0.

---

### 1.2 시크릿 로그 스캔 (헌법 V) — `verifySecretLogScan` ✅ PASS

**명령** (위 `check` 의 종속 태스크):
```
sh -c 'if grep -RInE "log\.(info|debug|warn)\(.*(secret|token|password|jwt|credential).*=.*\)" src/main; then exit 1; else exit 0; fi'
```

**출력**:
```
> Task :verifySecretLogScan
✓ secret log scan clean
```

판정: ✅ PASS — `src/main` 전체에 시크릿 키워드 평문 로그 0건.

---

### 1.3 회귀 표적 4종 재실행 — `--rerun-tasks` ✅ PASS

**명령**:
```
./gradlew test \
  --tests "com.planetrush.planetrush.verification.VerificationAsyncFlowIntegrationTest" \
  --tests "com.planetrush.planetrush.verification.VerificationConsumerOutageIntegrationTest" \
  --tests "com.planetrush.planetrush.verification.VerificationOutboxRepublisherIntegrationTest" \
  --tests "com.planetrush.planetrush.outbox.republisher.OutboxRepublisherIntegrationTest" \
  --rerun-tasks
```

**핵심 출력 발췌** (`/tmp/gradle-regression.log`):
```
BUILD SUCCESSFUL in 15s
4 actionable tasks: 4 executed
```

**개별 testcase 결과**:

| Suite | testcase | 결과 | time |
|---|---|---|---:|
| VerificationAsyncFlowIntegrationTest (Spec 005 P3 SC-001) | 컨테이너 라이프사이클 + 비동기 흐름 | ✅ | 1.116s |
| VerificationConsumerOutageIntegrationTest (Spec 005 P4 SC-003) | 컨슈머 일시 장애 시나리오 | ✅ | 0.819s |
| VerificationOutboxRepublisherIntegrationTest (Spec 005 P4 SC-004) | Republisher 직접 흐름 (신규 production 검증) | ✅ | 0.898s |
| OutboxRepublisherIntegrationTest (Spec 002 회귀) | SC-001 PENDING→PUBLISHED 전환 | ✅ | 0.183s |
| OutboxRepublisherIntegrationTest (Spec 002 회귀) | **SC-001 일시 장애 → 재시도 성공** (doThrow 정합) | ✅ | 0.027s |
| OutboxRepublisherIntegrationTest (Spec 002 회귀) | SC-005 사이클 배치 발행 | ✅ | 0.056s |
| OutboxRepublisherIntegrationTest (Spec 002 회귀) | SC-003 컷오프 초과 PENDING 잔존 | ✅ | 0.022s |

판정: ✅ PASS — 4종 표적 모두 그린. 특히:
- **`VerificationOutboxRepublisherIntegrationTest`** (신규 production 흐름 직접 검증 — `OutboxRepublisher` → `OutboxPublishingHelper` → XADD + dirty checking PUBLISHED 전이) 통과 = self-deadlock fix 의 실효성 확정.
- **`OutboxRepublisherIntegrationTest#transientFailureKeepsPendingThenSucceedsOnRetry`** (`doThrow(RuntimeException)` 정합) 통과 = 신규 publisher 계약(예외=실패) 과 정합.

---

## §2. production 결함 (self-deadlock) fix 정합 grep 확인

### 2.1 `OutboxPublishingHelper` — `@Transactional` 어노테이션 **0**

```
$ grep -n "@Transactional" src/main/java/com/planetrush/planetrush/outbox/OutboxPublishingHelper.java
# 1 match — 22번째 라인의 Javadoc 참조 (코드 어노테이션 0)
src/.../OutboxPublishingHelper.java:22:* @Transactional}(또는 {@code @Transactional(propagation = REQUIRES_NEW)} — AFTER_...
```

판정: ✅ 헬퍼는 호출자 트랜잭션을 상속받도록 **클래스/메서드 어노테이션 0**. Javadoc 한 줄만 매칭됨.

### 2.2 `OutboxRepublisher` — `outboxPublishingHelper` 주입 + 외부 `@Transactional`

```
$ grep -n "outboxPublishingHelper\|messagePublisher\|@Transactional" src/.../OutboxRepublisher.java
src/.../OutboxRepublisher.java:41:	private final OutboxPublishingHelper outboxPublishingHelper;
src/.../OutboxRepublisher.java:53:	@Transactional
src/.../OutboxRepublisher.java:86:		outboxPublishingHelper.publishAndMarkPublished(payload.toMessageCommand(event));
# messagePublisher: 0 matches — 의존성 교체 확인
```

판정: ✅ `messagePublisher` 직접 의존성 0 + 헬퍼 주입 + `republishPending()`에 `@Transactional` (외부 트랜잭션) 부착됨. self-deadlock 해소 흐름 완비.

### 2.3 `VerificationRedisStreamPublisher` — `@Transactional` **0**

```
$ grep -n "@Transactional" src/.../VerificationRedisStreamPublisher.java
# 0 matches
```

판정: ✅ publisher 어댑터는 트랜잭션 어노테이션 완전 제거. `REQUIRES_NEW`로 인한 SKIP LOCKED row 자기-자신 lock-wait 의 원인 자체가 사라짐.

### 2.4 `VerificationOutboxPublishListener` — `@Transactional(REQUIRES_NEW)` 부착

```
src/.../listener/VerificationOutboxPublishListener.java:50:@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
src/.../listener/VerificationOutboxPublishListener.java:51:@Transactional(propagation = Propagation.REQUIRES_NEW)
```

판정: ✅ AFTER_COMMIT 회색지대를 명시적 새 트랜잭션으로 우회. 헬퍼는 이 트랜잭션을 상속해 dirty checking 정상 작동.

---

## §3. 헌법 위반 0 재확인

| 원칙 | 점검 항목 | 결과 |
|---|---|---|
| **I** (Testcontainers) | Spec 005 통합테스트 4종 (Async/Consumer/Outbox/SliceTest 제외) 모두 `IntegrationTest` 기반 컨테이너 라이프사이클로 실행 — 변경 없음 | ✅ |
| **II** (외부 어댑터 격리) | `infra/publisher/VerificationRedisStreamPublisher` 가 Redis XADD 만 수행, 도메인 상태 전이 코드 0, 트랜잭션 어노테이션 0 — **강화** 확인 | ✅ |
| **IV** (Outbox 발행) | At-Least-Once 흐름 (INSERT → AFTER_COMMIT 발행 → status PUBLISHED) 이 `VerificationAsyncFlowIntegrationTest` (Phase 3 P2-A) + `VerificationOutboxRepublisherIntegrationTest` (Phase 4 SC-004) 두 통합 테스트로 입증 | ✅ |
| **V** (시크릿 로그 금지) | `verifySecretLogScan` clean — `src/main` 전체 keyword hits 0 | ✅ |

판정: ✅ 헌법 4개 원칙 위반 0.

---

## §4. Phase 4 완료 Verdict

**✅ PASS** — Phase 4 옵션 C-2 production fix 전체 회귀 그린(170/170), 시크릿 로그 게이트 clean, self-deadlock 원인(publisher 의 `@Transactional(REQUIRES_NEW)`) 완전 제거 + 책임 분리(헬퍼·리스너·republisher) grep 으로 정합 확인.

**한 줄 요약**: Spec 005 Phase 4 — production fix 적용 후 `./gradlew check` BUILD SUCCESSFUL (170 tests, 0 fail), self-deadlock 원인 제거 + 책임 분리 확정. **Phase 4 종료 가능.**

---

## 부록 — 실행 환경 메타

- Gradle: 8.8 (deprecation warning은 Gradle 9.0 마이그레이션용 — 빌드 영향 0)
- `./gradlew clean check --info` 소요: 32s
- `./gradlew test --rerun-tasks` (회귀 4종) 소요: 15s
- 로그 파일: `/tmp/gradle-check.log`, `/tmp/gradle-regression.log` (세션 임시)
- Test 결과 XML: `/Users/simjonghan/source_code/planetrush-api/build/test-results/test/TEST-*.xml`

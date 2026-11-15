# Phase 1 (Wave 1) 빌드 검증 — Statistics Cache Versioning

검증자: build-verifier · 일시: 2026-06-06 · 브랜치: dev · Docker: 기동 확인(`DOCKER_OK`, 0 containers 시작 상태)

종합 판정: **RED** — 신규 인수 테스트·시크릿 게이트는 통과하나, Caffeine→Redis 캐시 이관으로 기존 `MemberIntegrationTest` 2건이 회귀 실패. 머지·Phase 커밋 차단.

---

## 1. 컴파일 — PASS

명령: `./gradlew compileJava compileTestJava`
```
BUILD SUCCESSFUL in 7s
3 actionable tasks: 2 executed, 1 up-to-date
```
src/main · src/test 모두 컴파일 성공. 컴파일 결함 없음.

## 2. 신규 인수 테스트 — PASS (SC-001 · FR-008)

명령: `./gradlew test --tests "com.planetrush.planetrush.member.StatisticsCacheVersionIntegrationTest"`
```
BUILD SUCCESSFUL in 25s
```
결과 XML (`TEST-...StatisticsCacheVersionIntegrationTest.xml`):
```
<testsuite tests="2" skipped="0" failures="0" errors="0" time="0.566">
  SC-001: 배치 버전 전환(D→D+1) 후 첫 조회는 재계산되고, 전환 전 재조회는 캐시 HIT 다.
  FR-008: 동일 DB 상태에서 getCurrentVersion 은 결정적으로 같은 버전을 반환한다.
```
2/2 통과.

### 직렬화 round-trip 판정 — 정상 동작 확인
SC-001은 캐시 HIT 단계에서 Redis 역직렬화가 성공해야 통과한다. 이 테스트가 통과했으므로
`GenericJackson2JsonRedisSerializer`(default typing `@class`) + `@JsonAutoDetect(ANY)` + protected no-arg 생성자
조합의 `GetMyProgressAvgDto` round-trip 이 실제 동작함을 확인. **InvalidTypeIdException / 생성자 접근 오류 징후 없음.**
(아래 회귀 실패 2건도 역직렬화 자체는 성공하며, 오히려 "새 인스턴스가 정상 생성됨"을 보여준다 — 직렬화 결함이 아님.)

## 3. 회귀 점검 — FAIL (MemberIntegrationTest 2/2 실패, 단 설계 변경에 따른 예상 회귀)

명령: `./gradlew test --tests "com.planetrush.planetrush.member.MemberIntegrationTest"`
```
2 tests completed, 2 failed
BUILD FAILED in 7s
```

### 실패 A — `통계 데이터를 조회할 때 캐싱된 값을 반환해야 한다.` (line 100)
```
java.lang.AssertionError: Expecting actual not to be null
  at MemberIntegrationTest.java:100
```
- 테스트는 `cacheManager.getCache("challenge-avg").get(member.getId())` 로 **Long 키 `1L`** 을 직접 조회.
- 신규 캐시 키 전략(`MemberStatisticsCacheService`):
  `@Cacheable(cacheNames="challenge-avg", key="#memberId + ':' + #version", sync=true)`
  → 실제 키는 문자열 `"1:2026-06-06"`. 따라서 `get(1L)` 은 항상 null → 단언 실패.
- 즉 **기능적 캐싱은 정상**(SC-001이 HIT 검증). 테스트가 구(舊) Caffeine 평문-Long-키 가정에 묶여 있음.

### 실패 B — `동시에 같은 회원 통계 조회가 들어와도 캐시 로더는 한 번만 실행되어야 한다.` (line 164)
```
org.opentest4j.AssertionFailedError:
expected: GetMyProgressAvgDto@d678716
 but was: GetMyProgressAvgDto@52f3c809
  at MemberIntegrationTest.java:164  (assertThat(future.get(...)).isEqualTo(dto))
```
- 구 Caffeine 인메모리 캐시는 동일 참조를 반환 → `isEqualTo(dto)` 가 참조 동일성으로 통과했음.
- Redis 이관 후 round-trip 으로 **새 인스턴스**가 반환됨. `GetMyProgressAvgDto` 는 `equals/hashCode` 미구현
  (확인: 소스 내 equals/hashCode 0건, `@EqualsAndHashCode` 없음) → `isEqualTo` 가 Object 동일성으로 떨어져 실패.
- 역직렬화 자체는 성공(새 객체 정상 생성). 테스트의 "동일 참조 반환" 가정이 Redis 의미론과 불일치.

## 4. 시크릿 로그 게이트 (헌법 V) — PASS

명령: `./gradlew verifySecretLogScan`
```
> Task :verifySecretLogScan
✓ secret log scan clean
BUILD SUCCESSFUL in 351ms
```

## 5. 전체 게이트 — FAIL (실패 범위 = MemberIntegrationTest 2건으로 국한)

명령: `./gradlew check --continue`
```
MemberIntegrationTest > 동시에 같은 회원 통계 조회가 ... FAILED
MemberIntegrationTest > 통계 데이터를 조회할 때 ... FAILED
173 tests completed, 2 failed
BUILD FAILED in 32s
```
전체 173개 중 **2건만 실패**(둘 다 MemberIntegrationTest). 나머지 171건 그린 — Wave 1 변경에 의한
타 회귀 없음. verifySecretLogScan 포함 게이트는 통과.

---

## 책임 라우팅 제안

주 라우팅: **test-author** — `MemberIntegrationTest` 2건은 구 Caffeine 의미론(평문 Long 키 직접 조회 ·
캐시 값 참조 동일성)에 묶인 검증으로, 신규 설계(버전드 문자열 키 `{memberId}:{version}` + Redis round-trip)
에서 의도적으로 깨진다. 프로덕션 결함 아님(기능적 캐싱·역직렬화는 SC-001 통과로 입증).
권장 수정:
- 실패 A(line 98~102): 캐시 키를 `member.getId() + ":" + <version>` 로 조회하거나, 캐시 직접 조회 대신
  `verify(flaskApiClient, times(1))` 의 서비스 레벨 검증으로 전환.
- 실패 B(line 164): `isEqualTo(dto)` 를 필드 단위 비교(`usingRecursiveComparison().isEqualTo(dto)`)로 전환.

리더 결정 필요(설계 선택지): 실패 B 를 테스트 수정 대신 **code-implementer** 가 `GetMyProgressAvgDto` 에
`@EqualsAndHashCode`(값 동등성) 부여로 해결할 수도 있음. 단, 직렬화 필드 가시성(`@JsonAutoDetect(ANY)`)과의
상호작용 검토 필요 → 단순 테스트 수정(test-author)이 더 보수적.

인프라: 정상(Docker 기동, Testcontainers 컨테이너 기동 실패 없음). 인프라 결함 아님.

## 완료 기준 체크 (1차 검증 시점)
- [x] 컴파일 PASS
- [x] 신규 인수 테스트(SC-001·FR-008) PASS + 직렬화 round-trip 정상 확인
- [x] verifySecretLogScan PASS
- [ ] `./gradlew check` GREEN — **미달(MemberIntegrationTest 2건)** → test-author 수정 후 재검증 필요

---

# 재검증 (test-author 수정 후) — 2026-06-06

test-author 가 `MemberIntegrationTest.java` 회귀 2건 수정(평문 Long 키 직접조회 단언 제거 +
`isEqualTo` → `usingRecursiveComparison`). 재검증 결과 **종합 판정: GREEN — 회귀 0, 전체 게이트 통과.**

## R1. MemberIntegrationTest 재실행 — PASS

명령: `./gradlew test --tests "com.planetrush.planetrush.member.MemberIntegrationTest"`
```
BUILD SUCCESSFUL in 9s
```
결과 XML (`TEST-...MemberIntegrationTest.xml`):
```
<testsuite tests="2" skipped="0" failures="0" errors="0" time="0.74">
```
이전 RED 였던 2건(`통계 데이터를 조회할 때 캐싱된 값을 반환해야 한다.`,
`동시에 같은 회원 통계 조회가 들어와도 캐시 로더는 한 번만 실행되어야 한다.`) 모두 그린.

## R2. 전체 게이트 `./gradlew check` — PASS (GREEN)

명령: `./gradlew check`
```
BUILD SUCCESSFUL in 33s
```
전체 테스트 집계(`build/test-results/test/TEST-*.xml` 합산):
```
tests=173 skipped=0 failures=0 errors=0
```
시크릿 로그 게이트(헌법 V) — `verifySecretLogScan --rerun-tasks`:
```
✓ secret log scan clean
BUILD SUCCESSFUL in 364ms
```
173/173 그린, 실패·에러 0. 1차에서 실패했던 2건이 통과로 전환되었고 신규 회귀 없음.
verifySecretLogScan 포함 전체 게이트 통과.

## 재검증 완료 기준 체크
- [x] MemberIntegrationTest 2/2 PASS
- [x] `./gradlew check` GREEN (tests=173, failures=0, errors=0)
- [x] verifySecretLogScan PASS (`✓ secret log scan clean`)
- [x] 회귀 0

## 최종 판정: GREEN
Phase 1(Wave 1) 빌드 그린. code-reviewer 최종 리뷰 및 Phase 커밋 진행 가능.
RED 라우팅 사항 없음. (인프라 정상: Docker 기동, Testcontainers 기동 실패 없음.)

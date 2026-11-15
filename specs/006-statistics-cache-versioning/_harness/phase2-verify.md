# Phase 2 (Wave 2 + Wave 3) 빌드 검증 — Statistics Cache Versioning

검증자: build-verifier · 일시: 2026-06-06 · 브랜치: dev · Docker: 기동(Testcontainers 정상)

종합 판정: **GREEN** — 신규 6 + 기존 2 = 8 인수 테스트 전부 통과, 전체 게이트(`check` + `verifySecretLogScan`) 그린, 회귀 0. code-reviewer 최종 리뷰·Phase 커밋 진행 가능.

## 대상 변경
- src/test: `StatisticsCacheVersionIntegrationTest.java` 6개 메서드 추가 — SC-002·SC-003·SC-005·SC-004·FR-007·콜드스타트.
- src/main: `PlanetrushApplication.java` 중복 `@EnableCaching` 제거(RedisConfig 단일화).

---

## 1. 신규 인수 테스트 — PASS (8/8)

명령: `./gradlew test --tests "com.planetrush.planetrush.member.StatisticsCacheVersionIntegrationTest"`
```
BUILD SUCCESSFUL in 8s
```
결과 XML (`TEST-...StatisticsCacheVersionIntegrationTest.xml`):
```
<testsuite tests="8" skipped="0" failures="0" errors="0" time="0.777">
```
실행된 8개 testcase 전부 통과:
- SC-001: 배치 버전 전환(D→D+1) 후 첫 조회 재계산 + 전환 전 재조회 HIT
- SC-002: 배치 진행 중 조회는 직전 버전 HIT, 중간상태 새 버전 미캐싱
- SC-003: 버전 전환 후 구·신 버전 키 모두 잔존(사용자별 evict 0회)
- SC-004: 동일 member·동일 버전 동시 N 요청 → Flask 호출 1회 수렴(single-flight)
- SC-005: 캐시 키에 25h 근사 TTL 설정(과거 버전 자연 만료)
- FR-007: Flask 예외 시 키 미생성, 다음 요청이 재계산 재시도
- FR-008: 동일 DB 상태에서 getCurrentVersion 결정적 동일 버전
- 콜드스타트: 완료 배치 0건 → version=오늘(Asia/Seoul) 계산·캐싱

### 주의 신호 — 전부 검증 통과
- **FR-007 (예외 전파)**: PASS. `@Cacheable` 어드바이스가 `FlaskConnectionFailedException` 을 삼키지 않고
  전파했고(키 미생성 입증), 다음 요청 재계산 재시도 성공. 예외 삼킴(RED 징후) 없음.
- **SC-004 (single-flight)**: PASS. 동시 N 요청에도 `times(1)` 수렴 — `sync=true` 정상 동작.
- **SC-005 (TTL 범위 82800<ttl≤90000)**: PASS. 25h 근사 TTL 설정 확인.

## 2. 전체 게이트 `./gradlew check` — PASS (GREEN)

명령: `./gradlew check`
```
✓ secret log scan clean
BUILD SUCCESSFUL in 32s
```
전체 테스트 집계(`build/test-results/test/TEST-*.xml` 합산):
```
tests=179 skipped=0 failures=0 errors=0
```
시크릿 로그 게이트(헌법 V) — `verifySecretLogScan --rerun-tasks`:
```
✓ secret log scan clean
BUILD SUCCESSFUL in 321ms
```
179/179 그린, 실패·에러 0. 이전 Phase 173건 + 신규 6건 = 179건 모두 통과, 회귀 없음.

### @EnableCaching 제거 검증 — 캐싱 비활성화 안 됨
`PlanetrushApplication.java` 의 중복 `@EnableCaching` 제거 후에도 캐싱이 정상 동작함을
캐시 의존 테스트 통과로 입증: SC-001(HIT), SC-002(직전버전 HIT), SC-003(키 잔존),
SC-004(single-flight times(1)), MemberIntegrationTest(2건) 모두 그린. RedisConfig 단일 `@EnableCaching`
경로로 캐시 어드바이스가 정상 적용됨. 캐싱 비활성화 회귀 징후 없음.

---

## 완료 기준 체크
- [x] 신규 6 + 기존 2 = 8 메서드 PASS (`tests=8 failures=0`)
- [x] `./gradlew check` GREEN (`tests=179 failures=0 errors=0`)
- [x] verifySecretLogScan PASS (`✓ secret log scan clean`)
- [x] 회귀 0 / @EnableCaching 제거로 캐싱 비활성화 안 됨(캐시 테스트 통과 입증)
- [x] 주의 신호(FR-007 예외 전파 · SC-004 single-flight · SC-005 TTL) 전부 통과

## 최종 판정: GREEN
Phase 2(W2+W3) 빌드 그린. RED 라우팅 사항 없음. 인프라 정상(Docker·Testcontainers 기동 실패 없음).

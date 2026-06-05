# Spec 006 — Phase 1 (Wave 1) Code Review

**Scope**: Setup + Foundational + US1 (SC-001, FR-008). Build GREEN (173 tests, 0 fail, verifySecretLogScan clean).
**Verdict**: **P1 = 0, P2 = 0, P3 × 3.** Phase 게이트 PASS.

## 헌법 7원칙

| 원칙 | 결과 | 근거 |
|------|------|------|
| I Testcontainers | ✅ PASS | `StatisticsCacheVersionIntegrationTest extends IntegrationTest`(MySQL+Redis 싱글톤). Flask만 @MockBean(어댑터, 컨테이너 의무 대상 아님) |
| II 어댑터 격리 | ✅ PASS | 버전=JobLogRepository(JPA/DB), 캐시=@Cacheable 추상화. 도메인/서비스 RedisTemplate 직접접근 0. CacheConfig의 RedisCacheWriter는 config 레이어(허용) |
| III QueryDSL | ✅ PASS | `select(jobLog.endTime.max())` 스칼라 집계, Entity→DTO 수동매핑 없음, Optional.ofNullable |
| IV Outbox | ➖ N/A | 메시지 발행 없음 |
| V 시크릿 로그 | ✅ PASS | 신규 로깅 0건, verifySecretLogScan clean |
| VI 듀얼 리뷰 | ⏳ | PR 단계 |
| VII 인수=테스트 | ✅ PASS | SC-001(3단계 행위 박제 times1→times1→times2, 값 10→20), FR-008(결정성 프록시, 한계 주석) 약화 없음 |

## 결정 정합성 (R1~R10)

R1 Redis+TTL25h ✅ · R2 key `#memberId+':'+#version` ✅ · R3 endTime→Asia/Seoul LocalDate·콜드=오늘 ✅ · R4 버전 캐시 없음 ✅ · R5 lockingRedisCacheWriter+sync ✅ · R7 자기호출 회피(별도 빈 위임) ✅ · R8 GenericJackson2Json+@JsonAutoDetect ✅ · R9 Asia/Seoul·yyyy-MM-dd ✅

**US2/FR-003 race 구조 검증**: `ScheduledTasks.progressCalculation()`은 finish()(endTime 설정) 후에만 save → 진행중(endTime=null) JobLog 미영속 → 새 날 배치 실행 중 MAX(endTime)=어제 유지 → 중간상태 캐싱 불가. 설계 건전.

## 품질
- 자기호출 우회 없음, FR-007 예외 전파(미캐싱) 정합, null/Optional 안전, readOnly tx 경계 정상, 단일 CacheManager 빈, 테스트 격리(@BeforeEach cleanup+reset) 견고.
- 회귀 수정(MemberIntegrationTest): 내부구조 직접조회→행위검증, 참조동등→usingRecursiveComparison. 정당. SC-004/직렬화 round-trip 사실상 커버.
- 인덱스 idx_joblog_type_endtime 적합.

## 발견사항

**P1: 없음. P2: 없음.**

**P3 (비차단 — 백로그):**
1. 직렬화 클래스 결속(`@class` FQN 임베드) — DTO 이동/리네임 시 TTL≤25h 잔존 캐시 역직렬화 예외 가능. 현재 round-trip 정상. 클래스 진화 예상 시 "역직렬화 실패→miss" 복원력 고려. → code-implementer 백로그
2. `@EnableCaching` 중복(RedisConfig.java:15 + PlanetrushApplication.java:8). 멱등이라 무해하나 CacheConfig javadoc("RedisConfig에 이미 선언")이 단일출처 암시 → 주석/배치 정리 권고. → Polish
3. data-model.md endTime nullable 기술 ↔ JobLog `nullable=false` 불일치(엔티티는 본 Wave 변경 아님). 문서 보정. → **리더 처리(W1에서 수정)**

## 종합
P1=0 → 게이트 통과. SC-001·FR-008 약화 없이 박제. US2/US3/SC-004/FR-007은 명시 deferred(후속 Wave). 상충 의견 없음.

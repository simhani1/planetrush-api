# Claude 리뷰 — Spec 006: 사용자 통계 캐시 stale-cache 해소 (통계 버전 기반 캐시 키)

> 헌법 원칙 VI(듀얼 AI 리뷰) Claude 측 산출물. PR 본문에 Codex 리뷰와 병기.
> 대상: Wave 1+2+3 전체. **Build GREEN — 179 tests, 인수 테스트 8/8 통과, verifySecretLogScan clean.**

## 최종 권고: ✅ MERGE 가능 (P1 = 0)

7원칙 1차 게이트 위반 0건. 인수기준(SC-001~005·FR-007·FR-008·콜드스타트) 전부 자동 테스트로 약화 없이 박제. 잔여는 머지 비차단 P3 1건(직렬화 결속 백로그).

---

## 1. 핵심 설계 요약

마이페이지 통계(`GET /api/v1/members/mypage`) 캐시가 24h TTL 동안 stale 해지는 문제를 **캐시 키에 "통계 버전"을 포함**해 해소한다.

- **버전 = 직전 완료 `progressCalculation` 배치의 기준일**(`yyyy-MM-dd`, Asia/Seoul), `JobLog` 에서 DB 파생. `MAX(endTime) where jobType='progressCalculation' and endTime is not null` (QueryDSL 스칼라 집계). 완료 0건이면 오늘(콜드스타트 잠정).
- **완료 후에만 전환**: production 은 `finish()`(endTime set) 후에만 JobLog 를 save → 진행 중(endTime=null) row 미영속 → 새 날 배치 완료 전까지 버전이 어제로 유지 → 자정 직후 중간 상태가 새 버전으로 캐싱되지 않음(US2).
- **Caffeine 로컬 → Redis 공유 캐시 이관**: 다중 인스턴스가 동일 버전 키 공유, 전역 1회만 재계산.
- **버전 키**: `#memberId + ':' + #version` → 물리 키 `challenge-avg::{memberId}:{version}`. 버전 바뀌면 자연 miss → 재계산. 전수 evict 불요(과거 키 TTL 25h 자연 만료).
- **single-flight**: `lockingRedisCacheWriter` + `@Cacheable(sync=true)` 결합 → 동일 키 동시 첫 조회 Flask 1회 수렴.
- **실패 비캐싱**: Flask 예외(또는 member 미존재) 전파 → `@Cacheable` 미저장 → 다음 요청 재계산.
- **버전 캐시 없음(R4)**: 매 요청 인덱스된 `MAX(endTime)` 1건 읽기로 직접 파생 → stale 창 0 수렴.

신규 `build.gradle` 의존성 0, 신규 테이블 0. `JobLog` 에 `(job_type, end_time)` 복합 인덱스만 추가.

---

## 2. 헌법 7원칙 점검

| 원칙 | 결과 | 근거 |
|------|------|------|
| **I. Testcontainers 통합테스트** | ✅ PASS | 신규 인수 테스트 8 메서드 모두 `IntegrationTest`(싱글톤 MySQL 8.0.36 + Redis 7-alpine) 확장. Redis 키/TTL·JobLog 시드를 실제 컨테이너로 검증. Flask 만 `@MockBean`(비 MySQL/Redis 어댑터 — 의무 대상 아님, R10). 로컬 데몬 불요. |
| **II. 외부 의존 어댑터 격리** | ✅ PASS | `StatisticsVersionProvider`=JPA(DB) 파생, `MemberStatisticsCacheService`=`@Cacheable` 추상화+`FlaskApiClient`(infra). 도메인/서비스의 `RedisTemplate` 직접 호출 0. `CacheConfig` 의 `RedisConnectionFactory`/`RedisCacheWriter` 는 config 레이어 허용 범위. |
| **III. QueryDSL Projections** | ✅ PASS | `JobLogRepositoryCustomImpl`=`select(jobLog.endTime.max())` 스칼라 집계. Entity 반환·서비스단 수동 매핑 없음, N+1 무관. |
| **IV. Outbox 경유 발행** | ➖ N/A | 메시지 발행 없음. |
| **V. 민감정보 로그 금지** | ✅ PASS | 신규/변경 코드에 로깅 추가 0건. verifySecretLogScan clean. |
| **VI. 듀얼 AI 리뷰** | ⏳ 본 문서 + Codex | 본 Claude 리뷰 + Codex 리뷰 PR 병기. |
| **VII. 인수기준=자동테스트** | ✅ PASS | SC-001~005·FR-007·FR-008·콜드스타트 → 테스트 메서드 1:1 박제(아래 §4). |

기술 스택/라이브러리: 신규 의존성 0 → "라이브러리 도입 규칙" 미트리거. 운영 안티패턴(ddl-auto=update·show-sql) 신규 도입 0.

---

## 3. 변경 파일 전체 목록

**src/main (신규 4)**
- `member/service/StatisticsVersionProvider.java` — 버전 해석(매 요청 DB 파생, Asia/Seoul, 콜드스타트=오늘)
- `member/service/MemberStatisticsCacheService.java` — `@Cacheable(challenge-avg, key=memberId:version, sync=true)` 캐시 서비스(자기호출 회피용 별도 빈)
- `scheduler/log/JobLogRepositoryCustom.java` — QueryDSL custom 인터페이스
- `scheduler/log/JobLogRepositoryCustomImpl.java` — `MAX(endTime)` 구현

**src/main (수정 5)**
- `core/config/CacheConfig.java` — Caffeine `CacheManager` → `RedisCacheManager`(TTL 25h, JSON 직렬화, lockingRedisCacheWriter)
- `member/service/MemberServiceImpl.java` — `getMyProgressAvgPer`: `@Cacheable` 제거, 버전 해석 → 캐시 서비스 위임
- `member/service/dto/GetMyProgressAvgDto.java` — JSON round-trip 대응(`@NoArgsConstructor(PROTECTED)` + `@JsonAutoDetect(ANY)`)
- `scheduler/log/JobLog.java` — `(job_type, end_time)` 복합 인덱스 추가
- `scheduler/log/JobLogRepository.java` — `JobLogRepositoryCustom` 결합
- `PlanetrushApplication.java` — `@EnableCaching` 중복 제거(RedisConfig 단일화)

**src/test (신규 2)**
- `member/StatisticsCacheVersionIntegrationTest.java` — 인수 테스트 8 메서드
- `member/testsupport/StatisticsCacheTestSupport.java` — JobLog/Member 시드 · Redis 키/TTL 헬퍼

**src/test (수정 1)**
- `member/MemberIntegrationTest.java` — Redis+버전키 이관 회귀 수정(캐시 내부조회→행위 검증, 참조동등→값 동등 `usingRecursiveComparison`)

---

## 4. 인수기준 ↔ 테스트 최종 매핑 (헌법 VII 증빙)

모든 테스트는 `StatisticsCacheVersionIntegrationTest`(MySQL+Redis Testcontainers).

| 인수기준 | 테스트 메서드 | 검증 방식(행위 기반) |
|---|---|---|
| **SC-001** 배치 완료 후 첫 조회 갱신값 100%(옛 값 0%) | `should_recompute_on_first_read_after_version_transition_and_hit_within_same_version` | D 첫 조회 MISS(Flask×1, 10.0) → 동일 버전 HIT(×1 유지) → D+1 전환 재계산(×2, 20.0) |
| **SC-002** 중간 상태 고정 0건 | `should_not_cache_intermediate_state_while_batch_in_progress` | D+1 진행 중(완료 row 부재)=버전 D HIT, **D+1 키 미생성** → 완료 후에야 재계산 |
| **SC-003** 사용자별 evict 0회 | `should_keep_old_version_key_without_evict_on_version_transition` | 전환 후 구 키(:D)·새 키(:D+1) **둘 다 Redis 잔존**, evict 미호출 |
| **SC-004** 동시 첫 조회 Flask 1회 수렴 | `should_converge_flask_call_to_once_on_concurrent_first_reads` | 16 스레드 동시 출발(latch)+Flask 200ms in-flight → `verify times(1)` |
| **SC-005** 과거 버전 TTL 자연 만료 | `should_set_ttl_around_25h_on_cache_key` | 키 TTL `> 82800s & <= 90000s`(25h≈) — 무만료(-1)/미존재(-2) 배제 |
| **FR-007** 실패 비캐싱·재계산 | `should_not_cache_failure_and_allow_retry_on_next_request` | Flask 예외 전파 + **키 미생성**, 다음 요청 재시도(`times(2)`) |
| **FR-008** 전역 일관(결정성) | `should_return_deterministic_version_for_same_db_state` | 동일 DB 상태 연속 호출 동일 버전 반환(매 요청 DB 파생 → 전역 수렴 근거) |
| **콜드스타트(Q3)** 완료 0건=오늘 잠정 버전 | `should_use_today_as_version_on_cold_start_with_no_completed_batch` | 완료 0건 → `getCurrentVersion()`=오늘(Asia/Seoul), 오늘 버전 키 생성 |

> SC-004 single-flight 는 `MemberIntegrationTest` 동시성 회귀 테스트로도 교차 커버.
> 단일 JVM 한계상 FR-008(다중 인스턴스 동일 인식)·SC-004(교차 인스턴스 락)은 결정성/인스턴스 내 수렴으로 대체 검증 — 코드(매 요청 DB 파생·lockingRedisCacheWriter)가 구조적으로 보장, 테스트 주석에 한계 명시.

---

## 5. 잔여 백로그 (머지 비차단 P3)

1. **P3-1 직렬화 `@class` 결속 취약성** — `GetMyProgressAvgDto` / `GenericJackson2JsonRedisSerializer`. JSON 에 FQN(`@class`)이 박혀, 향후 DTO 이동/리네임 시 TTL(≤25h) 내 잔존 캐시 항목이 HIT 시 역직렬화 예외 → read 경로 500 위험. 현재 round-trip 테스트로 정상 확인됨(허용된 알려진 트레이드오프). 클래스 진화 예상 시 "역직렬화 실패→miss 취급" 복원력 또는 버전 태그 직렬화를 후속 도입 권고.
2. **P3(경미) SC-005 TTL 하한 느슨** — 23h 하한을 더 좁혀도 됨(무만료 배제 목적은 충족).
3. **P3(구조적) 교차 인스턴스 미검증** — FR-008·SC-004 의 다중 인스턴스 측면은 단일 JVM 테스트 한계. 인지 항목(코드 변경 불요).

> **해소 완료**: P3-2(@EnableCaching 중복) — `PlanetrushApplication` 에서 제거, `RedisConfig` 단일 출처로 정리됨.

---

## 6. 종합

- 헌법 7원칙 위반 **0**, P1 **0**, P2 **0**.
- 인수기준 8종 전부 Testcontainers 기반 행위 테스트로 박제(헌법 I·VII).
- 설계가 spec Clarifications(Q1~Q3)·research R1~R10·contracts(C1~C3, F1~F5)와 정합.
- **머지 권고**: 승인. P3-1 은 후속 백로그로 추적.
</content>

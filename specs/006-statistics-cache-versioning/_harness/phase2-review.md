# Spec 006 — Phase 2 (Wave 2 + Wave 3) Code Review

**Scope**: US2(SC-002) · US3(SC-003·SC-005) · single-flight(SC-004) · 실패 비캐싱(FR-007) · 콜드스타트 + P3-2 해소(@EnableCaching 단일화).
**Build**: GREEN — 179 tests, 인수 테스트 8/8 통과, verifySecretLogScan clean.
**Verdict**: P1 = 0. Phase 게이트 통과 가능. P2 = 0. P3 = 2(경미·구조적 한계).

대상:
- src/test `member/StatisticsCacheVersionIntegrationTest.java` — 신규 6 메서드(SC-002·SC-003·SC-005·SC-004·FR-007·콜드스타트)
- src/main `PlanetrushApplication.java` — `@EnableCaching` 중복 제거(P3-2)

---

## 1. P3-2 해소 검증 (@EnableCaching 단일화)

- `PlanetrushApplication.java`(1-15): `@EnableCaching` import·어노테이션 제거됨. 현재 `@EnableScheduling` + `@SpringBootApplication`만.
- `RedisConfig.java:15`: `@EnableCaching` 단일 잔존 → 애플리케이션 전역 캐싱 인프라 단일 출처.
- `CacheConfig` javadoc("@EnableCaching 은 RedisConfig 에 이미 선언되어 있어 여기서 중복하지 않는다")이 이제 코드와 **정확히 일치**.
- 회귀 위험: 없음. `@EnableCaching`은 캐싱 어드바이저 인프라를 등록하는 부수효과만 있고 단일 선언으로 충분. 빌드 GREEN(179)으로 캐시 어드바이스 정상 동작 확인됨(SC-001·004 HIT/single-flight 통과가 증빙).

→ **P3-2 정확히 해소. PASS.**

---

## 2. 신규 테스트 약화-없는 박제 점검

### SC-002 — `should_not_cache_intermediate_state_while_batch_in_progress` (141-174) — PASS
- **모델링 정확성(핵심)**: "배치 진행 중" = 해당 날짜 완료 JobLog row 부재로 모델링. 이는 production 실제 동작과 정합 — `ScheduledTasks.progressCalculation()`은 `finish()`(endTime set) **후에만** `save()`하므로 endTime=null(진행 중) row 는 절대 영속되지 않는다. 따라서 D+1 배치 진행 중에는 `MAX(endTime)`가 D 로 유지되어 버전이 올라갈 수 없다.
- **단언 강도**: (1) 버전 D 첫 조회 → `times(1)` + keyD 존재. (2) D+1 진행 중(미시드) 재조회 → 값 10.0 유지 + `times(1)` 유지 + **keyDPlus1 미존재**. (3) D+1 실제 완료 시드 → 값 20.0 + `times(2)` + keyDPlus1 존재.
- 중간상태가 새 버전 키로 캐싱되지 않음을 Redis 키 부재로 직접 증명. SC-002("중간 상태 고정 0건")를 약화 없이 박제. `endTime IS NOT NULL` 불변식의 행위적 증명.

### SC-003 — `should_keep_old_version_key_without_evict_on_version_transition` (181-204) — PASS
- 버전 D 조회로 keyD 생성 → 완료 D+1 시드 후 조회로 keyDPlus1 생성 → **keyD·keyDPlus1 둘 다 잔존** 단언(`challengeAvgKeys().contains(keyD, keyDPlus1)`). 사용자별 evict/`cache.evict` 호출 0건.
- SC-003("사용자별 일괄 캐시 삭제 0회 — 키 변경만으로 신선도")을 Redis 키 잔존으로 직접 검증. 약화 없음.

### SC-005 — `should_set_ttl_around_25h_on_cache_key` (210-225) — PASS
- 조회로 키 생성 후 `ttlSeconds(keyD)`가 `> 82800(23h)` 그리고 `<= 90000(25h)` 단언. TTL=-1(무만료)·-2(키없음)을 모두 배제 → 과거 버전 자연 만료 보장(FR-005/SC-005).
- 하한(23h)이 다소 느슨하나 핵심(TTL≈25h 설정·무만료 아님)은 정확히 검증. (P3 참고)

### SC-004 — `should_converge_flask_call_to_once_on_concurrent_first_reads` (231-271) — PASS
- 16 스레드 + `readyLatch`/`startLatch` 동시 출발, Flask `thenAnswer` 200ms sleep 으로 in-flight 구간 강제. 동일 member·동일 버전 첫 조회 동시 발생 → `verify times(1)`.
- 값은 Redis round-trip 역직렬화 새 인스턴스이므로 `usingRecursiveComparison` 값 동등성 비교(분산 캐시 정합 가정 정확). single-flight(locking RedisCacheWriter + `sync=true`)를 실제 Redis 컨테이너에서 검증. SC-004 약화 없음.
- 구조적 한계(P3): 단일 JVM 이라 `sync=true`(per-JVM)만으로도 1회 수렴 가능 — locking writer 의 **교차 인스턴스** 직렬화는 단일 JVM 테스트로 직접 재현 불가(FR-008 결정성 프록시와 동일 성격). 인스턴스 내 수렴은 직접 증명됨.

### FR-007 — `should_not_cache_failure_and_allow_retry_on_next_request` (279-300) — PASS
- Flask 가 `FlaskConnectionFailedException` throw → (1) 첫 호출 예외 전파 + **keyD 미생성**(실패 비캐싱). (2) 다음 호출 캐시 미스 → 재계산 시도(`verify times(2)`) + keyD 여전히 미생성.
- `@Cacheable` 예외 시 미저장 의미를 Redis 키 부재로 직접 검증. javadoc 이 "@MockBean 이라 production `@Retryable` 미적용"을 정직히 명시 — 본 스펙이 더한 제약("실패 결과 비캐싱")에 정확히 스코프됨. member 는 시드되어 `MemberNotFoundException` 단락 없이 Flask 단계까지 도달. 약화 없음.

### 콜드스타트 — `should_use_today_as_version_on_cold_start_with_no_completed_batch` (306-324) — PASS
- 완료 JobLog 0건(cleanUp 보장) → `getCurrentVersion() == today(Asia/Seoul)` + 조회 시 Flask `times(1)` + today-버전 키 생성. Q3/R3 콜드스타트("오늘 잠정 버전") 박제.

---

## 3. 헌법 I · VII

- **I (Testcontainers)**: 6 신규 메서드 모두 `IntegrationTest`(싱글톤 MySQL+Redis) 확장. Redis 키/TTL·JobLog 시드를 실제 컨테이너로 검증. Flask 만 `@MockBean`(비 MySQL/Redis 어댑터 — 의무 대상 아님). 로컬 데몬 불요. PASS.
- **VII (인수기준=테스트)**: SC-002·003·004·005·FR-007·콜드스타트가 메서드 1:1 박제. W1 의 SC-001·FR-008 와 합쳐 8/8. PASS.

## 4. 테스트 격리

- `@BeforeEach`(60-69): `support.cleanUp()`(challenge-avg::* 키 + JobLog + Member 삭제) + `cacheManager.getCache(...).clear()` + `reset(flaskApiClient)`. 콜드스타트 테스트의 "완료 0건" 전제도 cleanUp 으로 보장.
- 각 테스트 독립 시드(날짜·멤버). SC-004 executor 는 `shutdown()`+`awaitTermination` 으로 정리. 크로스-테스트 누수 없음. PASS.

---

## 5. 발견사항 (심각도별)

**P1 (머지 차단): 없음.**

**P2 (품질): 없음.**

**P3 (제안):**

1. **SC-005 TTL 하한 느슨** — `StatisticsCacheVersionIntegrationTest.java:224`. 하한 82800s(23h)는 생성 직후 검증 특성상 사실상 90000 근처여야 하므로 더 좁혀도 됨(예 `> 89000`). 현재도 무만료/미설정 배제 목적은 충족. 저우선.
2. **SC-004 교차 인스턴스 미검증(구조적)** — `:233`. 단일 JVM 한계로 locking RedisCacheWriter 의 교차 인스턴스 직렬화는 직접 재현 불가(FR-008 와 동일 성격). 인스턴스 내 수렴은 검증됨. 코드 변경 불요 — 인지 항목.

> 상충 의견 없음.

## 6. 종합

- **P1 = 0** → Phase 2 게이트 통과 가능.
- W2+W3 인수기준(SC-002·003·004·005·FR-007·콜드스타트) 전부 행위 기반·약화 없이 박제.
- P3-2(@EnableCaching 중복) 정확히 해소 — RedisConfig 단일 출처.
- 잔여 백로그(W1 부터 이월): P3-1 직렬화 `@class` 결속 취약성(GetMyProgressAvgDto / GenericJackson2JsonRedisSerializer) — 후속 복원력 과제(머지 비차단).
</content>
</invoke>

---
description: "Task list — 통계 캐시 버전 키 (stale-cache 해소)"
---

# Tasks: 사용자 통계 캐시 stale-cache 해소 (통계 버전 기반 캐시 키)

**Input**: Design documents from `specs/006-statistics-cache-versioning/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/cache-key.md, quickstart.md

**Tests**: 헌법 VII(인수기준=자동테스트, NON-NEGOTIABLE)에 따라 **필수 포함**. 각 SC 를 Testcontainers(MySQL+Redis) 통합 테스트로 박제한다.

**Organization**: 본 기능은 단일 코드 경로(버전 해석 → 버전 키 캐시 → Flask)를 여러 *시나리오*로 검증하는 인프라 기능이다. 따라서 핵심 메커니즘은 Foundational 에 모이고, 각 User Story 단계는 해당 시나리오의 **인수 테스트 + 필요한 마무리 배선**으로 구성된다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완 의존 없음)
- **[Story]**: US1/US2/US3
- 모든 태스크에 정확한 파일 경로 포함

## Path Conventions

- Spring Boot 단일 모듈: `src/main/java/com/planetrush/planetrush/...`, 테스트 `src/test/java/com/planetrush/planetrush/...`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 테스트 지원 도구 준비. 신규 `build.gradle` 의존성 없음(`spring-boot-starter-cache`·`data-redis`·`caffeine` 보유 확인).

- [ ] T001 [P] 테스트 지원 헬퍼 작성 — JobLog(완료/진행중) 시드 + Redis 키/TTL 조회 유틸 in `src/test/java/com/planetrush/planetrush/member/testsupport/StatisticsCacheTestSupport.java`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 버전 해석 + 버전 키 Redis 캐시 메커니즘. **모든 User Story 의 전제** — 완료 전 어떤 스토리도 통과 불가.

**⚠️ CRITICAL**: 이 단계가 끝나야 US1~US3 인수 테스트가 의미를 가진다.

- [ ] T002 JobLog 엔티티에 `(job_type, end_time)` 복합 인덱스 추가(`@Table(indexes=...)`) in `src/main/java/com/planetrush/planetrush/scheduler/log/JobLog.java`
- [ ] T003 JobLog 버전 쿼리(QueryDSL) — `findLatestCompletedProgressCalculationEndTime(): Optional<LocalDateTime>` (`jobType='progressCalculation'` AND `endTime IS NOT NULL` 의 `MAX(endTime)`) in `src/main/java/com/planetrush/planetrush/scheduler/log/JobLogRepositoryCustom.java` + `JobLogRepositoryCustomImpl.java`; `JobLogRepository` 가 custom 인터페이스 상속하도록 수정 in `JobLogRepository.java`
- [ ] T004 `StatisticsVersionProvider` 작성 — T003 결과를 `Asia/Seoul` `LocalDate`→`yyyy-MM-dd` 로 변환, 완료 0건이면 오늘(콜드스타트). **캐시 없음(매 요청 DB 파생, R4)** in `src/main/java/com/planetrush/planetrush/member/service/StatisticsVersionProvider.java`
- [ ] T005 `CacheConfig` 이관 — `challenge-avg` 를 `RedisCacheManager`(TTL 25h, `GenericJackson2JsonRedisSerializer`, `RedisCacheWriter.lockingRedisCacheWriter`)로 전환, 기존 Caffeine-only `cacheManager` 정리 in `src/main/java/com/planetrush/planetrush/core/config/CacheConfig.java`
- [ ] T006 [P] `GetMyProgressAvgDto` JSON 역직렬화 대응 — `@NoArgsConstructor`(필요 시 접근자) 보강, Redis 직렬화 호환 확인 in `src/main/java/com/planetrush/planetrush/member/service/dto/GetMyProgressAvgDto.java`

**Checkpoint**: 버전 해석·Redis 버전 키 캐시·직렬화 준비 완료 → 스토리 인수 테스트 착수 가능

---

## Phase 3: User Story 1 - 배치 갱신 후 최신 통계 노출 (Priority: P1) 🎯 MVP

**Goal**: 일별 배치가 새 집계값을 완료한 뒤 첫 조회에서 갱신된 수치가 반환된다(SC-001).

**Independent Test**: JobLog `endTime=D` 시드 → 조회(Flask 1회·캐싱) → JobLog `endTime=D+1` 시드 → 조회 시 Flask 추가 1회 호출·새 값 반환.

### Tests for User Story 1 ⚠️ (먼저 작성, 실패 확인 후 구현)

- [ ] T007 [US1] SC-001 통합 테스트 — 버전 전환(D→D+1) 후 첫 조회가 재계산(Flask 추가 호출)·신선값 반환, 전환 전 재조회는 HIT(Flask 미호출) in `src/test/java/com/planetrush/planetrush/member/StatisticsCacheVersionIntegrationTest.java` (`extends IntegrationTest`, `@MockBean FlaskApiClient` 호출 횟수 검증)

### Implementation for User Story 1

- [ ] T008 [US1] `MemberStatisticsCacheService` 작성 — `@Cacheable(cacheNames="challenge-avg", key="#memberId + ':' + #version", sync=true)`, 내부에서 member 검증 + `flaskApiClient.getMyProgressAvg(memberId)` 호출(예외 전파) in `src/main/java/com/planetrush/planetrush/member/service/MemberStatisticsCacheService.java`
- [ ] T009 [US1] `MemberServiceImpl#getMyProgressAvgPer` 리팩터 — 기존 `@Cacheable` 제거, `statisticsVersionProvider.getCurrentVersion()` 해석 → `memberStatisticsCacheService.getStatistics(memberId, version)` 위임(자기호출 회피, R7) in `src/main/java/com/planetrush/planetrush/member/service/MemberServiceImpl.java`

**Checkpoint**: SC-001 green — stale-cache 해소 핵심 동작 완성(MVP)

---

## Phase 4: User Story 2 - 배치 진행 중 중간 상태 캐싱 방지 (Priority: P1)

**Goal**: 배치가 *완료되기 전* 조회는 직전 버전 값으로 처리되고, 진행 중 데이터가 새 버전으로 캐싱되지 않는다(SC-002).

**Independent Test**: 마지막 완료=D, 진행중(`endTime=null`) JobLog(D+1) 존재 → 조회는 version=D 로 HIT(Flask 추가 호출 없음) → D+1 완료 처리 후 조회 시 재계산.

### Tests for User Story 2 ⚠️

- [ ] T010 [US2] SC-002 통합 테스트 — 진행중 배치(endTime=null) 구간 조회가 version=D 유지·중간상태 미캐싱, D+1 완료 후 전환 검증 in `StatisticsCacheVersionIntegrationTest.java`

### Implementation for User Story 2

- [ ] T011 [US2] `StatisticsVersionProvider`/T003 쿼리의 `endTime IS NOT NULL`(진행중 제외) 불변식 가드·검증 보강 in `JobLogRepositoryCustomImpl.java` (필요 시 주석/테스트 보강만)

**Checkpoint**: SC-002 green — 자정 레이스로 인한 중간상태 고정 방지

---

## Phase 5: User Story 3 - 전수 evict 없이 자연 만료 (Priority: P2)

**Goal**: 버전 전환이 사용자별 명시 evict 없이 키 변경만으로 신선도를 확보하고, 과거 버전 키는 TTL 로 자연 만료된다(SC-003, SC-005).

**Independent Test**: 버전 전환 후 신규 키 miss·구 키 잔존 확인, 사용자별 캐시 삭제 호출 0회; 캐시 항목 TTL(25h) 설정 확인.

### Tests for User Story 3 ⚠️

- [ ] T012 [US3] SC-003 통합 테스트 — 버전 전환 시 신규 키 생성·구 버전 키 잔존, 사용자별 evict/삭제 미수행 검증 in `StatisticsCacheVersionIntegrationTest.java`
- [ ] T013 [P] [US3] SC-005 테스트 — Redis 키 TTL 이 25h(≈90000s) 근사로 설정됨(`redis TTL` 조회) 검증 in `StatisticsCacheVersionIntegrationTest.java`

**Checkpoint**: SC-003·SC-005 green — 운영 evict 0회 + 무한 누적 방지

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 강건성 인수(SC-004/FR-007/콜드스타트) + 헌법 게이트.

- [ ] T014 [P] SC-004 통합 테스트(single-flight) — 동일 `memberId:version` 동시 N 요청에서 Flask 호출 1회 수렴(`CountDownLatch` + 지연 Flask mock) in `StatisticsCacheVersionIntegrationTest.java`
- [ ] T015 SC-004 충족 검증/보강 — `lockingRedisCacheWriter`+`sync=true` 구성이 동시 적재 직렬화를 보장하는지 확인(T005 연계) in `CacheConfig.java`
- [ ] T016 [P] FR-007 테스트 — Flask 예외 시 해당 키 미캐싱, 다음 요청 재시도(Flask 2회) 검증 in `StatisticsCacheVersionIntegrationTest.java`
- [ ] T017 [P] 콜드스타트(Q3) 테스트 — 완료 JobLog 0건이면 version=오늘(Asia/Seoul)로 계산·캐싱 검증 in `StatisticsCacheVersionIntegrationTest.java`
- [ ] T018 [P] Caffeine→Redis 이관 회귀 점검 — `challenge-avg` 외 캐시/테스트 영향 없는지 확인(필요 시 test 프로파일 캐시 설정 보정)
- [ ] T019 quickstart.md 검증 절차 실행·기록 in `specs/006-statistics-cache-versioning/quickstart.md`
- [ ] T020 헌법 게이트 — `./gradlew check` 및 `./gradlew verifySecretLogScan`(헌법 V) 통과 확인, 결과 증거 기록

---

## 인수 기준 ↔ 테스트 매핑 (헌법 VII — /speckit-analyze 게이트)

| Success Criteria | User Story | 테스트 태스크 | 메서드 위치 |
|------------------|-----------|---------------|-------------|
| SC-001 배치 완료 후 최신값 100% | US1 | T007 | `StatisticsCacheVersionIntegrationTest` |
| SC-002 중간상태 고정 0건 | US2 | T010 | 〃 |
| SC-003 사용자별 evict 0회 | US3 | T012 | 〃 |
| SC-005 과거 버전 TTL 자연 만료 | US3 | T013 | 〃 |
| SC-004 single-flight 1회 수렴 | Polish(FR-006) | T014 | 〃 |
| FR-007 실패 비캐싱 | Polish | T016 | 〃 |
| 콜드스타트(Q3) | Polish | T017 | 〃 |

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup(P1)**: 즉시 시작 가능
- **Foundational(P2)**: Setup 후. **모든 US 차단**. 내부 순서: T002 → T003 → T004(버전 해석), T005·T006(캐시 인프라)는 T003/T004 와 병렬 가능하나 T009 전 완료 필요
- **US1(P3)**: Foundational 후. MVP
- **US2(P4)·US3(P5)**: Foundational 후. US1 의 배선(T008/T009)에 의존(같은 코드 경로 검증) → US1 완료 후 진행 권장
- **Polish(P6)**: 대상 US 완료 후

### Within Each User Story

- 테스트 먼저 작성·실패 확인 → 구현 → green
- US2/US3 는 신규 src/main 변경이 거의 없고 주로 인수 테스트(메커니즘은 Foundational+US1 에서 완성)

### Parallel Opportunities

- T006 은 T002~T005 와 병렬(다른 파일)
- Polish 의 T014/T016/T017/T018 은 서로 병렬(독립 테스트 메서드/점검)
- 단, 동일 파일(`StatisticsCacheVersionIntegrationTest.java`)에 여러 [P] 테스트가 추가되므로, 물리적 병렬 작성 시 머지 충돌 주의(논리적 독립일 뿐)

---

## Parallel Example: Foundational

```bash
# T003/T004(버전 해석 라인)과 T005/T006(캐시 인프라)을 병렬로:
Task: "JobLog QueryDSL MAX(endTime) 쿼리 in JobLogRepositoryCustomImpl.java"
Task: "StatisticsVersionProvider in member/service/StatisticsVersionProvider.java"
Task: "CacheConfig Redis 이관 in core/config/CacheConfig.java"
Task: "GetMyProgressAvgDto 직렬화 보강 in member/service/dto/GetMyProgressAvgDto.java"
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 Setup → Phase 2 Foundational(메커니즘 완성)
2. Phase 3 US1(T007 테스트 → T008/T009 구현) → **SC-001 green**
3. **STOP & VALIDATE**: stale-cache 해소 핵심을 단독 검증·시연

### Incremental Delivery

- US1(SC-001) → US2(SC-002) → US3(SC-003/005) → Polish(SC-004/FR-007/콜드스타트/게이트)
- 각 단계가 이전 동작을 깨지 않고 인수 기준을 하나씩 박제

---

## Notes

- 신규 `build.gradle` 의존성 없음 → 헌법 라이브러리 규칙 미트리거
- 외부 의존(Flask)은 `@MockBean` 으로 호출 횟수 검증(헌법 I 의 컨테이너 의무 대상은 MySQL·Redis)
- 모든 통합 테스트는 `IntegrationTest`(MySQL+Redis Testcontainers) 확장 — 로컬 데몬 불요
- 각 태스크/논리 그룹 후 커밋. untracked `docs/maxlen-*.md`(타 세션 산출물)는 커밋 제외

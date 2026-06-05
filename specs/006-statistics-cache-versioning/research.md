# Phase 0 Research: 통계 캐시 버전 키

**Spec**: [spec.md](./spec.md) · **Branch**: `006-statistics-cache-versioning` · **Date**: 2026-06-06

기존 구현 사실(코드 확인 결과)을 전제로, spec 의 결정(Clarifications Q1~Q3)을 만족하는 기술 선택을 확정한다.

## 현행 구현 사실 (Baseline)

| 항목 | 현재 | 근거 |
|------|------|------|
| 통계 캐시 | Caffeine 로컬, cacheName `challenge-avg`, key `#memberId`, TTL 24h, `sync=true` | `MemberServiceImpl#getMyProgressAvgPer`, `CacheConfig` |
| 통계 계산 | Flask 호출 (RestTemplate, 재시도 5회) | `FlaskApiClient#getMyProgressAvg` |
| 일별 배치 | `progressCalculation()` — `progress_avg` 갱신, 완료 시 `JobLog(jobType="progressCalculation")` 저장 | `ScheduledTasks` |
| 배치 기록 | `JobLog`: `jobType`, `startTime`, `endTime`(완료 시 set, 미완료=null) | `JobLog`, `JobLogRepository`(plain JPA) |
| 엔드포인트 | `GET /api/v1/members/mypage` → `GetMyProgressAvgDto` | `MemberController` |
| 인프라 | Redis(Lettuce)·`@EnableCaching` 존재, Testcontainers(MySQL+Redis) 베이스 `IntegrationTest` 존재 | `RedisConfig`, `IntegrationTest` |
| 의존성 | `spring-boot-starter-cache`·`spring-boot-starter-data-redis`·`caffeine` 모두 보유 | `build.gradle` |

> 신규 `build.gradle` 의존성 추가 없음 → 헌법 "라이브러리 도입 규칙" 트리거되지 않음.

## 결정 (Decisions)

### R1. 통계 캐시 스토어: Caffeine 로컬 → Redis 공유 캐시

- **Decision**: `challenge-avg` 캐시를 Redis 백엔드(`RedisCacheManager`)로 이관한다. TTL 25시간(버전 1세대 수명 + 여유).
- **Rationale**: spec Q1 — 다중 인스턴스 환경에서 모든 인스턴스가 동일 버전 키를 공유해야 한다. Redis 공유 캐시면 새 버전 전환 시 **전역 1회**만 재계산되고 이후 모든 인스턴스가 결과를 공유(SC-004 의도). Caffeine 로컬은 인스턴스마다 중복 계산이 불가피.
- **Alternatives**: (a) Caffeine 유지 + 버전 키 — 정합성은 되나 인스턴스별 중복 계산, spec 전제와 불일치. (b) 2단계(L1 Caffeine + L2 Redis) — 복잡도 과다, 본 스펙 범위 밖.

### R2. 캐시 키에 버전 포함

- **Decision**: cacheName `challenge-avg` 유지, key = `#memberId + ':' + #version` (예: `challenge-avg::15:2026-06-06`). 서비스가 현재 버전을 먼저 해석해 캐시 메서드에 인자로 전달.
- **Rationale**: 버전이 바뀌면 키가 바뀌어 자연 miss → 재계산(FR-001/FR-002). 전수 evict 불필요(FR-004).
- **Alternatives**: cacheName 자체를 버전별로 분리(`challenge-avg-2026-06-06`) — Spring `RedisCacheManager`는 사전 등록 캐시명을 선호하고 동적 캐시명은 TTL 일괄 관리가 번거로움. 키 접미사 방식이 단순·표준적.

### R3. 통계 버전 산정: 직전 완료 배치의 기준일 (DB 파생)

- **Decision**: 통계 버전 = **마지막으로 완료된 `progressCalculation` JobLog 의 `endTime` 을 `Asia/Seoul` 기준 `LocalDate` 로 변환한 값**(`yyyy-MM-dd`). 완료 배치 0건이면 **오늘(Asia/Seoul)**.
  - 쿼리: `progressCalculation` 이고 `endTime IS NOT NULL` 인 JobLog 중 `MAX(endTime)`.
- **Rationale**:
  - spec Q2 — "직전 완료 배치의 데이터 기준일". `endTime.toLocalDate()` 는 날짜 특성상 **단조 진행**(FR-009) 이며 하루 여러 번 실행돼도 같은 날짜 → 같은 버전(캐시 유지).
  - **자정 레이스 회피(US2/FR-003)**: 새 날 배치가 *완료*되기 전에는 마지막 완료가 어제 → 버전=어제 → 어제 값 제공, 중간 상태가 새 버전으로 캐싱되지 않음. 새 날 배치 완료 시점에 `MAX(endTime)` 날짜가 오늘로 넘어가며 버전 전환.
  - **헌법 II**: 버전 진실 공급원을 DB(JobLog)로 두어 도메인/서비스가 `RedisTemplate` 을 직접 만지지 않는다. Redis 별도 버전 키 불필요 → 스케줄러 변경 0, 회귀 위험 최소.
- **Alternatives**:
  - 배치가 Redis 에 `statistics:version` 기록 — 스케줄러 수정 필요 + Redis 휘발 시 버전 유실 위험. 기각.
  - 단조 카운터 — 전용 상태 저장 필요, 날짜보다 가독성↓. 기각.
- **콜드스타트 잔여 리스크(Q3 인지)**: 완료 0건일 때 오늘을 잠정 버전으로 캐싱한 뒤, 같은 날짜 기준 배치가 완료되면 버전(날짜)이 동일해 즉시 갱신이 안 될 수 있음 → 최초 배포 당일 1회성·짧은 구간으로 허용(brief-stale 전제와 일관). 본 스펙에서는 잠정/확정 구분을 구현하지 않는다(Out of scope, 필요 시 후속).

### R4. 버전 해석 비용: 60초 로컬 캐시

- **Decision**: 버전 해석 결과를 짧은 TTL(60초) Caffeine 캐시로 메모이즈한다(`statistics-version` 캐시, 인스턴스 로컬 허용).
- **Rationale**: 버전은 하루 1회 전환되므로 매 요청 DB `MAX` 쿼리는 낭비. 60초 지연은 brief-stale 허용 범위. 버전 캐시는 로컬이어도 무방(전환 후 최대 60초 내 모든 인스턴스 수렴).
- **Alternatives**: 매 요청 DB 쿼리 — 정확하지만 불필요한 부하. 영구 캐시 — 전환 미감지. 60초가 균형점.

### R5. 단일 비행(single-flight) — FR-006 / SC-004

- **Decision**: `RedisCacheManager` 를 `RedisCacheWriter.lockingRedisCacheWriter(connectionFactory)` 로 구성하고 캐시 메서드에 `sync = true` 유지.
- **Rationale**: 새 버전 첫 조회가 동시 다발일 때 locking writer 가 동일 키 적재를 직렬화 → 외부 Flask 중복 호출을 "요청당 1회로 수렴"(SC-004). 인스턴스 내 `sync`(per-key lock)와 결합해 스탬피드 방지.
- **Alternatives**: 분산 락 라이브러리(Redisson) 도입 — 신규 의존성, 과한 복잡도. 기각.

### R6. 실패 미캐싱 — FR-007

- **Decision**: 캐시 메서드는 Flask 실패 시 예외를 그대로 전파한다(`@Cacheable` 은 예외 시 저장하지 않음). sentinel/빈 결과를 성공으로 캐싱하지 않는다.
- **Rationale**: 기존 `FlaskApiClient` 가 재시도 후 `FlaskConnectionFailedException`/`ProgressAvgNotFoundException` 을 던짐 → 자연히 미캐싱. 다음 요청이 재계산 시도 가능.

### R7. 자기호출(self-invocation) 회피

- **Decision**: `@Cacheable` 메서드를 별도 빈 `MemberStatisticsCacheService` 로 분리하고 `MemberServiceImpl` 이 주입받아 호출. `MemberServiceImpl#getMyProgressAvgPer` 는 (1) 버전 해석 → (2) 캐시 서비스 호출 오케스트레이션만.
- **Rationale**: 같은 빈 내부 호출은 Spring AOP 캐시 프록시를 우회한다. 분리해야 캐시 어드바이스가 적용됨. spec 의 `statisticsCacheService.getUserStatistics(...)` 스케치와 동일.

### R8. Redis 직렬화

- **Decision**: 값 직렬화는 `GenericJackson2JsonRedisSerializer` 사용. `GetMyProgressAvgDto` 는 모두 원시 타입 필드라 추가 모듈 불필요. (LocalDate 등 등장 시 `JavaTimeModule` 등록.)
- **Rationale**: JDK 직렬화 회피, 가독성·호환성. DTO 에 기본 생성자 보강 필요 여부는 구현 시 확인(`@NoArgsConstructor` 추가 가능).

### R9. 타임존 고정

- **Decision**: 버전 날짜 산정과 콜드스타트 모두 `Asia/Seoul` 고정.
- **Rationale**: 서버 로캘 흔들림에 따른 버전 경계 불일치 방지. 다중 인스턴스 일관성(FR-008).

### R10. 테스트 전략 (헌법 I·VII)

- **Decision**: `IntegrationTest`(MySQL+Redis Testcontainers) 확장. `FlaskApiClient` 는 `@MockBean` 으로 대체해 **호출 횟수**로 캐시 hit/miss 와 single-flight 를 검증. JobLog 는 실제 DB 에 시드해 버전 전환을 재현.
- **Rationale**: Flask 는 MySQL/Redis 가 아니므로 헌법 I 의 컨테이너 의무 대상이 아니고, 호출 횟수 검증이 캐시 동작 인수에 가장 직접적. Redis 캐시·JobLog DB 는 실제 컨테이너로 동작.
- **SC ↔ 테스트 매핑**: tasks 단계에서 SC-001~005 각각에 IT 메서드를 1:1 박제.

## 미해결(NEEDS CLARIFICATION)

없음 — Phase 0 의 모든 미지수는 위 결정으로 해소됨.

# Implementation Plan: 사용자 통계 캐시 stale-cache 해소 (통계 버전 기반 캐시 키)

**Branch**: `006-statistics-cache-versioning` | **Date**: 2026-06-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/006-statistics-cache-versioning/spec.md`

## Summary

마이페이지 통계(`GET /api/v1/members/mypage`) 캐시가 24h TTL 동안 stale 해지는 문제를, **캐시 키에 "통계 버전"을 포함**해 해소한다. 버전은 *직전에 완료된 `progressCalculation` 배치의 기준일*(`yyyy-MM-dd`, Asia/Seoul)이며 DB(`JobLog`)에서 파생한다. 배치가 *완료된 후에만* 버전이 전환되므로 자정 직후 중간 상태가 새 버전으로 캐싱되지 않는다(US2). 캐시 스토어는 다중 인스턴스 공유를 위해 Caffeine 로컬 → **Redis 공유 캐시**로 이관하며, 버전 전환은 키 변경만으로 신선도를 확보해 전수 evict 가 불필요하다. 신규 의존성·신규 테이블 없음.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.2.7, Spring Cache + Spring Data Redis(Lettuce), JPA + QueryDSL 5.0.0 — *모두 기존 보유, 추가 없음*

**Storage**: MySQL 8.x(`JobLog` 읽기 + `(job_type, end_time)` 인덱스), Redis 7.x(통계 캐시)

**Testing**: JUnit 5 + Testcontainers(MySQL+Redis, 기존 `IntegrationTest` 베이스 재사용), Flask 는 `@MockBean`

**Target Platform**: Linux 서버 (다중 인스턴스 가정 — spec Q1)

**Project Type**: web-service (Spring Boot 단일 모듈)

**Performance Goals**: mypage 통계 조회 캐시 hit 시 외부 호출 0회. 버전 해석은 매 요청 인덱스된 `MAX(endTime)` 1건 읽기(≈0.1ms, 메모 없음 — R4). 새 버전 첫 조회 Flask 호출 요청당 1회 수렴(SC-004).

**Constraints**: 배치 완료 후에만 버전 전환(FR-003). brief-stale(콜드스타트 당일, 버전 전환 경계 순간) 허용. 실패 결과 비캐싱(FR-007). 전수 evict 0회(SC-003).

**Scale/Scope**: 사용자별 통계 1종(`GetMyProgressAvgDto`). 변경 파일 ~6개(설정 1, 서비스 2, 리포지토리 1, DTO 0~1, 테스트 N).

## Constitution Check

*GATE: Phase 0 전 통과 필수. Phase 1 후 재점검.*

### 본 컨스티튜션 적용 항목 (Governance)

| 원칙 | 부합 방식 | 상태 |
|------|-----------|------|
| **I. Testcontainers 통합테스트** | 신규 통합 테스트는 기존 `IntegrationTest`(MySQL+Redis 싱글톤 컨테이너) 확장. 로컬 데몬 불요. | ✅ PASS |
| **II. 외부 의존 어댑터 격리** | Flask 는 기존 `FlaskApiClient`(infra) 경유. 통계 캐시는 Spring Cache 선언적 추상화 사용(서비스가 `RedisTemplate` 직접 호출 안 함). 버전은 JPA 리포지토리(DB)에서 파생 — Redis 직접 접근 없음. | ✅ PASS |
| **III. QueryDSL Projections** | 신규 조회는 `MAX(endTime)` 스칼라 집계(QueryDSL custom). Entity→DTO 수동 매핑 미도입. | ✅ PASS |
| **IV. Outbox 경유 발행** | 메시지 발행 없음 — 해당 없음. | ➖ N/A |
| **V. 민감정보 로그 금지** | 시크릿 로깅 추가 없음. | ✅ PASS |
| **VI. 듀얼 AI 리뷰** | PR 단계에서 Claude+Codex 리뷰 첨부. | ⏳ PR 시 |
| **VII. 인수기준=자동테스트** | SC-001~005 를 통합 테스트로 1:1 박제(tasks 에 매핑). | ✅ PASS(계획) |

**기술 스택/라이브러리**: 신규 `build.gradle` 의존성 없음 → "라이브러리 도입 규칙" 트리거 안 됨. 운영 안티패턴(ddl-auto=update 등) 신규 도입 없음.

**게이트 결과**: 위반 없음. `Complexity Tracking` 비움.

## Project Structure

### Documentation (this feature)

```text
specs/006-statistics-cache-versioning/
├── plan.md              # 이 파일
├── spec.md              # 기능 명세 (+ Clarifications)
├── research.md          # Phase 0 — 결정 R1~R10
├── data-model.md        # Phase 1 — StatisticsVersion·JobLog·캐시 항목
├── contracts/
│   └── cache-key.md      # Phase 1 — 캐시 키/버전 해석/조회 흐름 계약
├── quickstart.md        # Phase 1 — 검증 절차
└── tasks.md             # Phase 2 — /speckit-tasks 산출(미생성)
```

### Source Code (repository root)

```text
src/main/java/com/planetrush/planetrush/
├── core/config/
│   └── CacheConfig.java                    # [수정] challenge-avg 를 RedisCacheManager 로 이관
│                                           #        + lockingRedisCacheWriter(R5) + Jackson 직렬화(R8)
│                                           #        (버전 메모 캐시 없음 — R4)
├── member/service/
│   ├── MemberServiceImpl.java              # [수정] getMyProgressAvgPer: @Cacheable 제거,
│   │                                       #        버전 해석 → 캐시 서비스 위임(R7)
│   ├── StatisticsVersionProvider.java      # [신규] 현재 버전 해석(매 요청 DB 파생, 캐시 없음)
│   └── MemberStatisticsCacheService.java   # [신규] @Cacheable(challenge-avg, key=memberId:version)
└── scheduler/log/
    ├── JobLogRepository.java               # [유지] JpaRepository
    └── JobLogRepositoryCustom(+Impl).java  # [신규] findLatestCompletedProgressCalculationEndTime (QueryDSL)
                                            #        + JobLog 에 (job_type, end_time) 인덱스

src/test/java/com/planetrush/planetrush/member/
└── StatisticsCacheVersionIntegrationTest.java   # [신규] SC-001~005 (IntegrationTest 확장)
```

**Structure Decision**: 기존 Spring Boot 단일 모듈 레이어(`core/config`, `member/service`, `scheduler/log`)에 그대로 편입. 캐시 추상화는 설정 레이어, 버전 파생은 `scheduler/log` 리포지토리, 오케스트레이션은 `member/service`. 새 패키지 없음.

## 구현 단계 개요 (tasks 입력용)

1. **JobLog 버전 쿼리 + 인덱스**: QueryDSL custom 으로 `MAX(endTime)` (progressCalculation, endTime not null). `(job_type, end_time)` 복합 인덱스 추가.
2. **StatisticsVersionProvider**: 쿼리 → Asia/Seoul LocalDate → `yyyy-MM-dd`, 콜드스타트=오늘. 캐시 없음(매 요청 DB 파생, R4).
3. **CacheConfig 이관**: `RedisCacheManager`(challenge-avg TTL 25h, JSON 직렬화, lockingRedisCacheWriter). 기존 Caffeine-only 정리(버전 메모 캐시 없음).
4. **MemberStatisticsCacheService**: `@Cacheable(challenge-avg, key="#memberId + ':' + #version", sync=true)` 내부에서 member 검증 + Flask 호출.
5. **MemberServiceImpl 리팩터**: `getMyProgressAvgPer` = version 해석 → 캐시 서비스 호출. 기존 `@Cacheable` 어노테이션 제거.
6. **GetMyProgressAvgDto 직렬화 보강**: 필요 시 `@NoArgsConstructor` 등 JSON 역직렬화 대응.
7. **통합 테스트**: SC-001~005 박제(Flask `@MockBean` 호출 횟수, Redis 키/TTL, JobLog 시드).

## Complexity Tracking

> 위반 없음 — 비움.

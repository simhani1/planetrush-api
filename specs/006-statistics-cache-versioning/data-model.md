# Phase 1 Data Model: 통계 캐시 버전 키

**Spec**: [spec.md](./spec.md) · **Research**: [research.md](./research.md)

이 기능은 새 영속 테이블을 만들지 않는다. "통계 버전"은 기존 `JobLog` 에서 **파생되는 값**이고, 캐시 항목은 Redis 키-값이다.

## 1. StatisticsVersion (파생 값 객체, 비영속)

현재 유효한 통계 데이터 세대를 식별하는 값.

| 속성 | 타입 | 규칙 |
|------|------|------|
| value | `String` (`yyyy-MM-dd`) | 캐시 키 구성요소. 예 `2026-06-06` |

**산정 규칙** (R3):

```
endTime_max = MAX(JobLog.endTime) where jobType = 'progressCalculation' and endTime is not null
if endTime_max exists:
    version = endTime_max.atZone(Asia/Seoul).toLocalDate()      # 직전 완료 배치 기준일
else:
    version = LocalDate.now(Asia/Seoul)                          # 콜드스타트 잠정 버전 (Q3)
return version.format("yyyy-MM-dd")
```

**불변식**:
- **단조 진행(FR-009)**: `endTime` 은 증가만 하므로 version 날짜도 같거나 증가. 과거로 회귀 불가.
- **완료 후 전환(FR-003)**: 새 날 배치가 *완료*(endTime set)되기 전에는 version 이 직전 날짜로 유지.
- **전역 단일(FR-008)**: 모든 사용자·인스턴스가 동일 version 을 본다(같은 DB MAX 결과). 버전은 매 요청 DB 에서 직접 파생(R4, 메모 없음) — 배치 완료 즉시 전 인스턴스 동일 반영.

## 2. JobLog (기존 엔티티 — 통계 배치 실행 기록)

변경 없음. 본 기능은 **읽기 전용**으로 사용.

| 컬럼 | 타입 | 의미 |
|------|------|------|
| id | Long (PK) | |
| jobType | String | `progressCalculation` 필터 대상 |
| startTime | LocalDateTime | 배치 시작 |
| endTime | LocalDateTime (영속 시 NOT NULL) | 완료 시각. 엔티티는 `finish()`(endTime 설정) 후에만 save → 진행 중(미완료) row 는 영속되지 않음. "배치 진행 중"은 *해당 날짜 완료 row 부재*로 표현 |
| elapsedTime | String | 소요 |

**신규 조회 (QueryDSL custom, 헌법 III 정합)**:
- `findLatestCompletedProgressCalculationEndTime(): Optional<LocalDateTime>`
  - `select max(endTime)` from JobLog where `jobType = 'progressCalculation'` and `endTime is not null`.
  - 스칼라 집계 — Entity→DTO 수동 매핑 아님. N+1 무관.
- 인덱스: `job_log(job_type, end_time)` 복합 인덱스 **추가**(R4) — 매 요청 `MAX(endTime)` 을 인덱스 끝값 1건 읽기로 처리.

## 3. 통계 캐시 항목 (Redis)

| 요소 | 값 |
|------|-----|
| cacheName | `challenge-avg` (유지) |
| key | `{memberId}:{version}` → Redis 키 `challenge-avg::15:2026-06-06` |
| value | `GetMyProgressAvgDto` (JSON 직렬화, R8) |
| TTL | 25시간 (R1) — 1세대 수명 + 여유, 과거 버전 항목 자연 만료(FR-005) |
| 적재 정책 | miss 시 Flask 계산 후 저장. 예외 시 미저장(FR-007). 동일 키 동시 적재는 locking writer 로 직렬화(R5/FR-006) |

**GetMyProgressAvgDto** (기존, 변경 없음): `completionCnt`, `challengeCnt`, `myTotalAvg/Per`, `totalAvg`, 카테고리별(`exercise/beauty/life/study/etc`) `my*Avg`/`my*Per`/`*Avg` — 모두 원시 타입.

## 상태 전이 (버전 전환 타임라인)

```
[D-1 23:50 배치 완료] endTime=D-1 → version=D-1, 캐시 challenge-avg::{m}:D-1
        │
[D 00:02 요청] 새 배치 아직 미완료 → MAX(endTime)=D-1 → version=D-1
        │        → D-1 캐시 hit (중간 상태 캐싱 없음, US2/SC-002)
        │
[D 00:05 새 배치 완료] endTime=D → version=D
        │
[D 00:06 요청] version=D → challenge-avg::{m}:D MISS → Flask 재계산 → 최신값 캐싱 (US1/SC-001)
        │        (D-1 키는 참조 안 됨 → TTL 25h 후 자연 만료, evict 0회 SC-003)
```

# Contract: 통계 캐시 키 & 버전 해석

**Spec**: [../spec.md](../spec.md) · **Type**: 내부 계약(캐시 키 스킴 + 버전 해석 알고리즘). 외부 REST 계약 변경 없음.

## 1. REST 엔드포인트 — 변경 없음

`GET /api/v1/members/mypage` (`@RequireJwtToken`) → `BaseResponse<GetMyProgressAvgDto>`.

요청/응답 스키마·상태코드 불변. 본 기능은 **응답 신선도 보장 방식만** 바꾸며 클라이언트 계약에 영향 없음.

## 2. 캐시 키 계약

| 항목 | 값 |
|------|-----|
| cacheName | `challenge-avg` |
| key 표현식 | `#memberId + ':' + #version` |
| Redis 물리 키 | `challenge-avg::{memberId}:{version}` |
| version 포맷 | `yyyy-MM-dd` (Asia/Seoul) |
| 예시 | `challenge-avg::15:2026-06-06` |

**불변식**:
- 같은 `memberId` 라도 `version` 이 다르면 별개 항목(FR-001).
- 키에 `version` 외 가변 요소를 추가하지 않는다(사용자별 단일 활성 항목 + 직전 세대 1개로 수렴).

## 3. 버전 해석 계약 (`StatisticsVersionProvider`)

**입력**: 없음(현재 시각·DB 상태에서 파생). **출력**: `String version` (`yyyy-MM-dd`).

```
getCurrentVersion():
    # 매 요청 DB 파생 (별도 버전 캐시 없음, R4)
    endTimeMax = jobLogRepository.findLatestCompletedProgressCalculationEndTime()  # Optional<LocalDateTime>
    if endTimeMax.isPresent():
        date = endTimeMax.get().atZone(Asia/Seoul).toLocalDate()
    else:
        date = LocalDate.now(Asia/Seoul)          # 콜드스타트 잠정 버전
    return date.format("yyyy-MM-dd")
```

**보장**:
- C1 (단조): 연속 호출에서 반환 날짜는 같거나 증가.
- C2 (완료 후 전환): 새 날 `progressCalculation` 의 `endTime` 이 set 되기 전에는 직전 날짜 반환.
- C3 (전역 일관): 동일 DB 상태에서 모든 인스턴스 동일 반환(매 요청 DB 파생 → 배치 완료 즉시 수렴).

## 4. 통계 조회 흐름 계약

```
MemberServiceImpl.getMyProgressAvgPer(memberId):
    version = statisticsVersionProvider.getCurrentVersion()
    return memberStatisticsCacheService.getStatistics(memberId, version)

MemberStatisticsCacheService.getStatistics(memberId, version):   # @Cacheable(challenge-avg, key=memberId:version, sync=true)
    memberRepository.findById(memberId) or throw MemberNotFoundException
    return flaskApiClient.getMyProgressAvg(memberId)             # 예외 전파 → 미캐싱(FR-007)
```

**보장**:
- F1 (재계산): version 변경 후 첫 호출은 MISS → Flask 1회 호출 → 신선값 반환·캐싱(SC-001).
- F2 (중간상태 차단): 배치 미완료 구간 호출은 직전 version 으로 hit, 새 version 미생성(SC-002).
- F3 (single-flight): 동일 `memberId:version` 동시 첫 호출 시 Flask 호출 1회로 수렴(SC-004) — locking RedisCacheWriter + `sync=true`.
- F4 (실패 비저장): Flask 예외 시 해당 키 미저장, 다음 호출 재시도(FR-007).
- F5 (evict 불요): version 전환은 키 변경만으로 신선도 확보, 사용자별 명시 삭제 0회(SC-003). 과거 키는 TTL 25h 자연 만료(FR-005).

# Quickstart: 통계 캐시 버전 키 검증

**Spec**: [spec.md](./spec.md) · **Plan**: [plan.md](./plan.md)

## 전제

- Docker 실행 중(Testcontainers MySQL+Redis 자동 부팅). 로컬 데몬 불필요.
- Flask 통계 서버는 테스트에서 `@MockBean` 으로 대체(호출 횟수로 검증).

## 자동 테스트로 인수 검증 (권장)

```bash
./gradlew test --tests "*StatisticsCacheVersion*"
# 또는 전체 게이트
./gradlew check
```

기대: 아래 인수 시나리오가 통합 테스트로 통과.

| SC | 시나리오 | 검증 방식 |
|----|----------|-----------|
| SC-001 | 배치 완료 후 첫 조회 = 신선값 | JobLog endTime=D 시드 → 조회(Flask 1회) → JobLog endTime=D+1 시드 → 조회(Flask 추가 1회, 새 값) |
| SC-002 | 배치 진행 중 중간상태 미캐싱 | 마지막 완료=D, 진행중(endTime=null) JobLog(D+1) 존재 → 조회는 version=D 로 hit, Flask 추가 호출 없음 → D+1 완료 후 조회 시 재계산 |
| SC-003 | evict 0회 | version 전환 후 신규 키 miss·구 키 잔존 확인. 사용자별 캐시 삭제 호출 없음 |
| SC-004 | single-flight | 동일 member+version 동시 N 요청 → Flask 호출 1회 |
| SC-005 | 과거 버전 자연 만료 | 구 version 키 TTL(25h) 설정 확인 |

## 수동 확인 (로컬 Redis 사용 시)

```bash
# 1) 첫 조회 → MISS, Flask 호출, 캐싱
curl -H "Authorization: Bearer <JWT>" localhost:8080/api/v1/members/mypage

# 2) Redis 키 확인 (version = 직전 완료 progressCalculation 기준일)
redis-cli KEYS 'challenge-avg::*'
#   예: challenge-avg::15:2026-06-06

# 3) 재조회 → HIT (Flask 미호출)
curl -H "Authorization: Bearer <JWT>" localhost:8080/api/v1/members/mypage

# 4) 배치 1회 완료시켜 새 날짜 JobLog 생성 후 재조회 → 새 키 MISS, 재계산
redis-cli KEYS 'challenge-avg::*'
#   예: challenge-avg::15:2026-06-07  (구 키는 TTL 만료까지 잔존)
```

## 롤백/주의

- 캐시 스토어를 Caffeine→Redis 로 이관하므로, 배포 후 첫 워밍업 구간에 캐시 miss 가 일시 증가(정상).
- 버전 해석은 60초 로컬 캐시 → 배치 완료 후 최대 60초까지 직전 값이 보일 수 있음(brief-stale 허용 범위).

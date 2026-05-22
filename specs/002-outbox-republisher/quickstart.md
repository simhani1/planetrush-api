# Quickstart: Outbox Republisher Worker

**대상**: 폴러를 운영·튜닝하거나 outbox 재발행 동작을 검증하려는 개발자
**전제**: Spec 002 머지 완료 (`OutboxRepublisher` 가용)

---

## 1. 폴러가 하는 일 (30초 요약)

```
5초마다:
  status=PENDING 이고 createdAt이 컷오프(5분) 이내인 OutboxEvent 조회
    (SELECT ... FOR UPDATE SKIP LOCKED — 다중 인스턴스 안전)
    │
    ▼ 각 건마다
  payload(JSON) → MessageCommand 역직렬화 → Redis Stream 재발행
    ├─ 성공 → status=PUBLISHED
    └─ 실패 → PENDING 유지 → 다음 사이클 자연 재시도
```

발행 직전 앱이 죽거나 `VerificationRedisStreamPublisher` 발행이 실패해 `PENDING`으로 남은 outbox를 자동 복구한다. **At-Least-Once 발행 보장.**

---

## 2. 설정 (`application.yml`)

```yaml
outbox:
  republisher:
    enabled: true              # 폴러 활성 여부 (test 프로필은 false)
    polling-interval-ms: 5000  # 폴링 주기 (밀리초)
    cutoff-minutes: 5          # createdAt 컷오프 (분) — 초과 PENDING은 폴링 제외
    batch-size: 100            # 사이클당 처리 상한
```

모두 코드 수정 없이 조정 가능. 프로필별 오버라이드:
- `application-test.yml`: `enabled: false` (통합 테스트는 폴러를 직접 호출)
- `application-dev.yml`/`application-prod.yml`: 공통 `application.yml` 값 상속

---

## 3. 컷오프 튜닝 가이드

`cutoff-minutes`는 "이 시간을 넘긴 PENDING은 자동 복구를 포기한다"는 경계다.

```
[하한]                      [컷오프]                    [상한]
정상적 일시 장애      <      이 값      <      비즈니스 허용 지연
의 최대 회복 시간
(배포 ~3분)                 (기본 5분)            (인증 검증 분 단위 지연 무해)
```

- **너무 짧으면**: 배포 롤링 업데이트 중 쌓인 PENDING이 배포 끝나기 전에 버려짐 (false positive).
- **너무 길면**: poison message가 오래 폴러 사이클을 점유.
- **권장 튜닝법**: 운영 후 "정상 발행된 outbox의 `createdAt → PUBLISHED` 소요 시간" p99를 측정해 그보다 충분히 크게 설정. 메트릭은 후속 observability 스펙.

---

## 4. 동작 확인

### 로컬에서 폴러 강제 트리거

`test` 프로필에서는 폴러가 비활성이므로, 통합 테스트는 `OutboxRepublisher`의 메서드를 직접 호출한다:

```java
class SomeTest extends IntegrationTest {
    @Autowired OutboxRepublisher republisher;

    @Test
    void pendingIsRepublished() {
        // given: PENDING outbox 저장
        // when:
        republisher.republishPending();   // 폴러 사이클 1회 직접 실행
        // then: status == PUBLISHED 확인
    }
}
```

### 운영 중 stuck outbox 조회

컷오프를 넘겨 자동 복구에서 제외된 PENDING은 DB에 남는다. 직접 조회:

```sql
SELECT id, event_type, created_at
FROM outbox_event
WHERE status = 'PENDING' AND created_at <= NOW() - INTERVAL 5 MINUTE;
```

---

## 5. 트러블슈팅

| 증상 | 원인 / 조치 |
|---|---|
| 폴러가 안 도는 것 같음 | `outbox.republisher.enabled` 확인. `test` 프로필이면 의도된 비활성 |
| PENDING이 계속 쌓임 | Redis Stream 발행 자체가 실패 중. `VerificationRedisStreamPublisher` 로그 확인 (`[Redis Stream] publish failed`) |
| 컷오프 넘긴 PENDING이 안 줄어듦 | 정상 — 컷오프 초과분은 자동 복구 대상 아님. 위 SQL로 조회 후 수동 조치 |
| 다중 인스턴스에서 중복 발행 | `FOR UPDATE SKIP LOCKED` 미동작 의심. MySQL 8.0+ 인지 확인. 단 컨슈머 멱등성(Spec 003)이 무해화 |
| 폴러 사이클이 느림 | `batch-size`가 과도하게 큰지 확인. 락 보유 시간 ∝ 배치 크기 |

---

## 6. 후속 스펙과의 관계

| 스펙 | 관계 |
|---|---|
| **Spec 003** (Idempotent Stream Consumer) | 폴러의 재발행으로 발생 가능한 **중복 메시지를 컨슈머 측에서 무해화**. 본 스펙(At-Least-Once) + Spec 003(멱등성) = Exactly-Once-Effect |
| **Spec 005** (Flyway) | 본 스펙은 스키마 변경이 없어 무관. `ddl-auto` 전환은 Spec 005 |
| observability 스펙 | 컷오프 초과 stuck outbox 카운트, 발행 지연 분포 등 메트릭 노출 |

---

## 7. 알려진 한계 (의도된 단순화)

- **재시도 횟수 추적 없음**: `retryCount`/`FAILED` 상태 미도입. 무한 재시도는 시간 컷오프로만 차단. → 컨슈머 멱등성 전제이므로 "정확히 1번"이 불필요.
- **컷오프 초과 알림 없음**: stuck outbox를 운영자에게 자동 통지하지 않음. 위 SQL 수동 조회 또는 후속 observability 스펙.
- **메시지 순서 미보장**: 재발행으로 순서가 뒤바뀔 수 있음. 컨슈머가 감내.

# Phase 0 Research: Outbox Republisher Worker

**Feature**: Outbox Republisher Worker (At-Least-Once 보장 1/2)
**Branch**: `002-outbox-republisher`
**Date**: 2026-05-18

plan.md Technical Context의 미해결 결정을 푼다. 신규 의존성이 없으므로 라이선스 점검은 생략한다.

---

## R-001 · SKIP LOCKED 폴링 쿼리 구현 방식

**Decision**: `OutboxRepository`에 **native query**로 `SELECT ... FOR UPDATE SKIP LOCKED`를 작성한다.

```java
@Query(value = """
        SELECT * FROM outbox_event
        WHERE status = 'PENDING' AND created_at > :cutoff
        ORDER BY created_at
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
List<OutboxEvent> findRepublishableForUpdateSkipLocked(
        @Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
```

**Rationale**:

- MySQL 8.0+는 `FOR UPDATE SKIP LOCKED`를 정식 지원. 잠긴 행을 **대기 없이 즉시 건너뛴다** — FR-002의 "잠금 대기 없음" 요구에 정확히 부합.
- JPA `@Lock(PESSIMISTIC_WRITE)`만으로는 일반 `FOR UPDATE`까지만 — 잠긴 행에서 **대기**가 발생해 다중 폴러가 직렬화됨. SKIP LOCKED 의미론이 안 나온다.
- native query 결과도 Spring Data JPA가 컬럼→필드 매핑으로 **managed 엔티티**를 반환하므로, 같은 트랜잭션 내에서 `published()` 호출 시 dirty checking으로 자동 반영된다.

**Alternatives considered**:

| 대안 | 채택 X 사유 |
|---|---|
| `@Lock` + `@QueryHints(jakarta.persistence.lock.timeout = -2)` | Hibernate 6의 `-2`(SKIP_LOCKED 매직값) 의존 — 가독성 낮고 버전 의존적. native query가 의도를 직접 표현 |
| 애플리케이션 레벨 분산 락(Redisson 등) | 신규 의존성 + 별도 인프라. DB가 이미 SKIP LOCKED를 제공하는데 과함 |
| 락 없이 폴링 + 컨슈머 멱등성에 전적 의존 | 동작은 하나 다중 인스턴스에서 매 사이클 N배 중복 발행 → 브로커·컨슈머 부하 N배 (US2 위반) |

---

## R-002 · 폴러 트랜잭션 경계

**Decision**: 폴러 사이클 1회 = 트랜잭션 1개. `@Scheduled` 메서드에 `@Transactional`을 두고, 그 안에서 [SKIP LOCKED 조회 → 배치 각 건 발행 → 성공 시 `published()`]를 수행한다. 트랜잭션이 커밋될 때까지 조회된 행의 락이 유지되어 다른 폴러 인스턴스는 해당 행을 건너뛴다.

**Rationale**:

- SKIP LOCKED의 락은 **트랜잭션 생존 기간 동안만** 유효. 조회 후 트랜잭션을 닫으면 락이 풀려 다른 폴러가 끼어든다. 따라서 조회~발행~상태전환을 한 트랜잭션으로 묶어야 한다.
- 발행은 Redis Stream `XADD` — 보통 1건당 수 ms. 배치 100건이면 사이클당 수백 ms 수준이라 락 보유 시간이 짧다. 단순함 우선.
- 기존 `VerificationRedisStreamPublisher.publish()`가 `@Transactional`(전파 REQUIRED)이라 폴러 트랜잭션에 자연히 참여한다. `publish()` 내부 `catch (RuntimeException)`이 예외를 삼키므로 한 건 실패가 폴러 트랜잭션 전체를 롤백시키지 않는다 — 실패 건은 `PENDING`으로 남아 다음 사이클 재시도(FR-003).

**Alternatives considered**:

- **건별 트랜잭션** (각 outbox를 독립 트랜잭션): 락 보유 시간이 더 짧고 격리도 좋지만, 폴러가 `REQUIRES_NEW`로 건별 트랜잭션을 열어야 해 구조 복잡도↑. 발행이 빠른 본 도메인에선 사이클당 트랜잭션으로 충분 — 단순함 우선. 향후 발행 지연이 커지면 건별로 전환 가능(quickstart에 명시).

**Caveat**: 배치 크기를 과도하게 키우면(예: 10000) 락 보유 시간이 길어진다. 기본 100을 권장하며 설정 가능(R-006).

---

## R-003 · `OutboxEvent.payload`(JSON) → `MessageCommand` 역직렬화

**Decision**: 폴러가 `ObjectMapper`로 `payload`를 역직렬화해 `MessageCommand`를 조립한다. 필드 매핑:

| MessageCommand | 출처 |
|---|---|
| `eventId` | `OutboxEvent.id` |
| `standardImg` | `payload.standardImgUrl` |
| `targetImg` | `payload.verificationImgUrl` |
| `memberId` | `payload.memberId` |
| `planetId` | `payload.planetId` |

`payload`는 `VerificationExternalEventRecorder.buildPayload()`가 만든 `{standardImgUrl, verificationImgUrl, memberId, planetId}` JSON이다.

**Rationale**:

- `OutboxEvent`는 `payload`를 불투명 문자열로 들고 있고, 발행 경로(`VerificationMessagePublisher.publish`)는 `MessageCommand`를 받는다. 폴러가 둘을 잇는 변환을 담당.
- 역직렬화 대상은 작은 전용 record(`VerificationOutboxPayload`)로 두어 타입 안전성 확보. `Map<String,Object>` 직접 파싱은 키 오타 위험.

**Alternatives considered**:

- `payload` JSON에 처음부터 `MessageCommand`와 동일 구조를 저장: 기존 `buildPayload()` 변경 필요 + 기존 outbox 레코드와 불일치. 변환 레이어가 더 안전.

**Caveat**: `EventType`이 `VERIFICATION_REQUEST` 외로 늘어나면 폴러가 타입별 분기를 해야 한다. 현재 단일 타입이므로 본 스펙은 `VERIFICATION_REQUEST`만 처리하고, 다른 타입은 로그 경고 후 건너뛴다(향후 확장점).

---

## R-004 · Graceful Shutdown

**Decision**: Spring 기본 `ThreadPoolTaskScheduler`의 종료 대기를 활성화한다.

```yaml
spring:
  task:
    scheduling:
      shutdown:
        await-termination: true
        await-termination-period: 20s
```

**Rationale**:

- `@Scheduled` 메서드는 `ThreadPoolTaskScheduler`에서 실행된다. `await-termination`을 켜면 앱 종료 시 in-flight 폴러 사이클이 완료될 때까지 대기 → FR-007 충족.
- 설령 강제 종료로 사이클이 중단되어도, 트랜잭션이 롤백되어 해당 outbox는 `PENDING`으로 남고 다음 인스턴스가 재처리한다(At-Least-Once라 안전). graceful shutdown은 "정상 종료 시 깔끔함"을 위한 것이지 정합성의 필수 조건은 아니다.

**Alternatives considered**:

- 폴러에 `@PreDestroy` + 수동 플래그: `ThreadPoolTaskScheduler`의 표준 종료 처리로 충분하므로 불필요한 복잡도.

---

## R-005 · 폴러 활성/비활성 (프로필별)

**Decision**: 폴러 컴포넌트에 `@ConditionalOnProperty(name = "outbox.republisher.enabled", havingValue = "true")`를 붙인다.

- `application.yml` (공통 기본): `outbox.republisher.enabled: true`
- `application-test.yml`: `outbox.republisher.enabled: false`

**Rationale**:

- `test` 프로필에서 폴러가 자동으로 돌면 통합 테스트의 "발행 직전 상태"를 폴러가 가로채 검증이 불안정해진다. 비활성화 후 테스트가 폴러 컴포넌트의 메서드를 **직접 호출**해 결정론적으로 검증한다(FR-006).
- `dev`/`prod`는 공통 `application.yml`의 `true`를 그대로 상속하므로 별도 명시 불필요.

**Alternatives considered**:

- `@Profile("!test")`: 동작하지만 운영 중 폴러를 끄려면 재배포 필요. `@ConditionalOnProperty`는 설정만으로 토글 가능해 운영 유연성이 높다.

---

## R-006 · 설정 외부화 (`@ConfigurationProperties`)

**Decision**: `OutboxRepublisherProperties`를 `@ConfigurationProperties(prefix = "outbox.republisher")` record로 둔다.

| 키 | 타입 | 기본값 | 의미 |
|---|---|---|---|
| `enabled` | boolean | `true` | 폴러 활성 여부 |
| `polling-interval-ms` | long | `5000` | 폴링 주기(밀리초) |
| `cutoff-minutes` | long | `5` | `createdAt` 컷오프(분) — 초과 PENDING은 폴링 제외 |
| `batch-size` | int | `100` | 사이클당 처리 상한 |

`@Scheduled(fixedDelayString = "${outbox.republisher.polling-interval-ms:5000}")`로 주기를 주입한다.

**Rationale**:

- FR-005 — 컷오프·주기·배치를 코드 수정 없이 조정 가능해야 한다. `@ConfigurationProperties`가 타입 안전 + IDE 자동완성 + 검증(`@Validated`) 지원.
- 컷오프 5분 근거는 spec.md Assumptions에 명문화됨(배포 시간 ~3분 초과 + 비즈니스 허용 지연 이내 + 폴링 60회 기회). 운영 데이터로 조정.

**Alternatives considered**:

- 개별 `@Value` 주입: 키가 4개로 늘면 흩어져 응집도 저하. record properties로 묶는 게 명료.

---

## 결론

모든 미해결 결정이 해소되었다. Phase 1로 이동한다.

- `data-model.md`: **생성하지 않음** — `OutboxEvent`/`OutboxStatus` 스키마 변경이 0이다(FR-008). plan.md Structure에 명시.
- `contracts/`: **생성하지 않음** — 본 스펙은 신규 외부 API/계약을 추가하지 않으며 내부 컴포넌트(`OutboxRepublisher`)만 도입한다. 내부 협력 인터페이스는 기존 `VerificationMessagePublisher`를 재사용.
- `quickstart.md`: 폴러 운영·튜닝·트러블슈팅 가이드를 작성한다.

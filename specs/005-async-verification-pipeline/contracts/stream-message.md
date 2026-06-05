# Stream Message Contract: `verify:requests`

**Spec**: [../spec.md](../spec.md) · **Plan**: [../plan.md](../plan.md) · **Date**: 2026-06-03

Spring → 컨슈머 비동기 메시지 계약. 본 문서는 컨슈머(`planetrush_consumer/`) 측 `SPRING_INTEGRATION_BRIEF.md` §3 의 정의를 Spring 측 단일 source 로 미러링한다. 두 문서가 어긋나면 **본 문서가 컨슈머 통합 기준이며, BRIEF 변경 시 본 문서도 갱신해야 한다**.

---

## Stream

| 속성 | 값 |
|---|---|
| Stream name | `verify:requests` (`app.verification.stream-name` 으로 외부화) |
| Consumer group | `verifiers` (컨슈머가 자체 생성·관리, Spring 은 발행만) |
| Entry format | Redis Stream key-value pairs (모든 값은 string) |

## Entry fields (Spring → Stream)

| 필드 | 타입 | 필수 | 출처 | 설명 |
|---|---|---|---|---|
| `requestId` | string (UUID) | ✅ | `VerificationRequest.id` = `OutboxEvent.id` (R-004) | 컨슈머가 callback 매칭에 사용 |
| `standardImgUrl` | string | ✅ | `Planet.standardVerificationImg` | 기준 이미지 URL (스냅샷) |
| `targetImgUrl` | string | ✅ | `VerificationRequest.targetImgUrl` | 사용자가 업로드한 인증 이미지 URL |
| `callbackUrl` | string | ✅ | `app.verification.callback-url` | 컨슈머가 결과 POST 할 Spring 엔드포인트 절대 URL (예: `http://host.docker.internal:8080/api/v1/internal/verification-results`) |
| `threshold` | string (float) | — | `app.verification.threshold` 또는 기본 `"0.088"` | 컨슈머 측 유사도 판정 임계값. 부재 시 컨슈머 기본값 사용 |

**Serialization**:
- 모든 값을 **string 으로** 직렬화한다(컨슈머가 string 으로 파싱). 정수/실수 직접 발행 금지.
- `OutboxEvent.payload` 는 위 5개 필드를 키로 하는 JSON 으로 영속되며, `VerificationRedisStreamPublisher` 가 JSON → Map<String,String> 변환 후 `opsForStream().add(streamName, map)` 호출.

## 발행 시점

```
@Transactional 안:
  1. VerificationRequest INSERT (status=PENDING, id=UUID)
  2. OutboxEvent INSERT (id=같은 UUID, payload=위 5필드 JSON, status=PENDING)
@Transactional commit

AFTER_COMMIT 리스너 (VerificationExternalMessageListener):
  3. VerificationRedisStreamPublisher.publish(outboxEvent)
     → XADD verify:requests * requestId=... standardImgUrl=... targetImgUrl=... callbackUrl=... threshold=...
  4. 성공 시 OutboxEvent.status = PUBLISHED
  5. 실패 시 OutboxEvent.status = PENDING 유지 → OutboxRepublisher 가 다음 사이클에 재발행 (Spec 002)
```

## Idempotency / Ordering / Retry

- **Idempotency**: Outbox 가 At-Least-Once 만 보장(Spec 002). 컨슈머 측 멱등은 컨슈머 자체 책임. Spring 측 callback 핸들러 멱등은 본 스펙 FR-004 에서 별도 보장.
- **Ordering**: 보장 없음. Republisher 가 PENDING 을 다시 잡아 발행하면 순서가 어긋날 수 있음.
- **Retry**: Stream 발행 자체의 재시도는 Outbox 인프라 책임(Spec 002).

## 컨슈머 측 응답 (Stream → Spring callback)

컨슈머는 본 stream 의 entry 를 소비한 뒤 `callbackUrl` 로 다음 페이로드를 POST 한다. 자세한 페이로드 / 응답 코드 / 멱등 규칙은 [rest-api.md §3](rest-api.md#3-post-apiv1internalverification-results--컨슈머-callback-신규) 참조.

요약:
| 컨슈머 응답 | callback payload |
|---|---|
| 추론 성공 | `{ "requestId": "...", "similarityScore": 87, "verified": true }` |
| 추론 실패 (재시도 무의미) | `{ "requestId": "...", "error": "image_load_failed", "message": "..." }` |

## 비호환 변경 정책

본 메시지 계약은 Spring↔컨슈머 양측에 동시 적용. 한쪽만 변경 금지. 변경 시:
1. 컨슈머 측 `SPRING_INTEGRATION_BRIEF.md` §3 + 본 문서 동시 PR
2. 변경 키는 deprecated 표시 후 양측 1주 이상 호환 유지 (운영 가동 시)
3. 본 PoC 단계는 두 레포 동기 머지로 단순화 가능

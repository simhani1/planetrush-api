# Data Model: Async Verification Pipeline

**Spec**: [spec.md](spec.md) · **Plan**: [plan.md](plan.md) · **Research**: [research.md](research.md) · **Date**: 2026-06-03

## 개요

- 신규 entity 1개: `VerificationRequest` (인증 요청 라이프사이클 추적, 클라이언트 폴링 표면).
- 기존 entity 1개 수정: `VerificationRecord` — 사용자·챌린지·날짜 단위 unique 인덱스 추가.
- 기존 entity 0건 변경: `OutboxEvent`, `Member`, `Planet`. (Outbox payload 의 직렬화 컨텐츠만 변경 — entity 스키마 변경 X.)

## 엔티티: `VerificationRequest` (신규)

| 필드 | 타입 | 제약 | 비고 |
|---|---|---|---|
| `id` | `String (VARCHAR(36))` | PK, NOT NULL | UUID v4. `OutboxEvent.id`·stream `requestId` 와 동일 값(R-004). |
| `memberId` | `Long` | NOT NULL, INDEX | 사용자 식별자. 외래키는 두지 않는다(컨슈머 callback 처리 단계는 사용자 검증을 컨트롤러 인증 단계로 이관, lazy load 부담 회피). |
| `planetId` | `Long` | NOT NULL, INDEX | 챌린지 식별자. 동일 사유로 외래키 미사용. |
| `targetImgUrl` | `String (VARCHAR(500))` | NOT NULL | 사용자가 업로드한 인증 이미지 URL. |
| `standardImgUrl` | `String (VARCHAR(500))` | NOT NULL | 기준 이미지 URL(스냅샷 — `Planet.standardVerificationImg` 변경 영향 차단). |
| `status` | `enum VerificationRequestStatus` | NOT NULL, INDEX | `PENDING` / `SUCCESS` / `FAIL` / `ERROR`. |
| `similarityScore` | `Integer` | NULL | SUCCESS·FAIL 종착 시 기록. PENDING·ERROR 는 NULL. |
| `verified` | `Boolean` | NULL | SUCCESS=true, FAIL=false, PENDING·ERROR 는 NULL. |
| `errorMessage` | `String (VARCHAR(500))` | NULL | ERROR 종착 시 컨슈머가 보고한 사유. SUCCESS·FAIL 은 NULL. |
| `createdAt` | `LocalDateTime` | NOT NULL, `@CreationTimestamp` | 요청 영속 시각. |
| `completedAt` | `LocalDateTime` | NULL | 종착 상태 진입 시각. PENDING 은 NULL. |

**복합 인덱스**:
- `idx_verification_request_member_status (member_id, status)` — 마이페이지 등에서 사용자별 PENDING 조회용.

**라이프사이클 (state machine)**:

```text
        ┌────────────┐
        │  PENDING   │  (POST /api/v1/verify/planets/{id} 진입 시)
        └─────┬──────┘
              │  callback(VerifiedTrue)
              ├──────────────► SUCCESS  (종착, VerificationRecord 1건 저장 트리거)
              │  callback(VerifiedFalse)
              ├──────────────► FAIL     (종착, VerificationRecord 1건 저장 트리거)
              │  callback(error=image_load_failed | ...)
              └──────────────► ERROR    (종착, VerificationRecord 미저장 — clarify Q2)

종착 후 callback 추가 도착 → 본문 무시, 200 응답 (FR-004a 멱등)
```

**불변식**:
- PENDING 상태에선 `similarityScore`, `verified`, `errorMessage`, `completedAt` 모두 NULL.
- SUCCESS/FAIL 상태에선 `similarityScore`, `verified`, `completedAt` NON-NULL, `errorMessage` NULL.
- ERROR 상태에선 `errorMessage`, `completedAt` NON-NULL, `similarityScore`/`verified` NULL.
- 종착 상태 진입 후 다시 PENDING 으로 되돌리는 전이 없음 — `UPDATE ... WHERE status='PENDING'` optimistic update 절로 SQL 단계에서 강제 (R-002).

**JPA 매핑 메모**:
- `@Id` + `@GeneratedValue` 없음 — UUID 는 서비스 레이어에서 생성 후 주입.
- `@Enumerated(EnumType.STRING)` — 컬럼은 `status VARCHAR(20)` (Spec 002 의 `OutboxEvent.status` ORDINAL 버그 회피).
- `@CreationTimestamp` 는 `createdAt` 만.
- `completedAt` 은 callback 핸들러에서 명시적 set.

## 엔티티: `VerificationRecord` (수정)

기존 entity. 본 스펙에서 **컬럼 추가 없음**. 인덱스 1건 추가:

```sql
-- Generated column 으로 DATE 만 추출
ALTER TABLE verification_record
  ADD COLUMN upload_date_only DATE
  GENERATED ALWAYS AS (DATE(upload_date)) STORED;

-- 사용자·챌린지·날짜 단위 unique 안전망 (R-003)
ALTER TABLE verification_record
  ADD CONSTRAINT uniq_verification_record_member_planet_date
  UNIQUE (member_id, planet_id, upload_date_only);
```

**JPA 매핑 변경**:
- `VerificationRecord` 클래스 `@Table(name = "verification_record", uniqueConstraints = @UniqueConstraint(name = "uniq_verification_record_member_planet_date", columnNames = {"member_id", "planet_id", "upload_date_only"}))` 추가.
- generated column 은 entity 에서 별도 필드로 매핑하지 않음(읽기/쓰기 불필요). DDL 은 `application.yml` 의 `ddl-auto=update` 가 처리 — 단 `update` 모드의 generated column 자동 생성 신뢰도가 낮으므로 **tasks 에 수동 DDL 적용 단계 명시**.

**기존 데이터 점검 (R-003 risk)**:
```sql
SELECT member_id, planet_id, DATE(upload_date) d, COUNT(*) c
FROM verification_record
GROUP BY member_id, planet_id, d
HAVING c > 1;
```
운영 적용 전 0 row 확인 필요. quickstart.md §운영 적용 절차에 기재.

## 엔티티: `OutboxEvent` (변경 없음)

Spec 002 와 동일. 본 스펙은 entity 스키마를 건드리지 않는다. payload(JSON) 직렬화 결과만 R-004 에 맞춰 새 필드(`requestId`, `targetImgUrl`, `callbackUrl`, `threshold`)를 포함하도록 `VerificationExternalEventRecorder.buildPayload` 를 보강.

| 변경 위치 | 변경 내용 |
|---|---|
| `OutboxRecordCommand` (record) | `requestId` 필드 추가 |
| `VerificationExternalEventRecorder.save` | command 의 `requestId` 를 `OutboxEvent.id` 로 사용 (UUID 동일 — R-004) |
| `VerificationExternalEventRecorder.buildPayload` | payload 에 `requestId`, `callbackUrl`, `threshold` 추가, 키명 `verificationImgUrl` → `targetImgUrl` 로 컨슈머 계약 정합 |

## 라이프사이클 끝단 (UI 표현 매핑 — 참고)

| status | 사용자 UI 권장 표현 (클라이언트 책임) |
|---|---|
| PENDING | "인증 처리 중…" (애니메이션) |
| SUCCESS | "인증 통과! ⭐ 별 1개 획득" 또는 비슷 |
| FAIL | "인증 미통과. 같은 챌린지로 오늘 더 시도할 수 없음" |
| ERROR | "이미지 처리 오류. 다시 시도해주세요" (재시도 권한 보존, Q2) |

UI/UX 구체화는 클라이언트 측 작업이며 본 스펙 범위 외.

## ER 다이어그램

```text
[Member] 1───* [VerificationRequest]
                │  (UUID id = OutboxEvent.id = stream requestId)
                │
[Member] 1───* [VerificationRecord] (SUCCESS/FAIL 종착 시 0..1건)
                │
[Planet] 1───* [VerificationRequest]
[Planet] 1───* [VerificationRecord]

[VerificationRequest] ─── (UUID 일치) ─── [OutboxEvent]
                                         │  (AFTER_COMMIT)
                                         ▼
                                  Redis Stream `verify:requests`
                                         │
                                         ▼
                                 [Consumer (외부)]
                                         │  HTTP POST
                                         ▼
                  /api/v1/internal/verification-results
                                         │
                                         ▼
                              [VerificationResultService]
                                         │
                                         ├── status guard (FR-004a)
                                         │
                                         ▼
                  status=SUCCESS/FAIL → save VerificationRecord (uniq 제약이 FR-004b 안전망)
                  status=ERROR        → record 미저장 (Q2)
```

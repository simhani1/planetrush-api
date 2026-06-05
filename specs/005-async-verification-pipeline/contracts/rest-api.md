# REST API Contract: Async Verification Pipeline

**Spec**: [../spec.md](../spec.md) · **Plan**: [../plan.md](../plan.md) · **Date**: 2026-06-03

본 스펙이 노출/수정하는 HTTP 엔드포인트 계약. 모든 경로는 `Content-Type: application/json` 입출력.

---

## 1. `POST /api/v1/verify/planets/{planet-id}` — 인증 요청 발행 (수정)

**역할**: 사용자가 챌린지 인증 이미지를 업로드한다. Spring 은 인증 요청을 PENDING 으로 영속하고 Outbox 경유로 컨슈머에게 비동기 처리를 위임한 뒤 즉시 응답.

**Auth**: `@RequireJwtToken` — JWT 필수 (기존과 동일).

**Path Params**:
- `planet-id` (long) — 챌린지 행성 ID.

**Request Body**:
```json
{
  "verificationImgUrl": "https://s3.../uploads/abc.jpg"
}
```

| 필드 | 타입 | 필수 | Validation |
|---|---|---|---|
| `verificationImgUrl` | string | ✅ | NotBlank, 길이 ≤ 500 |

**Response — 202 Accepted**:
```json
{
  "code": "SUCCESS",
  "message": "...",
  "data": {
    "requestId": "9c3a... (UUID v4)",
    "status": "PENDING"
  }
}
```

**Response Status Codes**:
| Status | 의미 | 비고 |
|---|---|---|
| 202 | 정상 접수, PENDING 으로 영속됨 | 컨슈머 처리는 비동기 |
| 400 | Validation 실패 (`verificationImgUrl` 누락 등) | |
| 401 | JWT 누락/만료 | 기존 `JwtAuthenticationFilter` 책임 |
| 404 | 존재하지 않는 `planet-id` | `PlanetNotFoundException` |
| 409 | 같은 사용자가 오늘 같은 챌린지에 종착된 `VerificationRecord` 보유 시 `AlreadyVerifiedException` | 기존 가드. PENDING 상태는 가드하지 않음(Q3) |

**Side effects** (트랜잭션 1건):
- `verification_request` 1건 INSERT (status=PENDING)
- `outbox_event` 1건 INSERT (id=requestId 동일, status=PENDING)
- 트랜잭션 커밋 후 AFTER_COMMIT 리스너 → Redis Stream 발행

**기존과 차이**:
- 기존: 200 + `BaseResponse.ofSuccess()` (응답 본문에 결과 없음, 동기 처리 후 반환)
- 신규: 202 + `requestId` 동봉, 즉시 반환

---

## 2. `GET /api/v1/verify/{request-id}` — 인증 요청 상태 조회 (신규)

**역할**: 클라이언트가 발행한 인증 요청의 현재 상태와 결과(종착 시)를 조회.

**Auth**: `@RequireJwtToken` — JWT 필수. 요청자 본인의 인증 요청만 조회 가능(memberId 일치 검증).

**Path Params**:
- `request-id` (UUID string) — `POST /verify/planets/{id}` 에서 받은 식별자.

**Response — 200 OK (PENDING)**:
```json
{
  "code": "SUCCESS",
  "data": {
    "requestId": "9c3a-...",
    "status": "PENDING",
    "similarityScore": null,
    "verified": null,
    "errorMessage": null
  }
}
```

**Response — 200 OK (SUCCESS)**:
```json
{
  "data": {
    "requestId": "9c3a-...",
    "status": "SUCCESS",
    "similarityScore": 87,
    "verified": true,
    "errorMessage": null
  }
}
```

**Response — 200 OK (FAIL)**:
```json
{
  "data": {
    "requestId": "9c3a-...",
    "status": "FAIL",
    "similarityScore": 23,
    "verified": false,
    "errorMessage": null
  }
}
```

**Response — 200 OK (ERROR)**:
```json
{
  "data": {
    "requestId": "9c3a-...",
    "status": "ERROR",
    "similarityScore": null,
    "verified": null,
    "errorMessage": "image_load_failed"
  }
}
```

**Response Status Codes**:
| Status | 의미 |
|---|---|
| 200 | 정상 조회 (PENDING 포함) |
| 401 | JWT 누락/만료 |
| 403 | 다른 사용자의 `request-id` 조회 시 |
| 404 | 존재하지 않는 `request-id` (`VerificationRequestNotFoundException`) |

**Caching/Polling**:
- 클라이언트는 short polling (1~2초 간격) 권장. 본 스펙 범위에선 server-push 미구현.

---

## 3. `POST /api/v1/internal/verification-results` — 컨슈머 callback (신규)

**역할**: 컨슈머가 이미지 유사도 추론 결과를 Spring 으로 보고. 내부 전용 경로(`/internal/*`).

**Auth**: 본 PoC 단계는 **경로 분리만**. Spring Security 의 `JwtAuthenticationFilter` 는 `/internal/*` 패턴을 통과시킨다(R-005). 외부 망 차단은 ALB/Nginx 레벨(운영 인프라 책임). HMAC 검증은 후속 스펙.

**Request Body (정상 결과)**:
```json
{
  "requestId": "9c3a-...",
  "similarityScore": 87,
  "verified": true
}
```

**Request Body (오류 결과)**:
```json
{
  "requestId": "9c3a-...",
  "error": "image_load_failed",
  "message": "Failed to decode JPEG from target URL"
}
```

| 필드 | 타입 | 필수 | Validation |
|---|---|---|---|
| `requestId` | string (UUID) | ✅ | NotBlank, UUID 패턴 |
| `similarityScore` | integer | 정상 결과 시 ✅ | 0~100 |
| `verified` | boolean | 정상 결과 시 ✅ | — |
| `error` | string | 오류 결과 시 ✅ | NotBlank, 길이 ≤ 100 |
| `message` | string | 오류 결과 시 선택 | 길이 ≤ 500 |

**페이로드 분기 규칙**:
- `error` 필드가 존재 → 오류 결과 (status=ERROR)
- `error` 필드가 부재 + `verified` + `similarityScore` 존재 → 정상 결과 (status=SUCCESS/FAIL)
- 두 패턴 모두 어긋남 → 400

**Response Status Codes**:
| Status | 의미 |
|---|---|
| 200 | 정상 처리 또는 멱등 흡수 (이미 종착 상태인 requestId 도 200) |
| 200 | 존재하지 않는 `request-id` 도 200 (FR-011 — 컨슈머 무한 재시도 흡수). 단 의심 호출 로그 남김 |
| 400 | 페이로드 형식 오류(필드 누락, UUID 형식 위반, 분기 규칙 위반) |

**Side effects (트랜잭션 1건, 락 사용 없음 — R-002/R-003)**:
1. **Optimistic update** (`requestId` 단위 멱등 — FR-004a):
   ```sql
   UPDATE verification_request
      SET status=?, similarity_score=?, verified=?, error_message=?, completed_at=NOW()
    WHERE id=? AND status='PENDING';
   ```
   - 영향 행 수 **0**: 이미 종착 또는 존재하지 않는 ID → 본문 무시, 200 반환 (FR-004a + FR-011).
   - 영향 행 수 **1**: 정상 전이. 2단계로 진행.
2. SUCCESS/FAIL 결과인 경우 — **DB unique 안전망에 의존한 record 저장** (사용자·챌린지·날짜 단위 멱등 — FR-004b):
   - `verificationRecordRepository.save(...)` 시도.
   - 정상 INSERT: 첫 callback. record 1건 저장.
   - `DataIntegrityViolationException` (unique 위반) catch: 동시 race 또는 다른 PENDING 요청의 callback 이 record 를 먼저 저장. 추가 저장 skip, continue.
3. ERROR 결과인 경우: `VerificationRecord` 저장 단계 자체를 건너뛰고 트랜잭션 커밋 (clarify Q2).
4. 200 반환.

---

## 4. 기존 엔드포인트 영향 매트릭스

| 경로 | 변경 |
|---|---|
| `POST /api/v1/verify/planets/{id}` | **응답 상태 코드 200 → 202, 본문 변경**. 클라이언트는 이전과 다른 응답을 받음 — 마이그레이션 고려 필요(클라이언트 측 작업 별도). |
| `POST /api/v1/internal/verification-results` | 신규 |
| `GET /api/v1/verify/{request-id}` | 신규 |
| 기타 (`/members/*`, `/planets/*` 등) | 영향 없음 |

**Client compatibility 메모**: 본 PR 의 머지 시점에 클라이언트가 즉시 새 흐름으로 전환해야 한다. 클라이언트 측 변경이 동반되지 않으면 기존 클라이언트는 202 응답을 받고도 결과를 받지 못함. 단계적 cutover 가 필요한 경우 spec/plan 외 별도 협의(릴리스 노트).

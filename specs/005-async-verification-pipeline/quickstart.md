# Quickstart: Async Verification Pipeline

**Spec**: [spec.md](spec.md) · **Plan**: [plan.md](plan.md) · **Date**: 2026-06-03

본 스펙 구현 후 로컬에서 정상 흐름과 chaos 시나리오를 재현하는 가이드. 운영 적용 절차도 포함.

---

## 1. 로컬 사전 준비

### 1-1. 컨슈머 측 (별도 레포 — `planetrush_consumer/`)

```bash
cd path/to/planetrush_consumer
docker compose up -d           # redis + dummy_callback + consumer
docker compose ps              # 컨테이너 상태 확인
docker compose logs -f consumer  # 추론 로딩 로그 모니터링
```

- Redis 가 `localhost:6379` (호스트 매핑) 에 열린다.
- `consumer` 컨테이너가 `verify:requests` stream 을 `XREADGROUP` 폴링.
- 모델 다운로드/워밍업까지 약 1~2분.

### 1-2. Spring 측 (본 레포)

`.env` 또는 환경변수:
```bash
REDIS_HOST=localhost
REDIS_PORT=6379
# 컨슈머 컨테이너에서 호스트의 Spring 으로 가려면 host.docker.internal
VERIFICATION_CALLBACK_URL=http://host.docker.internal:8080/api/v1/internal/verification-results
```

```bash
./gradlew bootRun           # Spring 기동
# 또는 IDE 에서 PlanetrushApplication 실행
```

기동 로그에서 다음 확인:
- `Outbox republisher enabled` (Spec 002)
- `VerificationRedisStreamPublisher` 빈 등록
- callback URL 외부화 설정 노출 (`app.verification.callback-url=...`)

---

## 2. Phase 1 — 정상 흐름 (SC-001)

```bash
TOKEN="<JWT>"

# 인증 요청
curl -i -X POST http://localhost:8080/api/v1/verify/planets/1 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"verificationImgUrl":"https://s3.../uploads/test.jpg"}'

# 응답 예시:
# HTTP/1.1 202
# { "data": { "requestId": "9c3a...", "status": "PENDING" } }

REQ_ID="9c3a..."

# 폴링
curl -s http://localhost:8080/api/v1/verify/$REQ_ID \
  -H "Authorization: Bearer $TOKEN" | jq

# 1~3초 뒤 status 가 SUCCESS 또는 FAIL 로 전환됨
```

**검증 포인트**:
- Spring 로그: `VerificationRequest INSERT`, `OutboxEvent INSERT`, `AFTER_COMMIT publish`, `OutboxEvent.status PUBLISHED`
- 컨슈머 로그: `Received message`, `Inference complete`, `Callback POST 200`
- DB:
  ```sql
  SELECT id, status, similarity_score, verified, completed_at FROM verification_request WHERE id = '<REQ_ID>';
  SELECT id, status FROM outbox_event WHERE id = '<REQ_ID>';
  SELECT * FROM verification_record WHERE member_id = ... AND planet_id = ... AND DATE(upload_date) = CURDATE();
  ```
  - `verification_request.status` = SUCCESS/FAIL, `completed_at` NOT NULL
  - `outbox_event.status` = PUBLISHED
  - `verification_record` 1건 (SUCCESS/FAIL 일 때)

---

## 3. Phase 2 — 멱등 (SC-005)

### 3-a. `requestId` 단위 멱등

```bash
# Phase 1 의 정상 흐름이 끝난 직후 (REQ_ID 가 SUCCESS 상태)
# 동일 payload 로 callback 을 한 번 더 호출
curl -i -X POST http://localhost:8080/api/v1/internal/verification-results \
  -H "Content-Type: application/json" \
  -d '{"requestId":"<REQ_ID>","similarityScore":87,"verified":true}'

# 응답: 200 (멱등 흡수)
```

**검증**: `verification_request` row 의 `completed_at` / `similarity_score` 가 첫 callback 시점 값 그대로 유지. `verification_record` 도 1건만 존재.

### 3-b. 사용자·챌린지·날짜 단위 멱등

```bash
# 같은 사용자가 같은 planet 에 두 번 요청
RID1=$(curl -s -X POST http://localhost:8080/api/v1/verify/planets/1 -H "Authorization: Bearer $TOKEN" -d '{"verificationImgUrl":"..."}' | jq -r '.data.requestId')
RID2=$(curl -s -X POST http://localhost:8080/api/v1/verify/planets/1 -H "Authorization: Bearer $TOKEN" -d '{"verificationImgUrl":"..."}' | jq -r '.data.requestId')

# 둘 다 PENDING. 컨슈머가 둘 다 추론하고 callback 두 번.
# 충분히 대기 후 폴링
curl -s http://localhost:8080/api/v1/verify/$RID1 -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:8080/api/v1/verify/$RID2 -H "Authorization: Bearer $TOKEN"
# 둘 다 SUCCESS (또는 FAIL) 종착

# 하지만 verification_record 는 1건만:
mysql> SELECT COUNT(*) FROM verification_record WHERE member_id = ? AND planet_id = ? AND DATE(upload_date) = CURDATE();
+----------+
| COUNT(*) |
+----------+
|        1 |
+----------+
```

---

## 4. Phase 3 — Chaos (SC-003) — **본 스펙의 핵심 증거**

**목적**: 컨슈머가 단일 장애 지점이 될 수 있는 상황에서, 메시지 큐가 그 장애를 흡수함을 입증.

```bash
# 1. 정상 기동 (Phase 1 완료 상태)
docker compose ps | grep consumer  # 가동 중

# 2. 인증 요청 10건 발행
for i in {1..10}; do
  curl -s -X POST http://localhost:8080/api/v1/verify/planets/1 \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"verificationImgUrl":"https://s3.../uploads/img'$i'.jpg"}' \
    | jq -r '.data.requestId'
done > /tmp/requestIds.txt

# 3. ~5건 처리됐을 때 컨슈머 강제 종료
docker kill <consumer-container-name>

# 4. Redis 상태 확인
redis-cli XLEN verify:requests
# 컨슈머 다운 동안 신규 발행분이 누적

redis-cli XPENDING verify:requests verifiers
# 미처리(PEL) 메시지 확인

# 5. Spring 상태 확인 — 미처리 요청들은 여전히 PENDING (5xx 가 아님)
for id in $(cat /tmp/requestIds.txt); do
  curl -s http://localhost:8080/api/v1/verify/$id -H "Authorization: Bearer $TOKEN" \
    | jq -r '"\(.data.requestId) \(.data.status)"'
done | sort -u
# 출력: 일부 SUCCESS/FAIL + 일부 PENDING (실패는 0)

# 6. 30초 대기 후 컨슈머 재기동
docker compose up -d consumer

# 7. Awaitility 로 모두 종착할 때까지 폴링 (수동도 가능)
# 30초~1분 안에 모든 PENDING 이 SUCCESS/FAIL/ERROR 로 전환됨
```

**기대 결과 (narrative 의 증거)**:
- ✅ 요청 실패율 0% — 컨슈머 다운타임에도 인증 요청 5xx 발생 없음.
- ✅ 다운타임 동안 클라이언트는 PENDING (재시도 책임 없음).
- ✅ 복구 즉시 누적 메시지 자동 처리.

> **Before/After 비교 (선택 Phase 4)**: 동일 시나리오를 이전 동기 Flask 흐름으로 돌리면 다운타임 중 5건이 5xx (실패율 ~50%). 본 스펙 PR 머지 전 측정해 narrative 강화. 본 스펙 범위 안에선 자동 테스트가 SC-003 으로 검증.

---

## 5. 자동 테스트 실행

```bash
./gradlew test --tests "*VerificationAsyncFlowIntegrationTest"
./gradlew test --tests "*VerificationConsumerOutageIntegrationTest"
./gradlew test --tests "*VerificationCallbackIdempotencyTest"
./gradlew test --tests "*VerificationDailyIdempotencyTest"
./gradlew test --tests "*VerificationOutboxRepublisherIntegrationTest"

# 헌법 V 게이트
./gradlew verifySecretLogScan

# 전체
./gradlew check
```

---

## 6. 운영 적용 절차

### 6-1. 사전 점검 (R-003 risk)

운영 DB 에서 다음 SQL 실행 → **0 row** 확인:
```sql
SELECT member_id, planet_id, DATE(upload_date) d, COUNT(*) c
FROM verification_record
GROUP BY member_id, planet_id, d
HAVING c > 1;
```

1+ row 이면 운영 데이터 정리 후 unique 인덱스 적용. 정리 정책(어느 row 를 남길지)은 별도 협의.

### 6-2. DDL 적용 (수동)

`ddl-auto=update` 의 generated column 신뢰도가 낮아 본 인덱스는 수동 DDL 로 적용:
```sql
ALTER TABLE verification_record
  ADD COLUMN upload_date_only DATE
  GENERATED ALWAYS AS (DATE(upload_date)) STORED;

ALTER TABLE verification_record
  ADD CONSTRAINT uniq_verification_record_member_planet_date
  UNIQUE (member_id, planet_id, upload_date_only);

CREATE TABLE IF NOT EXISTS verification_request (
  id VARCHAR(36) PRIMARY KEY,
  member_id BIGINT NOT NULL,
  planet_id BIGINT NOT NULL,
  target_img_url VARCHAR(500) NOT NULL,
  standard_img_url VARCHAR(500) NOT NULL,
  status VARCHAR(20) NOT NULL,
  similarity_score INT NULL,
  verified BOOLEAN NULL,
  error_message VARCHAR(500) NULL,
  created_at TIMESTAMP NOT NULL,
  completed_at TIMESTAMP NULL,
  INDEX idx_verification_request_member_status (member_id, status)
);
```

> 정식 마이그레이션 도구(Flyway) 도입은 별도 스펙.

### 6-3. 환경변수 / 설정값

| 키 | dev | prod | 비고 |
|---|---|---|---|
| `REDIS_HOST` | localhost | 운영 Redis 호스트 | |
| `REDIS_PORT` | 6379 | 6379 | |
| `VERIFICATION_CALLBACK_URL` | `http://host.docker.internal:8080/api/v1/internal/verification-results` | `https://api.planetrush.../api/v1/internal/verification-results` | 컨슈머 컨테이너에서 도달 가능한 절대 URL |
| `app.verification.stream-name` | `verify:requests` | `verify:requests` | |
| `app.verification.threshold` | `"0.088"` | `"0.088"` | 컨슈머 기본값과 동일하면 생략 가능 |

### 6-4. 외부 망 차단

`/api/v1/internal/*` 경로는 ALB/Nginx 레벨에서 외부 인터넷 트래픽 차단. 사내 VPC 또는 컨슈머 컨테이너만 도달 가능하도록 보안 그룹 구성. (HMAC 검증 도입은 후속 스펙.)

### 6-5. 단계적 cutover

1. 본 PR 머지 직전: 클라이언트 측 코드가 202 응답·폴링 흐름을 처리하도록 동반 배포.
2. 머지 직후: Spring 은 즉시 새 흐름으로 전환됨. 기존 동기 Flask 호출 코드(`AsyncVerificationProcessor` + `FlaskApiClient`)는 dead path 가 됨 — R-008.
3. 회귀 안전 마진(1~2주) 후 별도 PR 로 dead path 정리.

---

## 7. 트러블슈팅

| 증상 | 원인 후보 | 점검 |
|---|---|---|
| 인증 요청은 202 인데 영원히 PENDING | 컨슈머 다운 / Stream 발행 실패 | (a) `docker compose ps consumer`, (b) `redis-cli XLEN verify:requests`, (c) `SELECT status FROM outbox_event WHERE id='<reqId>'` — `PENDING` 이면 Spec 002 Republisher 가 1분 안에 잡음 |
| callback 이 404 로 떨어짐 | `callbackUrl` 잘못됨 | 컨슈머 컨테이너 안에서 `curl <callbackUrl>` 가능한지 확인. `host.docker.internal` 매핑 필요할 수 있음 |
| `verification_record` 가 2건 저장됨 | unique 인덱스 미적용 또는 generated column 누락 | `SHOW CREATE TABLE verification_record` 로 인덱스 확인 |
| 동일 `requestId` 로 callback 2회 도착 후 두 번째도 도메인 효과 발생 | 멱등 가드 누락 | `VerificationResultService` 의 비관적 락 + status 가드 확인 (R-002) |
| chaos test 에서 컨슈머 복구 후에도 일부 PENDING 잔존 | Republisher 사이클 미도달 또는 ConsumerGroup PEL 미회수 | (a) `outbox_event` PENDING 잔여 — Republisher 로그 확인, (b) Redis `XPENDING` — 컨슈머 측 ack 누락 |

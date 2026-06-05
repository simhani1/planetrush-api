# Spring 통합 작업 지침서 (별도 Claude 세션용)

> **새 세션 첫 메시지에 이 문서 전체를 붙여넣으면 됨.** 컨슈머 측 작업 컨텍스트와 Spring 측 구현 범위 / 테스트 계획이 모두 포함되어 있다.

---

## 0. 너의 역할

PlanetRush 챌린지 인증 시스템을 **Redis Streams 기반 비동기 아키텍처**로 재구성하는 작업의 **Spring API 측 구현**을 담당한다.

컨슈머(Python, PyTorch 이미지 유사도 검증) 측 구현은 이미 완료되어 있으며 (`planetrush_consumer/`), end-to-end 동작 검증도 끝났다. **너는 Spring 쪽 publisher + callback 엔드포인트를 만들고, 컨슈머가 단일 장애 지점이 될 수 있음을 입증하는 chaos test 를 수행한다.**

## 1. 이 설계가 "처음부터 이렇게 되었어야 했던" 이유

기존 구조의 문제 (회고에서 발견):

| 문제 | 원인 |
|---|---|
| Spring 일반 API tail latency 악화 | 같은 EC2 위 Flask 의 PyTorch CPU 추론(1~2s)이 코어 점유 |
| 인증 요청이 즉시 실패 가능 | Flask 다운/지연 시 동기 HTTP 호출이 그대로 5xx |
| 모델 메모리 3중 로드 | `gunicorn --workers 3` 으로 인한 워커별 모듈 import |
| APScheduler 3중 실행 | 동일 원인. 매주 일요일 00:00 에 PopularKeyword INSERT 3번 발생 가능 |

**메시지 큐 기반으로 설계했다면 이 문제들이 모두 처음부터 발생하지 않는다**:
- Spring 은 인증 요청을 큐에 넣고 즉시 `202 Accepted` 반환 → 스레드/CPU 점유 0
- 컨슈머가 죽어도 메시지는 Redis Stream 에 영속 → 요청 유실 없음
- 컨슈머는 HTTP 서버가 아니므로 gunicorn 불필요 → 워커 부작용 자체 없음

본 작업은 이 "처음부터 했어야 할 설계"를 적용한다.

## 2. 전체 아키텍처

```
[클라이언트]
    │ ① 인증 요청 (이미지 업로드)
    ▼
[Spring]
    │ ② DB에 PENDING 상태로 record 생성
    │ ③ XADD verify:requests {requestId, standardImgUrl, targetImgUrl, callbackUrl, threshold}
    │
    ├─► 즉시 ④ 202 Accepted + {requestId} 응답
    │
    ▼
[Redis Streams]
    │ ⑤ XREADGROUP
    ▼
[Consumer (별도 컨테이너, PyTorch)]
    │ ⑥ 이미지 다운로드 + EfficientNet 추론 + cosine similarity
    │ ⑦ POST {callbackUrl} {requestId, similarityScore, verified}
    ▼
[Spring]
    │ ⑧ DB 업데이트 (PENDING → SUCCESS/FAIL/ERROR)
    │
[클라이언트] ── ⑨ GET /v1/verification/{requestId} (폴링) ──► [Spring]
```

## 3. 메시지 스키마 (컨슈머와의 계약)

### 3-1. 요청 (Spring → `verify:requests` stream)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `requestId` | string | ✅ | UUID. Spring 이 결과 매칭에 사용 |
| `standardImgUrl` | string | ✅ | 기준 이미지 URL (planet.standard_verification_img) |
| `targetImgUrl` | string | ✅ | 사용자가 업로드한 인증 이미지 URL |
| `callbackUrl` | string | ✅ | 컨슈머가 결과 POST 할 Spring 엔드포인트 |
| `threshold` | string (float) | — | 기본 `"0.088"` |

### 3-2. 응답 (Consumer → Spring callback `POST`)

**성공:**
```json
{ "requestId": "uuid", "similarityScore": 87, "verified": true }
```

**이미지 로드 실패 (입력 데이터 오류, 재시도 무의미):**
```json
{ "requestId": "uuid", "error": "image_load_failed", "message": "..." }
```

### 3-3. ⚠️ 멱등성 요구사항

컨슈머는 callback 이 실패할 경우 backoff 후 재시도하며, **최종 실패 시 메시지를 ACK 하지 않아** 다음 사이클에 동일 메시지를 다시 처리한다. 즉 **동일 requestId 로 callback 이 2회 이상 도착할 수 있다**. Spring 측 callback 핸들러는 반드시 멱등이어야 한다 (이미 완료 상태면 무시).

## 4. 구현 범위 (체크리스트)

### 4-1. 의존성 / 설정

- [ ] `spring-boot-starter-data-redis` 추가 (Lettuce 포함됨)
- [ ] `application.yml` 에 Redis 연결 + 비즈니스 설정 외부화

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      database: ${REDIS_DB:0}

app:
  verification:
    stream-name: verify:requests
    threshold: "0.088"
    callback-url: ${VERIFICATION_CALLBACK_URL:http://host.docker.internal:8080/internal/verification-results}
```

> **로컬 도커 환경 주의**: 컨슈머 컨테이너에서 호스트의 Spring(`localhost:8080`) 으로 가려면 `host.docker.internal` 사용. 또는 Spring 도 같은 docker network 에 띄우고 서비스명으로 호출.

### 4-2. Publisher

**책임**: 챌린지 인증 요청을 Redis Stream 에 publish.

권장 인터페이스:
```java
public interface VerificationPublisher {
    /** @return 생성된 requestId */
    String publish(String standardImgUrl, String targetImgUrl);
}
```

구현 힌트:
- `StringRedisTemplate.opsForStream().add(...)` 또는 `RedisTemplate<String, Object>.opsForStream()`
- `requestId = UUID.randomUUID().toString()`
- 모든 필드는 string 으로 직렬화 (컨슈머가 string 으로 파싱함)
- 발행 실패 시 예외 throw (Spring 컨트롤러가 5xx 응답)

### 4-3. 인증 요청 API 변경

**Before (동기):**
```
POST /v1/verification
  body:     { planetId, imgUrl }
  response: { similarityScore, verified }   // 1~2초 블로킹
```

**After (비동기):**
```
POST /v1/verification
  body:     { planetId, imgUrl }
  response: 202 Accepted + { requestId, status: "PENDING" }
```

처리 순서:
1. 입력 검증 + planet 존재 확인
2. `verification_request` row INSERT (status=PENDING)
3. `VerificationPublisher.publish(...)` 호출
4. 즉시 응답

### 4-4. Callback 엔드포인트

```
POST /internal/verification-results
```

**처리 순서:**
1. body 파싱
2. `requestId` 로 `verification_request` 조회
3. **상태가 이미 SUCCESS/FAIL/ERROR 이면 즉시 200 반환** (멱등 처리)
4. payload 형태에 따라:
   - 정상 응답 → `similarityScore`, `verified` 저장, status = SUCCESS or FAIL (verified 값 기준)
   - error 응답 → `error_message` 저장, status = ERROR
5. 도메인 로직 트리거 (챌린지 진행률 갱신 등 — 기존 로직 재사용)
6. 200 OK 응답

**보안**:
- `/internal/*` 경로는 외부 접근 차단 (Spring Security 또는 Nginx/ALB 레벨)
- 운영 전환 시 HMAC 헤더 검증 권장 (`X-Internal-Auth`). 본 PoC 단계에선 경로 분리만 해도 OK.

### 4-5. 상태 조회 엔드포인트

```
GET /v1/verification/{requestId}
  response: { status: "PENDING|SUCCESS|FAIL|ERROR",
              similarityScore?: int, verified?: bool, errorMessage?: string }
```

클라이언트는 발행 후 1~2초 간격으로 폴링. (향후 SSE/WebSocket 전환 가능, 본 작업 범위 외.)

### 4-6. DB 스키마

```sql
CREATE TABLE verification_request (
    request_id        VARCHAR(36) PRIMARY KEY,
    planet_id         BIGINT NOT NULL,
    member_id         BIGINT NOT NULL,
    standard_img_url  VARCHAR(500) NOT NULL,
    target_img_url    VARCHAR(500) NOT NULL,
    status            VARCHAR(20) NOT NULL,  -- PENDING / SUCCESS / FAIL / ERROR
    similarity_score  INT NULL,
    verified          BOOLEAN NULL,
    error_message     VARCHAR(500) NULL,
    created_at        TIMESTAMP NOT NULL,
    completed_at      TIMESTAMP NULL,
    INDEX idx_member_status (member_id, status)
);
```

기존 verification 관련 테이블이 있으면 컬럼 추가 방식으로 통합해도 됨. JPA 엔티티 + Repository 작성.

## 5. 검증해야 할 시나리오

### Phase 1 — 정상 흐름

1. `docker compose up` (컨슈머 측 docker-compose 활용, redis 공유)
2. Spring 기동 (`REDIS_HOST=localhost`)
3. `curl -X POST http://localhost:8080/v1/verification -d '{"planetId":1,"imgUrl":"..."}'`
4. 응답: `{ "requestId": "...", "status": "PENDING" }`
5. 1~3초 후 `GET /v1/verification/{requestId}` → `{ "status": "SUCCESS", "similarityScore": ..., "verified": ... }`

### Phase 2 — 멱등성

동일 callback payload 를 2회 호출 → DB 가 한 번만 업데이트, 두 번째는 무시. 로그로 확인.

### Phase 3 — Chaos Test (핵심)

**목적**: 컨슈머가 단일 장애 지점이 될 수 있는 상황에서, **메시지 큐가 그 장애를 흡수**함을 입증.

**시나리오**:
1. Spring + Redis + Consumer 정상 기동
2. 인증 요청 10건 발행 (스크립트 또는 반복 curl)
3. 5건쯤 처리됐을 때 컨슈머 강제 종료: `docker kill prc-consumer`
4. **Redis 상태 확인**:
   ```bash
   redis-cli XLEN verify:requests           # 전체 메시지 수
   redis-cli XPENDING verify:requests verifiers  # 미처리(pending) 메시지
   ```
   잔여 메시지가 있어야 함.
5. Spring 상태 조회: 미처리 요청들은 여전히 `PENDING` (실패가 아님)
6. 30초 대기
7. 컨슈머 재기동: `docker compose up -d consumer`
8. 잔여 메시지 자동 처리 → callback 도착 → 모든 요청 최종 SUCCESS

**기대 결과 (narrative 의 증거)**:
- **요청 실패율 0%** — 컨슈머 다운타임에도 요청 유실 없음
- 컨슈머 다운타임 동안 클라이언트는 PENDING (재시도 책임 없음)
- 복구 즉시 누적 메시지 처리

### Phase 4 — (선택) Before/After 비교

포트폴리오 narrative 강화용. 기존 동기 HTTP 호출 코드를 임시 브랜치로 살려두고:
1. Spring → Flask (HTTP 동기) 구조로 10건 발행
2. Flask 강제 종료 시 진행 중 5건 모두 5xx 또는 connection refused
3. **요청 실패율 ~50%** 측정

→ "메시지 큐 도입 전후 비교" 표로 정리.

## 6. 작업 범위 외 (이번에 하지 말 것)

- k6/Locust 부하 테스트 (별도 phase)
- 컨슈머 수평 확장 검증
- Lambda + SQS 전환 (운영 확장 옵션)
- 마이페이지 백분위 / 인기 키워드 로직 Spring 회수 (별도 작업)
- ONNX 변환
- SSE/WebSocket 으로 결과 푸시 (현재는 폴링으로 충분)

## 7. 작업 기록 의무

작업 진행하면서 **PORTFOLIO_PLAN.md / DEVLOG.md 와 같은 톤** 으로 진행 로그를 남길 것. 각 phase 끝날 때마다:
- 무엇을 했는지
- 왜 그렇게 결정했는지
- 검증 결과 (커맨드 출력 / DB row 등 증거 포함)

이 로그가 포트폴리오 narrative 의 원본 데이터가 된다.

## 8. 참고 — 컨슈머 측 동작 매트릭스

| 상황 | 컨슈머 동작 | Spring 영향 |
|---|---|---|
| 추론 성공 + callback 2xx | XACK (callback 1회) | 정상 처리 |
| 추론 성공 + callback 비-2xx/timeout | backoff 재시도 3회 → 최종 실패 시 XACK 안 함 → 다음 사이클 재전달 | **callback 중복 수신 가능** → 멱등 처리 필수 |
| 이미지 로드 실패 | error payload 로 callback 1회 → XACK | status=ERROR 처리 |
| 메시지 형식 오류 | XACK (poison pill 방지) | 영원히 모름 (이 케이스는 Spring 측 publisher 버그) |
| 컨슈머 크래시 | 처리 중이던 메시지는 PEL 에 남음 | Spring 은 PENDING 유지. 컨슈머 복구 시 자동 처리 |
| 컨슈머 장기 다운 | 신규 메시지 stream 에 누적 | Spring 은 PENDING 유지. 사용자에겐 "처리 중" UX |

## 9. 디렉토리 컨텍스트

```
planetrush-new/
├── PORTFOLIO_PLAN.md        ← 전체 narrative 와 작업 순서. 먼저 읽을 것
├── SPRING_INTEGRATION_BRIEF.md  ← 이 문서
├── planetrush_flask/        ← 기존 Flask 서버 (참고용, 수정 대상 아님)
├── planetrush_consumer/     ← 이미 완성된 컨슈머
│   ├── README.md            ← 메시지 스키마 + 실행법
│   ├── DEVLOG.md            ← 컨슈머 측 진행 기록
│   ├── consumer.py
│   ├── inference.py
│   ├── docker-compose.yml   ← redis + callback + consumer
│   └── scripts/             ← publisher, dummy_callback
└── (Spring 프로젝트는 별도 디렉토리에 있을 수 있음 — 사용자에게 확인)
```

## 10. 첫 액션

세션 시작 시:
1. 이 문서 끝까지 읽고 이해한 내용을 1~2단락으로 사용자에게 확인
2. Spring 프로젝트의 위치 / 빌드 도구 (Gradle/Maven) / 기존 인증 API 위치 확인
3. 변경 작업 시작 전에 작업 계획을 TaskCreate 로 정리
4. Phase 1 부터 순차 진행

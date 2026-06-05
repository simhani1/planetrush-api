# Research: Async Verification Pipeline

**Spec**: [spec.md](spec.md) · **Plan**: [plan.md](plan.md) · **Date**: 2026-06-03

Phase 0 산출. Spec 의 [NEEDS CLARIFICATION] 0건, Clarifications Q1~Q3 답변은 이미 spec 에 반영. 본 문서는 plan 단계에 미뤄둔 설계 결정과 기존 인프라 재사용 전략을 정리한다.

## R-001 — 인증 요청 모델: 별도 entity vs 기존 `VerificationRecord` 확장

**Decision**: 별도 entity `VerificationRequest` 신설.

**Rationale**:
- **라이프사이클이 다르다.** `VerificationRecord` 는 인증 사실(verified=true/false + similarityScore)의 영속 도메인 사실. `VerificationRequest` 는 처리 진행 상태(PENDING→SUCCESS/FAIL/ERROR) 추적용. 한 entity 에 두 라이프사이클을 합치면 `verified` 필드가 PENDING 동안 의미 없는 default 값을 가져야 하고, status enum 의 ERROR 상태가 VerificationRecord 의 "인증 사실" 의미에 어긋남.
- **식별자 체계가 다르다.** 컨슈머 계약과 클라이언트 폴링은 UUID(string) 식별자를 쓴다. `VerificationRecord` 의 PK 는 IDENTITY(long). 한 entity 로 합치려면 `request_id VARCHAR(36)` 컬럼을 추가해야 하고, 그 식별자가 PK 가 아니라 보조 키가 되어 외래 키/조회 의미가 어색해진다.
- **ERROR 정책이 다르다.** spec clarify Q2 — ERROR 종착 시 `VerificationRecord` 는 저장하지 않고 `VerificationRequest` 의 status 만 ERROR 로 기록. 한 entity 라면 "row 는 있는데 verified 가 의미 없음" 상태가 생기지만 별도 entity 면 표현이 자연스럽다.
- **읽기 표면 분리.** 클라이언트 폴링은 `VerificationRequest` 만 조회하면 충분(status + result fields). `VerificationRecord` 와 join 불필요 → QueryDSL Projection 도 단순.

**Alternatives considered**:
- (A) `VerificationRecord` 에 `request_id`, `status`, `error_message` 컬럼 추가하고 통합. 거부 — verified 필드의 의미 모호화, ERROR 시 row 자체를 안 만든다는 정책과 충돌.
- (B) `VerificationRecord` 의 PK 를 UUID 로 전환. 거부 — 기존 코드/통계/외래키 영향 전반적, ROI 음수.

## R-002 — `requestId` 단위 callback 멱등 메커니즘

**Decision**: **Optimistic update WHERE 절**로 race 와 멱등을 한 번에 해결. 비관적 락·낙관적 락 모두 사용하지 않는다.

```sql
-- 정상 결과 (SUCCESS/FAIL)
UPDATE verification_request
   SET status = ?,                -- SUCCESS or FAIL
       similarity_score = ?,
       verified = ?,
       completed_at = NOW()
 WHERE id = ?
   AND status = 'PENDING';        -- 종착 이미 진입한 경우 0 rows
```

**핸들러 로직**:
1. 위 UPDATE 실행 → 영향 행 수 확인.
2. **1 rows**: 정상 전이 완료. 후속 `VerificationRecord` 저장 단계로(R-003).
3. **0 rows**: 이미 종착 상태(SUCCESS/FAIL/ERROR) 또는 존재하지 않는 ID. 본문 무시, 200 반환. (FR-004a 멱등 흡수 + FR-011 의심 호출 흡수)

**Rationale** (사용자 피드백 반영):
- 같은 `requestId` 에 callback 두 건이 동시 도달해도 DB 가 UPDATE 를 한 번만 영향 1 rows 로 처리 — 한쪽은 1, 다른 쪽은 0. 코드는 영향 행 수만 보면 됨.
- 비관적 락(`SELECT ... FOR UPDATE`) 은 동일 시나리오에 과한 도구 — 짧은 트랜잭션이라도 락은 락. UPDATE 의 자체 row lock 만으로 충분.
- JPA `@Version` 도 불필요 — OptimisticLockException 처리 분기를 추가할 이유가 없다.

**Alternatives considered**:
- (A) `SELECT ... FOR UPDATE` 비관적 락. 거부 — UPDATE WHERE 절로 같은 효과를 코드 한 줄 단순화.
- (B) JPA `@Version` 낙관적 락. 거부 — OptimisticLockException 분기 필요, 컨슈머에 5xx 시 무한 재시도 위험.
- (C) Redis 분산 락. 거부 — DB 한 번에 끝나는 일에 인프라 도입.

## R-003 — 사용자·챌린지·날짜 단위 멱등 메커니즘 (Clarify Q3 답변 구체화)

**Decision**: **DB unique 인덱스 단일 안전망** 으로 끝낸다. 애플리케이션 레벨의 `findTodayRecord` 1차 가드는 두지 않는다.

```sql
-- Generated column 으로 DATE 만 추출
ALTER TABLE verification_record
  ADD COLUMN upload_date_only DATE
  GENERATED ALWAYS AS (DATE(upload_date)) STORED;

-- 사용자·챌린지·날짜 단위 unique
ALTER TABLE verification_record
  ADD CONSTRAINT uniq_verification_record_member_planet_date
  UNIQUE (member_id, planet_id, upload_date_only);
```

**핸들러 로직** (SUCCESS/FAIL 결과 분기):
1. `verificationRecordRepository.save(VerificationRecord.builder()...build())` 시도.
2. **정상 INSERT**: 첫 번째 callback 으로 record 저장 성공. `VerificationRequest.status` 는 R-002 UPDATE 에서 이미 갱신됨.
3. **DuplicateKeyException catch**: 같은 사용자·챌린지·날짜에 이미 record 가 있음(동시 race 또는 다른 PENDING 요청의 callback 이 먼저 도착). `VerificationRecord` 추가 저장하지 않고 정상 흐름으로 continue (R-002 UPDATE 는 본 callback 의 `requestId` 에 대해 이미 1 rows 영향이므로 status 는 SUCCESS/FAIL 로 갱신됨).
4. 200 응답.

**Rationale** (사용자 피드백 반영):
- DB unique 가 동시성 race 까지 모두 방어한다. 1차 SELECT 가드는 정상 흐름 1회 추가 쿼리 + race 안전망으로는 부족 → 어차피 DB unique 가 final source. 1차 가드를 두면 코드 분기가 늘 뿐 안전성은 unique 와 동일.
- "exception 을 정상 흐름 제어로 쓴다" 는 안티패턴 우려가 있지만, 본 시나리오의 발생 빈도(같은 사용자가 같은 챌린지에 PENDING 다중 요청 + 그게 모두 종착) 는 매우 드물어 정상 흐름은 항상 INSERT 성공 경로. exception 경로는 진짜 race 일 때만.

**Risks**:
- **기존 데이터 중복 가능성**: 운영 DB 에 같은 사용자·챌린지·날짜로 record 가 2건 이상 있는 경우 unique 인덱스 추가 실패. 사전 점검 SQL:
  ```sql
  SELECT member_id, planet_id, DATE(upload_date) d, COUNT(*) c
  FROM verification_record
  GROUP BY member_id, planet_id, d
  HAVING c > 1;
  ```
- 결과: 0 row 면 안전. 1+ row 면 운영 데이터 정리 후 적용. 로컬/dev/test 는 빈 DB 이므로 영향 없음. (운영 적용 절차는 quickstart 에 기재.)

**Alternatives considered**:
- (A) `findTodayRecord` 1차 가드 + DB unique 2차. 거부 — 1차 가드가 race 안전성에 기여하지 않음(어차피 unique 가 final). 추가 SELECT 1회는 noise.
- (B) 애플리케이션 가드만. 거부 — 다중 인스턴스 race 에 무력.
- (C) `VerificationRequest.status` 만 보고 사용자·챌린지·날짜를 식별. 거부 — `VerificationRequest` 는 PENDING N건 허용 모델.

## R-004 — 컨슈머 stream 메시지 페이로드 (BRIEF §3-1 정합)

**Decision**: `verify:requests` stream 의 entry 필드는 BRIEF §3-1 그대로 사용. 기존 `OutboxRecordCommand` 페이로드 빌더(`VerificationExternalEventRecorder.buildPayload`) 를 수정해 다음을 출력한다:

| 필드 | 출처 | 비고 |
|---|---|---|
| `requestId` | `VerificationRequest.id` (UUID) | OutboxEvent.id 와 동일하게 둘지 별도 둘지 — **OutboxEvent.id 와 일치시킨다**(아래 결정) |
| `standardImgUrl` | `Planet.standardVerificationImg` | 기존과 동일 |
| `targetImgUrl` | `VerificationRequest.verificationImgUrl` | 기존 `verificationImgUrl` 키를 `targetImgUrl` 로 컨슈머 계약에 맞춰 변경 |
| `callbackUrl` | `app.verification.callback-url` 설정값 | 환경별 외부화 |
| `threshold` | `app.verification.threshold` 설정값 | 기본 `"0.088"` |

**OutboxEvent.id ↔ requestId 결정**: 둘을 **동일 UUID** 로 둔다.
- 컨슈머가 보는 메시지 식별자(`requestId`)와 Spring 내부 outbox event 식별자(`OutboxEvent.id`)가 한 값이면 traceability 가 단순(같은 ID 로 outbox PUBLISHED 상태와 verification_request status 를 매핑 가능).
- VerificationRequest.id 도 동일 UUID — 한 요청에 대해 entity·outbox·stream·callback 의 ID 가 모두 일치.
- 한 곳에서 UUID 를 한 번만 생성(`VerificationServiceImpl.verifyTodayChallenge` 진입 시)해 세 곳에 주입.

**Alternatives considered**:
- 별도 ID 발행. 거부 — 매핑 부담만 늘어남.

## R-005 — callback 엔드포인트 보호 / 통합 테스트 호출 방식

**Decision**: 본 PoC 단계에선 **경로 분리(`/api/v1/internal/*`) 만** 적용(spec Assumptions). Spring Security 의 별도 인증 적용 없음. 통합 테스트는 Spring Security 컨텍스트의 `/internal/*` 허용 + MockMvc 직접 호출.

**Rationale**:
- BRIEF: "본 PoC 단계에선 경로 분리만 해도 OK". 운영 전환 시점에 HMAC 헤더 검증을 후속 스펙으로 도입.
- chaos test 에서 "가짜 컨슈머" 가 callback 을 호출할 때 같은 경로(`/api/v1/internal/verification-results`)를 그대로 사용.
- Spring Security 의 `JwtAuthenticationFilter` 가 `/internal/*` 을 통과시키도록 설정(기존 SecurityConfig 수정). 단 외부 망에서 진입은 ALB/Nginx 레벨에서 차단(운영 인프라 책임).

**Alternatives considered**:
- (A) HMAC 검증 도입. 거부 — BRIEF 가 후속으로 분리.
- (B) Spring Security 의 IP 화이트리스트(loopback/private network 만 허용). 거부 — Testcontainers/로컬 docker 환경마다 IP 가 달라 테스트 의존성 증가. 운영 배치는 인프라 책임으로 둔다.

## R-006 — chaos test 에서 컨슈머 다운/복구 시뮬레이션

**Decision**: 통합 테스트 안에 "가짜 컨슈머" 컴포넌트(`FakeVerificationConsumer`)를 둔다. Redis Stream `verify:requests` 를 `XREADGROUP` 폴링하고, 메시지를 받으면 MockMvc 를 통해 callback 을 친다.

**Run/Stop API**:
- `FakeVerificationConsumer.start()` — 별도 스레드에서 poll-and-call 루프 진입.
- `FakeVerificationConsumer.stop()` — 루프 중단(컨슈머 다운 모의).
- 다운 상태에선 Stream 메시지가 그대로 쌓임(XLEN 증가) → Spring 의 `VerificationRequest.status` 가 PENDING 으로 유지됨을 검증.
- `start()` 재호출 → 누적 메시지 처리 → callback 발생 → status 종착 전환을 Awaitility 로 대기.

**Why not Testcontainers 의 컨테이너 일시정지**:
- 컨슈머 자체가 별도 컨테이너이지만 본 통합 테스트는 Spring 단독 부팅(Testcontainers 는 MySQL+Redis 만). 본격 컨슈머 컨테이너를 띄우면 추론 모델 다운로드까지 필요 → 테스트 시간 분 단위. PoC 회귀 테스트로는 부적합.
- "가짜 컨슈머" 가 BRIEF §3 메시지 계약을 그대로 구현하므로 stream 발행 + callback 흐름의 정합성은 검증된다.
- 실제 컨슈머 통합 검증은 `quickstart.md` 의 수동 chaos 절차에 위임.

**Alternatives considered**:
- (A) 컨슈머 docker-compose 를 Testcontainers 로 띄움. 거부 — 모델 다운로드 시간, CI 자원 부담.
- (B) Stream message 검증만 하고 callback 흐름 검증 생략. 거부 — chaos 의 핵심은 "복구 후 callback 도착" 의 단대단 확인.

## R-007 — 인증 요청 트랜잭션 안에서 PENDING request INSERT + Outbox INSERT 순서

**Decision**: 한 `@Transactional` 안에서 다음 순서를 보장한다(`VerificationServiceImpl.verifyTodayChallenge` 흐름):

1. UUID 생성 → `requestId`
2. `VerificationRequest(PENDING)` INSERT (id=requestId)
3. `VerificationExternalEventRecorder.save(OutboxRecordCommand(requestId, ...))` 호출 → `OutboxEvent` INSERT
4. 트랜잭션 커밋 → AFTER_COMMIT 리스너 발화 → `VerificationRedisStreamPublisher.publish(...)` → Stream 발행

**Rationale**:
- (2) 와 (3) 이 같은 트랜잭션에 있으므로 둘 중 하나만 커밋되는 상황 없음 — 헌법 IV 의 원자성 보장 정신.
- (4) 가 실패해도 Spec 002 의 `OutboxRepublisher` 가 PENDING outbox 를 자동 재발행. 클라이언트는 PENDING 응답만 받았고, 결국 컨슈머가 메시지를 받아 callback 으로 종착 전환.
- 컨트롤러는 (1)~(3) 의 트랜잭션 종료 후 즉시 `requestId` 와 함께 202 응답. 응답 경로 안에서 컨슈머/Redis 호출 0 (SC-002).

**Alternatives considered**:
- (3) 을 `AsyncVerificationProcessor` 같은 `@Async` 컴포넌트로 옮김. 거부 — Outbox INSERT 가 메인 트랜잭션 밖이 되면 원자성 깨짐(클라이언트는 202 받았는데 Outbox 가 없어 영원히 PENDING 가능).

## R-008 — 기존 동기 흐름(`AsyncVerificationProcessor` + `SaveVerificationResultEvent`) 처리

**Decision**: 본 스펙은 신규 비동기 흐름이 동작하면 기존 `AsyncVerificationProcessor` 의 Flask 호출 경로를 **분리**(deactivate)한다. 단 코드 자체는 본 스펙에서 삭제하지 않는다 — 후속 스펙(또는 본 스펙 마무리)에서 cleanup.

**Rationale**:
- 신규 비동기 흐름이 callback 으로 `VerificationRecord` 를 저장하므로 기존 `SaveVerificationResultEvent` 리스너(`VerificationServiceImpl.saveVerificationResult`) 는 더 이상 publish 되지 않는다. 코드는 남지만 dead path.
- 단계적 cutover: 본 스펙 머지 직후 운영 트래픽이 즉시 비동기로 전환된다. 기존 `FlaskApiClient`/`AsyncVerificationProcessor` 삭제는 회귀 안전 마진 후 별도 PR.
- `VerificationServiceImpl.saveVerificationResult` 의 리스너 자체는 신규 callback 서비스에서 그대로 재사용 가능(또는 `VerificationResultService` 가 `eventPublisher.publishEvent(SaveVerificationResultEvent ...)` 로 호출해도 됨). 본 스펙은 후자(이벤트 재사용) 채택 — `saveVerificationResult` 의 도메인 로직 변경 0, 호출 트리거만 callback 서비스로 옮김.

**Alternatives considered**:
- (A) 본 스펙에서 `AsyncVerificationProcessor`/`FlaskApiClient` 삭제. 거부 — 본 스펙 PR 의 변경 범위만 키움. cutover 안전 마진을 위해 dead path 로 일시 보존.
- (B) `SaveVerificationResultEvent` 리스너 우회하고 `VerificationResultService` 가 `verificationRecordRepository.save` 직접 호출. 거부 — 기존 도메인 로직 중복 구현, 회귀 위험.

## R-009 — 응답 스키마 (헌법 III · QueryDSL Projections)

**Decision**: 폴링 응답 DTO 는 QueryDSL `Projections.constructor(VerificationStatusDto.class, ...)` 로 `VerificationRequest` 에서 직접 매핑. 서비스는 `Projections.constructor` 결과를 컨트롤러로 그대로 반환.

**Project structure**:
- `verification/service/dto/VerificationStatusDto.java` — `Projections.constructor` 대상.
- `verification/controller/res/VerificationStatusRes.java` — 컨트롤러 응답 래퍼(BaseResponse 와 결합).
- `verification/repository/custom/VerificationRequestRepositoryCustom.java` — `findStatusById(requestId)` QueryDSL 메서드.

**Rationale**: 헌법 III(Entity → DTO 매핑은 QueryDSL Projections 만)를 직접 인코딩. 서비스에서 entity getter 호출 0.

## 정리

| ID | 결정 |
|---|---|
| R-001 | 별도 entity `VerificationRequest` |
| R-002 | `requestId` 멱등 = `UPDATE ... WHERE status='PENDING'` optimistic update (영향 행 수 0 → 멱등 흡수) |
| R-003 | 사용자·챌린지·날짜 멱등 = DB unique 인덱스 단일 안전망 (`DuplicateKeyException` catch → status 만 갱신) |
| R-004 | stream payload 는 BRIEF §3-1; OutboxEvent.id ↔ VerificationRequest.id ↔ requestId 동일 UUID |
| R-005 | callback 보호는 경로 분리만(PoC) |
| R-006 | chaos test 는 in-process `FakeVerificationConsumer` 로 컨슈머 다운/복구 모의 |
| R-007 | PENDING request + Outbox INSERT 동일 트랜잭션 → AFTER_COMMIT 발행 |
| R-008 | 기존 Flask 동기 경로는 본 스펙에서 비활성(dead path) — 별도 PR cleanup |
| R-009 | 응답 DTO 매핑은 QueryDSL Projections (헌법 III) |

모든 NEEDS CLARIFICATION 해소. Phase 1 진입 가능.

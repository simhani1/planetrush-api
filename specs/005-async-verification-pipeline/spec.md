# Feature Specification: Async Verification Pipeline — 챌린지 인증 비동기 처리 노출

**Feature Branch**: `005-async-verification-pipeline`

**Created**: 2026-06-03

**Status**: Draft

**Input**: User description: "Redis Streams 기반 비동기 챌린지 인증 파이프라인 — Spring publisher + callback endpoint + chaos test (`docs/SPRING_INTEGRATION_BRIEF.md` 기반)"

## 배경 *(context)*

- Spec 001 (Testcontainers 안전망) + Spec 002 (Outbox Republisher Worker)로 At-Least-Once 메시지 발행 인프라가 갖춰졌다. 발행 경로(`VerificationExternalEventRecorder` → `OutboxEvent` → AFTER_COMMIT 리스너 → `VerificationRedisStreamPublisher`)와 PENDING 재발행 워커(`OutboxRepublisher`)는 이미 동작한다.
- 컨슈머(Python + PyTorch EfficientNet 이미지 유사도 검증)는 별도 레포(`planetrush_consumer/`)에 완성되어 있다. Redis Stream `verify:requests`를 소비하고, 결과를 Spring callback 엔드포인트로 POST 한다. 본 스펙은 **Spring 측 사용자 가시 표면**만 추가한다.
- **이번 스펙의 범위**: 클라이언트가 보는 비동기 인증 흐름(202 응답 → 폴링 → 결과)과 컨슈머 callback 수신, 그리고 컨슈머 단일 장애 지점성을 무력화함을 입증하는 chaos test.
- **이전 시스템의 사고 흔적**: 동기 HTTP 호출(Spring → Flask)이 같은 EC2 위 PyTorch CPU 추론(1~2s)에 발목 잡혀 일반 API tail latency를 악화시키고, Flask 다운 시 인증 요청이 5xx 로 즉시 실패했다. 본 스펙은 그 사고 패턴을 메시지 큐 + Outbox로 흡수한다.

### 설계 결정 (사전 확정)

- **메시지 발행은 Outbox 경유**(헌법 IV NON-NEGOTIABLE). BRIEF의 "Spring 컨트롤러가 직접 XADD" 패턴은 채택하지 않고, 기존 `VerificationExternalEventRecorder`를 재사용한다. 즉 발행 흐름은 다음과 같다: `POST /v1/verification` → DB 트랜잭션 안에서 인증 요청 영속 + `OutboxEvent` 영속 → 트랜잭션 커밋 → AFTER_COMMIT 리스너가 Redis Stream 발행 (실패 시 Republisher 워커가 자동 재시도).
- **클라이언트 식별자 = 요청 ID**: 컨슈머 callback 매칭 + 클라이언트 폴링 모두 같은 ID 를 사용한다. 신규 컬럼/엔티티 도입 여부는 plan 단계 결정 사항.
- **callback 멱등성은 상태 가드**: 인증 요청이 이미 종착 상태(SUCCESS/FAIL/ERROR)이면 callback 본문은 무시하고 200 응답한다.

## Clarifications

### Session 2026-06-03

- Q: 인증 결과 단대단(PENDING → 종착) SLO 를 정량값(p95/p99 등)으로 둘까? → A: 두지 않는다. 결과 처리는 비동기(컨슈머 추론)이므로 p95 가 본 스펙의 신뢰성 측정에 의미를 주지 않는다. 본 스펙은 "결국 종착" 함수만 보장하고, 인증 API 의 부하/성능 테스트(throughput·latency 측정 등)는 본 스펙 범위에서 제거한다.
- Q: `ERROR` (컨슈머가 `image_load_failed` 등을 보고) callback 시 도메인 인증 기록(`VerificationRecord`)을 저장할지? → A: 저장하지 않는다. ERROR 는 시스템/입력 측 문제이지 사용자의 인증 시도 결과가 아니므로, 인증 요청 레코드의 status 만 `ERROR` 로 기록하고 도메인 `VerificationRecord` 행은 만들지 않아 사용자가 오늘의 재시도 권한을 보존하게 한다. `SUCCESS`(`verified=true`)·`FAIL`(`verified=false`) 은 기존 동기 흐름과 동일하게 `VerificationRecord` 1건 저장. (기존 코드 확인: `VerificationServiceImpl.saveVerificationResult` 는 verified 값과 무관하게 record 를 항상 저장한다 — 본 스펙은 그 경로를 ERROR 케이스에서만 우회한다.)
- Q: 같은 사용자·챌린지에 PENDING 인증 요청이 떠 있는 동안 또 인증 요청을 보내면? (서로 다른 `requestId` 라 FR-004 의 callback 멱등으론 막히지 않음) → A: **입력 측 가드는 추가하지 않는다**. 사용자는 PENDING 중복 요청을 보낼 수 있다(컨슈머는 둘 다 추론). 다만 **결과 저장 시점**(callback 핸들러가 `VerificationRecord` 를 저장하는 시점) 에 "오늘 같은 사용자·챌린지에 이미 종착된 `VerificationRecord` 가 있으면 새 record 저장을 거부" 가드를 둔다. 즉 PENDING N건 / `VerificationRecord` 1건의 불변식을 결과 저장 측에서 보장한다. 두 callback 이 동시에 race 하는 경우의 안전망은 DB 유니크 제약(member_id, planet_id, 날짜) 또는 동등한 동시성 제어로 보강한다(plan 단계 결정). 두 번째 callback 은 status=SUCCESS/FAIL 응답까지 정상 반환되지만 `VerificationRecord` 는 추가되지 않는다.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 인증 요청은 즉시 접수되고 결과는 폴링으로 도착한다 (Priority: P1)

사용자가 챌린지 인증 이미지를 업로드하면, 시스템은 인증 처리가 끝나기를 기다리지 않고 즉시 "접수됨" 응답과 요청 식별자를 돌려준다. 사용자(또는 클라이언트 앱)는 1~2초 간격으로 그 식별자로 상태를 조회하며, 백그라운드 처리가 끝나면 결과(유사도 점수, 인증 통과 여부)를 받는다.

**Why this priority**: 비동기 파이프라인의 MVP. 이 흐름이 동작하지 않으면 스펙 전체가 무의미하다. 동기 HTTP 1~2초 블로킹을 제거해 Spring 일반 API tail latency 회귀를 막는다는 narrative의 핵심.

**Independent Test**: 컨슈머·Spring·Redis 정상 기동 상태에서 인증 요청 1건 → 즉시 "접수됨" 응답 수신 → 폴링 → 수 초 내 SUCCESS/FAIL 결과 수신. US2/US3와 독립 검증 가능.

**Acceptance Scenarios**:

1. **Given** 인증 가능한 챌린지에 참여 중인 사용자가 유효한 인증 이미지를 보유한 상태, **When** 사용자가 인증을 요청, **Then** 시스템은 요청 식별자와 함께 "처리 중(PENDING)" 응답을 즉시 반환한다(동기 처리 결과를 기다리지 않는다).
2. **Given** 사용자가 방금 받은 요청 식별자를 보유, **When** 잠시 후 식별자로 상태를 조회, **Then** 처리 완료 시 상태(SUCCESS 또는 FAIL)와 결과(유사도 점수, 통과 여부)를 응답받는다. 처리 미완료 시에는 여전히 PENDING 으로 응답받는다.
3. **Given** 인증 요청이 정상 발행되어 컨슈머가 처리를 마치고 SUCCESS callback 을 보낸 직후, **When** 사용자가 결과를 조회, **Then** 도메인 인증 기록(`VerificationRecord`)이 1건 저장되어 있어 사후 통계·중복 가드 등 기존 도메인 효과가 정상 동작한다.

---

### User Story 2 - 컨슈머가 죽어 있어도 사용자 요청은 실패하지 않는다 (Priority: P1)

이미지 유사도 컨슈머가 일시 다운되거나 처리가 지연되는 동안에도, 사용자가 보낸 인증 요청은 5xx 로 떨어지지 않고 "처리 중" 상태로 안전하게 보관된다. 컨슈머가 복구되면 누적된 요청이 자동으로 처리되어 결과 폴링이 정상 응답을 돌려준다.

**Why this priority**: 본 스펙의 가장 큰 신뢰성 카드이자 포트폴리오 narrative 의 핵심 증거. 컨슈머가 단일 장애 지점이 되지 않음을 입증한다. US1이 정상 흐름이라면 US2는 장애 흐름 — 동등한 P1 무게.

**Independent Test**: chaos 시나리오 — 인증 N건 발행 도중 컨슈머 강제 종료 → 잔여 요청들이 5xx 가 아닌 PENDING 유지 → 컨슈머 재기동 → 누적분이 모두 종착 상태(SUCCESS/FAIL/ERROR)로 전환됨을 확인. US1/US3와 독립 검증 가능.

**Acceptance Scenarios**:

1. **Given** 컨슈머가 다운된 상태(Spring·Redis 는 정상), **When** 사용자가 인증을 요청, **Then** 시스템은 정상 흐름과 동일하게 "접수됨" 응답을 반환한다(컨슈머 가용 여부에 의존하지 않는다).
2. **Given** 컨슈머 다운 중 누적된 PENDING 요청 N건이 큐와 DB에 존재, **When** 컨슈머가 재기동, **Then** 누적된 요청들이 컨슈머에 의해 처리되고 callback 이 도착하여 모두 종착 상태로 전환된다. 클라이언트 폴링 결과 실패율 0%.
3. **Given** Redis Stream 발행 자체가 일시 실패한 인증 요청이 OutboxEvent 에 `PENDING`으로 남은 상태(Spec 002 워커 대상), **When** Republisher 워커 사이클이 회전, **Then** 해당 요청의 메시지가 자동 재발행되어 컨슈머가 처리할 수 있게 된다.

---

### User Story 3 - 컨슈머 callback 중복은 도메인에 한 번만 반영된다 (Priority: P2)

컨슈머는 callback 실패 시 backoff 재시도하며 최종 실패 시 메시지를 ACK 하지 않아 다음 사이클에 동일 메시지를 재처리한다. 즉 같은 요청 식별자로 callback 이 2회 이상 도착할 수 있다. 시스템은 이를 멱등으로 흡수해 도메인 효과(인증 요청 상태 전이 + `VerificationRecord` 저장)를 한 번만 발생시킨다.

**Why this priority**: at-least-once 시스템의 필수 조건. US1/US2가 동작해도 멱등이 깨지면 사용자에게 별이 두 번 깨지는 등 "보이는 사고"가 난다. 단 정상 흐름 자체는 US1로 검증되므로 우선순위는 P2.

**Independent Test**: 동일 callback payload 2회 호출 → 인증 요청 레코드가 1회만 SUCCESS/FAIL 로 전환되고 `VerificationRecord` 도 정확히 1건만 저장됨을 확인. US1/US2와 독립 검증 가능.

**Acceptance Scenarios**:

1. **Given** 인증 요청이 정상 처리되어 callback 으로 결과(SUCCESS)가 도착한 직후, **When** 컨슈머가 같은 요청 식별자로 동일 callback 을 한 번 더 호출, **Then** 시스템은 두 번째 callback 에 대해서도 정상 200 응답을 돌려주되 DB 상태를 다시 갱신하지 않고 도메인 효과를 재실행하지 않는다.
2. **Given** 처리 결과가 이미 ERROR(image_load_failed 등 비-재시도 사유)로 종착, **When** 정상 추론 결과를 담은 callback 이 늦게 도착, **Then** 시스템은 종착 상태를 유지하고 응답만 200으로 반환한다(상태 역전 금지).

---

### Edge Cases

- 존재하지 않는 요청 식별자로 callback 이 도착 → 200 응답하되 도메인 영향 없음(컨슈머의 무한 재시도를 막기 위해 2xx 로 흡수, 단 의심스러운 호출은 로그로 가시화).
- 존재하지 않는 요청 식별자로 클라이언트가 상태 조회 → 404 응답.
- 컨슈머가 `image_load_failed` 페이로드로 callback → 상태 ERROR 로 기록(SUCCESS/FAIL 과 구분되는 종착 상태).
- 컨슈머 callback 페이로드가 형식 오류(필수 필드 누락 등) → 400 응답. 본 케이스는 컨슈머/Spring 계약 위반이므로 ACK 되도록 시스템이 흡수하지 않는다.
- 같은 사용자가 같은 챌린지에 동시 2건 인증 요청(서로 다른 `requestId`, 두 요청 모두 PENDING) → 컨슈머는 두 건 모두 추론하고 callback 도 두 번 도착한다. FR-004의 사용자·챌린지·날짜 단위 멱등 가드가 결과 저장 시점에 작동해 `VerificationRecord` 는 1건만 보존된다. 두 인증 요청 레코드는 각자 SUCCESS/FAIL 로 종착 표시되며 클라이언트는 어느 쪽 식별자로 조회하든 결과를 받는다.
- 매우 오래된 PENDING(예: 24시간 이상) → 본 스펙은 reaper/타임아웃을 도입하지 않는다(아웃 오브 스코프). 사용자 가시 UX 는 "처리 중" 으로 무한 노출되지 않도록 후속 스펙에서 다룬다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 인증 요청(`POST /v1/verification`)을 수신하면 처리 결과를 기다리지 않고 즉시 응답한다. 응답은 클라이언트가 결과를 추적할 수 있는 **요청 식별자**와 현재 상태(`PENDING`)를 포함한다.
- **FR-002**: 시스템은 인증 요청 수신 시 단일 DB 트랜잭션 안에서 (a) 인증 요청 영속 레코드를 `PENDING` 상태로 생성하고 (b) 외부 메시지 발행용 `OutboxEvent` 를 함께 영속한다. 두 작업의 원자성은 헌법 IV에 따라 Outbox 경유로 보장한다(서비스/도메인 레이어는 메시지 브로커를 직접 호출하지 않는다).
- **FR-003**: 시스템은 인증 결과 callback 을 수신하는 **내부 전용 엔드포인트**(`POST /internal/verification-results`)를 제공한다. 외부 직접 접근은 차단된다.
- **FR-004**: callback 핸들러는 **두 종류의 멱등** 을 모두 보장한다. (a) **`requestId` 단위 멱등** — 동일 요청 식별자로 callback 이 2회 이상 도착해도 인증 요청 레코드의 상태 전이는 1회만 발생하고 도메인 효과(`VerificationRecord` 저장 등)도 1회만 발생한다. 종착 상태 진입 후 도착한 callback 은 본문을 무시하고 200 으로 응답한다. (b) **사용자·챌린지·날짜 단위 멱등** — 같은 사용자가 같은 챌린지에 서로 다른 `requestId` 로 다중 인증 요청을 보낸 결과 두 callback 이 모두 SUCCESS/FAIL 종착에 도달하더라도, 결과 저장 시점에 "오늘 같은 사용자·챌린지에 이미 `VerificationRecord` 가 있으면 추가 저장을 거부" 가드를 둬 `VerificationRecord` 는 1건만 보존한다. 두 번째 callback 은 인증 요청 레코드의 status 는 SUCCESS/FAIL 로 기록하되 `VerificationRecord` 는 만들지 않고 200 응답.
- **FR-005**: 시스템은 인증 요청 상태 조회 엔드포인트(`GET /v1/verification/{requestId}`)를 제공한다. 응답은 현재 상태(`PENDING|SUCCESS|FAIL|ERROR`)와 종착 시 결과 필드(유사도 점수, 인증 통과 여부 또는 오류 사유)를 포함한다. 존재하지 않는 식별자는 404를 반환한다.
- **FR-006**: 컨슈머가 처리 불가 사유(`image_load_failed` 등)를 페이로드로 보고하면 시스템은 인증 요청 레코드의 상태를 `ERROR` 로 기록하고 별도 오류 메시지 필드에 사유를 저장한다. ERROR 종착 시 도메인 인증 기록(`VerificationRecord`) 은 저장하지 않아 사용자에게 오늘의 재시도 권한을 보존한다(SUCCESS/FAIL 과 구분되는 종착 상태).
- **FR-007**: 결과가 `SUCCESS`(verified=true) 또는 `FAIL`(verified=false) 로 종착될 때 시스템은 기존 인증 결과 저장 도메인 로직(`VerificationServiceImpl.saveVerificationResult` 경로) 을 재사용해 `VerificationRecord` 1건을 저장한다(신규 도메인 규칙 도입 없음). `ERROR` 종착은 이 경로를 호출하지 않는다. 본 저장은 FR-004의 멱등 조건을 만족해야 한다 — 중복 callback 으로도 `VerificationRecord` 는 정확히 1건만 저장된다.
- **FR-008**: 시스템은 인증 처리 결과(유사도 점수, 인증 통과 여부, 오류 메시지, 종착 시각)를 인증 요청 레코드에 보존해 사후 조회·감사·통계가 가능하다.
- **FR-009**: 인증 요청 처리 응답 경로 안에서는 컨슈머(이미지 유사도 추론) 호출이 발생하지 않는다 — 추론은 별도 인프라(컨슈머)에서 비동기로 진행되며, 인증 요청 핸들러는 DB 트랜잭션 종료 직후 즉시 반환된다. 본 요건은 절차적(코드 경로) 함수로 검증되며 정량 부하 게이트(throughput·latency 측정)는 본 스펙 범위 밖이다.
- **FR-010**: 컨슈머 callback 의 외부 URL(`callbackUrl`) 등 환경 의존 설정은 `application.yml` 로 외부화되며 코드 수정 없이 환경별로 다르게 주입 가능하다.
- **FR-011**: 시스템은 잘못된 형식의 callback 페이로드(필수 필드 누락 등 컨슈머/Spring 계약 위반)에 대해 400 응답한다. 존재하지 않는 요청 식별자로 도착한 callback 은 200 응답하되 도메인 효과를 발생시키지 않고 의심 호출 로그를 남긴다(컨슈머의 무한 재시도를 흡수하기 위함).
- **FR-012**: 시스템은 본 스펙의 작업 어디서도 시크릿(이미지 서명 URL 토큰 등) 평문을 로그에 출력하지 않는다(헌법 V).

### Key Entities

- **인증 요청 (Verification Request)**: 사용자의 챌린지 인증 1회 시도. 식별자(요청 ID — 컨슈머·클라이언트가 공유), 상태(`PENDING|SUCCESS|FAIL|ERROR`), 챌린지 ID, 사용자 ID, 기준 이미지 참조, 인증 이미지 참조, 결과(유사도 점수·통과 여부·오류 사유), 생성 시각, 종착 시각. 기존 인증 도메인 레코드를 확장할지 별도 엔티티로 분리할지는 plan 단계 결정 사항.
- **인증 결과 Callback**: 컨슈머가 Spring 으로 보내는 결과 페이로드. 정상 응답(`requestId`, `similarityScore`, `verified`) 또는 오류 응답(`requestId`, `error`, `message`). 컨슈머와의 계약은 `docs/SPRING_INTEGRATION_BRIEF.md` §3 에 명시.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001 (정상 흐름, US1)**: 정상 환경에서 인증 요청 → 결과 조회 종착까지의 전체 흐름이 통합 테스트로 검증된다. 발행~callback~상태 전이까지 단일 테스트가 단대단으로 통과한다.
- **SC-002 (즉시 응답, US1)**: 인증 요청 핸들러는 컨슈머 추론 시간(실측 1~2초)과 무관하게 응답한다 — 응답 경로 안에서 컨슈머 호출이 발생하지 않고, 핸들러는 DB 트랜잭션 종료 직후 즉시 반환됨이 단위/슬라이스 테스트로 검증된다.
- **SC-003 (컨슈머 장애 흡수, US2)**: 컨슈머 미가용 상태에서 인증 요청 10건을 발행해도 모두 PENDING 상태가 유지되고 클라이언트 응답 실패율 0%. 컨슈머 복구(가용 상태 복원) 이후 누적분 모두가 종착 상태(SUCCESS/FAIL/ERROR)로 전환됨이 통합 테스트로 검증된다.
- **SC-004 (Outbox 재발행, US2)**: Redis Stream 발행 자체가 실패해 OutboxEvent 가 PENDING 으로 남은 경우, 외부 개입 없이 Republisher 워커가 자동 재발행함을 통합 테스트로 검증한다(Spec 002 SC와의 정합성 확인).
- **SC-005 (멱등, US3)**: 두 종류 멱등을 통합 테스트로 검증한다. (a) **`requestId` 단위**: 동일 요청 식별자로 callback 을 2회 호출하면 인증 요청 레코드 상태 전이 1회, `VerificationRecord` 저장 1건만 발생. 종착 후 도착한 callback 도 200 응답. ERROR 케이스는 `VerificationRecord` 가 단 한 건도 생성되지 않음. (b) **사용자·챌린지·날짜 단위**: 같은 사용자가 같은 챌린지에 서로 다른 `requestId` 로 두 인증 요청을 발행하고 둘 다 SUCCESS callback 이 도착해도 `VerificationRecord` 는 1건만 보존됨. 두 인증 요청 레코드는 각각 SUCCESS 로 종착 표시.
- **SC-006 (인수 기준 ↔ 테스트 매핑, 헌법 VII)**: 위 SC-001~SC-005가 각각 `tasks.md` 의 자동화 테스트 태스크와 1:1 매핑되며, 매핑이 spec ↔ tasks 양방향으로 명시된다.
- **SC-007 (시크릿 미노출, 헌법 V)**: 인증 요청 처리·callback 수신 경로 어디에서도 시크릿/토큰 평문이 로그에 출력되지 않음이 시크릿 로그 스캔 게이트(`./gradlew verifySecretLogScan`)로 검증된다.

## Assumptions

- **컨슈머는 이미 완성되어 있다**. 본 스펙은 Spring 측 통합과 chaos test 만 다루며, 컨슈머 코드 변경·재배포는 범위 외이다. Spring↔컨슈머 메시지 계약은 `docs/SPRING_INTEGRATION_BRIEF.md` §3 가 단일 source of truth.
- **Outbox + Republisher 인프라(Spec 001, 002)는 본 스펙의 전제이며 변경하지 않는다**. 발행 경로는 `VerificationExternalEventRecorder` → `OutboxEvent` → `VerificationRedisStreamPublisher` 가 그대로 재사용된다. Outbox 스키마·라이프사이클 변경은 본 스펙 범위 외이다.
- **컨슈머는 동일 `requestId` 로 callback 을 2회 이상 보낼 수 있다**(at-least-once). 본 스펙의 멱등 요구사항은 이 전제의 직접 귀결이다.
- **컨슈머는 비-재시도 오류를 별도 페이로드로 보고한다**(예: `image_load_failed`). 이 경우 메시지를 ACK 하므로 재발행은 일어나지 않는다.
- **클라이언트는 short polling(1~2초 간격)으로 상태를 조회한다**. SSE/WebSocket 으로의 전환은 별도 스펙.
- **callback 엔드포인트 보호는 본 PoC 단계에서 경로 분리(`/internal/*`) 만으로 충족된다**. HMAC 헤더 검증 도입은 후속 스펙(운영 전환 시점).
- **테스트는 Testcontainers MySQL + Redis 로 단대단 실행된다**(헌법 I). 컨슈머는 테스트 안에서 가짜 컨슈머(Spring 자체에서 Stream 을 읽고 callback HTTP 를 친다)로 대체한다.

## Out of Scope

- 컨슈머(Python + PyTorch) 자체 구현·재배포·튜닝.
- 인증 API 부하·성능 테스트(k6/Locust 등), 단대단 latency 정량 측정(p95/p99), throughput 게이트. 결과 처리가 비동기(컨슈머 추론)이므로 본 스펙은 SLO 정량값을 두지 않는다.
- 컨슈머 수평 확장(다중 인스턴스/Consumer Group 분기) 검증.
- AWS Lambda + SQS 로의 운영 확장 전환.
- 마이페이지 백분위 / 인기 키워드 로직 Spring 회수 (별도 스펙).
- ONNX 변환·모델 경량화.
- SSE/WebSocket 으로의 결과 푸시(현재는 폴링).
- 매우 오래된 PENDING 자동 정리(reaper/TTL/타임아웃 종착).
- callback HMAC 검증·운영 보안 강화.
- Prometheus/Grafana 메트릭 노출(별도 observability 스펙).
- 정식 DB 마이그레이션 도구 도입(Flyway/Liquibase — 별도 스펙).

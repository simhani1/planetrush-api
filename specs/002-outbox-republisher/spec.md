# Feature Specification: Outbox Republisher Worker (At-Least-Once 보장 1/2)

**Feature Branch**: `002-outbox-republisher`

**Created**: 2026-05-18

**Status**: Draft

**Input**: User description: "planetrush-api의 Outbox 인프라를 At-Least-Once 보장 완성으로 끌어올리는 첫 절반. PENDING 상태 OutboxEvent를 자동 재발행하는 워커를 도입한다. 멱등 컨슈머는 Spec 003에서."

## 배경 *(context)*

- Spec 001로 Testcontainers 인프라 + 시크릿 마스킹 안전망 마련됨.
- 현재 `OutboxEvent` + AFTER_COMMIT 리스너(`VerificationExternalMessageListener`) → `VerificationRedisStreamPublisher`가 Redis Stream에 발행하고 성공 시 `published()`로 전환한다.
- **구멍**: `VerificationRedisStreamPublisher`의 발행이 실패하면 `catch (RuntimeException)`이 로그만 찍고 끝나 `OutboxEvent`는 `PENDING`으로 남는다. 또한 발행이 `@TransactionalEventListener(AFTER_COMMIT)`에서 일어나므로 그 리스너 실행 전 앱이 죽으면 영영 `PENDING`. **누구도 재발행하지 않는다.**
- 본 스펙은 At-Least-Once 보장의 **publisher 측 절반**만 다룬다. 컨슈머 측 멱등성/중복 제거/Consumer Group/DLQ는 Spec 003 범위이며, **본 스펙은 "컨슈머가 중복을 무해화한다"를 전제로 설계한다.**

### 설계 단순화 결정 (브레인스토밍 산출)

컨슈머 멱등성이 중복을 보장하므로 본 스펙은 "정확히 1번"이 아니라 **"최소 1번"만 보장**하면 된다. 그 결과:

- **재시도 횟수 제어 불필요**: 폴러가 `PENDING`을 매 사이클 잡는 것 자체가 자연 재시도. `retryCount`/`nextRetryAt`/지수 백오프 **도입하지 않음**.
- **`FAILED` 상태 불필요**: 무한 재시도 방지는 횟수가 아니라 **시간 컷오프**로 한다. `OutboxStatus`에 `FAILED` 추가하지 않음.
- **스키마 변경 0**: `OutboxEvent`에 새 필드를 추가하지 않는다. 컷오프 판정은 기존 `createdAt` 필드만으로 한다.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 발행 직전 앱이 다운돼도 메시지는 결국 도달한다 (Priority: P1)

운영 환경에서 `OutboxEvent`가 DB에 커밋된 직후 외부 메시지 발행이 일어나기 전에 인스턴스가 비정상 종료되거나 발행 자체가 실패해도, 메시지는 결국 컨슈머에 도달해야 한다. 폴러 워커가 `PENDING`으로 남은 outbox를 주기적으로 발견해 자동 재발행한다.

**Why this priority**: At-Least-Once 메시지 발행의 핵심. 이 워커가 없으면 Outbox 패턴 도입 가치가 사라진다. 본 스펙의 MVP.

**Independent Test**: 카오스 시나리오 — Publisher에 일시 장애를 주입해 `OutboxEvent`를 `PENDING`으로 만든 뒤, 장애 해소 후 폴러 사이클이 돌면 재발행되어 `PUBLISHED`로 전환됨을 확인. US2/US3와 독립 검증 가능.

**Acceptance Scenarios**:

1. **Given** `OutboxEvent`가 `PENDING` 상태로 DB에 있고 Publisher의 일시 장애가 해소된 상황, **When** 폴러가 다음 사이클을 돌림, **Then** 재발행이 시도되고 성공 시 상태가 `PUBLISHED`로 전환된다.
2. **Given** Publisher 발행이 1차 실패하여 `OutboxEvent`가 `PENDING`으로 남음, **When** 폴러가 여러 사이클에 걸쳐 재시도, **Then** 장애 해소 후 사이클에서 발행에 성공하고 `PUBLISHED`로 전환된다(별도 재시도 카운트 없이 "성공할 때까지").
3. **Given** 폴러 사이클 도중 정상 종료(graceful shutdown) 신호 수신, **When** 진행 중인 발행 작업이 존재, **Then** 폴러는 in-flight 작업 완료까지 대기한 뒤 종료한다.

---

### User Story 2 - 폴러 인스턴스 N개 동시 기동에서 동일 outbox 이중 처리 0건 (Priority: P1)

운영 다중 인스턴스(HA) 환경에서 동일 outbox를 두 폴러가 동시에 잡으면 불필요한 중복 발행이 발생한다. 컨슈머가 중복을 무해화하더라도 브로커·컨슈머 부하가 인스턴스 수만큼 증폭되므로, 행 단위 락으로 한 워커만 각 outbox를 처리하도록 한다.

**Why this priority**: HA 운영에서는 거의 항상 인스턴스 2개 이상이 동시 가동된다. 락이 없으면 매 사이클 동일 PENDING이 N번 발행되어 부하가 N배가 된다. At-Least-Once를 유지하면서 "동일 사이클 내 중복은 0"이어야 한다.

**Independent Test**: 동일 `OutboxEvent` 100건이 `PENDING`인 상태에서 폴러 워커 2개를 동시 실행 → 각 outbox가 정확히 1회만 `PUBLISHED`로 전환되는지 검증.

**Acceptance Scenarios**:

1. **Given** `OutboxEvent` 100건이 `PENDING` 상태, **When** 폴러 스레드 2개가 동시에 같은 사이클을 돌림, **Then** 각 outbox의 발행 호출은 정확히 1번이며 `PUBLISHED` 카운트는 100건이다.
2. **Given** 폴러 A가 outbox X를 행 단위 락으로 잠근 상태, **When** 폴러 B가 같은 사이클을 돌림, **Then** 폴러 B는 X를 잠금 대기 없이 즉시 건너뛰고 다른 outbox만 처리한다.

---

### User Story 3 - 오래된 PENDING은 자동 재시도 루프에서 제외된다 (Priority: P2)

발행이 영구적으로 실패하는 outbox(데이터 손상, 외부 시스템 영구 장애)가 폴러 사이클을 영원히 점유하지 않도록, 일정 시간(컷오프)을 넘긴 `PENDING`은 자동 재발행 대상에서 제외한다.

**Why this priority**: P2인 이유 — poison message 방어는 운영 건전성 개선이지만 US1/US2가 없으면 무의미. P1 둘이 At-Least-Once 핵심이고 본 스토리는 그 위에 얹는 안정화 계층.

**Independent Test**: `createdAt`이 컷오프보다 오래된 `PENDING` outbox는 폴러의 폴링 쿼리 결과에 포함되지 않음을 검증.

**Acceptance Scenarios**:

1. **Given** `createdAt`이 컷오프(기본 5분)보다 오래된 `PENDING` outbox 1건과 컷오프 이내 `PENDING` outbox 1건, **When** 폴러가 사이클을 돌림, **Then** 컷오프 이내 건만 재발행 시도되고 오래된 건은 선택되지 않는다(DB에는 `PENDING`으로 잔존 — 삭제 아님, 수동 조치 영역으로 이관).
2. **Given** 컷오프 설정값을 변경, **When** 앱을 재기동, **Then** 변경된 컷오프 기준으로 폴링 대상이 결정된다.

---

### Edge Cases

- **앱 종료 중 폴러 사이클**: 진행 중 작업은 graceful shutdown으로 완료 후 종료. 강제 종료 시 해당 outbox는 다음 인스턴스 폴러가 재발견해 재시도(US1).
- **사이클 간격보다 발행이 오래 걸림**(예: 5초 사이클 + 6초 발행): 행 단위 락이 살아있는 동안 다음 사이클은 해당 outbox를 건너뜀(US2와 동일 메커니즘).
- **컷오프 경계의 메시지**: `createdAt`이 정확히 컷오프 직전인 outbox가 폴링 도중 컷오프를 넘기면 다음 사이클부터 제외 — 자연스러운 동작, 별도 처리 불필요.
- **컷오프 초과 후 영구 잔존**: 컷오프를 넘긴 `PENDING`은 자동 복구 대상에서 빠지며 DB에 남는다. 운영자 가시성(알림/메트릭)은 본 스펙 범위 밖(후속 observability 스펙).
- **메시지 순서**: 본 스펙은 순서를 약속하지 않는다(At-Least-Once만 보장). 재발행으로 순서가 뒤바뀔 수 있으며 컨슈머가 이를 감내한다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 `status = PENDING`이며 `createdAt`이 컷오프 이내인 `OutboxEvent`를 주기적(기본 5초 간격)으로 조회한다.
- **FR-002**: 폴링 쿼리는 행 단위 락 + 건너뛰기 의미론(`SELECT ... FOR UPDATE SKIP LOCKED` 또는 동등 효과)으로 동시 폴러 인스턴스 간 안전을 보장한다. 잠긴 행은 대기 없이 즉시 건너뛴다.
- **FR-003**: 폴러는 조회된 각 `OutboxEvent`를 기존 발행 경로(`VerificationMessagePublisher`)로 재발행한다. 성공 시 상태가 `PUBLISHED`로 전환되고, 실패 시 상태는 `PENDING`으로 남아 다음 사이클에 자연 재시도된다(별도 재시도 카운터·백오프 없음).
- **FR-004**: `createdAt`이 컷오프를 초과한 `PENDING` outbox는 폴링 대상에서 제외된다. 해당 레코드는 삭제되지 않고 DB에 `PENDING`으로 잔존한다.
- **FR-005**: 컷오프 시간, 폴링 주기, 배치 크기는 `application.yml` 설정값으로 외부화되며 코드 수정 없이 변경 가능하다. 기본값 — 컷오프 5분, 폴링 주기 5초, 배치 크기 100.
- **FR-006**: 폴러는 `dev`/`prod` 프로필에서 활성화되고 `test` 프로필에서는 비활성화된다. `@ConditionalOnProperty(name = "outbox.republisher.enabled", havingValue = "true")` 기반이며 `test` 프로필은 해당 속성을 `false`로 둔다(통합 테스트는 폴러를 직접 호출해 검증).
- **FR-007**: 폴러는 graceful shutdown을 지원한다 — 종료 신호 수신 시 in-flight 발행 작업 완료 후 종료한다.
- **FR-008**: 본 스펙은 `OutboxEvent` 엔티티에 새 필드를 추가하지 않으며 `OutboxStatus` enum도 변경하지 않는다. 컷오프 판정은 기존 `createdAt` 필드만으로 수행한다.

### Key Entities

- **OutboxEvent**: 외부 시스템으로 발행해야 할 도메인 이벤트의 영속 표현. **본 스펙에서 스키마 변경 없음.** 사용하는 기존 필드 — `id`(eventId), `status`(`PENDING`/`PUBLISHED`), `payload`, `createdAt`(컷오프 판정 기준).
- **OutboxStatus**: outbox 라이프사이클 상태. 본 스펙은 기존 `PENDING`/`PUBLISHED`만 사용하며 enum을 변경하지 않는다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 카오스 통합 테스트가 통과한다 — Publisher에 일시 장애를 주입하면 `OutboxEvent`가 `PENDING`으로 남고, 장애 해소 후 폴러 사이클이 재발행하여 `PUBLISHED`로 전환됨을 확인.
- **SC-002**: 동시성 통합 테스트가 통과한다 — `OutboxEvent` 100건 `PENDING` 상태에서 폴러 2개 동시 실행 시 각 outbox가 정확히 1회만 발행됨을 `RepeatedTest 10회` 모두 만족.
- **SC-003**: 컷오프 통합 테스트가 통과한다 — `createdAt`이 컷오프 초과인 `PENDING`은 폴링 결과에서 제외되고, 컷오프 이내인 `PENDING`만 재발행됨을 확인.
- **SC-004**: 폴러 비활성 검증 — `test` 프로필 부팅 시 폴러 빈이 컨텍스트에 부재함.
- **SC-005**: 정상 경로 평균 발행 지연이 폴링 주기 + 1초 이내(기본 5초 주기 → 평균 ≤ 6초)임을 통합 테스트로 측정.

## Assumptions

- **컨슈머 멱등성 전제**: 컨슈머(Spec 003)가 동일 `eventId` 중복 메시지를 무해화한다. 따라서 본 스펙은 "정확히 1번"이 아닌 "최소 1번"만 보장하면 충분하다.
- **컷오프 값 근거**: 기본 5분은 (a) 정상적 일시 장애의 최대 회복 시간 — 배포 롤링 업데이트 약 2~3분, Redis/네트워크 순단 1분 미만 — 보다 길고, (b) 챌린지 인증 검증의 비즈니스 허용 지연(당일 인증이면 충분, 분 단위 지연 무해) 이내이다. 5분 ÷ 5초 폴링 = 약 60회 재시도 기회. 운영 데이터로 조정 가능하도록 설정값으로 둔다.
- 신규 컬럼이 없으므로 DB 마이그레이션이 불필요하다. (정식 마이그레이션 도구 Flyway는 Spec 005.)
- Spec 001에서 마련한 `IntegrationTest` 베이스(Testcontainers MySQL/Redis)를 그대로 상속하여 새 테스트를 작성한다.
- 운영은 다중 인스턴스(HA)를 가정한다. 단일 인스턴스라면 `SELECT ... FOR UPDATE SKIP LOCKED`는 불필요하지만, 유지해도 무해하며 HA 전환 시 안전망이 된다.
- 단일 폴러 사이클의 처리량 상한(batch size)은 기본 100으로 두고 설정 가능하게 한다.

## Out of Scope *(non-mandatory)*

- 재시도 카운트(`retryCount`), 지수 백오프, `nextRetryAt`, `lastError` 필드 — 컨슈머 멱등성 전제로 불필요.
- `OutboxStatus.FAILED` 상태 — 무한 재시도 방지는 시간 컷오프가 담당.
- 영구 실패 outbox에 대한 Mattermost 알림 — 컷오프 초과분의 운영자 가시성(알림/메트릭/대시보드)은 후속 observability 스펙.
- 컨슈머 측 멱등성, `eventId` 기반 dedup, Consumer Group 운영, DLQ 자동 이동 (→ Spec 003).
- Prometheus/Grafana 메트릭 노출 (→ 별도 observability 스펙).
- Flyway/Liquibase 정식 마이그레이션 (→ Spec 005).
- Jitter 백오프, 우선순위 큐, 메시지 순서 보장, Outbox 보존(TTL) 정책.

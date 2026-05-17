# Feature Specification: Outbox Republisher Worker (At-Least-Once 보장 1/2)

**Feature Branch**: `002-outbox-republisher`

**Created**: 2026-05-18

**Status**: Draft

**Input**: User description: "planetrush-api의 Outbox 인프라를 At-Least-Once 보장 완성으로 끌어올리는 첫 절반. PENDING 상태 OutboxEvent를 자동 재발행하는 워커를 도입한다. 멱등 컨슈머는 Spec 003에서."

## 배경 *(context)*

- Spec 001로 Testcontainers 인프라 + 시크릿 마스킹은 안전망 마련됨.
- 현재 `OutboxEvent` + AFTER_COMMIT 리스너(`VerificationExternalMessageListener`)는 존재하나, **발행 직전 앱이 다운되면 outbox는 영원히 PENDING으로 남는다** (재발행 메커니즘 부재).
- 발행 실패 시 자동 재시도 없음, 다중 인스턴스 동시 기동에서 락 미사용으로 이중 처리 위험.
- `OutboxEvent`에 `retryCount`/`nextRetryAt`/`lastError` 필드 부재, `OutboxStatus` enum에 `FAILED` 부재, `OutboxRepository`는 빈 `JpaRepository`.
- 본 스펙은 At-Least-Once 보장의 **publisher 측 절반**만 다룬다. Consumer 측 멱등성/DLQ/Consumer Group은 Spec 003 범위.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 발행 직전 앱이 다운돼도 메시지는 결국 도달한다 (Priority: P1)

운영 환경에서 `OutboxEvent`가 DB에 커밋된 직후, 외부 메시지 발행이 일어나기 전에 인스턴스가 비정상 종료되어도 메시지는 결국 컨슈머에 도달해야 한다. 재기동된 인스턴스의 폴러 워커가 PENDING 상태로 남은 outbox를 발견해 자동으로 재발행한다.

**Why this priority**: At-Least-Once 메시지 발행의 핵심. 분산 시스템 신뢰성의 토대이며, 이 워커가 없으면 outbox 패턴 도입 자체의 가치가 사라진다. 본 스펙의 MVP.

**Independent Test**: 카오스 시나리오 — outbox 저장 후 발행 직전 강제 종료 → 재기동 → 폴러가 PENDING을 발견해 재발행 → 컨슈머에 메시지 도달. 다른 사용자 스토리(US2/US3)와 독립적으로 검증 가능.

**Acceptance Scenarios**:

1. **Given** `OutboxEvent`가 PENDING 상태로 DB에 저장되고 Publisher에 IOException이 강제 주입된 상황, **When** 폴러가 다음 사이클에 해당 PENDING을 발견하고 일시 장애 해소 후 재시도, **Then** 재발행이 시도되고 성공 시 상태가 `PUBLISHED`로 전환된다.
2. **Given** `OutboxEvent` 저장 직후 앱이 강제 종료된 상태(SIGKILL 시뮬레이션), **When** 새 앱 인스턴스가 부팅되고 폴러가 가동, **Then** 해당 PENDING outbox가 재발행되어 컨슈머에 정확히 1번 이상 도달한다.
3. **Given** 폴러 사이클 도중 정상 종료 신호 수신, **When** 진행 중인 발행 작업이 존재, **Then** 폴러는 in-flight 작업이 완료될 때까지 대기한 뒤 종료한다(graceful shutdown).

---

### User Story 2 - 폴러 인스턴스 N개 동시 기동에서 동일 outbox 이중 처리 0건 (Priority: P1)

운영 다중 인스턴스(HA) 환경에서 동일 outbox를 두 폴러가 동시에 잡으면 메시지 중복 발행이 발생한다. 행 단위 락으로 정확히 한 워커만 각 outbox를 처리하도록 보장한다.

**Why this priority**: HA 운영에서는 거의 항상 인스턴스 2개 이상이 동시 가동된다. 이중 처리는 외부 시스템(Mattermost 등)에 중복 메시지를 발송해 운영자 신뢰를 손상시킨다. US1과 동등한 P1 — At-Least-Once를 망치지 않으려면 "최소 1번"을 보장하면서 동시에 "동일 워커 사이클 내 중복은 0"이어야 한다.

**Independent Test**: 동일 outbox 100건 PENDING 상태에서 폴러 워커 2개 동시 실행 → 각 outbox가 정확히 1회만 published()로 전환되는지 검증. US1의 재발행 로직이 없어도 동시성 락 메커니즘 자체는 단독으로 검증 가능.

**Acceptance Scenarios**:

1. **Given** `OutboxEvent` 100건이 PENDING 상태, **When** 폴러 스레드 2개가 동시에 같은 사이클을 돌림, **Then** 각 outbox의 발행 호출은 정확히 1번이며 `PUBLISHED` 카운트는 100건이다.
2. **Given** 폴러 A가 outbox X를 잠근 상태(트랜잭션 중), **When** 폴러 B가 같은 사이클을 돌림, **Then** 폴러 B는 X를 건너뛰고 다른 outbox만 처리한다(잠금 대기 없음).

---

### User Story 3 - 영구 실패 outbox는 자동 격리 + Mattermost 알림 (Priority: P2)

특정 outbox가 발행 시 항상 실패(예: 외부 시스템 영구 장애, 데이터 손상)할 때 무한 재시도로 폴러 사이클을 잡아먹는 것을 방지하고, 운영자가 수동 조치할 수 있도록 알림을 발송한다.

**Why this priority**: P2인 이유 — 운영 효율성과 가시성 개선이지만, US1/US2가 없으면 무의미. P1 두 개가 At-Least-Once 핵심 보장이고, 본 스토리는 그 위에 얹는 격리/관측 계층.

**Independent Test**: 발행이 항상 실패하는 outbox → `maxRetryCount`(기본 5) 초과 → `status=FAILED` + Mattermost 알림 1회 호출 검증. US1의 재시도 인프라가 있어야 의미가 있지만, 단위 테스트 수준에서는 mock으로 단독 검증 가능.

**Acceptance Scenarios**:

1. **Given** Publisher가 항상 RuntimeException을 던지는 `OutboxEvent` 1건, **When** 폴러가 `maxRetryCount`만큼 재시도, **Then** 상태가 `FAILED`로 전환되고 `lastError`가 기록되며 Mattermost 알림이 정확히 1회 발송되고 이후 사이클에서 더 이상 잡히지 않는다.

---

### Edge Cases

- **앱 종료 중 폴러 사이클**: 진행 중 작업은 graceful shutdown 패턴으로 완료 후 종료. 강제 종료 시 해당 outbox는 다음 인스턴스에서 재발견되어 재시도(US1 시나리오).
- **사이클 간격보다 발행이 오래 걸림**(예: 5초 사이클 + 6초 발행): 행 단위 락이 살아있는 동안 다음 사이클은 해당 outbox를 건너뜀(US2와 동일 메커니즘).
- **백오프 누적**: 첫 재시도 1s → 2s → 4s → 8s → 16s (단순 지수). Jitter는 본 스펙 범위 밖.
- **DLQ 자동 이동**: 본 스펙 범위 밖. Spec 003에서 Consumer Group + DLQ 일괄 처리.
- **메시지 순서**: 본 스펙은 순서 보장을 약속하지 않는다(At-Least-Once만 보장). 재발행으로 인해 순서가 뒤바뀔 수 있다.
- **시계 왜곡(clock skew)**: 다중 인스턴스 간 시계 차이로 `nextRetryAt` 비교 결과가 흔들릴 수 있으나, 락이 동시 처리를 막으므로 영향 미미.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 PENDING 상태이며 `nextRetryAt`이 도래한 `OutboxEvent`를 주기적(기본 5초 간격)으로 조회한다.
- **FR-002**: 폴링 쿼리는 행 단위 락 + 건너뛰기 의미론(`SELECT ... FOR UPDATE SKIP LOCKED` 또는 동등 효과의 비관적 락)으로 동시 폴러 인스턴스 간 안전을 보장한다. 잠긴 행은 즉시 건너뛰며 대기하지 않는다.
- **FR-003**: 발행 성공 시 상태를 `PUBLISHED`로 전환한다. 실패 시 `retryCount`를 1 증가시키고 `nextRetryAt`을 `now + 2^retryCount` 초로 설정한다.
- **FR-004**: `retryCount`가 `maxRetryCount`(기본 5)를 초과하면 상태를 `FAILED`로 전환하고 Mattermost 알림을 정확히 1회 발송한다. 이후 사이클은 해당 outbox를 더 이상 선택하지 않는다.
- **FR-005**: `OutboxEvent`에 `retryCount`(정수, default 0), `nextRetryAt`(nullable 시각), `lastError`(nullable 문자열) 필드를 추가한다. 마이그레이션은 `ddl-auto=update`에 의존(정식 마이그레이션 도입은 Spec 005).
- **FR-006**: `OutboxStatus` enum에 `FAILED` 값을 추가한다.
- **FR-007**: `OutboxRepository`에 잠금 기반 폴링 쿼리 메서드를 추가한다(Native query 또는 JPA `@Lock` + `Pageable` 조합).
- **FR-008**: 폴러는 graceful shutdown을 지원한다 — 종료 신호 수신 시 in-flight 작업 완료 후 종료(`@PreDestroy` + 짧은 대기 패턴).
- **FR-009**: 폴러는 `dev`/`prod` 프로필에서 활성화되고 `test` 프로필에서는 비활성화된다(통합 테스트 충돌 방지). `@ConditionalOnProperty(name="outbox.republisher.enabled", havingValue="true")` 기반.

### Key Entities

- **OutboxEvent**: 외부 시스템으로 발행해야 할 도메인 이벤트의 영속 표현. 본 스펙에서 다음 필드가 신설/변경된다.
  - `status`: `PENDING`/`PUBLISHED`/`FAILED`(신규)
  - `retryCount`(신규): 누적 재시도 횟수, 기본 0
  - `nextRetryAt`(신규): 다음 재시도 가능 시각, nullable(최초 저장 시 null 또는 즉시 가능)
  - `lastError`(신규): 직전 실패의 사유 문자열, nullable
- **OutboxStatus**: outbox 라이프사이클 상태. `FAILED` 추가.
- **Mattermost 알림 채널**: 운영자에게 영구 실패를 통지하는 외부 통지 채널. 본 스펙은 "1회 발송" 계약만 정의(메시지 포맷/대상 채널 설정은 기존 인프라 재사용).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 카오스 통합 테스트가 통과한다 — Publisher Mock에 일시 장애를 주입하면 폴러가 자동 재시도하여 메시지가 결국 컨슈머에 도달함을 확인.
- **SC-002**: 동시성 통합 테스트가 통과한다 — outbox 100건 PENDING 상태에서 폴러 2개 동시 실행 시 각 outbox가 정확히 1회만 발행됨을 `RepeatedTest 10회` 모두 만족.
- **SC-003**: 백오프 단위 테스트가 통과한다 — `retryCount` 0~5에서 `nextRetryAt` 간격이 1s, 2s, 4s, 8s, 16s, 32s로 단조 증가.
- **SC-004**: 영구 실패 단위 테스트가 통과한다 — `maxRetryCount` 초과 시 상태가 `FAILED`로 전환되고 Mattermost mock이 정확히 1회 호출되며 후속 사이클에서 재선택되지 않음.
- **SC-005**: 폴러 비활성 검증 — `test` 프로필 부팅 시 폴러 빈이 컨텍스트에 부재함(`assertThat(...).doesNotHaveBean(...)` 동등).
- **SC-006**: 정상 경로 평균 발행 지연이 폴링 주기 + 1초 이내(예: 기본 5초 주기 → 평균 ≤ 6초)로 측정된다.

## Assumptions

- 재시도 백오프는 단순 지수(`2^retryCount` 초)로 충분하다고 가정. 무작위 jitter는 본 스펙 범위 밖(트래픽 폭주가 큰 규모가 아니므로 도입 효익 낮음).
- DLQ 자동 이동/관리, Consumer Group 구성, eventId 기반 컨슈머 멱등성은 Spec 003에서 일괄 다룬다.
- 메트릭(예: `outbox_pending_count`, `outbox_failed_count` 등 Prometheus 노출)은 별도 observability 스펙에서 일괄 도입한다.
- 신규 컬럼은 `ddl-auto=update`로 자동 추가된다고 가정(정식 마이그레이션 도구 도입은 Spec 005).
- Spec 001에서 마련한 `IntegrationTest` 베이스(Testcontainers MySQL/Redis)를 그대로 상속하여 새 테스트를 작성한다.
- Mattermost 알림은 기존 인프라(`MattermostNotifier` 또는 동등 구성요소)가 존재하여 본 스펙은 호출 1회 계약만 보장한다고 가정. 부재 시 본 스펙에서 최소 추상화(인터페이스 + no-op 구현)를 도입한다.
- 단일 폴러 사이클 1회 처리량 상한(batch size)은 합리적 기본값(예: 100)으로 두고 구성 가능하게 한다.

## Out of Scope *(non-mandatory)*

- 컨슈머 측 멱등성, `eventId` 기반 dedup, Consumer Group 운영, DLQ 자동 이동 (→ Spec 003).
- Prometheus/Grafana 메트릭 노출 및 대시보드 (→ 별도 observability 스펙).
- Flyway/Liquibase 정식 마이그레이션 (→ Spec 005).
- Jitter 백오프, 우선순위 큐, 메시지 순서 보장.
- Outbox 보존 정책(TTL 기반 삭제, 아카이브).

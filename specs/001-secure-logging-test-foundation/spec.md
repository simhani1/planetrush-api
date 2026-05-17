# Feature Specification: Secure Logging & Test Foundation

**Feature Branch**: `001-secure-logging-test-foundation`

**Created**: 2026-05-16

**Status**: Draft

**Input**: User description: "planetrush-api에 보안 로그 가드와 통합 테스트 인프라 기반을 도입한다. 이 두 가지는 후속 모든 스펙(Outbox 워커, 동시성 비교, 성능 개선, Resilience 적용 등)의 안전망이며 함께 머지된다."

## Background

- 현재 `JwtTokenProvider`에 `log.info("secret key: {}", SECRET_KEY)` 패턴이 정상 흐름에서 매 요청마다 시크릿을 평문 출력하는 사고 흔적이 있다.
- 통합 테스트는 로컬 MySQL/Redis 데몬에 의존하여 CI/CD·신규 개발자 환경에서 회귀가 늦게 감지된다.
- `application.yml`의 `show-sql=true`, `org.springframework.web=DEBUG`가 모든 프로필에 적용되어 운영 로그 노이즈를 유발한다.
- 본 스펙은 후속 모든 스펙(Outbox 워커, 동시성 비교, 성능 개선, Resilience 적용 등)의 안전망을 함께 머지하는 단일 PR이다.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 로그 어디에도 시크릿 평문이 남지 않는다 (Priority: P1)

운영자 관점에서, 어떤 코드 경로의 로그라도 시크릿·토큰·자격증명이 평문으로 외부 로그 시스템·백업·스택트레이스에 노출되지 않아야 한다.

**Why this priority**: 본 스펙의 최우선 동기. 과거 사고 재발 방지가 컨스티튜션 원칙 V로 명문화되어 있으며, 운영 사고로 직결되는 위험.

**Independent Test**: `secret`, `token`, `password`, `jwt`, `credential` 키워드를 포함한 임의 문자열을 로그로 출력했을 때, 최종 appender 출력에서 해당 값이 `***`로 마스킹되는지 단위 테스트로 검증.

**Acceptance Scenarios**:

1. **Given** `logger.info("secret key: {}", "abc123")` 호출, **When** 로그가 출력되면, **Then** 출력 라인에 `"abc123"`이 포함되지 않고 `***`로 마스킹되어 있다.
2. **Given** `JwtTokenProvider`의 기존 시크릿 출력 경로, **When** 단위 테스트가 출력 캡처 후 검사, **Then** 시크릿 평문이 발견되지 않는다.
3. **Given** 마스킹 대상이 아닌 일반 로그(`logger.info("user joined: {}", userId)`), **When** 출력, **Then** 변형 없이 그대로 출력된다.

---

### User Story 2 - 로컬 데몬 없이도 통합 테스트가 통과한다 (Priority: P1)

개발자 관점에서, 로컬에 MySQL/Redis 데몬을 띄우지 않은 상태로 `./gradlew test`만 실행하면 모든 통합 테스트가 자동으로 컨테이너를 부팅하고 통과해야 한다.

**Why this priority**: 후속 모든 스펙(Outbox, 동시성, 성능, Resilience)이 이 인프라 위에서 통합 테스트를 작성한다. 본 인프라가 없으면 후속 스펙의 인수 기준 검증 자체가 불가능.

**Independent Test**: 로컬 MySQL/Redis 데몬을 완전히 종료한 상태에서 `./gradlew test` 실행 시 모든 테스트가 통과하는지 확인.

**Acceptance Scenarios**:

1. **Given** MySQL/Redis 데몬 미가동, **When** `./gradlew test` 실행, **Then** 모든 테스트 통과 + Testcontainers MySQL/Redis 컨테이너가 자동 부팅 후 종료된다.
2. **Given** 기존 통합 테스트 5종(PlanetIntegrationTest 등)이 새 베이스 클래스로 이전된 상태, **When** 테스트 실행, **Then** 어서션 변경 없이 동일하게 통과한다.
3. **Given** Docker 미설치 환경, **When** 테스트 실행, **Then** 명확한 에러 메시지로 Docker 필요성을 안내한다(빌드 silent fail 금지).

---

### User Story 3 - 운영 프로필은 SQL/디버그 로그를 출력하지 않는다 (Priority: P2)

운영자 관점에서, 운영 프로필로 부팅된 인스턴스는 SQL 출력과 Spring web 디버그 로그를 표시하지 않아야 한다.

**Why this priority**: 운영 로그 노이즈 감소 + 시크릿 평문이 SQL 파라미터로 새는 부수 경로 차단. P1만큼 시급하지 않지만 같은 PR에서 함께 청산하는 것이 효율적.

**Independent Test**: `prod` 프로필로 부팅 시 SQL 출력 라인 수가 0이고 web 로그 레벨이 INFO 이상인지 부팅 로그 검증.

**Acceptance Scenarios**:

1. **Given** `--spring.profiles.active=prod`, **When** 앱 부팅, **Then** 부팅 직후 SQL 출력이 0건이고 web 로그 레벨이 INFO이다.
2. **Given** `--spring.profiles.active=dev`, **When** 앱 부팅, **Then** 기존과 동일하게 SQL 출력과 DEBUG 로그가 허용된다.

---

### Edge Cases

- **마스킹 키워드의 정상 텍스트 부수 매칭** (예: 사용자 닉네임 `"tokenmaster"`): 의도된 부수 마스킹을 허용하며 옵트아웃 옵션을 제공하지 않는다(단순함 우선, 닉네임이 시크릿 키워드를 포함할 확률이 낮음).
- **멀티라인 로그(스택트레이스)** 안에 시크릿이 메시지가 아닌 변수에 들어가 있는 경우: 단위 테스트로 멀티라인 입력에 대해서도 마스킹이 적용되는지 명시적으로 검증한다.
- **Testcontainers MySQL 부팅 시간(수십 초)**: 테스트 클래스 간 컨테이너 재사용(`@Container static` + `withReuse(true)` 또는 동등 패턴) 명시.
- **CI 환경에 Docker 미설치**: 빌드는 명확한 실패 메시지로 중단(silent skip 금지).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 로그 메시지와 파라미터에서 지정된 시크릿 키워드 패턴 값이 평문 출력되지 않도록 `***`로 치환해야 한다.
- **FR-002**: 마스킹 키워드 목록은 설정으로 관리되며 기본값에 `secret`, `token`, `password`, `jwt`, `credential` 5종을 포함한다.
- **FR-003**: 시스템은 통합 테스트용 추상 베이스 클래스를 제공하여 MySQL/Redis 컨테이너를 자동 부팅하고 Spring DataSource·Redis 설정을 동적으로 주입해야 한다.
- **FR-004**: 기존 통합 테스트 5종(`PlanetIntegrationTest`, `VerificationServiceFailureIntegrationTest`, `VerificationRedisStreamPublisherTest`, 외 2종)을 위 베이스 클래스 기반으로 이전해야 한다. 어서션 변경은 금지한다.
- **FR-005**: 시스템은 `prod` 프로필을 신설하여 `show-sql=false`와 `org.springframework.web=INFO`로 운영 기본값을 분리한다. `dev` 프로필은 기존 동작을 유지한다.
- **FR-006**: `JwtTokenProvider`의 정상 흐름 시크릿 출력 로그를 제거한다. 동등 정보가 필요한 경우 마스킹된 형태로 대체한다.
- **FR-007**: 마스킹 가드와 Testcontainers 베이스 클래스는 각각 자동화 테스트로 동작이 검증되어야 하며, 빌드 파이프라인에서 자동 실행된다.

### Key Entities *(N/A)*

본 스펙은 인프라/관측성 변경으로 새 도메인 엔티티를 도입하지 않는다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 시크릿 키워드 5종(`secret`, `token`, `password`, `jwt`, `credential`) 모두에 대한 마스킹 단위 테스트가 100% 통과한다.
- **SC-002**: 로컬 MySQL/Redis 데몬이 완전히 종료된 상태에서 `./gradlew test` 전체가 통과한다(컨테이너 자동 부팅·종료 포함).
- **SC-003**: 기존 통합 테스트 5종이 Testcontainers 베이스 위에서 어서션 변경 없이 동일하게 통과한다.
- **SC-004**: `prod` 프로필 부팅 직후 로그에 SQL 출력이 0건이고 web 패키지 로그가 INFO 이상 레벨로 제한된다.
- **SC-005**: `grep -r "secret key:" src/main` 결과 0건이 된다. 더 일반화하여 `grep -RInE "log\.(info|debug|warn)\(.*(secret|token|password|jwt|credential).*=.*\)" src/main` 결과도 0건이 된다.
- **SC-006**: 본 PR 머지 후 후속 스펙 작성자가 베이스 클래스 1줄 상속만으로 새 통합 테스트를 작성할 수 있다(추가 셋업 없이).

## Assumptions

- 개발자 워크스테이션과 CI 환경에 Docker(또는 동등 컨테이너 런타임)가 설치되어 있다.
- 기존 통합 테스트 5종의 어서션 자체는 유효하며, 본 스펙은 인프라만 교체한다. 테스트 로직 리팩토링은 별도 스펙.
- `application.yml`의 공통 설정을 유지하고, `application-prod.yml`은 prod 전용 오버라이드만 둔다(기존 dev/test 동작은 변경 없음).
- Spring Security 필터 체인 마이그레이션, Refresh Token Rotation, JWT 검증 예외 분리는 본 스펙 범위 밖이며 Spec 6(Outbound Resilience & Auth Hardening)에서 다룬다.
- Logback이 현재 빌드의 기본 로깅 백엔드로 유지된다(별도 변경 없음).
- 후속 스펙들은 본 베이스 클래스를 상속하여 통합 테스트를 작성한다는 전제로 설계된다.

## Constitution Alignment

본 스펙이 만족하는 컨스티튜션 원칙:

- **I. Testcontainers 통합 테스트** — 본 스펙의 핵심 인프라가 이 원칙을 충족시키며, 후속 모든 스펙이 의존하는 토대.
- **V. 민감정보 로그 출력 금지** — Logback 마스킹 컨버터로 본 원칙의 자동화된 보장을 구현.
- **VII. 인수 기준 자동 테스트화** — 모든 SC가 자동 테스트로 검증됨.

---

description: "Task list for Spec 001 — Secure Logging & Test Foundation"
---

# Tasks: Secure Logging & Test Foundation

**Input**: Design documents from `/specs/001-secure-logging-test-foundation/`

**Prerequisites**: plan.md (✅), spec.md (✅), research.md (✅), quickstart.md (✅)

**Tests**: 인수 기준(SC-001~006)이 자동 테스트로 검증되어야 한다(컨스티튜션 원칙 VII). 본 스펙은 **TDD 적용** — 마스킹 컨버터는 실패 테스트 작성 → 구현 → 통과 순서.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story?] Description with file path`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Maps to user story (US1, US2, US3) — only on Phase 3+ tasks

## Path Conventions

Single Spring Boot module:
- Production: `src/main/java/com/planetrush/planetrush/...`
- Resources: `src/main/resources/...`
- Tests: `src/test/java/com/planetrush/planetrush/...`

## Discovered Constraint (research → tasks)

기존 `src/test/java/com/planetrush/planetrush/IntegrationTest.java`가 이미 추상 베이스 클래스로 존재한다. **신규 `IntegrationTestSupport`를 만드는 대신 기존 `IntegrationTest`를 그대로 활용(이름 유지 + Testcontainers 기능 확장)** — 하위 5종(`MemberIntegrationTest`, `PlanetIntegrationTest`, `VerificationIntegrationTest`, `VerificationServiceFailureIntegrationTest`, `VerificationServiceIntegrationTest`)이 이미 `extends IntegrationTest`라면 import/extends 변경 0건. 단순함 우선.

> spec/plan에 `IntegrationTestSupport`라는 새 이름이 명시되어 있으나, 본 task 단계에서 기존 클래스 재활용으로 결정. plan.md의 Structure 섹션은 본 결정 반영을 위해 합의 후 갱신 가능(현 단계에서는 tasks.md가 진실의 단일 출처).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 빌드 의존성과 디렉토리 준비.

- [X] T001 Add Testcontainers BOM 2.0.5 + `testcontainers-junit-jupiter` + `testcontainers-mysql` + `commons-lang3:3.18.0` to `build.gradle` (testImplementation). 초기 1.20.4 → Docker Desktop 4.x socket redirect 호환 이슈로 2.0.5 채택 (research R-006 회고 참조).
- [X] T002 [P] Create new package directory `src/main/java/com/planetrush/planetrush/core/logging/` with empty `package-info.java`
- [X] T003 [P] Add Docker prerequisite note to `README.md` (one line under existing Tools section): "통합 테스트는 Docker 런타임(Docker Desktop / OrbStack / Colima 등)을 요구합니다. 자세한 셋업은 `specs/001-.../quickstart.md` 참조."

**Checkpoint**: 의존성 해소 (`./gradlew dependencies | grep testcontainers` 확인), 빈 패키지 생성됨.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 User Story가 의존하는 공통 기반.

**⚠️ CRITICAL**: 이 페이즈가 끝나야 US1·US2·US3 진입 가능.

- [X] T004 Create `src/main/resources/logback-spring.xml` with appender wiring `%msk` conversion rule placeholder (MaskingPatternConverter 클래스는 T005에서 구현, XML에서는 `<conversionRule conversionWord="msk" converterClass="com.planetrush.planetrush.core.logging.MaskingPatternConverter"/>`로 선참조)
- [X] T005 [P] Implement skeleton class `src/main/java/com/planetrush/planetrush/core/logging/MaskingPatternConverter.java` extending `ch.qos.logback.classic.pattern.ClassicConverter` — 메서드는 일단 입력 그대로 반환(stub). T009에서 실제 마스킹 로직 추가.
- [X] T006 [P] Extend existing `src/test/java/com/planetrush/planetrush/IntegrationTest.java` to add Testcontainers MySQL(`mysql:8.0.36`) + Redis(`redis:7-alpine`) `@Container static` fields with `withReuse(true).withLabel("project", "planetrush-api")`, and inject `spring.datasource.*` + `spring.data.redis.*` via `@DynamicPropertySource`. Keep `@SpringBootTest`/`@ActiveProfiles("test")` and existing `@LocalServerPort` field. Add `@Testcontainers` class annotation.
- [X] T007 Add Gradle task `verifySecretLogScan` to `build.gradle` (Exec type, runs grep regex from research R-005 against `src/main`, fails build on match) and wire `tasks.named('check') { dependsOn 'verifySecretLogScan' }`

**Checkpoint**: `./gradlew test` 통과 가능(컨버터는 stub, 마스킹 미동작이지만 빌드 OK). `verifySecretLogScan` 실행 시 현재 `JwtTokenProvider`의 평문 로그가 잡혀 빌드 실패 — 이는 의도된 상태(T010이 해결).

---

## Phase 3: User Story 1 — 시크릿 평문 0건 (Priority: P1) 🎯 MVP

**Goal**: 어떤 로그 경로에서도 시크릿이 평문으로 출력되지 않는다.

**Independent Test**: 5개 키워드(`secret`/`token`/`password`/`jwt`/`credential`)를 포함한 임의 메시지·파라미터·멀티라인 입력에 대해 `MaskingPatternConverter`가 값 부분만 `***`로 치환하는지 단위 테스트로 검증. + `grep -RIn "secret key:" src/main` 결과 0건.

### Tests for User Story 1 (TDD — 작성 후 실패 확인 → 구현 → 통과)

- [X] T008 [P] [US1] Write `src/test/java/com/planetrush/planetrush/core/logging/MaskingPatternConverterTest.java` with parameterized cases: (a) 5 keywords × 4 형식(`key=value`, `key: value`, JSON-like `"key":"value"`, 멀티라인/스택트레이스 안 변수), (b) 마스킹 대상 아닌 일반 로그는 변형 0, (c) 키워드는 보존하고 값만 `***`로 치환. 테스트 실행 시 **모두 실패해야 함**(stub 구현이라).

### Implementation for User Story 1

- [X] T009 [US1] Implement real masking logic in `src/main/java/com/planetrush/planetrush/core/logging/MaskingPatternConverter.java` using regex `(?i)(\b(?:secret|token|password|jwt|credential)[^=:\n]{0,20}[=:]\s*)([^\s,;}'"\\]+)` per research R-001 (refined to avoid greedy value capture across separators). T008의 모든 케이스가 통과한다 (21/21).
- [X] T010 [P] [US1] Remove plaintext secret logs from `src/main/java/com/planetrush/planetrush/core/jwt/JwtTokenProvider.java` — `log.info("secret key: {}", SECRET_KEY)` 2건 삭제(createAccessToken 라인 63, validateToken 라인 106). 메타데이터 대체 로그는 추가하지 않음(verifySecretLogScan + 마스킹 컨버터 이중 방어로 충분, 필요 시 별도 변경으로 추가). research R-004와 약간 다른 결정(스코프 최소화).
- [X] T011 [US1] Add `src/test/java/com/planetrush/planetrush/core/jwt/JwtTokenProviderSecretLogTest.java` — Logback `ListAppender<ILoggingEvent>`로 `JwtTokenProvider` 로거 캡처 후 `createToken`/`validateToken` 호출, 어떤 이벤트에도 SECRET_KEY 평문 미포함 검증(2 tests 통과). SC-005 + US1-2 충족.

**Checkpoint**: `./gradlew check` 통과 (T007 grep 게이트 포함). SC-001, SC-005, US1 Acceptance Scenarios 모두 자동 통과.

---

## Phase 4: User Story 2 — Testcontainers로 통합 테스트 자립 (Priority: P1)

**Goal**: 로컬 MySQL/Redis 데몬 없이 `./gradlew test` 통과.

**Independent Test**: `docker stop` + 로컬 MySQL/Redis 데몬 종료 상태에서 `./gradlew test` 실행 시 전체 통과.

### Tests for User Story 2

테스트 클래스 자체가 인수 기준이므로 별도 테스트 작성 없음. 기존 5종 + T006의 변경된 베이스가 통합 검증.

### Implementation for User Story 2

- [X] T012 [US2] Audit 5 existing integration test files. 결과: Member/Planet은 직접 `extends IntegrationTest`, VerificationServiceFailure/VerificationService는 `extends VerificationIntegrationTest`(이게 다시 `extends IntegrationTest`). 6번째로 발견된 `infra/publisher/VerificationRedisStreamPublisherTest`는 순수 Mockito 단위 테스트라 영향권 밖. 어떤 파일에도 `localhost:*` 하드코딩이나 `@TestPropertySource` override 없음. **추가 코드 변경 0건**.
- [X] T013 [US2] **옵션 B 부분 검증** (Docker daemon OFF 상태에서 수행). `./gradlew test --tests "*MemberIntegrationTest"` 실행 시 `IllegalStateException: Could not find a valid Docker environment. Please check configuration.` 출력. **US2-3 Acceptance Scenario 자동 통과** (silent fail 0, 명확한 에러). US2-1·2 풀 검증(데몬 OFF에서 컨테이너 자동 부팅·통과)은 사용자가 Docker(Desktop/OrbStack/Colima) 가동 환경에서 직접 검증.
- [X] T014 [P] [US2] quickstart.md §1.3의 "Could not find a valid Docker environment. Please check configuration." 문자열이 실제 출력과 정확히 일치함을 확인. 문서 정합 ✓.

**Checkpoint**: SC-002, SC-003, US2 Acceptance Scenarios 통과. 후속 스펙이 본 베이스를 상속할 준비 완료.

---

## Phase 5: User Story 3 — 운영 프로필 로그 정리 (Priority: P2)

**Goal**: `prod` 프로필에서 SQL 출력·web 디버그 로그 0건.

**Independent Test**: `--spring.profiles.active=prod`로 부팅한 ConfigurableApplicationContext의 `Environment` 검사 + 부팅 로그 SQL 라인 카운트.

### Implementation for User Story 3

- [X] T015 [P] [US3] Create `src/main/resources/application-prod.yml` with overrides per research R-003: `spring.jpa.show-sql: false`, `spring.jpa.properties.hibernate.format_sql: false`, `logging.level.org.springframework.web: INFO`. `spring.config.activate.on-profile: prod` 명시로 dev/test 회귀 방지. `ddl-auto`는 Spec 5에서 일괄 처리 (TODO 주석으로 링크).
- [X] T016 [US3] Add `src/test/java/com/planetrush/planetrush/core/config/ProdProfileBootTest.java`. `@SpringBootTest` 풀 부팅 대신 `YamlPropertiesFactoryBean`으로 application-prod.yml 직접 로드 후 키 값 검증 — 외부 환경변수(JWT/Kakao/AWS) 의존을 본 PR 범위 밖으로 유지. 풀 부팅 통합 테스트가 후속 필요 시 Testcontainers 베이스에 prod 프로필 변형으로 추가 가능. 2 tests 통과 (overrides + on-profile scoping). SC-004 충족.

**Checkpoint**: SC-004, US3 Acceptance Scenarios 통과.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 머지 가능 상태로 마무리.

- [X] T017 [P] Update root `README.md` Tools section: Testcontainers 추가, 통합 테스트 Docker 의존 명시(한 줄 + quickstart.md 링크). — T003에서 처리됨.
- [X] T018 [P] Add or extend `.github/PULL_REQUEST_TEMPLATE.md` with section "## AI Review (Constitution VI)" requiring `- [ ] Claude Code 리뷰 첨부 (결과/채택·기각 사유)` and `- [ ] Codex 리뷰 첨부 (결과/채택·기각 사유)` checkboxes. — Constitution Check + Spec Reference + Verification 섹션도 함께 추가.
- [X] T019 **부분 검증 (옵션 B)**:
  - ✅ `./gradlew verifySecretLogScan`: `secret log scan clean`
  - ✅ `./gradlew test --tests "*core*"`: 단위 테스트 25 (21+2+2) 통과
  - ✅ `./gradlew compileJava compileTestJava`: 통과
  - ✅ `grep -RIn "secret key:" src/main` 결과 0건 (수동 확인 + Gradle 게이트 동시 보장)
  - ⏳ **사용자 수동 검증 위임 (Docker 가동 후)**:
    - `./gradlew clean check` (통합 테스트 포함)
    - 로컬 MySQL/Redis 데몬 OFF + `./gradlew test` 풀 통과
    - `SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun` 부팅 로그 SQL 0건 (운영 환경변수 의존, 운영 환경에서 검증 권장)
- [X] T020 Update Constitution Alignment table in `specs/001-.../plan.md` "Post-Design Constitution Re-Check" 섹션을 본 구현 완료 후 결과로 갱신. Project Structure도 `IntegrationTest` 재활용 결정에 맞춰 정정. "Post-Implementation Constitution Re-Check" 섹션 신규 추가.

**Checkpoint**: PR 머지 준비 완료.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)** → **Phase 2 (Foundational)** → **Phase 3·4·5 (User Stories)** → **Phase 6 (Polish)**
- Phase 3 (US1) 와 Phase 4 (US2)는 **서로 독립** (다른 파일군). 단, 둘 다 Phase 2 완료 필요.
- Phase 5 (US3) 는 Phase 2 완료 후 Phase 3·4와 독립 진행 가능.

### Task-Level Dependencies

| Task | 차단 | 비고 |
|---|---|---|
| T001 | — | |
| T002·T003 | — | T001과 병렬 |
| T004 | T001 | logback-spring.xml에 conversionRule 등록 (T005에서 구현 채움) |
| T005·T006 | T001, T002 | T004 stub 등록 후 병렬 가능 |
| T007 | T001 | 단독 |
| T008 | T005 (stub 존재 필요) | 실패 테스트 작성 |
| T009 | T008 (실패 확인 후) | TDD |
| T010 | — (Phase 2 끝나면 진행 가능) | T009와 병렬 |
| T011 | T010 | T010 검증 테스트 |
| T012 | T006 | 베이스 변경 후 |
| T013 | T012 | 전체 smoke run |
| T014 | T013 | 에러 메시지 검증 |
| T015 | — (Phase 2 끝나면 가능) | |
| T016 | T015 | prod 프로필 부팅 검증 |
| T017·T018 | — | 항시 가능 |
| T019 | T009, T011, T013, T016 | 최종 검증 |
| T020 | T019 | 문서 갱신 |

### Within Each User Story

- TDD 적용 영역: T008 → T009 (마스킹 컨버터)
- 외 영역: 인수 기준이 명확하면 구현 → 검증 테스트(T010 → T011, T015 → T016)

---

## Parallel Execution Examples

### Phase 1 일괄 가능

```
T001 build.gradle 의존성 추가
T002 [P] core/logging 패키지 생성
T003 [P] README Tools 한 줄 추가
```

### Phase 2 부분 병렬

```
T004 logback-spring.xml conversionRule 등록
T005 [P] MaskingPatternConverter stub  ┐
T006 [P] IntegrationTest 확장          │  T001·T002 후 병렬
T007 verifySecretLogScan Gradle task   ┘
```

### Phase 3+4 동시 진행 (이도류 적합)

- **Claude Code (메인 세션)**: Phase 3 US1 — T008(TDD 테스트) → T009(구현) → T010·T011
- **Codex (별도 워크트리)**: Phase 4 US2 — T012(베이스 적용) → T013(smoke run) → T014

### Phase 5 단독

```
T015 [P] application-prod.yml
T016 prod 부팅 검증 테스트
```

### Phase 6 마무리

```
T017 [P] README
T018 [P] PR 템플릿
T019 최종 로컬 검증
T020 plan.md re-check 섹션 갱신
```

---

## Implementation Strategy

### MVP First

본 스펙은 단일 PR이지만 MVP 관점으로 보면 **US1 (P1) — 시크릿 평문 0건**이 가장 시급(컨스티튜션 V 사고 방지). US2(Testcontainers)는 후속 스펙의 토대로서 동등 P1이지만, 단독 머지 시 가치는 US1보다 낮음.

따라서 만약 시간 압박이 극도로 심해 **본 스펙을 둘로 쪼개야 한다면**:

1. **PR-1**: Phase 1·2(부분)·3 + Phase 6 일부 — US1만 머지
2. **PR-2**: Phase 4·5 + Phase 6 나머지 — US2·US3 머지

권장은 단일 PR(현 계획).

### Incremental Delivery (단일 PR 내부)

- Phase 1·2 한 자리(commit 1) — setup + foundational
- Phase 3 (US1) 한 자리(commit 2) — 시크릿 마스킹
- Phase 4 (US2) 한 자리(commit 3) — Testcontainers 적용
- Phase 5 (US3) 한 자리(commit 4) — prod 프로필
- Phase 6 한 자리(commit 5) — polish & validate

squash-merge 시 PR 본문에 위 5개 단계 요약.

### Parallel Team / 이도류 Strategy

- Phase 1·2 협업으로 빠르게 깔기
- Phase 3 (US1) ↔ Phase 4 (US2) 워크트리 분리해 클코·코덱스 동시 진행 (research에 언급된 패턴 A)
- Phase 5 (US3) 단독, Phase 6 마무리

---

## Acceptance Criteria ↔ Task Mapping (Constitution VII)

| SC | 검증 Task | 검증 방식 |
|---|---|---|
| SC-001 | T008·T009 | 단위 테스트 5 키워드 × 4 형식 |
| SC-002 | T013 | 로컬 데몬 OFF + `./gradlew test` |
| SC-003 | T012·T013 | 5종 통합 테스트 어서션 무변경 통과 |
| SC-004 | T016 | prod 프로필 부팅 검증 테스트 |
| SC-005 | T007·T011·T019 | Gradle task `verifySecretLogScan` + JwtTokenProvider 로그 캡처 + 최종 grep |
| SC-006 | (구조적) | 본 PR 머지 후 후속 스펙이 `extends IntegrationTest` 한 줄로 통합 테스트 시작 가능 — Spec 002+ 시작 시 자동 검증 |

---

## Notes

- [P] tasks = different files, no dependencies
- TDD 적용 명시 영역: T008 → T009
- 모든 Phase 2 task는 무조건 끝낸 뒤 Phase 3+ 진입
- 매 task 완료 시 git commit (squash-merge 가정이라 자유롭게)
- `IntegrationTestSupport` 이름은 본 task에서 `IntegrationTest` 유지로 변경 — plan.md Structure 섹션은 T020에서 정리
- Avoid: 시크릿 키워드를 포함한 어떤 새 로그도 코드에 추가 금지(grep 게이트가 차단함, 메타데이터 형태로만)

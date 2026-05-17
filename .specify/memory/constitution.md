<!--
SYNC IMPACT REPORT
==================
Version change: (template / unversioned) → 1.0.0
Rationale: Initial ratification of the planetrush-api constitution.
            All seven core principles, additional constraints, and
            governance rules are newly introduced; first published version.

Modified principles:
  - (none — first ratification)

Added sections:
  - Core Principles I–VII
  - Additional Constraints
  - Governance

Removed sections:
  - (none)

Templates requiring updates:
  - ✅ .specify/templates/plan-template.md — `Constitution Check` gate
       intentionally generic ("Gates determined based on constitution file");
       no edits required, principles are referenced by name during /speckit-plan.
  - ✅ .specify/templates/spec-template.md — no constitution-specific tokens;
       no edits required.
  - ✅ .specify/templates/tasks-template.md — no constitution-specific tokens;
       no edits required.
  - ✅ .specify/templates/checklist-template.md — no edits required.
  - ⚠ CLAUDE.md — auto-generated stub points to "the current plan"; will
       absorb constitution context automatically once a plan exists. No
       manual edit required at this stage.

Deferred items:
  - (none)
-->

# planetrush-api Constitution

## Core Principles

### I. 통합 테스트는 Testcontainers 기반 (NON-NEGOTIABLE)

모든 새 기능의 통합 테스트는 Testcontainers MySQL 및 Redis 컨테이너로
자동 부팅되어야 한다. 로컬 환경에 MySQL/Redis 데몬이 떠 있지 않은 상태에서도
`./gradlew test`가 통과해야 한다. 외부 의존(Mock, H2 in-memory 대체 등)은
단위 테스트(@WebMvcTest, @DataJpaTest 슬라이스)에서만 허용된다.

**Rationale**: 운영과 동등한 의존성으로 회귀를 잡기 위함. H2/Mock는
운영 환경의 MySQL 잠금·인덱스·트랜잭션 격리, Redis Stream/PEL 등
실제 동작을 가리지 못한다.

**Enforcement**: 신규 `@SpringBootTest`가 로컬 데몬을 요구하는 PR은
머지 차단. 위반 시 ADR 작성 후 우회만 가능.

### II. 외부 의존은 어댑터로 격리

Flask, Redis, S3, OAuth 공급자 등 외부 시스템은 `infra` 레이어의
어댑터(Port-Adapter 패턴)를 통해서만 호출되어야 한다. 도메인·서비스
레이어가 `RestClient`, `RedisTemplate`, `AmazonS3` 등을 직접 참조하면
안 된다.

**Rationale**: 외부 의존을 어댑터로 격리해야 회로 차단기·재시도·
타임아웃 정책을 한 곳에서 강제할 수 있고, 테스트 더블 교체가 쉽다.

**Enforcement**: 도메인/서비스 패키지에서 외부 클라이언트 import 발견
시 PR 차단.

### III. DTO 매핑은 QueryDSL Projections만 사용

Entity → DTO 변환은 QueryDSL `Projections.constructor` 또는
`Projections.fields`만 허용한다. Entity를 영속성 컨텍스트에서 꺼내
서비스 레이어에서 수동 매핑(`new Dto(entity.getX(), ...)`)하는 패턴은
금지한다.

**Rationale**: N+1 회귀를 구조적으로 차단하고 응답 페이로드를 SQL
레벨에서 결정하기 위함. ModelMapper/MapStruct도 lazy 트리거 위험이
있어 본 헌장에서는 채택하지 않는다.

**Enforcement**: 서비스/컨트롤러에서 Entity getter 호출이 발견되는
신규 코드는 PR 차단.

### IV. 메시지 발행은 Outbox 경유 강제 (NON-NEGOTIABLE)

Repository·Service에서 메시지 브로커(Redis Stream 등)를 직접 publish
금지. 모든 외부 이벤트 발행은 `OutboxEvent` 저장 → `AFTER_COMMIT`
리스너 경유로만 가능하다.

**Rationale**: DB 트랜잭션과 메시지 발행의 원자성을 보장하기 위함.
직접 publish는 "DB 커밋 후 publish 실패 → 영원히 유실" 사고의 직접
원인이며, 본 프로젝트의 핵심 신뢰성 카드이다.

**Enforcement**: 신규 `RedisTemplate#opsForStream().add(...)` 직접
호출이 도메인 코드에서 발견되면 PR 차단. 단, Outbox 워커 자체와
어댑터 내부 호출은 예외.

### V. 민감정보 로그 출력 금지 (NON-NEGOTIABLE)

`secret`, `token`, `password`, `jwt`, `credential` 키워드를 포함한
값은 Logback `PatternConverter`로 자동 마스킹(`***`) 처리되어야 한다.
의도적으로 마스킹을 우회하는 코드(`MDC.put("secret", raw)` 등)는
금지한다. 운영 환경에서 본 원칙 위반은 사고(incident)로 간주한다.

**Rationale**: 본 레포에는 과거 `log.info("secret key: {}", SECRET_KEY)`
패턴이 정상 흐름에서 매 호출 찍히는 시크릿 노출 사고 흔적이 있으며,
재발 방지가 명문화된 책임이다.

**Enforcement**: 마스킹 컨버터 단위 테스트 미통과 시 빌드 실패.
PR 리뷰에서 의심 로그 발견 시 머지 차단.

### VI. 듀얼 AI 리뷰 첨부

모든 PR 본문에는 Claude Code 리뷰와 Codex(또는 동등한 2차 모델) 리뷰
결과가 함께 첨부되어야 한다. 각 코멘트에 대해 "채택" 또는 "기각 + 사유"를
명시한다.

**Rationale**: 단일 모델 환각·블라인드 스폿을 상호 검증으로 보완하고,
판단의 흔적을 자산화하여 의사결정 근거를 보존하기 위함.

**Enforcement**: 리뷰 첨부 누락 PR은 머지 차단(레이블 또는 PR
체크리스트로 강제).

### VII. 인수 기준은 자동 테스트로 검증

각 스펙의 인수 기준(Acceptance Criteria)은 해당 PR에 자동화 테스트로
포함되어야 한다. "수동으로 확인했다"는 표기로 충족 처리할 수 없다.
인수 기준 ↔ 테스트 매핑은 `tasks.md`에 명시한다.

**Rationale**: 인수 기준이 살아있는 테스트로 박제되어야만 후속
리팩토링·이전 작업에서 회귀가 감지된다. 본 프로젝트의 SDD 워크플로우는
이 매핑에 전적으로 의존한다.

**Enforcement**: `/speckit-analyze` 단계에서 인수 기준 ↔ 테스트
누락이 발견되면 `/speckit-implement` 진입 차단.

## Additional Constraints

**기술 스택 (고정)**:

- Java 21, Spring Boot 3.2.7, JPA + QueryDSL 5.0.0, MySQL 8.x, Redis 7.x
- 빌드: Gradle, 테스트: JUnit 5 + Testcontainers
- 외부: Flask(이미지 유사도), AWS S3, Kakao OAuth, Mattermost

**운영 안티패턴 즉시 차단**:

- `spring.jpa.hibernate.ddl-auto=update` 금지 (운영 프로필).
  허용값: `validate` (운영) / `create-drop` (테스트).
- `spring.jpa.show-sql=true` 금지 (운영 프로필).
  관찰 필요 시 p6spy 또는 DataSource Proxy 사용.
- 시크릿 환경변수의 평문 로그 금지 (원칙 V).

**라이브러리 도입 규칙**:

- 신규 `build.gradle` 의존성 추가 시 해당 스펙의 `plan.md`에
  "도입 사유 / 거절된 대안 / 라이선스 / 유지보수 상태"를 명시해야 한다.
- 동일 기능 라이브러리 중복 도입 금지 (예: `RestTemplate` + `WebClient`
  + `RestClient` 동시 사용 금지 — 신규 코드는 하나로 통일).

## Governance

**스펙 시작 시**:

- 각 `/speckit-plan` 산출물(`plan.md`)에는 "본 컨스티튜션 적용 항목"
  섹션을 두고, 해당 스펙이 어느 원칙(I–VII)에 어떻게 부합하는지
  명시한다.
- 위반이 불가피한 경우 `plan.md`의 `Complexity Tracking` 표에 사유를
  기록한 뒤, 별도 ADR(`docs/adr/NNN-*.md`)을 작성해 PR 본문에 링크한다.
  ADR 없이는 머지 불가.

**개정 절차**:

- 컨스티튜션 개정 PR은 별도 PR로 분리하며 본문에 (a) 변경 항목,
  (b) Sync Impact Report, (c) 영향받는 진행 중 스펙 목록을 포함한다.
- 버전 규칙(SemVer):
  - MAJOR: 기존 원칙 제거 또는 비호환 재정의
  - MINOR: 새 원칙·섹션 추가 또는 의미 있는 가이드 확장
  - PATCH: 표현 다듬기, 오타, 비의미 정제

**준수 검증**:

- 모든 PR 리뷰는 본 컨스티튜션 7개 원칙에 대한 위반 여부를 1차 게이트로
  검증한다.
- `/speckit-analyze`는 spec ↔ plan ↔ tasks 정합성과 함께 컨스티튜션
  위반 여부도 함께 보고한다.

**Version**: 1.0.0 | **Ratified**: 2026-05-16 | **Last Amended**: 2026-05-16

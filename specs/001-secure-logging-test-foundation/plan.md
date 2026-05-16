# Implementation Plan: Secure Logging & Test Foundation

**Branch**: `001-secure-logging-test-foundation` | **Date**: 2026-05-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-secure-logging-test-foundation/spec.md`

## Summary

Brownfield 인프라 도입. 두 가지를 단일 PR로 머지한다:

1. **민감정보 로그 마스킹 가드** — Logback `PatternConverter`로 `secret/token/password/jwt/credential` 키워드 값이 자동 `***` 치환되며, 빌드 파이프라인의 단위 테스트로 회귀를 잡는다. 동시에 `JwtTokenProvider`의 시크릿 평문 출력 경로를 제거한다.
2. **Testcontainers 통합 테스트 베이스** — MySQL/Redis 컨테이너를 자동 부팅하는 `IntegrationTestSupport` 추상 클래스를 도입하고, 기존 통합 테스트 5종을 이전한다. 컨테이너 재사용(`withReuse(true)`)으로 부팅 비용을 상각한다. 운영 프로필(`application-prod.yml`)을 신설하여 `show-sql=false`, `web=INFO`로 분리한다.

본 PR이 머지된 시점부터 후속 모든 스펙(Outbox, 동시성, 성능, Resilience)이 이 안전망 위에서 통합 테스트를 작성한다.

## Technical Context

**Language/Version**: Java 21, Spring Boot 3.2.7 (`build.gradle`의 toolchain·springframework.boot 버전 변경 없음)

**Primary Dependencies (신규 도입)**:

| 의존성 | 도입 사유 | 거절된 대안 |
|---|---|---|
| `org.testcontainers:testcontainers-bom:1.20.4` | 버전 통일성 + 컨스티튜션 원칙 I 충족 토대 | 개별 모듈 직접 버전 지정 — 향후 버전 드리프트 위험 |
| `org.testcontainers:junit-jupiter` | `@Testcontainers` 어노테이션 + JUnit 5 라이프사이클 통합 | 수동 컨테이너 시작/종료 — 보일러플레이트 폭증 |
| `org.testcontainers:mysql` | MySQL 8.x 컨테이너 헬퍼 | Embedded MariaDB / H2 — 운영 동작 차이 + 컨스티튜션 원칙 I 위반 |

Redis는 별도 모듈 추가 없이 `GenericContainer<>` + `redis:7-alpine` 이미지로 어댑터 한 곳에 격리(JedisConnectionFactory 동적 주입). Redis 모듈은 컨스티튜션 원칙 II("외부 의존은 어댑터로 격리")에 따라 통합 테스트 베이스 내부에 캡슐화.

**Storage**: MySQL 8.0.36 (Testcontainers `mysql:8.0.36`), Redis 7.x (Testcontainers `redis:7-alpine`). 운영과 동일 메이저 버전 강제.

**Testing**: JUnit 5 + Testcontainers + 기존 Spring Boot Test 스택. 컨테이너 재사용: `@Container static` + `withReuse(true)` + 사용자 환경의 `~/.testcontainers.properties`에 `testcontainers.reuse.enable=true` 설정(README/quickstart에 안내).

**Target Platform**: Linux/macOS 개발자 워크스테이션, Linux CI 러너(Docker 데몬 또는 Colima/OrbStack 호환 런타임 가정).

**Project Type**: Spring Boot 단일 모듈 웹 서비스. 멀티모듈 분리는 본 스펙 범위 밖.

**Performance Goals**:

- 마스킹 컨버터 추가에 따른 로그 처리량 저하 < 5% (간이 측정 또는 의도적 생략 — Phase 0 research에서 결정).
- Testcontainers 첫 부팅 < 60s (Cold start), 재사용 시 < 2s (Warm start).
- 단위 테스트 모듈(`src/test/.../core/logging/`) 실행 < 1s.

**Constraints**:

- 운영 프로필에서 `show-sql=false`, `org.springframework.web=INFO` (FR-005).
- `JwtTokenProvider`의 시크릿 평문 로그 0건 (SC-005, grep으로 자동 검증).
- 기존 통합 테스트 5종의 어서션 변경 금지 (FR-004).
- `application.yml` 공통 설정 동작 변경 금지 (dev 프로필 회귀 0건).

**Scale/Scope**: 단일 PR. 신규 파일 ~6개, 수정 파일 ~10개(통합 테스트 베이스 교체 포함), build.gradle +3 의존성.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| 원칙 | 적용 여부 | 본 스펙에서의 의미 | Pass / Violation |
|---|---|---|---|
| **I. Testcontainers 통합 테스트 (NON-NEGOTIABLE)** | 본 스펙이 구현체 | `IntegrationTestSupport` 베이스 + 기존 5종 이전 | ✅ Pass |
| **II. 외부 의존 어댑터 격리** | 부분 적용 | Testcontainers MySQL/Redis는 테스트 인프라 내부 어댑터로 캡슐화. 도메인 변경 없음. | ✅ Pass |
| **III. QueryDSL Projections 강제** | N/A | DTO/엔티티 변경 없음 | ✅ Pass (해당 없음) |
| **IV. Outbox 강제 (NON-NEGOTIABLE)** | N/A | 메시지 발행 변경 없음 | ✅ Pass (해당 없음) |
| **V. 시크릿 로그 금지 (NON-NEGOTIABLE)** | 본 스펙이 구현체 | Logback `MaskingPatternConverter` + `JwtTokenProvider` 정상 흐름 로그 제거 + 자동 단위 테스트 + grep CI 게이트 | ✅ Pass |
| **VI. 듀얼 AI 리뷰 첨부** | 적용 | PR 본문에 Claude Code 리뷰 + Codex 리뷰 결과 첨부, 채택/기각 사유 표기 | ✅ Pass (PR 단계 강제) |
| **VII. 인수 기준 자동 테스트화** | 적용 | SC-001~006 전부 자동 테스트 또는 grep 스크립트로 검증. SC-005는 `./gradlew check`에 grep step 추가 | ✅ Pass |

**결과**: 모든 게이트 통과. Phase 0 진입 가능.

**라이브러리 도입 규칙** (Additional Constraints): 신규 의존성 3종(Testcontainers BOM/junit-jupiter/mysql) 각각의 도입 사유와 거절된 대안을 위 Technical Context 표에 명시. 컨스티튜션 준수.

## Project Structure

### Documentation (this feature)

```text
specs/001-secure-logging-test-foundation/
├── spec.md                       # 작성 완료
├── plan.md                       # 본 파일
├── research.md                   # Phase 0 산출물
├── quickstart.md                 # Phase 1 산출물 (통합 테스트 작성 가이드)
├── checklists/
│   └── requirements.md           # 작성 완료
└── tasks.md                      # /speckit-tasks 단계에서 생성

# data-model.md, contracts/ — N/A (도메인/계약 변경 없음). plan에 명시 후 생략.
```

### Source Code (repository root)

```text
src/main/java/com/planetrush/planetrush/
├── core/
│   ├── logging/                                # 신규
│   │   └── MaskingPatternConverter.java        # Logback PatternConverter 구현
│   └── jwt/
│       └── JwtTokenProvider.java               # 수정: 시크릿 평문 로그 제거
└── (기존 도메인 패키지 변경 없음)

src/main/resources/
├── application.yml                             # 수정: 운영 종속 설정 prod로 이동
├── application-prod.yml                        # 신규: show-sql=false, web=INFO
├── application-dev.yml                         # 기존 유지 (변경 없음 또는 최소 정렬)
└── logback-spring.xml                          # 신규: MaskingPatternConverter 등록

src/test/java/com/planetrush/planetrush/
├── IntegrationTest.java                        # 수정: 기존 추상 베이스를 Testcontainers MySQL/Redis로 확장
│                                               # (신규 IntegrationTestSupport 만들지 않고 기존 재활용 결정 — tasks.md 발견)
├── core/
│   ├── logging/                                # 신규
│   │   └── MaskingPatternConverterTest.java    # 단위 테스트 (5종 키워드)
│   ├── jwt/                                    # 신규
│   │   └── JwtTokenProviderSecretLogTest.java  # ListAppender 로그 캡처 검증
│   └── config/                                 # 신규
│       └── ProdProfileBootTest.java            # application-prod.yml 키 검증
└── (기존 통합 테스트 5종 자동 혜택: IntegrationTest 확장으로 데몬 의존 제거)

build.gradle                                     # 수정: Testcontainers BOM + 2 modules + verifySecretLogScan 태스크
```

**Structure Decision**: 단일 Spring Boot 모듈 유지. 마스킹 컨버터는 `core/logging` 신규 패키지(다른 core 유틸과 동일 레벨), 통합 테스트 베이스는 `test/.../support` 신규 패키지(컨스티튜션 원칙 II의 "테스트 인프라 내부 어댑터" 격리 위치). 멀티모듈 분리는 향후 별도 스펙.

## Constitution Alignment (Governance §1)

본 스펙이 컨스티튜션의 어느 원칙을 어떻게 충족시키는지 명시(개정 절차 §"스펙 시작 시" 충족):

- **원칙 I, V, VII**: 본 스펙의 핵심 목표 자체.
- **원칙 II**: Testcontainers MySQL/Redis는 도메인 어댑터가 아닌 테스트 인프라 어댑터로, `IntegrationTestSupport` 내부에 격리되어 도메인 코드는 외부 의존을 인지하지 않음.
- **원칙 III, IV**: 본 스펙 범위 밖 (해당 없음).
- **원칙 VI**: PR 단계에서 강제. PR 템플릿에 클코+코덱스 리뷰 결과 섹션 명시.

## Complexity Tracking

> 위반 없음. 표 비움.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| (해당 없음) | — | — |

## Post-Design Constitution Re-Check

Phase 1 (design) 완료 후 재검토:

- Phase 1 산출물(research.md, quickstart.md) 작성에서 신규 위반 없음. ✅
- `data-model.md`/`contracts/` 생략은 도메인·계약 변경 없음에 따른 의도된 N/A이며 본 plan의 Structure 섹션에 명시. ✅
- 모든 게이트 재통과. Phase 2(`/speckit-tasks`) 진입 가능.

## Post-Implementation Constitution Re-Check (T020)

구현 진행 후 갱신(Phase 1·2·3·5 완료 시점, Phase 4·6 진행 중):

| 원칙 | 구현 결과 |
|---|---|
| **I. Testcontainers (NON-NEGOTIABLE)** | ✅ `IntegrationTest` 베이스가 MySQL 8.0.36 + Redis 7-alpine 컨테이너 자동 부팅. `@DynamicPropertySource`로 spring.datasource·spring.data.redis 동적 주입. `withReuse(true)` + label scoping. (실제 5종 통합 테스트 실행 검증은 Phase 4) |
| **II. 외부 의존 어댑터 격리** | ✅ Testcontainers는 테스트 인프라 내부 어댑터로 격리. 도메인/서비스 코드 변경 0. |
| **III. QueryDSL Projections** | N/A (DTO 변경 없음). |
| **IV. Outbox 강제 (NON-NEGOTIABLE)** | N/A (메시지 발행 변경 없음). |
| **V. 시크릿 로그 금지 (NON-NEGOTIABLE)** | ✅ `MaskingPatternConverter` 21 test 통과 + `JwtTokenProvider` 평문 2건 제거 + `JwtTokenProviderSecretLogTest` 2 test 통과 + `verifySecretLogScan` Gradle 태스크가 `check`에 hook. 다중 방어. |
| **VI. 듀얼 AI 리뷰** | ✅ `.github/PULL_REQUEST_TEMPLATE.md`에 Constitution Check + Claude Code + Codex 리뷰 섹션 강제. |
| **VII. 인수 기준 자동 테스트** | ✅ SC-001·SC-004·SC-005 모두 자동 테스트로 검증. SC-002·SC-003은 Phase 4 통합 테스트 smoke run에서, SC-006은 quickstart.md §2 가이드로 구조적 충족. |

**Complexity Tracking**: 위반 없음.

**구현 중 발견 사항**:
- 기존 `IntegrationTest` 추상 베이스가 이미 존재했으므로 신규 `IntegrationTestSupport`를 만들지 않고 기존 클래스를 확장하여 단순화 (Structure 섹션 동기화 완료).
- 통합 테스트 5종 이름이 모두 정확함을 빌드 출력에서 확인 (analyze F1 false positive로 철회).
- JWT 메타데이터 대체 로그(`log.debug("jwt secret loaded ...")`) 추가는 본 PR에서 의도적으로 제외. `verifySecretLogScan` + 마스킹 컨버터 이중 방어로 충분하며, 메타데이터 자체가 마스킹 정규식에 부수 매칭될 위험이 있음. 필요 시 후속 PR.

**결과**: 게이트 재통과. Phase 4·6 진입 가능.

---
name: sdd-verify
description: "planetrush-api를 Gradle 빌드·테스트·헌법 게이트로 검증하는 절차 가이드. build-verifier 에이전트가 Phase별 검증을 수행할 때 사용. ./gradlew check·test·verifySecretLogScan 명령과 증거 기반 보고 규칙을 담는다. SDD 빌드 검증·테스트 실행·게이트 확인·재검증 시 적용."
---

# SDD 빌드 검증 가이드

planetrush-api 구현 결과를 Gradle로 검증하는 절차. 핵심 원칙: **실제 명령 출력 없이 통과를 단언하지 않는다.**

## 검증 명령 표

| 목적 | 명령 | 사용 시점 |
|---|---|---|
| 변경 범위 빠른 확인 | `./gradlew test --tests "*Pattern*"` | 구현+테스트 직후, 빠른 피드백 |
| 시크릿 로그 게이트(원칙 V) | `./gradlew verifySecretLogScan` | 매 Phase. `src/main`에 시크릿 평문 로그가 있으면 빌드 실패 |
| 전체 게이트(원칙 I·VII 포함) | `./gradlew check` | Phase 종료 시. `test` + `verifySecretLogScan`을 포함 |
| 컴파일만 | `./gradlew compileJava compileTestJava` | 빠른 컴파일 확인 |

`./gradlew check`는 `verifySecretLogScan`을 의존하므로, check가 그린이면 시크릿 게이트도 통과한 것이다.

## 검증 절차 (Phase 단위)

1. `code-implementer`·`test-author`로부터 변경 파일 목록을 받는다.
2. **빠른 피드백**: 변경 범위에 맞는 `--tests "*Pattern*"`을 먼저 돌려 즉시 보고한다.
3. **Phase 게이트**: `./gradlew check` 전체를 돌린다.
4. 결과를 `specs/{spec}/_harness/phase{N}-verify.md`에 기록한다 — 실행한 명령 + 출력 발췌(`BUILD SUCCESSFUL`/`BUILD FAILED`, 테스트 개수, 실패 케이스) + 판정.

## 증거 기반 보고 — 절대 원칙

"테스트 통과"라고 쓰려면 출력의 다음 중 하나를 인용한다:
- `BUILD SUCCESSFUL in ...`
- `N tests completed, 0 failed`

출력을 보지 않은 통과 단언은 금지다. 빌드가 그린이 아니면 그린이라고 하지 않는다. 이것이 verifier가 존재하는 이유다.

## 실패 분석과 라우팅

실패 시 로그에서 원인을 파일:라인까지 좁히고, 책임 에이전트로 라우팅한다:

| 실패 유형 | 신호 | 라우팅 |
|---|---|---|
| 컴파일 에러 (`src/main`) | `compileJava FAILED` | code-implementer |
| 컴파일 에러 (`src/test`) | `compileTestJava FAILED` | test-author |
| 테스트 단언 실패 | `expected: ... but was: ...` | 기대값이 맞으면 code-implementer(프로덕션 결함), 기대값이 틀리면 test-author |
| 시크릿 로그 게이트 실패 | `verifySecretLogScan FAILED` | code-implementer |
| Testcontainers/Docker 문제 | 컨테이너 기동 실패·커넥션 타임아웃 | 인프라 문제로 분류, 리더에게 보고(코드 결함 아님) |

## flaky 테스트
간헐 실패는 반복 실행(`--tests` 반복 또는 `@RepeatedTest` 확인)으로 재현하고 `test-author`에게 보고한다. "재실행하니 통과"로 넘기지 않는다 — 숨겨진 동시성·격리 결함일 수 있다.

## 완료 기준
- 배정 Phase에 대해 `./gradlew check` 그린.
- `phase{N}-verify.md`에 명령·출력·판정이 증거와 함께 기록됨.
- 실패가 있으면 책임 에이전트에 라우팅 완료.

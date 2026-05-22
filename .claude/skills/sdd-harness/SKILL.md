---
name: sdd-harness
description: "planetrush-api의 SDD 구현 단계를 4-에이전트 팀으로 실행하는 오케스트레이터. /speckit-analyze까지 끝난 스펙의 tasks.md를 입력받아 구현→테스트→검증→리뷰를 tasks.md Phase 단위로 점진 수행한다. 'Spec NNN 구현', 'tasks.md 구현', '하네스로 구현', '스펙 구현해줘', 그리고 후속 작업 — '구현 이어서', 'Phase N부터 다시', '검증 다시 돌려', '리뷰 다시', '구현 재개', '이전 구현 보완' — 시 반드시 이 스킬을 사용. 단, specify·clarify·plan·tasks·analyze 단계는 /speckit-* 커맨드가 담당하므로 이 스킬을 쓰지 않는다."
---

# SDD Harness Orchestrator

planetrush-api의 SDD(spec-kit) 워크플로우 중 **구현 단계**를 4-에이전트 팀으로 실행하는 오케스트레이터. `/speckit-analyze`까지 통과한 스펙의 `tasks.md`를 받아, 구현→테스트→빌드검증→코드리뷰를 **tasks.md의 Phase 단위로 점진적으로** 수행한다.

## 실행 모드: 에이전트 팀

## 에이전트 구성

| 팀원 | agent_type | 역할 | 스킬 | 출력 |
|---|---|---|---|---|
| code-implementer | code-implementer | 프로덕션 코드 구현 | sdd-implement | `src/main/...` |
| test-author | test-author | 인수기준→자동 테스트 작성 | sdd-test-authoring | `src/test/...` |
| build-verifier | build-verifier | Gradle 빌드·게이트 검증 | sdd-verify | `_harness/phase{N}-verify.md` |
| code-reviewer | code-reviewer | 헌법·품질·정합성 리뷰 | sdd-review | `_harness/phase{N}-review.md`, `claude-review.md` |

리더(이 스킬을 실행하는 메인)는 팀을 구성·조율하고 Phase 게이트를 관리한다. 모든 팀원은 `model: "opus"`로 스폰한다.

## 워크플로우

### Phase 0: 컨텍스트 확인

1. **대상 스펙 식별**: 사용자가 명시한 스펙(예 "Spec 003") 또는 현재 git 브랜치명(`003-*`)에서 추론. 모호하면 사용자에게 확인.
2. **전제 확인**: `specs/{spec}/tasks.md`가 존재하고 `/speckit-analyze`를 통과했는지 확인. tasks.md가 없으면 "먼저 `/speckit-tasks`·`/speckit-analyze`를 실행하라"고 안내하고 중단.
3. **실행 모드 결정** — `specs/{spec}/_harness/` 존재 여부로 분기:
   - **미존재** → 초기 실행. Phase 1로.
   - **존재 + `progress.md`에 미완료 Phase 있음** → 재개 실행. 완료 Phase 다음부터.
   - **존재 + 사용자가 특정 Phase 재실행 요청** → 부분 재실행. 해당 Phase만 다시.

### Phase 1: 준비

1. `specs/{spec}/`의 `spec.md`·`plan.md`·`research.md`·`tasks.md`와 `.specify/memory/constitution.md`를 읽는다.
2. `tasks.md`를 **Phase 단위로 파싱**한다 — 각 tasks.md Phase의 태스크 목록, 프로덕션(`src/main`)/테스트(`src/test`) 구분, `depends_on`을 추출.
3. `specs/{spec}/_harness/` 디렉토리 생성, `progress.md` 초기화(tasks.md Phase 목록 + 각 상태 `pending`).
4. 현재 git 브랜치가 `{spec}` 피처 브랜치인지 확인. 아니면 사용자에게 확인(브랜치는 `/speckit-specify` 시점에 생성됨 — 하네스는 새로 만들지 않는다).

### Phase 2: 팀 구성

`TeamCreate`로 4-에이전트 팀을 만든다(세션 내내 유지):
```
TeamCreate(team_name: "sdd-impl-{spec}", members: [
  { name: "code-implementer", agent_type: "code-implementer", model: "opus", prompt: "{스펙 경로 + 담당 범위 + sdd-implement 스킬 사용 지시}" },
  { name: "test-author",      agent_type: "test-author",      model: "opus", prompt: "{... sdd-test-authoring ...}" },
  { name: "build-verifier",   agent_type: "build-verifier",   model: "opus", prompt: "{... sdd-verify ...}" },
  { name: "code-reviewer",    agent_type: "code-reviewer",    model: "opus", prompt: "{... sdd-review ...}" },
])
```
각 prompt에 대상 스펙 경로(`specs/{spec}/`), `_harness/` 워크스페이스 경로, 자신의 스킬명을 전달한다.

### Phase 3: Phase별 점진 루프

tasks.md의 각 Phase를 순서대로 반복한다(tasks.md의 Phase Dependencies 존중):

**3-1. 구현 + 테스트 (병행)**
- `TaskCreate`로 해당 tasks.md Phase의 태스크를 등록 — 프로덕션 태스크는 `code-implementer`, 테스트 태스크는 `test-author`에 배정, `depends_on` 반영.
- 둘은 병행한다. `test-author`는 구현 시그니처가 필요하면 `SendMessage`로 요청하거나 tasks.md 명시 시그니처로 선행 작성.

**3-2. 검증 (구현+테스트 완료 후)**
- `build-verifier`가 변경 범위 대상 테스트 + `./gradlew check`·`verifySecretLogScan`을 실행, `phase{N}-verify.md`에 증거와 함께 기록.
- 실패 → 책임 에이전트(`code-implementer`/`test-author`)에게 라우팅 → 수정 → 재검증.

**3-3. 리뷰 (빌드 그린 후)**
- `code-reviewer`가 헌법 7원칙·품질·정합성 리뷰, `phase{N}-review.md` 기록.
- P1 발견 → 책임 에이전트 수정 → 재검증 → 재리뷰.

**3-4. Phase 게이트**
- 빌드 그린 + P1 0건이면 Phase 통과. 리더가 git commit — 레포 컨벤션 `feat|test|refactor(spec-NNN): {요약}` 준수.
- `progress.md`에서 해당 Phase를 `done`으로 갱신.
- 수정 루프는 Phase당 최대 3회. 초과 시 리더가 사용자에게 보고하고 판단을 구한다(임의 진행 금지).

다음 tasks.md Phase로 진행.

### Phase 4: 통합

1. 모든 tasks.md Phase가 `done`인지 확인.
2. `build-verifier`가 전체 `./gradlew check`를 최종 실행 — 회귀 없음 확인.
3. `code-reviewer`가 전 Phase 리뷰를 `claude-review.md`로 통합(헌법 VI의 Claude 측 리뷰 — PR 본문용).
4. tasks.md "Acceptance Criteria ↔ Task Mapping"의 모든 SC가 통과 테스트로 검증됐는지 최종 확인.

### Phase 5: 정리 및 보고

1. 팀 해체(`TeamDelete`).
2. `_harness/` 보존(감사 추적·PR 첨부용 — 삭제하지 않는다).
3. 사용자에게 보고: 구현된 Phase, 테스트 결과(증거 인용), `claude-review.md` 경로, 잔여 이슈.
4. **여기서 멈춘다** — PR 생성·Codex 듀얼 리뷰·머지는 사용자가 직접 진행한다.

## 데이터 흐름

태스크 기반(공유 작업 목록 조율) + 파일 기반(`src/`·`_harness/` 산출물) + 메시지 기반(`SendMessage` 실시간 피드백)을 함께 쓴다.

워크스페이스 `specs/{spec}/_harness/`:
- `progress.md` — Phase 상태 추적(리더 관리)
- `phase{N}-verify.md` — Phase별 검증 결과(build-verifier)
- `phase{N}-review.md` — Phase별 리뷰(code-reviewer)
- `claude-review.md` — 전 Phase 통합 최종 리뷰(code-reviewer, PR 본문용)

```
[리더] TeamCreate → code-implementer ─시그니처→ test-author
                          │                        │
                       src/main                 src/test
                          └──────────┬───────────┘
                                build-verifier (그린?) → code-reviewer (P1 0?)
                                     │                        │
                            phase{N}-verify.md          phase{N}-review.md
                                     └──── Phase 게이트 통과 → 리더 commit ────┘
```

## 에러 핸들링

| 상황 | 전략 |
|---|---|
| 팀원 1명 실패·중지 | 리더가 `SendMessage`로 상태 확인 → 재시작. 불가 시 리더가 해당 역할을 직접 수행 |
| 수정 루프 3회 초과 | Phase를 멈추고 사용자에게 결함·시도 내역 보고, 판단을 구함 |
| tasks.md ↔ 코드베이스 충돌 | 임의 보정 금지. 리더가 사용자에게 보고 |
| 헌법 위반 불가피 | 멈추고 사용자에게 보고 — ADR 작성 필요 가능(헌법 Governance) |
| 빌드 환경 문제(Docker 미기동 등) | 코드 결함과 구분해 인프라 문제로 사용자에게 보고 |
| 신규 의존성에 plan.md 근거 없음 | 멈추고 사용자에게 보고 |
| 팀원 과반 실패 | 사용자에게 알리고 진행 여부 확인 |

상충하는 판단(리뷰 지적 ↔ 구현자 반론)은 삭제하지 않고 `claude-review.md`에 "기각 + 사유"로 병기한다.

## 테스트 시나리오

### 정상 흐름
1. 사용자: "Spec 003 하네스로 구현해줘"
2. Phase 0: `specs/003-*/tasks.md` 확인, `_harness/` 미존재 → 초기 실행.
3. Phase 1: 설계 문서 로드, tasks.md를 N개 Phase로 파싱, `_harness/progress.md` 생성.
4. Phase 2: 4-에이전트 팀 생성.
5. Phase 3: tasks.md Phase 1부터 — 구현+테스트 → 검증 그린 → 리뷰 P1 0 → 커밋 → 다음 Phase. 반복.
6. Phase 4: 전체 `check` 그린, `claude-review.md` 통합, 전 SC 검증 확인.
7. Phase 5: 팀 해체, 보고. PR은 사용자.
8. 예상 결과: 전 Phase 구현·커밋 완료, `specs/003-*/_harness/claude-review.md` 생성.

### 에러 흐름
1. Phase 3에서 tasks.md Phase 3 검증이 실패, 수정 3회로도 미해결.
2. `build-verifier`가 리더에게 에스컬레이션.
3. 리더가 팀을 일시 보류하고 사용자에게 실패 테스트·에러·시도 내역을 보고.
4. 사용자 판단(설계 변경·스펙 수정·ADR)을 받아 재개하거나 중단.
5. `progress.md`에 중단 지점 기록 — 다음 호출 시 Phase 0이 재개 실행으로 분기한다.

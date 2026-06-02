<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan:

- Active spec: `specs/005-async-verification-pipeline/`
- Plan: `specs/005-async-verification-pipeline/plan.md`
- Research: `specs/005-async-verification-pipeline/research.md`
- Data model: `specs/005-async-verification-pipeline/data-model.md`
- Contracts: `specs/005-async-verification-pipeline/contracts/`
  (rest-api.md · stream-message.md)
- Quickstart: `specs/005-async-verification-pipeline/quickstart.md`
- Constitution: `.specify/memory/constitution.md`
- Prior specs (foundation·infra): `specs/001-secure-logging-test-foundation/`, `specs/002-outbox-republisher/`
<!-- SPECKIT END -->

## 하네스: SDD 구현 팀

**목표:** `/speckit-analyze`까지 끝난 스펙의 `tasks.md`를 4-에이전트 팀(구현·테스트·검증·리뷰)으로 구현해 결과물 품질을 높인다.

**트리거:** 스펙 구현 단계 작업("Spec NNN 구현", "tasks.md 구현", "하네스로 구현", 구현 재개·부분 재실행) 요청 시 `sdd-harness` 스킬을 사용하라. specify·clarify·plan·tasks·analyze 단계는 `/speckit-*` 커맨드가 담당하므로 하네스를 쓰지 않는다. 단순 질문은 직접 응답 가능.

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-05-22 | 초기 구성 — 4-에이전트 팀(code-implementer·test-author·build-verifier·code-reviewer) + `sdd-harness` 오케스트레이터 | 전체 | SDD 구현 단계 결과물 품질 향상 |

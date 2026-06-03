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

## 운영 함정 (Spec 005 학습)

- `@TransactionalEventListener(AFTER_COMMIT)` 안에서 기본 propagation 의 `@Transactional` 만 부착하면 동기화 매니저 정리 직전의 회색지대에서 잔재 EntityManager 가 재사용돼 dirty checking 이 사일런트 무시될 수 있다 — 리스너 메서드 자체에 `REQUIRES_NEW` 를 부착해 새 EntityManager 를 강제.
- `OutboxRepublisher` 처럼 `SELECT ... FOR UPDATE SKIP LOCKED` 로 row 락을 보유한 외부 트랜잭션이 호출하는 메서드에 `REQUIRES_NEW` 를 부착하면 같은 row 에 새 트랜잭션이 막혀 self-deadlock. 외부 발행 어댑터(`infra/publisher/`) 는 트랜잭션을 자체 들지 말고 호출자에게 위임 (헌법 II 실전 적용 — publisher = XADD 만, status 전이 = 호출자).
- 빌드 캐시: 코드 변경 0이면 `./gradlew check` 가 ms 안에 캐시 hit — 디버깅 사이클에 유용.

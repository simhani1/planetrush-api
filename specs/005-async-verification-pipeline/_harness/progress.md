# SDD Harness Progress — spec 005-async-verification-pipeline

**Spec**: [../spec.md](../spec.md) · **Plan**: [../plan.md](../plan.md) · **Tasks**: [../tasks.md](../tasks.md)
**Started**: 2026-06-03 · **Branch**: `005-async-verification-pipeline`

## 운영 모드

- **변형**: `TeamCreate + 동시 운영` 패턴 대신 `Agent` 툴로 Phase 별 sequential spawn. 각 Phase 안에서 code-implementer + test-author 병행 → build-verifier → code-reviewer 직렬.
- **수정 루프 한도**: Phase 당 3회. 초과 시 사용자 에스컬레이션.
- **헌법 게이트**: 각 Phase commit 전 `./gradlew check` + `verifySecretLogScan` clean 확인.

## Phase 상태

| tasks.md Phase | 태스크 범위 | 상태 | 시작 | 완료 | 산출물 |
|---|---|---|---|---|---|
| 1. Setup | T001 (yml 설정) | done | 2026-06-03 | 2026-06-03 | application.yml/application-test.yml verification.* 키 추가 |
| 2. Foundational | T002~T009 (entity·repo·outbox payload·security·테스트 인프라) | pending | — | — | — |
| 3. US1 (P1 MVP) | T010~T024 (테스트 2 + 구현 13) | pending | — | — | — |
| 4. US2 (P1) | T025~T026 (chaos·outbox republisher 정합) | pending | — | — | — |
| 5. US3 (P2) | T027~T029 (멱등 2종 + slice) | pending | — | — | — |
| 6. Polish | T030~T033 (secret scan·dead path·plan 갱신·PR 체크) | pending | — | — | — |

## Phase 의존성

```
Phase 1 (Setup)
   ▼
Phase 2 (Foundational) ─ 차단 단계
   ▼
Phase 3 (US1, MVP)
   ├──► Phase 4 (US2)  ─┐
   └──► Phase 5 (US3)  ─┤  (Phase 4·5 병렬 가능 — 본 운영에선 sequential)
                        ▼
                   Phase 6 (Polish)
```

## Analyze 단계 잔여 findings (참고)

| ID | 영역 | 처리 |
|---|---|---|
| A1 (HIGH) | SC-002 검증 강도 | code-implementer 가 service 단위 테스트 보강 또는 chaos 케이스로 자연스럽게 흡수 |
| C1 (MEDIUM) | FR-002 원자성 실패 케이스 | test-author 가 통합 테스트에 보강 가능 |
| U1 (MEDIUM) | schema.sql 적용 메커니즘 | code-implementer 가 Foundational Phase 에서 명시 결정 |
| U2/U3/I1 (LOW) | 부분 누락 케이스 / FakeConsumer Import / 본인 가드 응답 | 구현 단계에서 자연 보정 |

## 이벤트 로그

- 2026-06-03 — 하네스 시작. Phase 0 컨텍스트 확인 통과, `_harness/` 디렉토리 생성.
- 2026-06-03 — Phase 1 (T001) 완료. **plan 단계 결정 보정**: tasks.md 가 `app.verification.*` prefix 를 명시했지만 기존 코드베이스가 `verification.*` (`verification.publisher.type`, `verification.redis.stream-key`) 를 이미 사용 중이라 정합 위해 `verification.*` prefix 그대로 채택. 추가: `verification.callback-url`, `verification.threshold`. 부수 정정: `verification.redis.stream-key` 값을 `verification:stream` → `verify:requests` (BRIEF §3-1 컨슈머 계약 정합). dev/prod 는 application.yml 기본값 상속 + 필요 시 환경변수 override 패턴.

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
| 2. Foundational | T002~T009 (entity·repo·outbox payload·security·테스트 인프라) | done | 2026-06-03 | 2026-06-03 | phase2-impl-report.md (+ verify 회귀 fix: schema.sql → ApplicationRunner) |
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
| U1 (MEDIUM) | schema.sql 적용 메커니즘 | **확정**: ApplicationRunner 패턴 (`VerificationRecordSchemaInitializer`) 채택 — schema.sql 의 stored procedure 가 Spring ScriptUtils 와 호환 안 됨 (회귀로 확정). |
| U2/U3/I1 (LOW) | 부분 누락 케이스 / FakeConsumer Import / 본인 가드 응답 | 구현 단계에서 자연 보정 |

## 이벤트 로그

- 2026-06-03 — 하네스 시작. Phase 0 컨텍스트 확인 통과, `_harness/` 디렉토리 생성.
- 2026-06-03 — Phase 1 (T001) 완료. **plan 단계 결정 보정**: tasks.md 가 `app.verification.*` prefix 를 명시했지만 기존 코드베이스가 `verification.*` (`verification.publisher.type`, `verification.redis.stream-key`) 를 이미 사용 중이라 정합 위해 `verification.*` prefix 그대로 채택. 추가: `verification.callback-url`, `verification.threshold`. 부수 정정: `verification.redis.stream-key` 값을 `verification:stream` → `verify:requests` (BRIEF §3-1 컨슈머 계약 정합). dev/prod 는 application.yml 기본값 상속 + 필요 시 환경변수 override 패턴.
- 2026-06-03 — Phase 2 (T002~T009) 프로덕션 코드 + 테스트 인프라 구현 완료. `compileJava`/`compileTestJava`/`verifySecretLogScan` 모두 그린. 상세는 [phase2-impl-report.md](phase2-impl-report.md). 다음 단계: test-author 의 Phase 2 검토 → build-verifier(./gradlew check) → code-reviewer.
- 2026-06-03 — Phase 2 build-verifier 회귀 확정(phase2-verify.md). 근본 원인: 초기 schema.sql 의 `CREATE PROCEDURE ... BEGIN ... END;` 패턴이 Spring `ScriptUtils.executeSqlScript` 의 단순 `;` split 과 충돌. 16/16 통합 테스트 부팅 실패. **수정안 채택**: `VerificationRecordSchemaInitializer` (TestConfiguration + ApplicationRunner). schema.sql 삭제, `application-test.yml` 의 `spring.sql.init.mode`/`defer-datasource-initialization` 제거, `IntegrationTest` 베이스에 `@Import` 부착. 실측: `./gradlew test --tests "*OutboxRepublisher*"` (Spec 002) / `--tests "*MemberIntegrationTest"` (Spec 001) / 전체 `./gradlew check` 모두 BUILD SUCCESSFUL. `verifySecretLogScan` clean. **Phase 2 완료 확정 (Y)**.
- 2026-06-03 — Phase 2 code-reviewer 리뷰 완료(phase2-review.md). **Verdict: PASS** — P1 0건. 헌법 7원칙(I/II/IV/V) 신규/수정 코드 모두 직접 grep 으로 재검증, NON-NEGOTIABLE 위반 0건. **P2-1 (Phase 3 진입 게이트 조건)**: `OutboxRepublisher` 가 새 payload(memberId/planetId=null) 를 Spec 002 publisher 경로로 흘릴 때 stream entry 깨질 위험 — Phase 3 T023 publisher cutover 전에 회귀 검증 통합 테스트 필수. P2-2: `VerificationRecord` Javadoc 의 schema.sql 잔존 참조 정정. P3-1: `JwtInterceptor` 토큰 평문 로그가 `verifySecretLogScan` 정규식(`=` 요구)을 우회 — 별도 spec 으로 트래킹. **Phase 2 완료 (구현 + 검증 + 리뷰 all green) — Phase 3 진입 가능**.

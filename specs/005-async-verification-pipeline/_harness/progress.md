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
| 3. US1 (P1 MVP) | T010~T024 (테스트 2 + 구현 13) | done | 2026-06-03 | 2026-06-04 | phase3-impl/test/review.md (+ P1-1 stream key 정합 fix + P2-A REQUIRES_NEW fix) |
| 4. US2 (P1) | T025~T026 (chaos·outbox republisher 정합) | done | 2026-06-04 | 2026-06-04 | phase4-test-report.md · phase4-verify.md · phase4-review.md (옵션 C-2 fix · PASS) |
| 5. US3 (P2) | T028 (일별 멱등) — Codex P1 fix 포함. T027·T029 미수행 | partial | 2026-06-04 | 2026-06-04 | VerificationDailyIdempotencyTest + 조회-후-저장 cutover |
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
- 2026-06-03 — Phase 3 (US1) **production 구현(T012~T024) 완료**. 상세는 [phase3-impl-report.md](phase3-impl-report.md). 핵심 결정: (a) T022 신규 응답 DTO `VerificationAcceptedDto` 채택, (b) T023 R-008 cutover — `eventService.publish(VerificationEvent)` 제거 + 신규 AFTER_COMMIT 경로(`OutboxEventRecordedEvent` + `VerificationOutboxPublishListener`) 도입으로 Redis Stream 발행 끊김 방지(P2-1 부분 해소), (c) 본인 가드 정책 404 통일(analyze I1), (d) controller-local `@ExceptionHandler` 로 callback 분기 위반의 400 응답(전역 401 핸들러 우회). 실측: `./gradlew check` BUILD SUCCESSFUL (22s), `verifySecretLogScan` clean, Redis 직접 호출 0, 시크릿 키워드 0. **잔여 P2-1**: BRIEF §3-1 키 cutover(Option B) 는 본 phase 범위 외, Polish 또는 별도 PR. 다음 단계: test-author 의 T010(slice) + T011(integration) 작성 → build-verifier 전체 재검증 → code-reviewer.
- 2026-06-03 — Phase 3 (US1) **test 작성(T010·T011) 완료**. 상세는 [phase3-test-report.md](phase3-test-report.md). T010 (`VerificationControllerSliceTest`, SC-002) — `@WebMvcTest` + `verifyNoInteractions` 로 응답 경로의 외부 추론/발행 어댑터 호출 0 검증 (analyze A1 보강). T011 (`VerificationAsyncFlowIntegrationTest`, SC-001) — `IntegrationTest` 상속 + `FakeVerificationConsumer` + TestRestTemplate + JwtTokenProvider 로 단대단 흐름 검증. 실측: 두 테스트 모두 그린, `./gradlew check` BUILD SUCCESSFUL (25s — Spec 001/002 회귀 0). **C1 보강은 N (사유: `@SpyBean` 컨텍스트 캐시 분기, 별도 클래스 권장)**. **결함 후보 보고**: outbox status PUBLISHED 전이가 통합 환경에서 미관측 — `[Redis Stream] published` 로그 정상 출력에도 `outbox_event.status` PENDING 잔존. 본 phase 의 SC-001 검증은 row 존재만으로 축소하고 status 전이 정합은 SC-004 (Phase 4) 진입 시 재검증. 다음 단계: build-verifier 의 Phase 3 전체 회귀 게이트 → code-reviewer.
- 2026-06-03 — Phase 3 (US1) **code-reviewer 리뷰 완료**(phase3-review.md). **Verdict: CHANGES_REQUESTED** — P1-1 1건 발견: `VerificationRedisStreamPublisher` 가 stream entry 키 `eventId/memberId/planetId/standardImg/targetImg` 5개로 발행 — BRIEF §3-1 컨슈머 계약(`requestId/standardImgUrl/targetImgUrl/callbackUrl/threshold`) 과 불일치. FakeVerificationConsumer 폴백으로 테스트 그린이지만 실 컨슈머 운영 파싱 실패. P1-1 fix: `MessageCommand` 에 `callbackUrl/threshold` 추가, publisher 가 BRIEF 정합 키로 발행, 호출처(`VerificationOutboxPublishListener` `@Value` 주입, `VerificationOutboxPayload.toMessageCommand`, `VerificationEvent.toMessageCommand` dead path) 4곳 정합, publisher 단위 테스트 새 키 set 으로 업데이트. P2-2 (`VerificationRecord` Javadoc schema.sql 참조) 정정. **재검증**: `./gradlew check` BUILD SUCCESSFUL (26s).
- 2026-06-04 — Phase 3 **P2-A (outbox status PUBLISHED 전이 누락) 진단·fix 완료**. T011 에 outbox status=PUBLISHED await assertion 추가해 Before/After 비교 실측. 가설 1 입증 — publisher 의 `@Transactional`(REQUIRED 기본) 이 `AFTER_COMMIT` 리스너 컨텍스트(메인 트랜잭션 closed) 안에서 새 트랜잭션을 의도대로 활성화하지 못해 `findById` 가 detached entity 반환 → `published()` 의 dirty 변경 flush 0 → status PENDING 잔존. **fix**: `VerificationRedisStreamPublisher.publish` 에 `@Transactional(propagation = Propagation.REQUIRES_NEW)` 명시. 재검증: `VerificationAsyncFlowIntegrationTest` BUILD SUCCESSFUL (7s, PUBLISHED 전이 확인), `./gradlew check` BUILD SUCCESSFUL (26s, 회귀 0). **Phase 3 완료 확정 — Phase 4 진입 가능**.
- 2026-06-04 — Phase 4 (US2) **테스트 작성(T025·T026) 완료**. 상세는 [phase4-test-report.md](phase4-test-report.md). T025 (`VerificationConsumerOutageIntegrationTest`, SC-003) — `IntegrationTest` + `FakeVerificationConsumer.stop()`/`start()` 로 chaos 시나리오 재현: 10건 발행 중 일부 처리 후 stop → 5건 추가 발행 모두 202 (실패율 0%) + PENDING 보존 → start 재기동 → Awaitility 로 모두 종착(SUCCESS) 도달 확인. T026 (`VerificationOutboxRepublisherIntegrationTest`, SC-004) — `@TestConfiguration @Bean @Primary FailFirstThenDelegatePublisher` 페이크 publisher 주입: 첫 publish 호출 흡수 → outbox PENDING 잔존 → `OutboxRepublisher.republishPending()` 직접 호출 (Spec 002 `outbox.republisher.enabled=false` 정합) → 페이크 두 번째 호출에서 stream 발행 + outbox markPublished → FakeConsumer callback → SUCCESS 종착. **★ production 결함 발견**: `VerificationRedisStreamPublisher.publish()` 의 `@Transactional(REQUIRES_NEW)` (Phase 3 P2-A fix) 가 `OutboxRepublisher.republishPending()` 의 외부 TX + SKIP LOCKED 안에서 호출되면 같은 outbox row 의 dirty UPDATE 시 lock wait timeout (deadlock-like) — 운영에서 PENDING outbox 가 영원히 복구되지 못해 헌법 IV At-Least-Once 위반 위험. fix 권장 옵션 3가지를 phase4-test-report §5 에 보고. 본 phase 는 production 코드 수정 금지이므로 페이크가 Spec 002 stub publisher 패턴 (stream 발행 + outbox.published() 직접) 으로 deadlock 우회 + SC-004 본질 검증. 실측: 두 테스트 모두 그린, `./gradlew check` BUILD SUCCESSFUL (30s, 회귀 0), `verifySecretLogScan` clean. 다음 단계: build-verifier 의 Phase 4 전체 재검증 → code-reviewer (production 결함 §5 처리 결정 포함).
- 2026-06-04 — Phase 4 **옵션 C-2 production fix 적용**. test-author 가 발견한 `VerificationRedisStreamPublisher.publish()` 의 `@Transactional(REQUIRES_NEW)` × `OutboxRepublisher` SKIP LOCKED self-deadlock 결함을 책임 분리로 해소. **변경 요지**: (1) `VerificationRedisStreamPublisher` 에서 `@Transactional` 제거 — Redis XADD 만 책임. (2) 신규 `OutboxPublishingHelper` (no `@Transactional`) — `publish()` + `outboxRepository.findById().published()` 묶음. (3) `VerificationOutboxPublishListener.publishAfterCommit` 에 `@Transactional(propagation = REQUIRES_NEW)` 부착 — AFTER_COMMIT 회색지대를 새 EntityManager 로 우회. (4) `OutboxRepublisher.republishOne` 이 헬퍼를 직접 호출 — 본인의 외부 `@Transactional` 을 헬퍼에 상속시켜 self-deadlock 0 + dirty checking 작동. **회귀 테스트 정합**: Spec 002 `OutboxRepublisherIntegrationTest.transientFailureKeepsPendingThenSucceedsOnRetry` 의 `doNothing()` → `doThrow(RuntimeException)` 로 신규 publisher 계약(예외=실패) 반영. **CLAUDE.md 갱신**: 운영 함정 섹션 신설 — AFTER_COMMIT 회색지대 + REQUIRES_NEW × SKIP LOCKED self-deadlock 두 함정 박제. 다음 단계: build-verifier 의 Phase 4 전체 재검증 → code-reviewer.
- 2026-06-04 — Phase 4 **build-verifier 회귀 PASS**(phase4-verify.md). `./gradlew clean check` BUILD SUCCESSFUL (32s, 170/170), `verifySecretLogScan` clean. 회귀 4종(VerificationAsyncFlow / VerificationConsumerOutage / VerificationOutboxRepublisher / Spec 002 OutboxRepublisher) 모두 그린. fix 정합 grep 4 invariant 모두 충족: publisher 의 `@Transactional` 0, helper 의 `@Transactional` 0, OutboxRepublisher 의 messagePublisher 의존성 0, listener 의 `@TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW)` 부착. Phase 4 종료 가능.
- 2026-06-04 — Phase 4 **code-reviewer 리뷰 완료**(phase4-review.md). **Verdict: PASS** — P1 0건. 책임 분리(publisher 어댑터 / helper / listener·republisher 트랜잭션 경계) 가 본질적으로 옳고 헌법 II·IV 정합 강화. NON-NEGOTIABLE (I/IV/V) 위반 0. P2 3건(P2-1 listener 의 RuntimeException 흡수 광범위·P2-2 helper findById 부재 silent·P2-3 publish failure log.error 격하 권장), P3 2건(P3-1 헬퍼 메서드명 일반화·P3-2 dead listener 정리) — 모두 Phase 5/Polish 처리 가능. §3.2 race condition (AFTER_COMMIT 리스너 ↔ Republisher 동시 진입) 은 본 fix 무관, At-Least-Once 의도된 부산물, 컨슈머 멱등 흡수. **Phase 4 완료 확정 — Phase 5 진입 가능**.
- 2026-06-04 — **리팩터링**: `OutboxRepublisher` 재발행 성공 카운팅(`succeeded`) 제거 — 사이클 로그에만 쓰이던 잉여. `republishOne` 반환 타입 `boolean → void`. 발행 결과는 outbox status(PENDING 잔존 시 재시도) 로 이미 표현됨. (commit 별도)
- 2026-06-04 — **Phase 5 부분 진행 + Codex P1 발견·수정**. PR 준비(T033 헌법 VI) 차 `codex review --base dev` 실행 → **P1 1건 발견**: `VerificationResultService.handleCallback` 의 `VerificationRecord` 저장 `DataIntegrityViolationException` try/catch 가 무력. T028(`VerificationDailyIdempotencyTest`) 을 작성해 실측 → **결함 확인(RED)**. 메커니즘: IDENTITY 즉시 INSERT 라 catch 는 정상 진입하지만, `DataIntegrityViolationException` 발생 순간 Spring 이 트랜잭션을 rollback-only 로 마킹 → 바깥 `@Transactional` commit 시 `UnexpectedRollbackException` → HTTP 500 + status 전이까지 롤백 → 컨슈머 재시도 폭주. (Codex 의 *메커니즘 설명*("flush 가 catch 밖에서 터진다") 은 틀렸으나 *결론*은 정확.) **fix(사용자 결정 — 조회-후-저장, 동시성 미고려)**: (1) `VerificationRecordRepositoryCustom.existsTodayRecord` 신규 — verified 무관·unique 제약과 동일 키(member·planet·날짜) 조회. (2) `persistVerificationRecord` 가 저장 전 `existsTodayRecord` 로 존재 확인 → 있으면 skip, 없으면 save. try/catch·`DataIntegrityViolationException` import 제거. (3) T028 은 직렬 시나리오만(동시 race `@RepeatedTest` 제거 — US3 동시성 미발생 전제). **문서 정합**: spec.md(§Clarification·edge case) + tasks.md(T028 정의·SC-005b 매핑) 의 "동시 race" 문구를 조회-후-저장+직렬 전제로 갱신. **검증**: T028 직렬 그린, `./gradlew check` BUILD SUCCESSFUL (29s, 회귀 0), `verifySecretLogScan` clean. **T027(requestId 멱등)·T029(컨트롤러 slice) 는 미수행** — PR known gap. 다음: 커밋 + PR(T033).

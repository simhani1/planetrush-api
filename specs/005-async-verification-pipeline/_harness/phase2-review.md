# Phase 2 (Foundational) — Claude Code Review

**Spec**: 005-async-verification-pipeline · **Branch**: `005-async-verification-pipeline`
**Date**: 2026-06-03 · **Scope**: T002~T009 (8 tasks, Foundational)
**Reviewer**: Claude (code-reviewer 에이전트, 헌법 VI 듀얼 AI 리뷰의 Claude 측)

## Verdict

**PASS** — P1 0건. P2 1건은 Phase 3 cutover 작업의 사전 검증 게이트로 escalation.

build-verifier 가 `./gradlew check` 그린(16/16 회귀 0) + `verifySecretLogScan` 그린을 확정. 신규/수정 production 코드 6 파일에 헌법 II/IV/V NON-NEGOTIABLE 직접 위반 0건을 직접 grep 으로 재검증.

## 헌법 7원칙 점검 결과

| 원칙 | 결과 | 근거 |
|---|---|---|
| I. Testcontainers (NON-NEGOTIABLE) | OK | `IntegrationTest:34`이 `@Import(VerificationRecordSchemaInitializer.class)`를 단 채로 그대로 MySQL/Redis Testcontainers 위에서 부팅. `VerificationRecordSchemaInitializer.java:53` 의 `DataSource.getConnection()` 은 컨테이너 JDBC 만 사용 — H2/임베디드 가정 없음. testsupport 패키지에 `@SpringBootTest` 추가 사용/H2 의존 grep 0건. |
| II. 외부 의존 어댑터 격리 (production 한정) | OK | `src/main/java/com/planetrush/planetrush/verification`·`outbox` 에 `RedisTemplate`/`opsForStream`/`XADD`/`RestClient`/`AmazonS3`/`WebClient` 직접 호출 **0건 매칭** (이미 분리된 `infra/publisher/VerificationRedisStreamPublisher` 제외). `VerificationExternalEventRecorder.java:60` 은 `OutboxRepository` 만 호출. 테스트 인프라(`FakeVerificationConsumer`, `VerificationRecordSchemaInitializer`)는 어댑터 규칙 비대상(test scope). |
| III. QueryDSL Projections | DEFERRED | `VerificationRequestRepository.java:15` 는 기본 `JpaRepository<VerificationRequest, String>` 만 노출. custom interface 결합은 Phase 3 T014 — tasks.md 가 명시적으로 허용. `VerificationRequest` 의 생성자/빌더는 `Projections.constructor()` 매핑이 가능한 시그니처(필드명 일치 + non-final). |
| IV. Outbox 강제 (NON-NEGOTIABLE) | OK | `VerificationExternalEventRecorder.save()` 가 `outboxRepository.save(OutboxEvent.pending(...))` 만 수행, stream 발행은 AFTER_COMMIT 리스너(`VerificationRedisStreamPublisher`) 에 위임 — 같은 트랜잭션 내 Redis 직접 호출 0. `VerificationOutboxPayload` 변경은 역직렬화 record 만, 브로커 호출 없음. |
| V. 시크릿 로그 금지 (NON-NEGOTIABLE) | OK | 신규/수정 6 파일(`VerificationRequestStatus`/`VerificationRequest`/`VerificationRequestRepository`/`OutboxRecordCommand`/`VerificationExternalEventRecorder`/`VerificationOutboxPayload`) + 테스트 인프라 2 파일에 `secret`/`token`/`password`/`jwt`/`credential` 평문 0 매칭. `VerificationExternalEventRecorder.java:59` 의 로그는 `command.requestId()` UUID 만 출력 (callback URL/threshold 평문 미출력). `FakeVerificationConsumer.java:285` 의 callback 실패 로그도 `requestId` 만. `verifySecretLogScan` 게이트 그린. |
| VI. 듀얼 AI 리뷰 | IN PROGRESS | 본 문서가 Claude 측 리뷰. PR 단계에서 Codex 리뷰와 짝을 이룸. |
| VII. 인수 기준 자동 테스트 | N/A | Phase 2 는 Foundational — SC 직접 매핑 없음. Phase 3+ 에서 검증. |

## P1 (머지 차단)

**없음.**

## P2 (권장 — Phase 3 진입 전 반드시 해소)

### P2-1. Spec 002 republisher 회귀 위험 — 새 payload 의 `memberId/planetId=null` 이 stream 에 `"null"` 문자열로 적재될 가능성

- **위치**: `src/main/java/com/planetrush/planetrush/outbox/republisher/VerificationOutboxPayload.java:39-47` + `src/main/java/com/planetrush/planetrush/infra/publisher/VerificationRedisStreamPublisher.java:38-39`
- **경계면**: 
  - 왼쪽(생산자): `VerificationExternalEventRecorder.buildPayload()` 가 BRIEF §3-1 정합 새 payload `{requestId, standardImgUrl, targetImgUrl, callbackUrl, threshold}` 만 outbox 적재. `memberId/planetId` 는 **payload 에 없음**.
  - 오른쪽(소비자): `OutboxRepublisher.republishOne()` 이 PENDING outbox 를 `VerificationOutboxPayload` 로 역직렬화 — `memberId/planetId` 는 `null`. → `payload.toMessageCommand(event)` 가 `MessageCommand(memberId=null, planetId=null)` 생성 → `VerificationRedisStreamPublisher.publish()` 의 `String.valueOf(command.memberId())` 가 `"null"` 문자열로 변환되어 Redis Stream 에 적재. 컨슈머 계약(BRIEF §3-1)의 `requestId/standardImgUrl/targetImgUrl/callbackUrl/threshold` 키 명과도 불일치.
- **트리거 시나리오**: `outbox.republisher.enabled=true`(prod). BEFORE_COMMIT 리스너가 새 payload 로 outbox 적재 후, AFTER_COMMIT publish 가 실패(또는 publish 직전 크래시)하면 PENDING 잔존 → republisher 폴러가 잡아 다음 사이클에 위 깨진 stream entry 발행. 컨슈머는 `eventId/memberId="null"` 메시지를 받아 callback 무력화.
- **현재 상태**: `phase2-impl-report.md` §2 T005 마지막 단락이 이 위험을 명시 — "Spec 005 신규 흐름의 stream 발행 ... 은 Phase 3 의 publisher 변경에서 다뤄야 한다. 본 phase 의 산출물은 컴파일·기존 회귀 안전이며, 새 stream 키 정합은 Phase 3 작업."
- **수정 방향 (Phase 3 T023 게이트)**: 
  1. **Option A** (권장) — Spec 005 의 신규 EventType(`VERIFY_REQUEST_V2` 등)을 도입해 `OutboxRepublisher` 가 EventType 별로 처리 분기(`findRepublishableForUpdateSkipLocked` 에 eventType 필터 추가, payload record/publisher 도 분기). Phase 3 cutover 시 기존 Spec 002 흐름과의 격리 보장.
  2. **Option B** — `VerificationRedisStreamPublisher.publish()` 가 BRIEF §3-1 키 명으로 발행하도록 변경 + `VerificationOutboxPayload` 의 `memberId/planetId` 필드 제거. 단 Spec 002 의 다른 호출(`VerificationExternalMessageListener` → `event.toMessageCommand()` → `MessageCommand(memberId, planetId)` 보유)도 동시에 마이그레이션 필요.
- **판정**: tasks.md 가 Phase 3 T023 에 publisher cutover 위임을 명시한 dead path 위험. **P1 차단은 부적절** — Phase 2 의 자체 게이트(`./gradlew check` + secret scan) 는 통과. 그러나 Phase 3 진입 시 반드시 본 회귀 경로를 검증할 통합 테스트(stream entry 내용 단언)를 추가해야 함.

### P2-2. `VerificationRecord` Javadoc 의 schema.sql 잔존 참조 — 문서-코드 불일치

- **위치**: `src/main/java/com/planetrush/planetrush/verification/domain/VerificationRecord.java:36-37`
- **내용**: Javadoc 이 `"테스트는 src/test/resources/schema.sql 자동 적용으로 해결한다. (quickstart §6-2 참조.)"` 라고 명시하지만, phase2-verify.md 의 회귀 fix 로 `schema.sql` 은 폐기되고 `VerificationRecordSchemaInitializer`(ApplicationRunner) 로 교체됨.
- **수정 방향**: 해당 문장을 `"테스트는 VerificationRecordSchemaInitializer(ApplicationRunner) 가 부팅 시 적용한다."` 로 수정. 운영 적용 경로(`schema-mysql-uniq.sql`)는 그대로 유지.

## P3 (제안 — 선택적 개선)

### P3-1. `verifySecretLogScan` 게이트 우회 — `JwtInterceptor` 의 토큰 평문 로그

- **위치**: `src/main/java/com/planetrush/planetrush/core/interceptor/JwtInterceptor.java:47, 50`
- **본 phase 변경 대상 외**(미수정 파일)이지만 발견 — `log.info("토큰 사용 가능 : {}", token);` / `log.info("토큰 사용 불가능 : {}", token);` 가 JWT 토큰 평문을 SLF4J placeholder 로 출력. 헌법 V 위반 소지.
- **게이트 우회 원인**: `build.gradle:87` 의 정규식 `log\.(info|debug|warn)\(.*(secret|token|password|jwt|credential).*=.*\)` 가 `=` 기호를 필수로 요구하지만, SLF4J `{}` placeholder 패턴은 `=` 가 없어 매칭 실패.
- **수정 방향(별도 spec)**: 게이트 정규식에서 `=` 요구를 제거하거나, 키워드 매치 + 변수 참조(`,`로 구분된 추가 arg) 로 변경. 본 phase 범위 밖이므로 후속 spec(예: Spec 001 보강) 으로 트래킹.

### P3-2. `IntegrationTest` 의 미사용 `@LocalServerPort`

- **위치**: `src/test/java/com/planetrush/planetrush/IntegrationTest.java:60-61`
- `private int port` 가 abstract base 에 선언되지만 base 내에서 사용처 없음. 자식 테스트들이 자체 `@LocalServerPort` 를 재선언하면 base 의 필드가 영구 unused. 본 phase 변경(`@Import` 부착)과 직접 관련 없으나 base 클래스 의도가 모호. 향후 callback 통합 테스트에서 자식이 base 의 `getPort()` accessor 를 호출하는 패턴으로 정리 가능.

### P3-3. `FakeVerificationConsumer.postCallback` 의 `RestTemplate` 타임아웃

- **위치**: `src/test/java/com/planetrush/planetrush/verification/testsupport/FakeVerificationConsumer.java:85`
- `new RestTemplate()` 이 기본 `connectTimeout`/`readTimeout=infinite`. 통합 테스트가 chaos 시나리오(서버 응답 지연 모의) 에서 매달릴 위험. `SimpleClientHttpRequestFactory` 에 `connectTimeout=2s`/`readTimeout=5s` 정도 설정 권장.

## 인수 기준 정합성

Phase 2 는 Foundational — spec.md SC 와 직접 매핑되지 않는다. Phase 3+ 에서 다음 SC 가 테스트로 박제될 예정:
- SC-001 (P95 응답 시간) → 비기능 — Phase 3 이후 측정.
- SC-002 (성공률) — Phase 3 통합 테스트.
- SC-003 (chaos 컨슈머 다운 복구) — `FakeVerificationConsumer.stop()/start()` API 가 본 phase 산출. Phase 3 테스트가 호출.
- Clarification Q1-Q3 — Phase 3 통합 테스트가 검증.

Phase 2 의 책무는 위 SC 들이 의지할 entity/repository/payload/callback whitelist/test infrastructure 의 안정적 시그니처 제공이며, 본 리뷰에서 시그니처 호환성을 확인.

## 잘된 점

- **회귀 fix 의 ApplicationRunner 패턴 선택 (P0 차단 해소)**: phase2-verify.md 가 진단한 `ScriptUtils` 단순 `;` split 충돌에 대해 `INFORMATION_SCHEMA` 가드 기반 멱등 ALTER 발행으로 견고하게 풀어냄. Testcontainers `withReuse(true)` 시나리오 안전.
- **`@JsonAlias` 보강의 사전 차단**: payload 키 명 변경(`verificationImgUrl → targetImgUrl`)이 Spec 002 의 `OutboxRepublisher` 역직렬화 회귀를 일으킬 수 있다는 점을 implementer 가 자가 점검 + `@JsonAlias({"targetImgUrl","verificationImgUrl"})` 양방향 호환으로 미리 차단. 단 P2-1 의 `memberId/planetId=null` 회귀는 별개 — Phase 3 에서 해소 필수.
- **시그니처 안정성**: `OutboxRecordCommand` 에 `requestId` 를 선두 추가하면서 기존 `eventId` 필드를 보존해 호출처 1곳(`VerificationEvent.toRecordCommand()`) 의 임시 호환 경로를 닫지 않고 점진 마이그레이션 — Phase 3 cutover 의 부담을 줄였다.
- **헌법 V 일관성**: 신규/수정 코드의 모든 로그가 `requestId` UUID 만 출력. payload/callback URL/threshold 평문 미출력. `verifySecretLogScan` 우회 가능 경로 (예: `MDC.put` 사용) 도 0건.

## 다음 단계 권장 (Phase 3 진입 시 우선 점검)

1. **P2-1 회귀 검증 통합 테스트**: Spec 005 신규 흐름이 outbox 적재 → publish 실패 → republisher 재발행 시 stream entry 의 키/값이 컨슈머 계약과 일치하는지(또는 republisher 가 EventType 분기로 새 흐름을 처리하는지) 단언. 본 테스트 부재 시 Phase 3 진입 금지.
2. **P2-2 Javadoc 정정**: `VerificationRecord` Javadoc 의 schema.sql 참조를 ApplicationRunner 패턴으로 갱신. quickstart §6-2 도 동일 정합.
3. **`VerificationRequest` Projections 적용 (T014)**: 빌더/생성자가 `Projections.constructor` 매핑 가능한 시그니처임을 직접 테스트로 단언.
4. **`FakeVerificationConsumer` 사용처 회귀 점검**: `@Import` 누락 시 컴파일 에러가 아닌 런타임 NoSuchBeanDefinitionException 으로 잡힘 — Phase 3 의 첫 callback 테스트에서 명시적 `@Import(FakeVerificationConsumer.class)` 확인.

## 진행 보고

- 본 리뷰는 Phase 2 만 다룬다. Phase 3~5 진행 후 `claude-review.md` 로 통합 예정.
- `progress.md` 의 Phase 2 리뷰 task 를 `completed` 로 갱신 가능. P2-1 은 Phase 3 진입 게이트 조건으로 별도 트래킹.

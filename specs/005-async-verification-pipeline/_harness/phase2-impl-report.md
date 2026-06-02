# Phase 2 (Foundational) — Implementation Report

**Spec**: 005-async-verification-pipeline · **Branch**: `005-async-verification-pipeline`
**Date**: 2026-06-03 · **Scope**: T002~T009 (8 tasks)
**Status**: 구현 완료, 본 Phase 의 다음 단계(검증/리뷰)로 이관 가능.

---

## 1. 변경/추가된 파일 (절대 경로)

### Production code — 신규 (5)

- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/domain/VerificationRequestStatus.java` — enum PENDING/SUCCESS/FAIL/ERROR (T002).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/domain/VerificationRequest.java` — entity (T003).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/repository/VerificationRequestRepository.java` — JpaRepository placeholder (T006).
- `/Users/simjonghan/source_code/planetrush-api/src/main/resources/schema-mysql-uniq.sql` — 운영 적용 DDL 참고용 (T007 보조).

### Production code — 수정 (5)

- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/outbox/dto/OutboxRecordCommand.java` — `requestId` 필드 선두 추가 (T004).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/outbox/VerificationExternalEventRecorder.java` — payload BRIEF §3-1 정합 (T005).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/outbox/republisher/VerificationOutboxPayload.java` — `@JsonAlias({"targetImgUrl","verificationImgUrl"})` 추가, `requestId/callbackUrl/threshold` 필드 추가 (T005 정합 보강 — Spec 002 회귀 차단).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/service/dto/VerificationEvent.java` — `toRecordCommand()` 가 새 record 시그니처 호환 (T004 임시 보정 — Phase 3 T023 에서 호출 자체 제거 예정).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/verification/domain/VerificationRecord.java` — `@UniqueConstraint(name="uniq_verification_record_member_planet_date", columnNames={"member_id","planet_id","upload_date_only"})` 추가 (T007).
- `/Users/simjonghan/source_code/planetrush-api/src/main/java/com/planetrush/planetrush/core/config/WebConfig.java` — JwtInterceptor `excludePathPatterns` 에 `/api/v1/internal/**` 추가 (T008).

### Configuration — 수정 (1)

- `/Users/simjonghan/source_code/planetrush-api/src/main/resources/application-test.yml` — `spring.sql.init.mode=always` + `spring.jpa.defer-datasource-initialization=true` 추가 (T007 보조).

### Test infrastructure — 신규 (2)

- `/Users/simjonghan/source_code/planetrush-api/src/test/resources/schema.sql` — generated column + unique constraint 멱등 ALTER 스크립트 (T007).
- `/Users/simjonghan/source_code/planetrush-api/src/test/java/com/planetrush/planetrush/verification/testsupport/FakeVerificationConsumer.java` — XREADGROUP 폴링 + RestTemplate callback (T009).

---

## 2. 태스크별 핵심 결정 사항

### T002 — `VerificationRequestStatus`
- 단순 enum. data-model 의 4 상태 그대로. Javadoc 에 라이프사이클·ERROR 정책 포함.

### T003 — `VerificationRequest` entity
- `@Id String(36)` UUID, `@CreationTimestamp createdAt`, 결과 필드 nullable, `@Enumerated(EnumType.STRING)` 명시(Spec 002 OutboxEvent.status ORDINAL 버그 회피).
- 빌더 + `@NoArgsConstructor(access=PROTECTED)` — 기존 entity 컨벤션 일관.
- `Member`/`Planet` 외래키 미사용 — data-model.md §외래키 미사용 메모 반영.
- 정적 팩토리 `pending(...)` 추가 — 서비스 레이어가 UUID 외부 주입 + status=PENDING 으로 새 요청을 깔끔하게 생성하도록.

### T004 — `OutboxRecordCommand` 호환 처리
- record 시그니처: `(requestId, eventId, targetImg, standardImg, memberId, planetId)`.
- 기존 필드 `eventId` 도 유지(점진 마이그레이션). Phase 3 T023 에서 호출처가 `requestId` 일원화 시 `eventId` 제거 검토 권장.
- 임시 호환: 유일 caller 인 `VerificationEvent.toRecordCommand()` 가 deterministic UUID 1개를 `requestId/eventId` 양쪽에 동일 주입. 동작 변경 없음.
- 호출처 사이즈 점검: `grep -rn "new OutboxRecordCommand\|toRecordCommand"` → 단일 caller 1곳만 (`VerificationEvent.java:16`).

### T005 — `VerificationExternalEventRecorder`
- `save()` 의 OutboxEvent.id 를 `command.requestId()` 로 사용 (R-004 — entity·outbox·stream·callback 4면 동일 UUID).
- `buildPayload` 키: `requestId`, `standardImgUrl`, `targetImgUrl`, `callbackUrl`, `threshold` (BRIEF §3-1). 기존 `verificationImgUrl` 키 명을 `targetImgUrl` 로 변경.
- `@Value` 키 prefix: **tasks.md 가 `app.verification.callback-url` 을 명시**했지만 **기존 application.yml 이 `verification.callback-url` prefix 를 사용** 중이라 정합 위해 후자 채택 (Phase 1 의 진행 보정과 동일 정책). 키: `${verification.callback-url}`, `${verification.threshold:0.088}`.
- 헌법 V — 로그는 `command.requestId()` 하나만 출력(이미 UUID, 평문 시크릿 키워드 없음). payload 평문 미출현.

### T005 정합 보강 — `VerificationOutboxPayload`
- Spec 002 의 `OutboxRepublisher` 가 사용하는 역직렬화 record. 키 명 변경(`verificationImgUrl → targetImgUrl`) 으로 기존 발행된 outbox event 가 republisher 사이클에서 `targetImgUrl=null` 로 매핑되는 회귀 우려.
- 결정: `@JsonAlias({"targetImgUrl", "verificationImgUrl"})` 로 신/구 키 양쪽 인식. `requestId/callbackUrl/threshold` 필드도 record 에 추가(역직렬화 손실 0). `memberId/planetId` 는 신규 payload 에 없으므로 nullable 로 둠.
- 호환 메모: Spec 002 흐름의 발행은 `toMessageCommand()` 를 호출해 기존 stream 키 명(`eventId/standardImg/targetImg/memberId/planetId`)으로 발행한다. Spec 005 신규 흐름의 stream 발행 (BRIEF §3-1 정합 — `requestId/standardImgUrl/targetImgUrl/callbackUrl/threshold`) 은 Phase 3 의 publisher 변경에서 다뤄야 한다. **본 phase 의 산출물은 컴파일·기존 회귀 안전이며, 새 stream 키 정합은 Phase 3 작업.**

### T006 — `VerificationRequestRepository`
- 기본 `JpaRepository<VerificationRequest, String>` 만. Custom 인터페이스 결합은 Phase 3 T014 에서 (헌법 III QueryDSL Projections).

### T007 — `VerificationRecord` unique constraint
- entity `@Table(uniqueConstraints=...)` 추가 (T007 본문 명시).
- Generated column `upload_date_only DATE GENERATED ALWAYS AS (DATE(upload_date)) STORED` 는 entity 매핑 없음 — hibernate `ddl-auto` 가 자동 생성 불가.
- **테스트 schema.sql 적용 방식**:
  - `spring.sql.init.mode=always` + `spring.jpa.defer-datasource-initialization=true` 조합 — hibernate ddl-auto(update) 이후 schema.sql 실행.
  - hibernate 는 entity 에 매핑되지 않은 `upload_date_only` 컬럼을 만들지 못하고, unique constraint 도 emit 하지 않는다 (columnNames 중 미매핑 컬럼은 무시 또는 skip). schema.sql 이 두 단계 모두 ALTER 로 적용.
  - Testcontainers `withReuse(true)` 정합: INFORMATION_SCHEMA 조회 후 dynamic ALTER 의 **stored procedure** 패턴으로 멱등 처리(`CREATE PROCEDURE ... IF NOT EXISTS ... CALL ... DROP PROCEDURE`).
- **운영 적용**: `src/main/resources/schema-mysql-uniq.sql` 참고용 DDL. application.yml `spring.sql.init.mode` 기본은 그대로 `never` — 운영자 수동 적용(quickstart §6-2).
- 사전 점검 SQL 은 운영 DDL 파일 헤더 주석에 명시.

### T008 — JwtInterceptor whitelist
- 코드베이스 점검: Spring Security 미사용. `JwtInterceptor`(HandlerInterceptor) + `@RequireJwtToken` 어노테이션 + AOP 가 인증 책임.
- `WebConfig.addInterceptors` 의 `excludePathPatterns` 리스트에 `/api/v1/internal/**` 추가 — JwtInterceptor 가 컨슈머 callback 진입을 통과시킴.
- 의도: PoC 단계 경로 분리만(R-005). 외부 망 진입 차단은 ALB/Nginx 인프라 책임.

### T009 — `FakeVerificationConsumer`
- **노출 방식**: `@TestComponent` 부착. analyze U3 보강 — `@TestComponent` 가 자동 스캔되지 않으므로 통합 테스트 클래스가 `@Import(FakeVerificationConsumer.class)` 로 명시 import 해야 함을 Javadoc 에 명시.
- **lombok 미사용**: test scope 에 lombok 의존성이 없음(build.gradle 의 compileOnly 만 main scope). `@RequiredArgsConstructor`/`@Slf4j` 대체로 직접 생성자 + `LoggerFactory.getLogger(...)` 사용.
- **API**: `start()`/`stop()`/`processOne(Duration)`/`runUntilProcessed(int, Duration)`/`clearGroup()`/`processedCount()`. 결과 주입: `setNextResult(CallbackResult)`/`enqueueResults(List)`.
- **callback URL 주입**: 테스트가 `@LocalServerPort` 로 받은 port 를 `setCallbackBaseUrl("http://localhost:<port>")` 로 주입. stream entry 의 `callbackUrl` 키는 무시(테스트 환경에선 dummy URL 이므로).
- **chaos 지원**: `stop()` 후 stream 메시지가 누적, `start()` 재호출 시 누적분이 lastConsumed 위치부터 순차 소비됨.
- **헌법 V**: callback 실패 로그에 `requestId` 만 출력. payload 본문 평문 미출현.
- **헌법 II 정합 메모**: 본 컴포넌트는 테스트 인프라이며 production 의 Redis 직접 호출 금지(IV) 와 충돌 없음 — production verification/outbox 도메인 코드에 신규 RedisTemplate 호출 0건 (게이트 통과).

---

## 3. 빌드/게이트 결과

### `./gradlew compileJava`
- 결과: BUILD SUCCESSFUL (7s, 단일 task 실행).

### `./gradlew compileTestJava`
- 결과: BUILD SUCCESSFUL (917ms). 초기 시도에서 lombok 미존재(test scope) 컴파일 실패 발생 → FakeVerificationConsumer 의 lombok 사용 제거로 해결.

### `./gradlew verifySecretLogScan`
- 결과: BUILD SUCCESSFUL — `✓ secret log scan clean`.

### Production 영역 헌법 IV 게이트
- `grep -rn "RedisTemplate\|opsForStream\.add\|XADD" src/main/java/com/planetrush/planetrush/verification src/main/java/com/planetrush/planetrush/outbox`
- 결과: **0건 매칭** — 신규/수정 production 코드에 Redis 직접 호출 없음.

### 신규 코드 시크릿 키워드 평문 점검
- `grep -rnE "(secret|token|password|jwt|credential)"` 본 phase 신규/수정 6 파일 대상.
- 결과: **0건 매칭**.

---

## 4. plan/research 와의 정합 이슈

| ID | 항목 | 결정 |
|---|---|---|
| P1 | yml prefix 가 plan/research(`app.verification.*`) vs 코드(`verification.*`) 불일치 | Phase 1 결정 그대로 — `verification.*` 채택. T005 의 `@Value` 키도 동일 정책. |
| P2 | T005 가 outbox payload 키 변경 시 Spec 002 republisher 의 `VerificationOutboxPayload` 호환 회귀 우려 | `@JsonAlias` + 신규 필드 추가로 양쪽 호환 보강. Phase 3 의 publisher 신규 stream 키 발행은 별도 작업으로 위임. |
| P3 | T007 의 schema.sql 적용 메커니즘(analyze U1) — tasks.md 본문은 `defer-datasource-initialization=true` 또는 동등 효과 설정으로 결정 위임 | `spring.sql.init.mode=always` + `defer-datasource-initialization=true` + stored procedure 멱등 패턴 채택. Testcontainers `withReuse(true)` 안전. |
| P4 | tasks.md 가 `SecurityConfig` 또는 동등 위치 수정을 지시했으나 코드베이스에 Spring Security 미존재 | `WebConfig.addInterceptors` 의 `excludePathPatterns` 에 `/api/v1/internal/**` 추가. 본문이 "동등 위치" 를 허용하므로 정합 유지. |
| P5 | T006 의 custom 인터페이스 결합 시점 | tasks.md 본문이 Phase 3 결합 허용 → 본 phase 는 기본 인터페이스만. |

위 결정은 모두 tasks.md 본문이 명시한 허용 범위 안의 보정이며 plan/research 의 본질을 침해하지 않는다.

---

## 5. 본 Phase 완료 가능 여부

**Y** — 컴파일 그린 + 시크릿 로그 게이트 그린 + 신규 코드에 헌법 II/IV/V 위반 0.

### 다음 단계(권장)

1. **test-author** 가 본 phase 의 공개 시그니처(특히 `FakeVerificationConsumer` API, `VerificationRequest` 빌더, `OutboxRecordCommand` 시그니처) 를 받아 Phase 3 의 테스트 작성에 진입할 수 있다.
2. **build-verifier** 가 Phase 2 의 그레이드 게이트(`./gradlew check`) 를 추가로 실행해 전체 테스트 회귀 0 을 확인한다.
   - 주의: `./gradlew check` 가 통합 테스트 전체를 돌리면 기존 `VerificationServiceIntegrationTest` / `VerificationServiceFailureIntegrationTest` 등이 schema.sql 신규 적용의 영향을 받을 수 있다. stored procedure 패턴은 멱등이므로 회귀는 없을 것으로 예상되나 실측 필요.
3. **code-reviewer** 는 다음을 우선 검토하면 좋다:
   - `VerificationOutboxPayload` 의 `@JsonAlias` 보강이 Spec 002 republisher 회귀를 정확히 차단하는지(특히 `memberId/planetId` 가 새 payload 에서 null 인 점이 republisher 의 `toMessageCommand()` 호출에서 안전한지).
   - `FakeVerificationConsumer` 의 `XREADGROUP` 폴링 로직이 `stop()` 직후 `start()` 재호출 시 마지막 소비 위치(lastConsumed) 를 정확히 이어받는지.
   - `application-test.yml` 의 `spring.sql.init.platform=mysql` 설정이 Testcontainers MySQL 8.0.36 환경에서 `schema-mysql.sql` 자동 fallback 없이 `schema.sql` 만 실행되는지(현재 `mode=always` + 단일 `schema.sql` 파일이라 안전 예상).

### 잠재 위험 항목 (Phase 3 진입 전 확인 권장)

- `FakeVerificationConsumer.postCallback` 의 `RestTemplate` 가 동일 JVM 내에서 Spring 서버로 호출 — `MalformedURLException`/`Connection refused` 가능성. 통합 테스트가 `@LocalServerPort` 로 받은 실제 port 를 주입했는지 케이스별 점검 필요. 본 phase 산출물 자체에는 영향 없음.
- Spec 002 의 `OutboxRepublisherIntegrationTest` 등은 payload JSON 을 `"verificationImgUrl"` 키로 직접 만든다 — `@JsonAlias` 가 양쪽을 받으므로 회귀 없음(컴파일/역직렬화 OK).

---

## 6. 후속: build-verifier 회귀 수정 (2026-06-03)

### 6.1 회귀 근본 원인

build-verifier 가 `phase2-verify.md` 에서 확정한 회귀:
- 초기 산출물의 `src/test/resources/schema.sql` 이 사용한 `CREATE PROCEDURE ... BEGIN ... ALTER TABLE ... ; END;` stored procedure 패턴이 Spring `ScriptUtils.executeSqlScript` 와 호환 안 됨.
- `ScriptUtils` 는 `;` 단순 split — MySQL `DELIMITER //` 메커니즘을 모름. procedure body 내부 `;` 가 문 종결자로 오인되어 `SQLSyntaxErrorException` 발생.
- `spring.sql.init.mode=always` 활성 → 모든 `@SpringBootTest` ApplicationContext 부팅 실패 → Spec 001/002 16/16 회귀.

### 6.2 채택한 수정안 — TestConfiguration + ApplicationRunner

권장안(phase2-verify.md §"수정 방향")을 그대로 채택. 근거: schema.sql 의 stored procedure 패턴을 단순 `;` split 친화 SQL로 풀어 쓰는 것도 가능하나, **단일 SQL 문 단위 멱등 보장**(if-not-exists 가드를 SQL 만으로) 이 MySQL 표준에 부재. JDBC 코드로 INFORMATION_SCHEMA 조회 후 동적 ALTER 가 가장 견고.

### 6.3 변경된 파일

| 변경 | 파일 | 내용 |
|---|---|---|
| 삭제 | `/Users/simjonghan/source_code/planetrush-api/src/test/resources/schema.sql` | stored procedure 패턴 회귀 원인 — 완전 제거 |
| 수정 | `/Users/simjonghan/source_code/planetrush-api/src/main/resources/application-test.yml` | `spring.sql.init.mode=always` / `spring.sql.init.platform` / `spring.jpa.defer-datasource-initialization=true` 제거. 주석으로 ApplicationRunner 패턴 명시. |
| 신규 | `/Users/simjonghan/source_code/planetrush-api/src/test/java/com/planetrush/planetrush/verification/testsupport/VerificationRecordSchemaInitializer.java` | `@TestConfiguration` + `ApplicationRunner` 빈. INFORMATION_SCHEMA 가드(`columnExists`/`uniqueConstraintExists`/`tableExists`) 후 ALTER 발행. Testcontainers `withReuse(true)` 안전. |
| 수정 | `/Users/simjonghan/source_code/planetrush-api/src/test/java/com/planetrush/planetrush/IntegrationTest.java` | `@Import(VerificationRecordSchemaInitializer.class)` 부착 — 자식 통합 테스트 전부에 자동 전파. |

### 6.4 실측 결과

| 명령 | 결과 |
|---|---|
| `./gradlew clean compileTestJava` | BUILD SUCCESSFUL (1s, 4 tasks) |
| `./gradlew test --tests "*OutboxRepublisher*"` | **BUILD SUCCESSFUL** (13s) — Spec 002 회귀 0 |
| `./gradlew test --tests "*MemberIntegrationTest"` | BUILD SUCCESSFUL (6s) — Spec 001 회귀 0 |
| `./gradlew test --tests "*VerificationServiceIntegrationTest" --tests "*VerificationIntegrationTest" --tests "*VerificationServiceFailureIntegrationTest"` | BUILD SUCCESSFUL (8s) — verification 도메인 기존 통합 테스트 회귀 0 |
| `./gradlew check` | **BUILD SUCCESSFUL** (22s, 전체 테스트 회귀 0) |
| `./gradlew verifySecretLogScan` | `✓ secret log scan clean` |

### 6.5 본 Phase 완료 가능 여부 (재확정)

**Y** — phase2-verify.md 의 모든 P0 차단 항목 해소. 핵심 게이트 전부 그린:
- 컴파일 그린.
- 전체 `./gradlew check` 그린 (16/16 회귀 0).
- 시크릿 로그 스캔 그린.
- 신규/수정 코드에 헌법 II/IV/V 위반 0.

### 6.6 analyze U1 확정 기록

`analyze U1 (schema.sql 적용 메커니즘)` 의 결정이 **ApplicationRunner 패턴**으로 본 회귀 fix 를 통해 확정됨. progress.md §"Analyze 단계 잔여 findings" 표에 반영.

### 6.7 헌법 정합 점검

| 원칙 | 점검 결과 |
|---|---|
| II (외부 의존 어댑터 격리) | `VerificationRecordSchemaInitializer` 가 `DataSource` 를 직접 잡으나 테스트 인프라 범위 — production 어댑터 규칙 비대상. |
| V (시크릿 로그 금지) | 로그 라인: `[schema-init] added generated column ...`, `[schema-init] added unique constraint ...`. 시크릿 키워드(`secret/token/password/jwt/credential`) 미사용. `verifySecretLogScan` clean 으로 실측 확인. |

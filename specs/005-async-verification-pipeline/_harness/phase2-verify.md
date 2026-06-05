# Phase 2 (Foundational) — Build Verification Report

**Spec**: 005-async-verification-pipeline · **Branch**: `005-async-verification-pipeline`
**Verifier**: build-verifier · **Date**: 2026-06-03
**Scope**: T002~T009 빌드·테스트·헌법 게이트 검증

---

## 판정 (TL;DR)

**Phase 2 commit 가능 여부 — N (실패)**

사유: `src/test/resources/schema.sql` 의 `CREATE PROCEDURE` DDL 이 Spring 의 `ScriptUtils` 가 사용하는 statement separator(`;`) 와 호환되지 않아, `spring.sql.init.mode=always` 활성화 후 **모든 통합 테스트의 ApplicationContext 부팅이 실패**한다. Spec 002 의 OutboxRepublisher 통합 테스트 3종은 물론 Spec 001 의 `MemberIntegrationTest` 까지 회귀.

## 환경

- Docker: `29.4.3 Docker Desktop` (Testcontainers 가용).
- JVM/Gradle: `./gradlew` wrapper (BUILD SUCCESSFUL/FAILED 인용 — 별도 환경 문제 아님).
- 변경 파일 8건(prod 7 + config 1) + 신규 5건(prod 4 + 테스트 인프라 2) — impl 보고서 §1 과 일치.

---

## 게이트 1 — 컴파일

### 명령
```
./gradlew clean compileJava compileTestJava
```

### 출력 (인용)
```
BUILD SUCCESSFUL in 2s
4 actionable tasks: 4 executed
```

### 판정 — 통과
- Production / test 양쪽 컴파일 그린. `OutboxRecordCommand` 시그니처 확장이 단일 caller(`VerificationEvent.toRecordCommand`) 와 정합.
- `FakeVerificationConsumer` 가 lombok 없이 명시 생성자 + `LoggerFactory.getLogger(...)` 로 작성된 것을 컴파일 그린으로 확정 (test scope 에 lombok 의존성 없음).

---

## 게이트 2 — 헌법 V (시크릿 로그 금지)

### 명령
```
./gradlew verifySecretLogScan
```

### 출력 (인용)
```
> Task :verifySecretLogScan
✓ secret log scan clean

BUILD SUCCESSFUL in 326ms
1 actionable task: 1 executed
```

### 판정 — 통과
- 본 phase 신규/수정 `src/main` 파일 7건 모두 시크릿 평문 로그 0건.
- `VerificationExternalEventRecorder` 의 로그가 `command.requestId()` 만 출력하고 payload 본문은 미출현.

---

## 게이트 3 — 헌법 IV (Redis 직접 호출 금지, 정적 검증)

### 명령
```
grep -rn "RedisTemplate\|opsForStream\|XADD" \
  src/main/java/com/planetrush/planetrush/verification/ \
  src/main/java/com/planetrush/planetrush/outbox/
```

### 출력
0건 매칭.

### 판정 — 통과
- 본 Phase production 변경 코드에 직접 Redis 호출 없음. `OutboxRepublisher`(Spec 002) 가 유일한 어댑터로 유지.
- `FakeVerificationConsumer` 는 `src/test/java` 위치 — 외부 컨슈머 시뮬레이션(헌법 IV 적용 대상 아님).

---

## 게이트 4 — 헌법 I (Testcontainers 통합 테스트)

### 명령
```
./gradlew test --tests "*OutboxRepublisher*" --tests "*VerificationOutboxPayload*"
```

### 출력 (인용)
```
OutboxRepublisherConcurrencyTest > SC-002: 폴러 2개 동시 실행에서 각 outbox는 정확히 1회만 발행된다 > repetition 1 of 10 FAILED
    java.lang.IllegalStateException at DefaultCacheAwareContextLoaderDelegate.java:180
        Caused by: org.springframework.beans.factory.BeanCreationException at AbstractAutowireCapableBeanFactory.java:1788
            Caused by: org.springframework.jdbc.datasource.init.ScriptStatementFailedException at ScriptUtils.java:282
                Caused by: java.sql.SQLSyntaxErrorException at SQLError.java:121
...
16 tests completed, 16 failed
BUILD FAILED in 6s
```

### 회귀 범위 확인 — 본 phase 변경이 Spec 002 한정인지 더 넓은지

추가 probe:
```
./gradlew test --tests "com.planetrush.planetrush.member.MemberIntegrationTest"
```

출력 인용:
```
MemberIntegrationTest > 통계 데이터를 조회할 때 캐싱된 값을 반환해야 한다. FAILED
    java.lang.IllegalStateException at DefaultCacheAwareContextLoaderDelegate.java:145
MemberIntegrationTest > 동시에 같은 회원 통계 조회가 들어와도 캐시 로더는 한 번만 실행되어야 한다. FAILED
2 tests completed, 2 failed
```

**회귀 범위**: Spec 002 OutboxRepublisher 3 클래스만이 아니라 **`@SpringBootTest` 를 사용하는 모든 통합 테스트** 가 부팅 단계에서 실패. test profile 의 `spring.sql.init.mode=always` 가 ApplicationContext 초기화 단계에서 schema.sql 을 실행하는데, 거기서 SQL syntax error 가 나기 때문이다.

### 판정 — 실패

---

## 근본 원인 분석

### 정확한 에러
```
Caused by: org.springframework.jdbc.datasource.init.ScriptStatementFailedException:
Failed to execute SQL script statement #2 of file
[/Users/simjonghan/source_code/planetrush-api/build/resources/test/schema.sql]:
CREATE PROCEDURE spec005_add_upload_date_only() BEGIN IF NOT EXISTS (
SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'verification_record'
 AND COLUMN_NAME = 'upload_date_only' ) THEN ALTER TABLE verification_record
 ADD COLUMN upload_date_only DATE GENERATED ALWAYS AS (DATE(upload_date)) STORED

Caused by: java.sql.SQLSyntaxErrorException:
You have an error in your SQL syntax; check the manual that corresponds to
your MySQL server version for the right syntax to use near '' at line 1
```

### 진단
- Spring 의 `ScriptUtils.executeSqlScript` 는 기본 `statementSeparator=";"` 로 SQL 파일을 split 한 뒤 각 단편을 개별 statement 로 실행한다.
- MySQL 의 **stored procedure body 는 `;` 를 procedure 내부 statement 종결자로 쓴다** — MySQL CLI 는 `DELIMITER //` 메커니즘으로 외부 separator 를 일시 변경해 이 충돌을 회피하지만, **Spring `ScriptUtils` 는 `DELIMITER` 지시문을 인식하지 못한다**.
- 결과: `CREATE PROCEDURE ... BEGIN IF NOT EXISTS (...) THEN ALTER TABLE verification_record ADD COLUMN upload_date_only DATE GENERATED ALWAYS AS (DATE(upload_date)) STORED` 까지만 잘려서 실행되고, `END;` 와 후속 `CALL ... ; DROP PROCEDURE ...` 가 모두 단편으로 전송돼 syntax error.

### Suspect 위치
- `src/test/resources/schema.sql:15-31` — `CREATE PROCEDURE spec005_add_upload_date_only` 블록.
- `src/test/resources/schema.sql:34-50` — `CREATE PROCEDURE spec005_add_uniq_record_member_planet_date` 블록.
- 즉, **stored procedure 멱등 패턴 자체가 Spring `sql.init` 메커니즘과 호환되지 않는다.**

### 자가점검 보고와의 차이
impl 보고서 §3 은 `compileJava`/`compileTestJava`/`verifySecretLogScan` 만 그린 확인하고 **schema.sql 을 실제 통합 테스트 부팅에서 실행시키지 않은 채** "회귀는 없을 것으로 예상되나 실측 필요" 라고만 명시했다. 본 실측에서 회귀가 실재함을 확정.

---

## 책임 라우팅

### code-implementer 로 라우팅 (P0 — 본 phase 완료 차단)

**문제**: `src/test/resources/schema.sql` 의 stored procedure 패턴이 Spring `ScriptUtils` 와 호환되지 않아 모든 통합 테스트 ApplicationContext 부팅 실패.

**선택지** (어느 것을 채택할지 code-implementer 결정 — analyze U1 의 위임 범위 안):

1. **`ScriptUtils.setSeparator` 활용**:
   - schema.sql 상단에 `--@@@SEPARATOR`/`/*$$$*/` 같은 Spring 인식 separator hint 를 쓰는 방법은 없다 — `spring.sql.init.separator` 속성으로 외부화는 가능하지만 procedure body 내부 `;` 까지 살리지 못한다(전체 script 가 단일 statement 로 강제됨 → CALL · DROP 등 후속 statement 도 분리 못함). 권장 X.

2. **stored procedure 제거하고 prepared statement / 단일 statement 패턴으로 재작성**(권장):
   - generated column 추가는 단일 `ALTER TABLE` 로 가능 — 멱등성은 **테스트 환경에서 새로 컨테이너를 띄울 때마다 깨끗한 상태**로 시작하므로 (Testcontainers `withReuse(true)` 가 적용되어 있더라도 schema.sql 은 `spring.sql.init.mode=always` 가 매번 실행 → DUPLICATE COLUMN 에러 가능) `continue-on-error: true` + 단순 ALTER 로 처리하거나, 또는 hibernate `ddl-auto=create` + schema.sql 시점에 IF NOT EXISTS 가 없는 단순 ALTER 를 사용.
   - **권장 패턴**:
     ```sql
     -- 단일 statement, IF NOT EXISTS 미지원 회피
     ALTER TABLE verification_record
       ADD COLUMN upload_date_only DATE GENERATED ALWAYS AS (DATE(upload_date)) STORED;

     ALTER TABLE verification_record
       ADD CONSTRAINT uniq_verification_record_member_planet_date
       UNIQUE (member_id, planet_id, upload_date_only);
     ```
   - + `application-test.yml` 에 `spring.sql.init.continue-on-error: true` (`Duplicate column` / `Duplicate key name` 무시) — **단, 다른 진짜 SQL 에러도 무시되므로 권장 신중**.

3. **Testcontainers `withReuse(false)` 명시** + 단순 ALTER:
   - 컨테이너가 매 클래스마다 깨끗이 부팅된다는 보장이 있으면 IF NOT EXISTS 멱등 처리 자체가 불필요. `Testcontainers` 설정(`AbstractIntegrationTest` 또는 동등 클래스) 확인 필요.

4. **schema.sql 대체 — `JdbcTemplate` 으로 부팅 후 ALTER**:
   - `@TestConfiguration` 빈이 `CommandLineRunner` 로 부팅 직후 ALTER 를 실행. `INFORMATION_SCHEMA` 조회로 멱등 보장. SQL 파싱 문제 회피.

5. **Generated column 대안 — entity 컬럼 추가**:
   - data-model.md §VerificationRecord 의 generated column 자체를 entity 매핑 가능한 일반 컬럼(`uploadDateOnly LocalDate`) + 서비스 레이어가 `LocalDate.from(uploadDate)` 으로 채우는 방식으로 변경하면 hibernate ddl-auto 가 자동 생성. 하지만 data-model.md 본문이 generated column 을 명시했으므로 본 변경은 plan 단계 결정 변경 — 신중.

**권장**: (4) 가 가장 안전. `@TestConfiguration` 위치의 `CommandLineRunner` 가 `INFORMATION_SCHEMA` 조회 + 동적 ALTER 를 수행. 일반 SQL 한 statement씩 처리하므로 ScriptUtils 호환 문제 회피, 멱등성 보장. Testcontainers `withReuse` 와도 안전.

### test-author 로 라우팅 — 해당 없음
본 게이트 실패는 schema.sql 자체의 문제이며 새 test 작성과는 무관. test-author 는 Phase 3 진입 시점까지 대기.

---

## 그 외 정합 점검 (참고용)

### 신규 산출물 정합

| 항목 | 결과 |
|---|---|
| `VerificationRequest` 필드·타입·인덱스 vs data-model.md §VerificationRequest | 일치. `idx_verification_request_member_status (member_id, status)` 인덱스, `@Enumerated(STRING)`, `@CreationTimestamp` 모두 명세대로. |
| `VerificationRequestStatus` enum 4 상태 | 일치. PENDING/SUCCESS/FAIL/ERROR. |
| `OutboxRecordCommand` 신 시그니처 호출처 | `VerificationEvent.toRecordCommand()` 1곳만 (`grep` 확인 — schema-mysql-uniq.sql 같은 운영 DDL 제외). 그 caller 의 caller 도 `VerificationExternalEventRecordListener` 1곳뿐. 점진 마이그레이션 의도 부합. |
| `application-test.yml` 의 `spring.sql.init.mode=always` + `defer-datasource-initialization=true` 가 schema.sql 을 hibernate 이후에 실행시키는지 | 실측 결과 — **순서는 맞으나(hibernate 가 verification_record 테이블을 먼저 만든 뒤 schema.sql 이 ALTER 시도)** schema.sql 자체가 syntax error 로 실패. |
| schema.sql Testcontainers MySQL 8 에서 멱등 적용 | **불가** — 첫 실행조차 실패. |
| `FakeVerificationConsumer` lombok 없이 명시 생성자·LoggerFactory | 확인. `private static final Logger log = LoggerFactory.getLogger(FakeVerificationConsumer.class);` (Line 76), 명시 생성자 (Line 87-90). |
| `VerificationOutboxPayload` 의 `@JsonAlias({"targetImgUrl","verificationImgUrl"})` 정합 | 정적 점검 OK — but Spec 002 회귀 테스트가 부팅 자체에서 실패해 **동적 검증 불가**. schema.sql 수정 후 재실측 필요. |
| `WebConfig.addInterceptors` 의 `/api/v1/internal/**` exclude 추가 | 정적 확인 OK. |

### 보조 게이트
- `./gradlew check` 전체 실행은 schema.sql 차단으로 인해 의미 있는 신호를 더 주지 않음 — **위 게이트 4 가 차단 신호로 충분**. schema.sql 수정 후 재실행 필수.

---

## 실행한 명령 요약

| # | 명령 | 결과 |
|---|---|---|
| 1 | `./gradlew clean compileJava compileTestJava` | BUILD SUCCESSFUL |
| 2 | `./gradlew verifySecretLogScan` | BUILD SUCCESSFUL — `✓ secret log scan clean` |
| 3 | `grep -rn "RedisTemplate\|opsForStream\|XADD" verification outbox` | 0건 매칭 |
| 4 | `./gradlew test --tests "*OutboxRepublisher*" --tests "*VerificationOutboxPayload*"` | **BUILD FAILED — 16 tests completed, 16 failed** |
| 5 | `./gradlew test --tests "MemberIntegrationTest"` (회귀 범위 확정 probe) | BUILD FAILED — 2 tests completed, 2 failed |

---

## 다음 단계 (필수)

1. **code-implementer** — `src/test/resources/schema.sql` 의 stored procedure 멱등 패턴을 위 §책임 라우팅의 선택지 중 하나로 교체. 권장: `@TestConfiguration` + `CommandLineRunner` 로 `INFORMATION_SCHEMA` 조회 + 동적 ALTER 패턴(SQL 파싱 회피).
2. 수정 후 **build-verifier 재호출** — `./gradlew test --tests "*OutboxRepublisher*"` + `./gradlew test --tests "MemberIntegrationTest"` 두 probe 가 모두 그린 → 그 후 `./gradlew check` 전체.
3. 그린 확정 후에만 code-reviewer 가 Phase 2 리뷰 진입.

## 헌법 게이트 통과 증거 요약

| 원칙 | 게이트 명령 | 상태 |
|---|---|---|
| V (시크릿 로그 금지) | `./gradlew verifySecretLogScan` | 통과 (`✓ secret log scan clean`) |
| IV (Redis 직접 호출 금지) | `grep -rn ...` 0건 | 통과 |
| I (Testcontainers 통합 테스트) | `./gradlew test` 통합 테스트 | **실패** — schema.sql syntax error 로 ApplicationContext 부팅 차단 |
| VII (인수기준=테스트) | Phase 3 대상 | N/A (본 Phase 미실행) |

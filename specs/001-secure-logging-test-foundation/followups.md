# Spec 001 — Follow-ups

본 스펙 머지 후 처리할 잔여 항목. Codex 듀얼 리뷰에서 발견된 P1 중 본 PR 스코프
밖으로 분리한 작업을 박제한다. (해당 리포가 GitHub Issues 비활성이므로 본 파일로
영구 추적.)

---

## FU-001 · Logback throwable 마스킹 (P1, Codex 리뷰)

**상태**: Open
**우선순위**: P1 (보안, Constitution 원칙 V 부분 우회)
**예상 처리 시점**: Spec 006(Outbound Resilience & Auth Hardening)와 묶거나 별도 hot-fix PR

### 문제

`logback-spring.xml`의 CONSOLE 패턴 끝부분:

```xml
... %msk%n${LOG_EXCEPTION_CONVERSION_WORD:-%wEx}
```

`%msk`는 `event.getFormattedMessage()`만 마스킹한다. throwable이 동봉된 로그
(`log.error("...", exception)`)에서 `%wEx`가 처리하는 **stack trace + exception
메시지는 본 컨버터를 거치지 않는다**.

본 프로젝트에 `catch (Exception e) { log.error("...", e); }` 패턴이 다수 존재하며,
exception 메시지나 message-of-cause에 `token=abc` 같은 값이 포함될 경우 콘솔 로그에
평문 노출 가능.

### 해결 방향

1. `ch.qos.logback.classic.pattern.ThrowableProxyConverter`를 상속한
   `MaskingThrowableConverter` 도입
2. `<conversionRule conversionWord="mskex" converterClass="..."/>` 등록
3. CONSOLE 패턴에서 `%wEx` → `%mskex`로 교체
   (`${LOG_EXCEPTION_CONVERSION_WORD:-%mskex}` 형식 유지)
4. 단위 테스트: throwable 메시지/스택 프레임에 시크릿 키워드 포함된 케이스 마스킹 검증

### 검증된 사실 (출처)

- Codex CLI 리뷰 (PR #1 본문 "AI Review" 섹션 참조)
- 본 follow-up 박제 후 PR #1 머지 진행

### 본 PR 머지 가능 판정

본 위험은 throwable 경로에 한정되며, 일반 메시지 경로의 마스킹은 28종 테스트로
보장된다. 또한 본 PR이 머지되기 전 상태(`JwtTokenProvider`의 `log.info("secret
key: {}", SECRET_KEY)`가 매 요청 찍힘)와 비교하면 압도적 개선이다. 본 follow-up을
박제한 채 머지 진행.

---

## FU-002 · `ddl-auto=update` → `validate` 전환

**상태**: Open
**우선순위**: P2 (운영 안티패턴, Constitution Additional Constraints)
**예상 처리 시점**: Spec 005 (Query Performance & Schema Versioning) — Flyway 도입과 함께 일괄 처리

### 배경

`application.yml`, `application-dev.yml`, `application-test.yml`이 모두
`spring.jpa.hibernate.ddl-auto: update`로 운영. Constitution Additional
Constraints에 "운영 안티패턴 즉시 차단" 4종 중 하나로 명시되어 있으나, 본 스펙
(Spec 001)에서는 `show-sql`/`web=DEBUG`만 청산하고 `ddl-auto`는 Spec 005에서
Flyway 도입과 함께 처리하기로 의도적으로 분리.

### 해결 방향 (Spec 005에서)

1. Flyway 도입 (`gradle plugin` + `org.flywaydb:flyway-mysql`)
2. 현재 스키마를 `V1__baseline.sql`로 dump
3. `application-prod.yml`에 `ddl-auto: validate` 추가
4. `application-dev.yml` / `application-test.yml`도 점진적 전환

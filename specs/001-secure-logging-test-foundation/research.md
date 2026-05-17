# Phase 0 Research: Secure Logging & Test Foundation

**Feature**: Secure Logging & Test Foundation
**Branch**: `001-secure-logging-test-foundation`
**Date**: 2026-05-16

본 문서는 plan.md의 Technical Context에 남은 미해결 결정을 풀고, 신규 의존성에 대한 베스트 프랙티스를 정리한다.

---

## R-001 · Logback PatternConverter 마스킹 구현 방식

**Decision**: `ch.qos.logback.classic.pattern.ClassicConverter`를 상속한 `MaskingPatternConverter`를 만들고, `logback-spring.xml`의 `<conversionRule conversionWord="msk" ... />`로 등록한다. 콘솔 `<encoder><pattern>` 안에서 `%msk` 워드로 메시지를 한 번 감싼다. 정규식은 `(?i)(secret|token|password|jwt|credential)[^=:]*[=:]\s*\S+`을 1차 안으로 사용하고, 매칭부 뒤 값만 `***`로 치환한다(키워드 자체는 보존하여 가독성 유지).

**Rationale**:

- Logback 공식 가이드의 `ClassicConverter` 패턴은 메시지 전체 텍스트(파라미터 포맷팅 후)를 받기 때문에 `logger.info("secret: {}", value)` 호출에서 `value`가 평문으로 들어와도 정규식이 잡는다.
- `<conversionRule>` 방식은 Logback 표준이며 의존성 추가 없이 동작.
- MDC 기반 마스킹(`%mdc`) 대안은 호출자가 MDC를 명시적으로 사용해야 하므로 "암묵적 평문 출력"을 못 잡음. 컨스티튜션 원칙 V("위반은 운영 사고")를 충족하지 못함.

**Alternatives considered**:

| 대안 | 채택 X 사유 |
|---|---|
| `MessageMaskingTurboFilter` | 메시지를 변형해 다음 appender로 흘리는 방식. ClassicConverter 대비 복잡도↑, Logback 11.x 기준 호환성 미세한 차이 |
| AOP로 호출 자체 차단 | logger 호출 지점 전체 weaving 비용 + 통계/메트릭 로그도 영향. 과한 침습 |
| 의존성(예: `logstash-logback-encoder`) | 단일 마스킹 컨버터 한 줄로 끝나는 일에 외부 인코더 부가 비용 |

**Open question (Phase 2에서 결정)**:
- 정규식의 false-positive 허용 폭(닉네임 `tokenmaster` 등)은 Edge Cases의 결정대로 옵트아웃 X. 단위 테스트로 의도된 부수 마스킹 케이스도 검증.

---

## R-002 · Testcontainers 컨테이너 재사용(`withReuse(true)`)

**Decision**: `@Container static MySQLContainer<>` 필드를 `withReuse(true).withLabel("project", "planetrush-api")`로 선언. 개발자/CI에는 `~/.testcontainers.properties`에 `testcontainers.reuse.enable=true`를 quickstart.md에서 안내. CI는 ephemeral 러너라 재사용 효과가 제한적이나 동일 잡 내 멀티 테스트 클래스에서는 효과 있음.

**Rationale**:

- 컨테이너 부팅 50~60s가 매 테스트 클래스마다 발생하면 14h 예산에서 후속 스펙 작업 시간을 잠식.
- 재사용은 Testcontainers 1.16+ 공식 기능이며, 컨테이너에 부여된 label hash로 동일 spec 컨테이너를 찾아 재사용.

**Alternatives considered**:

- **Singleton container 패턴** (정적 필드 + `static { container.start(); }` 직접 호출): 작동하지만 JUnit `@Container` 라이프사이클 우회로 코드 가독성↓ + Ryuk(자동 정리) 비활성화 위험.
- **TestContainers 없이 H2/Embedded Redis**: 컨스티튜션 원칙 I 위반, 운영 회귀 못 잡음.
- **Docker Compose 의존**: 신규 개발자가 별도 데몬 가동 필요 → US2 인수 기준 위반.

**Side effect / Caveats**:

- 재사용 활성 시 컨테이너가 테스트 종료 후에도 살아 있어 `docker ps`로 잔존 확인. quickstart.md에 정리 가이드 명시.
- Ryuk(자동 정리)는 재사용과 함께 사용 시 `TESTCONTAINERS_RYUK_DISABLED=true` 필요할 수 있음 — Testcontainers 1.20.4 기준 자동 처리되므로 명시적 비활성화 X.

---

## R-003 · `application-prod.yml` 프로필 분리 전략

**Decision**: `application.yml`은 공통 기본값만 유지하되, **현재 `application.yml`에 있는 운영 부적합 키 두 개를 prod 오버라이드로 이동**한다.

| Key | application.yml (공통) | application-prod.yml (오버라이드) | application-dev.yml |
|---|---|---|---|
| `spring.jpa.show-sql` | `true` (개발 친화 기본) | `false` | (없음, 공통 상속) |
| `spring.jpa.properties.hibernate.format_sql` | `true` | `false` | (없음) |
| `logging.level.org.springframework.web` | `DEBUG` | `INFO` | (없음) |
| `spring.jpa.hibernate.ddl-auto` | `update` | `validate` | (없음) |

**Rationale**:

- 컨스티튜션 Additional Constraints의 "운영 안티패턴 즉시 차단" 4종 중 본 스펙은 `show-sql`/`web=DEBUG`만 청산. `ddl-auto=update → validate` 전환은 **Flyway 도입과 함께 가야 안전**하므로 Spec 5(Query Performance & Schema Versioning)로 분리. plan에 명시.
- `application.yml`이 dev 친화 기본값을 유지하는 편이 개발 편의성과 백워드 호환에 우호적.

**Alternatives considered**:

- **공통값을 prod 친화로 바꾸고 dev에서 켜기**: dev 프로필 오버라이드가 늘어 dev 회귀 위험.
- **application.yml에서 `show-sql` 제거 후 dev에서 명시**: dev 개발자 경험 저하.

---

## R-004 · JwtTokenProvider 시크릿 출력 제거 방식

**Decision**: 정상 흐름 `log.info("secret key: ...")`는 **삭제**한다. 디버깅 시 동등 정보가 필요한 경우 `log.debug("jwt secret loaded (length={}, fingerprint={})", len, sha256_8byte_prefix)`로 대체. fingerprint는 SHA-256 첫 8바이트 hex(키 회전 추적용).

**Rationale**:

- 시크릿 길이/지문은 보안 위험이 거의 없는 메타데이터로, 운영 트러블슈팅에 충분.
- 마스킹 컨버터로도 보호되지만 "원천 차단(코드 제거)" + "Defense in depth(컨버터)" 이중 방어가 컨스티튜션 원칙 V("운영 사고로 간주")에 부합.

**Alternatives considered**:

- 단순 삭제만: 충분하지만 향후 다른 곳에서 같은 안티패턴 등장 시 가드 없음. 컨버터 병행 필요.
- 평문 유지 + 컨버터만 의존: 의존성 단일점. "코드 리뷰에서 평문이 합법적으로 보이는" 안티 시그널.

---

## R-005 · `./gradlew test` grep CI 게이트 (SC-005)

**Decision**: `build.gradle`에 `tasks.named('check')` 의존성으로 `verifySecretLogScan` 태스크를 추가한다. 태스크는 `Exec`로 grep 명령을 실행하고 매치 발견 시 빌드 실패.

```groovy
tasks.register('verifySecretLogScan', Exec) {
    workingDir project.rootDir
    commandLine 'sh', '-c',
        'if grep -RInE "log\\.(info|debug|warn)\\(.*(secret|token|password|jwt|credential).*=.*\\)" src/main; then exit 1; else exit 0; fi'
}
tasks.named('check') { dependsOn 'verifySecretLogScan' }
```

**Rationale**:

- SC-005를 자동 테스트로 박제(컨스티튜션 원칙 VII).
- 추후 누군가 동일 안티패턴을 재도입하면 CI에서 즉시 빨간불.

**Alternatives considered**:

- Checkstyle/Spotbugs 규칙: 설정 학습/유지보수 비용↑. grep 한 줄이 더 명료.
- 단위 테스트로 정적 분석: classpath 스캐닝 필요. 과한 복잡도.

---

## R-006 · 새 의존성 라이선스/유지보수 점검

| 의존성 | 라이선스 | 채택 버전 | 비고 |
|---|---|---|---|
| `testcontainers-bom` | Apache 2.0 | 2.0.5 | 사실상 표준. 메이저 2.x는 Docker Desktop 4.x의 socket redirect 응답을 정상 처리 |
| `testcontainers-mysql` | Apache 2.0 | 2.0.5 (BOM 관리) | MySQL 8.x 헬퍼 |
| `testcontainers-junit-jupiter` | Apache 2.0 | 2.0.5 (BOM 관리) | JUnit 5 표준 통합 |
| `commons-lang3` | Apache 2.0 | 3.18.0 | Testcontainers 2.x explicit 의존 |

**버전 결정 회고 (구현 중 발견)**: 초기 1.20.4로 시작했으나 통합 테스트 실행 시
Docker Desktop 4.x가 빈 JSON에 `Labels: ["com.docker.desktop.address=..."]`만 담은
redirect 응답을 보내고 1.20.x `DockerClientProviderStrategy`가 이를 처리하지 못해
`IllegalStateException` 발생. 2.0.5로 업그레이드 후 정상 동작 확인. 모듈 이름 prefix가
`testcontainers-`로 변경되어 함께 갱신, commons-lang3 explicit 추가.

라이선스·유지보수 측면 위험 없음. 컨스티튜션 "라이브러리 도입 규칙" 충족.

---

## 결론

모든 [NEEDS CLARIFICATION]이 해소되었다. Phase 1 산출물(quickstart.md) 작성으로 이동한다. `data-model.md`와 `contracts/`는 본 스펙이 도메인/API를 변경하지 않으므로 생성하지 않으며, 이를 plan.md의 Documentation 트리에 명시했다.

---
name: sdd-implement
description: "planetrush-api 프로덕션 코드를 헌법 7원칙에 맞춰 구현하는 절차 가이드. code-implementer 에이전트가 tasks.md의 src/main 태스크를 구현할 때 사용. 외부 의존 어댑터 격리, QueryDSL Projections, Outbox 경유 발행, 시크릿 로그 금지 패턴을 담는다. SDD 구현·Phase 구현·프로덕션 코드 작성·구현 수정 시 적용."
---

# SDD 프로덕션 코드 구현 가이드

planetrush-api(Java 21 · Spring Boot 3.2.7 · JPA+QueryDSL 5.0)의 프로덕션 코드를 구현하는 절차. 헌법(`.specify/memory/constitution.md`)을 코드 작성 시점에 지켜, 리뷰·검증 단계의 재작업을 없앤다.

## 태스크 수행 절차

1. tasks.md에서 배정 Phase의 `src/main/...` 태스크를 의존성 순서로 정렬한다.
2. 각 태스크가 명시한 파일 경로·클래스명·메서드 시그니처를 그대로 구현한다 — 임의 변경은 정합성 결함이 된다.
3. 같은 패키지의 이웃 코드를 먼저 읽어 네이밍·주석 밀도·DI 스타일을 맞춘다.
4. 공개 시그니처가 확정되면 `test-author`에게 알린다 — 테스트가 병행될 수 있도록.

## 헌법 원칙별 구현 패턴

### 원칙 II — 외부 의존은 어댑터로 격리
Redis·S3·OAuth·Flask 등 외부 시스템은 `infra` 레이어 어댑터(Port-Adapter)를 통해서만 호출한다.
- 도메인·서비스 클래스에서 `RedisTemplate`·`AmazonS3`·`RestClient`·`WebClient`를 **import·필드 주입 금지**.
- 외부 호출이 필요하면 `infra`에 어댑터 인터페이스(포트)를 두고, 도메인은 포트에 의존한다.
- 회로 차단기·재시도·타임아웃은 어댑터 한 곳에서 강제한다 — 그래야 정책을 한 곳에서 바꾸고 테스트 더블 교체가 쉽다.

### 원칙 III — DTO 매핑은 QueryDSL Projections만
Entity→DTO 변환은 `Projections.constructor` 또는 `Projections.fields`만 쓴다.
- 서비스에서 `new SomeDto(entity.getX(), entity.getY())`처럼 Entity getter로 수동 매핑 금지 — N+1·lazy 트리거의 원인.
- ModelMapper·MapStruct도 쓰지 않는다(lazy 트리거 위험으로 헌법 채택 제외).
- 응답 페이로드는 QueryDSL 쿼리에서 SQL 레벨로 결정한다.

### 원칙 IV — 메시지 발행은 Outbox 경유
메시지 브로커(Redis Stream 등) 발행을 직접 호출하지 않는다.
- Repository·Service에서 `redisTemplate.opsForStream().add(...)` 직접 호출 금지.
- 외부 이벤트는 `OutboxEvent`를 저장하고, `AFTER_COMMIT` 리스너가 발행하게 한다.
- 예외: Outbox 워커(`outbox/republisher`) 자체와 어댑터 내부 호출.
- 이유: DB 트랜잭션과 발행의 원자성이 본 프로젝트의 핵심 신뢰성 카드다. 직접 publish는 "커밋 후 publish 실패 → 영원히 유실" 사고의 직접 원인이다.

### 원칙 V — 민감정보 로그 금지
`secret`·`token`·`password`·`jwt`·`credential` 키워드를 포함한 값을 로그로 출력하지 않는다.
- 마스킹은 Logback `PatternConverter`가 자동 처리하지만, 우회 코드(`MDC.put("secret", raw)` 등)를 만들지 않는다.
- 시크릿 환경변수를 평문 로그로 찍지 않는다 — `verifySecretLogScan` 게이트가 빌드를 깬다.

### 원칙 I·VII (테스트) — test-author 담당
통합 테스트 Testcontainers 의무·인수기준 테스트화는 `test-author`가 맡는다. 단, 프로덕션 코드가 테스트 가능하도록 의존성을 **생성자 주입**으로 노출한다.

## 운영 안티패턴 (헌법 Additional Constraints)
- `spring.jpa.hibernate.ddl-auto=update` 금지(운영 프로필). 허용: `validate`(운영) / `create-drop`(테스트).
- `spring.jpa.show-sql=true` 금지(운영). 관찰이 필요하면 p6spy·DataSource Proxy.
- 동일 기능 라이브러리 중복 도입 금지(`RestTemplate`+`WebClient`+`RestClient` 혼용 금지 — 신규 코드는 하나로 통일).

## 신규 의존성 규칙
`build.gradle`에 의존성을 추가하려면 해당 스펙 `plan.md`에 "도입 사유 / 거절된 대안 / 라이선스 / 유지보수 상태"가 명시돼 있어야 한다. 근거가 없으면 추가하지 말고 리더에게 보고한다.

## 코드 컨벤션
- DI는 Lombok `@RequiredArgsConstructor` + `final` 필드(이웃 코드 관습).
- 조건부 빈은 `@ConditionalOnProperty`, 외부화 설정은 `@ConfigurationProperties` record + `@Validated`.
- 패키지는 도메인별 구성(`outbox/`, `planet/`, `member/`, `verification/`, `infra/`, `core/`).

## 완료 기준
- 배정 Phase의 모든 `src/main` 태스크가 tasks.md 명세대로 구현됨.
- 컴파일 성공. 헌법 원칙 II~V 위반 0.
- 공개 시그니처가 `test-author`와 동기화됨.

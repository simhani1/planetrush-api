# Quickstart: 통합 테스트 작성 가이드 (Spec 001 산출)

**대상**: planetrush-api에서 Spec 002 이후의 통합 테스트를 작성하는 개발자
**전제**: Spec 001 머지 완료 (`IntegrationTestSupport` 가용)

---

## 1. 처음 한 번만 — 로컬 환경 셋업

### 1.1. Docker 런타임 설치

다음 중 하나가 활성 상태여야 한다:

- Docker Desktop (macOS/Windows/Linux)
- OrbStack (macOS, 권장)
- Colima (macOS, `colima start`)
- Linux: `dockerd` 직접 또는 `podman` + dockershim

확인:

```bash
docker info | head -5
```

### 1.2. Testcontainers 컨테이너 재사용 활성화 (선택, 강력 권장)

```bash
mkdir -p ~/.testcontainers && \
echo "testcontainers.reuse.enable=true" >> ~/.testcontainers.properties
```

활성화하면 동일 spec의 MySQL/Redis 컨테이너가 테스트 종료 후에도 살아남아 다음 실행 시 부팅을 건너뛴다(첫 부팅 ~60s → 이후 ~2s).

> ⚠️ 컨테이너는 `docker ps`로 확인 가능. 정리하려면 `docker rm -f $(docker ps -aq --filter "label=org.testcontainers.reuse=true")`.

### 1.3. (선택) Docker 미설치 환경 확인

Docker 데몬이 꺼진 상태에서 `./gradlew test` 실행 시 다음과 같은 메시지로 명확히 실패해야 한다:

```text
Could not find a valid Docker environment. Please check configuration. ...
```

`silent skip`은 컨스티튜션 위반으로 간주(Spec 001 인수 기준 US2-3).

---

## 2. 새 통합 테스트 작성 — 1줄 상속만 하면 됨

```java
package com.planetrush.planetrush.someFeature;

import com.planetrush.planetrush.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;

class SomeFeatureIntegrationTest extends IntegrationTestSupport {

    @Test
    void shouldDoSomething() {
        // given
        // when
        // then
        // MySQL/Redis는 베이스가 자동 부팅·주입 완료
    }
}
```

**자동으로 주입되는 것**:

- `spring.datasource.url`, `username`, `password` — MySQL 컨테이너 endpoint
- `spring.data.redis.host`, `port` — Redis 컨테이너 endpoint
- 트랜잭션 롤백, JPA 영속성 컨텍스트 (Spring Boot Test 기본값)
- 마스킹 PatternConverter (운영과 동일하게 활성)

**자동으로 안 되는 것** (테스트에서 명시):

- 테스트 데이터 시드 — `@Sql` 또는 `@BeforeEach`에서 직접
- Mock 외부(Flask, S3) — `@MockBean` 또는 `MockWebServer`

---

## 3. 시크릿 로깅 가드 동작 확인

새 코드에 로그 추가 시 의도치 않은 평문 노출 회귀를 막기 위해 다음을 기억:

### 안전한 패턴

```java
log.info("user {} logged in", userId);                        // OK
log.debug("jwt loaded (length={}, fp={})", len, fingerprint); // OK (메타데이터만)
log.warn("auth failed for {}", maskedEmail);                  // OK (호출자가 마스킹)
```

### 위험한 패턴 (CI grep 게이트가 차단함)

```java
log.info("secret key: {}", SECRET_KEY);              // ❌ build 실패
log.debug("password = {}", rawPassword);             // ❌ build 실패
log.info("token={}, jwt={}", t, j);                   // ❌ build 실패
```

CI grep 정규식: `log\.(info|debug|warn)\(.*(secret|token|password|jwt|credential).*=.*\)`

설령 grep을 우회해도 Logback `MaskingPatternConverter`가 런타임에서 `***`로 치환하나, **이중 방어 원칙으로 코드 자체에 평문 출력을 두지 말 것**(컨스티튜션 원칙 V).

---

## 4. 운영 프로필 부팅 검증

로컬에서 prod 프로필 부팅 동작 확인:

```bash
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun --args='--spring.config.import=optional:file:.env[.properties]'
```

부팅 로그에 다음이 없어야 한다:

- SQL 출력 (`Hibernate: select ...`)
- `o.s.web` 패키지의 DEBUG 라인

만약 보인다면 `application-prod.yml`의 오버라이드 누락 또는 환경 변수 강제 적용을 의심.

---

## 5. 트러블슈팅

| 증상 | 원인 / 조치 |
|---|---|
| 첫 `./gradlew test`가 60s 넘게 멈춤 | MySQL 이미지 pull 중. 후속 실행에서는 즉시. |
| `Could not find a valid Docker environment` | Docker/Colima/OrbStack 미가동. `docker info` 확인. |
| 컨테이너가 종료되지 않음 | 의도된 재사용 동작. 위 1.2의 정리 명령 참고. |
| `verifySecretLogScan` 태스크 실패 | 신규 코드에 시크릿 키워드 로그 발견. 메타데이터 형태로 변경 또는 `log.debug` 강등 후 마스킹 확인. |
| 통합 테스트 P99 갑자기 증가 | 컨테이너 재사용 비활성화 의심. `~/.testcontainers.properties` 확인. |

---

## 6. 후속 스펙에서의 사용 패턴

| 스펙 | 본 인프라 활용 방식 |
|---|---|
| Spec 002 (Outbox Republisher) | `IntegrationTestSupport` 상속 + 카오스 테스트: 폴러 강제 종료 후 재기동 메시지 도달 검증 |
| Spec 003 (Idempotent Consumer) | 상속 + 동일 eventId N회 전달 시 1회만 처리 검증 |
| Spec 004 (Redis Counter) | 상속 + k6 부하 시나리오 외부 실행 결과 비교 |
| Spec 005 (Performance) | 상속 + p6spy로 쿼리 수 회귀 검증 |
| Spec 006 (Resilience) | 상속 + WireMock으로 Flask 장애 시나리오 |

본 quickstart는 Spec 002+의 첫 번째 통합 테스트 클래스에서 다시 참조한다.

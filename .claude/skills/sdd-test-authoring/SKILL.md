---
name: sdd-test-authoring
description: "planetrush-api의 인수 기준(SC)을 Testcontainers 기반 자동 테스트로 작성하는 절차 가이드. test-author 에이전트가 tasks.md의 src/test 태스크를 수행할 때 사용. 헌법 원칙 I(Testcontainers)·VII(인수기준=테스트) 집행. SDD 구현·테스트 작성·인수 기준 검증·테스트 수정 시 적용."
---

# SDD 인수 기준 테스트 작성 가이드

planetrush-api의 Success Criteria(SC)를 살아있는 자동 테스트로 박제하는 절차. 헌법 원칙 VII는 "수동 확인"을 인수 기준 충족으로 인정하지 않는다 — 인수 기준은 반드시 자동 테스트로 검증돼야 후속 리팩토링에서 회귀가 감지된다.

## 작성 절차

1. `spec.md`의 Success Criteria(SC-001…)와 `tasks.md`의 "Acceptance Criteria ↔ Task Mapping" 표를 읽는다.
2. 각 SC에 대해, 그 SC가 말하는 **기대 동작**을 명세에서 도출한다 — 구현 코드를 보고 베끼지 않는다.
3. 배정 Phase의 `src/test/...` 태스크를 작성한다. 각 테스트 메서드가 어느 SC를 검증하는지 메서드명·주석으로 명확히 한다.
4. 구현 시그니처가 필요하면 `code-implementer`에게 요청하거나, tasks.md 명시 시그니처로 선행 작성한다.

## 원칙 I — 통합 테스트는 Testcontainers
- 통합 테스트는 `extends IntegrationTest`(`src/test/java/com/planetrush/planetrush/IntegrationTest.java`, Spec 001 베이스)를 상속한다. MySQL·Redis 컨테이너가 자동 부팅된다.
- 로컬에 MySQL/Redis 데몬이 없어도 `./gradlew test`가 통과해야 한다.
- **싱글톤 컨테이너 패턴**: 컨테이너는 `static`으로 한 번만 띄워 Spring 컨텍스트 캐시와 공유한다. `@Container` 인스턴스 라이프사이클을 매 클래스 새로 만들면 컨텍스트 캐시와 충돌해 전체 테스트가 커넥션 타임아웃이 난다(Spec 002에서 수정한 결함 — 반복 금지). 새 통합 테스트는 `IntegrationTest`를 상속하기만 하면 된다.

## 슬라이스·단위 테스트
- Mock·H2 in-memory 대체는 `@WebMvcTest`·`@DataJpaTest` 슬라이스 테스트에서만 허용된다.
- 신규 `@SpringBootTest`가 로컬 데몬을 요구하면 헌법 위반 — PR 머지가 차단된다.

## SC를 테스트로 옮기는 법
- **1 SC ↔ 1+ 테스트**: 모든 SC가 최소 한 개의 테스트 assertion으로 검증돼야 한다. 미매핑 SC는 0이어야 한다.
- **명세 기반 단언**: "PENDING이 재발행되면 PUBLISHED가 된다"가 SC면, 테스트는 `status`가 `PUBLISHED`인지 단언한다. 구현이 틀리면 테스트가 빨개지는 게 정상이다 — 그것이 테스트의 목적이다.
- **카오스·동시성 SC**: 장애 주입(Mock 일시 실패)·다중 스레드(`ExecutorService`)로 재현하고, 동시성 테스트는 `@RepeatedTest(10)`로 안정성을 확인한다.
- **시간 의존 SC**: `@CreationTimestamp` 같은 자동 시각은 테스트에서 네이티브 업데이트로 과거 시각을 주입해 컷오프 등을 재현한다.

## 작성 스타일
- 단언은 AssertJ(`assertThat(...)`).
- given-when-then 주석 구획.
- 폴러·스케줄러처럼 운영에서 자동 트리거되는 컴포넌트는 테스트 프로필에서 비활성(`enabled: false`)이므로, 테스트는 메서드를 **직접 호출**해 사이클을 1회 실행한다.

## 테스트가 결함을 드러냈을 때
테스트 실패는 발견이다. 통과시키려 단언을 약화하거나 테스트를 건너뛰지(`@Disabled`) 않는다.
- 프로덕션 코드 결함으로 판단되면 `code-implementer`에게 "SC-00X 기대 vs 실제"로 전달한다.
- 테스트 자체 결함이면 수정한다.
- flaky 테스트는 원인을 규명해 보고한다 — 숨기지 않는다.

## 완료 기준
- 배정 Phase의 모든 `src/test` 태스크 작성 완료.
- 해당 Phase가 커버하는 모든 SC가 테스트로 검증됨.
- 통합 테스트가 Testcontainers로 동작, 로컬 데몬 불요.

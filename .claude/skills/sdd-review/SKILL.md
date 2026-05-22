---
name: sdd-review
description: "planetrush-api 구현을 헌법 7원칙·코드 품질·통합 정합성으로 리뷰하는 절차 가이드. code-reviewer 에이전트가 Phase별 리뷰와 PR용 Claude 리뷰(헌법 VI)를 작성할 때 사용. 원칙별 위반 탐지법과 경계면 교차 검증법을 담는다. SDD 코드 리뷰·헌법 점검·정합성 검증·리뷰 갱신 시 적용."
---

# SDD 코드 리뷰 가이드

planetrush-api 구현을 리뷰해, 헌법 원칙 VI가 요구하는 듀얼 AI 리뷰의 Claude 측 리뷰를 산출하는 절차.

## 리뷰 절차 (Phase 단위)

1. `build-verifier`의 "빌드 그린" 신호를 받고 시작한다(그린이 아니면 리뷰하지 않는다 — 컴파일도 안 되는 코드 리뷰는 무의미).
2. 변경된 `src/main`·`src/test`를 헌법 7원칙으로 점검한다.
3. 통합 정합성을 경계면 교차 비교로 검증한다.
4. 코드 품질을 점검한다.
5. `specs/{spec}/_harness/phase{N}-review.md`에 기록한다. 전 Phase 종료 후 `claude-review.md`로 통합한다.

## 헌법 7원칙 위반 탐지

| 원칙 | 위반 신호 | 탐지법 |
|---|---|---|
| I. Testcontainers | 신규 `@SpringBootTest`가 로컬 데몬 요구 | `IntegrationTest` 미상속 통합 테스트, H2·임베디드 의존 검색 |
| II. 어댑터 격리 | 도메인·서비스에서 외부 클라이언트 직접 참조 | `service`·`domain` 패키지에서 `RedisTemplate`·`AmazonS3`·`RestClient`·`WebClient` import grep |
| III. QueryDSL Projections | 서비스에서 Entity getter 수동 매핑 | 서비스·컨트롤러에서 `new ...Dto(엔티티.get` 패턴 grep |
| IV. Outbox 경유 | 브로커 직접 publish | 도메인 코드에서 `opsForStream().add(` grep (워커·어댑터 제외) |
| V. 시크릿 로그 | 시크릿 평문 로그 | `log.`에 `secret`·`token`·`password`·`jwt`·`credential` 인자, `MDC.put` 우회 grep |
| VI. 듀얼 리뷰 | — | 본 리뷰가 그 집행이다 |
| VII. 인수기준=테스트 | SC가 테스트로 박제 안 됨 | spec.md SC ↔ 테스트 매핑 교차 검증(아래) |

`verifySecretLogScan` 게이트가 원칙 V를 자동 차단하지만, 우회 코드(`MDC.put`)는 게이트가 못 잡으므로 리뷰가 본다.

## 통합 정합성 — "양쪽을 동시에 읽어라"

정합성 결함은 두 컴포넌트가 각각은 맞지만 경계면에서 어긋날 때 생긴다. 한쪽만 읽으면 못 잡는다. 반드시 양쪽을 같이 연다:

| 경계면 | 왼쪽 (생산자) | 오른쪽 (소비자) | 흔한 결함 |
|---|---|---|---|
| 인수 기준 | spec.md의 SC 문장 | 그 SC를 검증한다는 테스트 | 테스트는 있으나 SC가 말하는 동작을 실제로 단언하지 않음 |
| 직렬화 계약 | `OutboxEvent.payload` JSON 구조 | 역직렬화 record 필드명 | 필드명 불일치, 누락 필드 |
| 영속 계약 | JPA Entity 필드·`@Column` | 네이티브 쿼리 컬럼명·스키마 | 컬럼명 오타, 타입 불일치 |
| 설정 계약 | `@ConfigurationProperties` record 필드 | `application*.yml` 키 | 키 이름 불일치, 프로필별 누락 |
| tasks.md 명세 | 태스크가 지정한 시그니처 | 실제 구현 시그니처 | 메서드명·파라미터가 임의 변경됨 |

**핵심 질문은 "존재하는가"가 아니라 "일치하는가"다.** "SC를 검증하는 테스트가 있는가"가 아니라 "그 테스트가 SC의 동작을 실제로 단언하는가".

## 코드 품질 점검
- 가독성·네이밍이 이웃 코드와 일관적인가.
- 중복 로직, 죽은 코드, 미사용 import.
- 예외 처리 — 삼킨 예외, 빈 catch, 부적절하게 광범위한 catch.
- 트랜잭션 경계 — `@Transactional` 범위가 적절한가, 외부 호출이 트랜잭션 안에 갇히지 않는가.

## 심각도 분류
- **P1 (머지 차단)**: 헌법 7원칙 위반, 통합 정합성 결함, 미검증 SC.
- **P2 (권장 수정)**: 코드 품질 — 중복, 네이밍, 예외 처리.
- **P3 (제안)**: 선택적 개선.

모든 지적에 `파일:라인` + 위반 원칙/이유 + 구체적 수정 방향을 단다.

## 출력 형식

`phase{N}-review.md` 및 통합 `claude-review.md`는 다음 구조를 따른다:

```
## Phase N 리뷰 — {한 줄 요약}

### P1 (머지 차단)
- [ ] `파일:라인` — {위반 원칙/이유}. 수정: {방향}

### P2 (권장)
- [ ] `파일:라인` — {이유}. 수정: {방향}

### P3 (제안)
- {내용}

### 인수 기준 정합성
| SC | 검증 테스트 | SC 동작을 실제로 단언? |
|----|-----------|----------------------|
```

`claude-review.md`는 그대로 PR 본문에 들어가 Codex 리뷰와 짝을 이룬다. 각 지적은 추후 "채택" 또는 "기각 + 사유"로 처리된다(헌법 VI).

## 완료 기준
- 배정 Phase에 대해 P1 0건(또는 전부 수정·재검증됨).
- 모든 SC가 정합성 표에서 "실제로 단언함"으로 확인됨.
- `phase{N}-review.md` 기록 완료.

## 개요

동기 Flask 기반 이미지 유사도 인증 흐름을 **Redis Streams + Outbox 기반 비동기 파이프라인**으로 전환한다 (Spec 005).

- 인증 요청은 즉시 `202 Accepted + requestId` 반환, 클라이언트는 `GET /api/v1/verify/{request-id}` 로 폴링.
- 인증 요청은 Outbox 경유로 Redis Stream 에 발행(At-Least-Once), 외부 컨슈머가 추론 후 callback(`POST /api/v1/internal/verification-results`) 으로 결과 전달.
- 전 구간 헌법 7원칙 준수 (Testcontainers / 어댑터 격리 / Outbox 강제 / 시크릿 로그 금지 / 인수기준=테스트).

스펙·설계 문서: `specs/005-async-verification-pipeline/` (spec·plan·research·data-model·contracts·tasks·quickstart).

## 주요 변경

### US1 — 비동기 인증 흐름 (P1 MVP)
- `VerificationRequest`(PENDING/SUCCESS/FAIL/ERROR) 엔티티 + `requestId`(UUID) 4면 통합(entity·outbox·stream·callback) — R-004.
- 인증 접수: `VerificationServiceImpl.verifyTodayChallenge` → `VerificationRequest` INSERT + Outbox 적재 → 즉시 202.
- 발행: Outbox INSERT 후 `AFTER_COMMIT` 리스너가 Redis Stream(`verify:requests`) 에 BRIEF §3-1 계약 키로 발행.
- 결과 조회: `GET /api/v1/verify/{request-id}` (QueryDSL Projections, 본인 가드 404 통일).
- callback: `VerificationResultService` — optimistic UPDATE(`WHERE status='PENDING'`) 로 requestId 단위 멱등.

### US2 — At-Least-Once 보강 (P1)
- 컨슈머 장애(chaos) 시에도 접수 202 + Outbox PENDING 보존 → 복구 후 종착(`VerificationConsumerOutageIntegrationTest`).
- `OutboxRepublisher` 가 PENDING outbox 를 SKIP LOCKED 로 재발행(`VerificationOutboxRepublisherIntegrationTest`).

### US3 — 사용자·챌린지·날짜 단위 멱등 (P2)
- callback record 저장은 `existsTodayRecord`(verified 무관·unique 제약과 동일 키) 조회-후-저장으로 멱등 보장.

## 헌법 VI — 듀얼 AI 리뷰

### Claude 리뷰 (phase별, `_harness/phase*-review.md`)
| 발견 | 등급 | 처리 |
|---|---|---|
| Phase 3 P1-1 — stream entry 키가 BRIEF §3-1 컨슈머 계약과 불일치 | P1 | **채택·수정** — `MessageCommand`에 callbackUrl/threshold 추가, BRIEF 정합 키로 발행 |
| Phase 3 P2-A — outbox status PUBLISHED 전이 누락(AFTER_COMMIT 회색지대) | P1 | **채택·수정** — 리스너에 `@Transactional(REQUIRES_NEW)` |
| Phase 4 self-deadlock — REQUIRES_NEW × SKIP LOCKED | P1 | **채택·수정** — publisher 트랜잭션 책임 호출자 이관 + `OutboxPublishingHelper` 추출 |
| Phase 4 P2-1/2/3 (listener 예외 흡수 범위·helper findById silent·log.error 격하) | P2 | **보류** — Polish/후속 PR (사유: 동작 정상, 운영 관측성 개선 항목) |

### Codex 리뷰 (`codex review --base dev`)
| 발견 | 등급 | 처리 |
|---|---|---|
| callback record 저장의 `DataIntegrityViolationException` try/catch 가 무력 → 두 번째 callback 500 + status 롤백 + 재시도 폭주 | P1 | **채택·수정** |

Codex 가설의 *메커니즘 설명*("flush 가 catch 밖에서 터진다")은 false — `VerificationRecord`가 IDENTITY 전략이라 `save()` 시점 즉시 INSERT 되어 catch 는 정상 진입한다. 그러나 *결론*은 정확했다: `DataIntegrityViolationException` 발생 순간 Spring 이 트랜잭션을 `rollback-only` 로 마킹 → 바깥 `@Transactional` commit 시 `UnexpectedRollbackException` → HTTP 500 + status 전이 롤백. **T028 통합 테스트로 RED 재현 후, 조회-후-저장 방식으로 수정해 GREEN 확인.**

## 게이트 결과

- `./gradlew check` — **BUILD SUCCESSFUL** (전체 테스트 통과, Spec 001/002 회귀 0)
- `./gradlew verifySecretLogScan` — **clean** (헌법 V, SC-007)
- 헌법 NON-NEGOTIABLE (I Testcontainers / IV Outbox / V 시크릿 로그) 위반 0

## Known gaps (후속 처리)

- **T027** (requestId 멱등 전용 테스트) 미작성 — 구현(optimistic update)은 존재하며 US1 통합 테스트가 정상 흐름 일부 커버. 동시성 미고려 결정에 따라 우선순위 하향.
- **T029** (`InternalVerificationResultController` slice 테스트) 미작성.
- **T032** (plan.md Post-Implementation Constitution Re-Check 표) 미작성.
- **US3 동시성(race)**: 같은 사용자·날 callback 동시 도착은 미발생 전제(spec US3). DB unique 제약은 데이터 정합성 최종 방어로 유지하나 race 흡수는 보장하지 않음.
- Phase 4 P2-1/2/3 (listener 예외 흡수 범위 등) 운영 관측성 개선 항목.

## 테스트 계획

핵심 인수 기준은 Testcontainers(MySQL+Redis) 통합 테스트로 박제:
- SC-001 비동기 흐름 — `VerificationAsyncFlowIntegrationTest`
- SC-003 컨슈머 장애 복원 — `VerificationConsumerOutageIntegrationTest`
- SC-004 Outbox 재발행 — `VerificationOutboxRepublisherIntegrationTest`
- SC-005b 일별 멱등 — `VerificationDailyIdempotencyTest`

🤖 Generated with [Claude Code](https://claude.com/claude-code)

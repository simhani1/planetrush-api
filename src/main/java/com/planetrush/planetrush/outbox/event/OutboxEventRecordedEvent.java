package com.planetrush.planetrush.outbox.event;

import com.planetrush.planetrush.outbox.dto.OutboxRecordCommand;

/**
 * OutboxEvent 가 영속된 직후 발행되는 application event.
 *
 * <p>Spec 005 — Phase 3. {@code VerificationExternalEventRecorder.save(...)} 가 outbox row 를
 * INSERT 한 직후 본 이벤트를 publish 한다. {@link
 * com.planetrush.planetrush.verification.event.listener.VerificationOutboxPublishListener
 * VerificationOutboxPublishListener} (AFTER_COMMIT) 가 catch 해 Redis Stream 발행을 트리거한다.
 *
 * <h3>왜 새 이벤트 클래스가 필요한가</h3>
 * <p>기존 {@code VerificationEvent} 경로는 Flask 동기 흐름의 잔재 (deterministic UUID for eventId
 * via member:planet:imgs hash). Spec 005 의 신규 흐름은 UUID 를 서비스 레이어에서 직접 생성해
 * entity·outbox·stream·callback 4면에 동일 ID 로 흐른다(R-004). 본 이벤트는 그 requestId 를
 * 보존한 채 publish 트리거를 AFTER_COMMIT 으로 미루는 표면이다.
 *
 * <p>헌법 IV 정합: outbox INSERT 와 stream 발행의 원자성을 유지하는 핵심 표면 —
 * outbox INSERT 가 메인 트랜잭션에서 일어나고, stream 발행은 AFTER_COMMIT 으로 지연되어
 * 발행 실패 시 OutboxRepublisher 가 잔존 PENDING 을 자동 복구한다.
 *
 * @param command 원본 OutboxRecordCommand — payload 빌더가 보존한 requestId/이미지 URL/외부화 설정
 *                일체를 그대로 들고 있어 AFTER_COMMIT 리스너가 추가 조회 없이 발행 페이로드를 구성한다.
 */
public record OutboxEventRecordedEvent(OutboxRecordCommand command) {
}

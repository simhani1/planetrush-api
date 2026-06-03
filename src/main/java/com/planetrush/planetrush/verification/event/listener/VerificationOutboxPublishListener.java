package com.planetrush.planetrush.verification.event.listener;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.planetrush.planetrush.outbox.dto.OutboxRecordCommand;
import com.planetrush.planetrush.outbox.event.OutboxEventRecordedEvent;
import com.planetrush.planetrush.verification.event.publisher.VerificationMessagePublisher;
import com.planetrush.planetrush.verification.service.dto.MessageCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Spec 005 신규 흐름의 Redis Stream 발행 AFTER_COMMIT 리스너.
 *
 * <p>{@code VerificationExternalEventRecorder.save(...)} 가 outbox row INSERT 후
 * {@link OutboxEventRecordedEvent} 를 publish 한다. 본 리스너가 AFTER_COMMIT 으로 잡아
 * {@link VerificationMessagePublisher#publish}({@code MessageCommand}) 를 호출한다.
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * <ul>
 *   <li>outbox row INSERT 와 stream 발행을 한 트랜잭션에 합치지 않는다 — 발행 실패가
 *       메인 트랜잭션을 롤백시키지 않도록(원자성을 발행 측이 깨지 않도록).</li>
 *   <li>발행 실패 시 outbox row 는 PENDING 으로 잔존 — Spec 002 의 {@code OutboxRepublisher}
 *       가 폴링 사이클에서 자동 재발행한다(헌법 IV — 발행 At-Least-Once 보장).</li>
 * </ul>
 *
 * <h3>본 리스너의 책무 분리</h3>
 * <p>기존 {@code VerificationExternalMessageListener} 는 {@code VerificationEvent} 를 듣지만,
 * Spec 005 Phase 3 cutover 로 {@code VerificationEvent} 는 더 이상 publish 되지 않는다
 * (dead path). 본 리스너가 신규 흐름의 AFTER_COMMIT 발행을 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationOutboxPublishListener {

	private final VerificationMessagePublisher messagePublisher;

	@Value("${verification.callback-url}")
	private String callbackUrl;

	@Value("${verification.threshold:0.088}")
	private String threshold;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void publishAfterCommit(OutboxEventRecordedEvent event) {
		OutboxRecordCommand command = event.command();
		// 헌법 V — requestId 만 출력. payload/콜백 URL/threshold 등 본문 평문 미출현.
		log.info("[AFTER COMMIT] requestId={}", command.requestId());
		messagePublisher.publish(new MessageCommand(
			command.requestId(),
			command.targetImg(),
			command.standardImg(),
			command.memberId(),
			command.planetId(),
			callbackUrl,
			threshold
		));
	}
}

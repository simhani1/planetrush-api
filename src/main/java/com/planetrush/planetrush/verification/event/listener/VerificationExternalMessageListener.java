package com.planetrush.planetrush.verification.event.listener;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.planetrush.planetrush.verification.event.publisher.VerificationMessagePublisher;
import com.planetrush.planetrush.verification.service.dto.VerificationEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationExternalMessageListener {

	private final VerificationMessagePublisher messagePublisher;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void publish(VerificationEvent event) {
		log.info("[AFTER COMMIT] event {}", event);
		messagePublisher.publish(event.toMessageCommand());
	}
}
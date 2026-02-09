package com.planetrush.planetrush.verification.event.listener;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.planetrush.planetrush.outbox.VerificationExternalEventRecorder;
import com.planetrush.planetrush.verification.service.dto.VerificationEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationExternalEventRecordListener {

	private final VerificationExternalEventRecorder eventRecorder;

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	public void recordMessageHandler(VerificationEvent event) {
		log.info("[BEFORE COMMIT] event {}", event);
		eventRecorder.save(event.toRecordCommand());
	}
}

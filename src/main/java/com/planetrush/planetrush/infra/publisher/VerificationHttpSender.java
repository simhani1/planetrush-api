package com.planetrush.planetrush.infra.publisher;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.planetrush.planetrush.infra.flask.exception.FlaskConnectionFailedException;
import com.planetrush.planetrush.infra.flask.util.FlaskApiClient;
import com.planetrush.planetrush.outbox.VerificationExternalEventRecorder;
import com.planetrush.planetrush.outbox.domain.OutboxEvent;
import com.planetrush.planetrush.verification.event.publisher.VerificationMessagePublisher;
import com.planetrush.planetrush.verification.service.dto.MessageCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
	name = "verification.publisher.type",
	havingValue = "http",
	matchIfMissing = true
)
public class VerificationHttpSender implements VerificationMessagePublisher {

	private final FlaskApiClient flaskApiClient;
	private final VerificationExternalEventRecorder eventRecorder;

	@Transactional
	@Override
	public void publish(MessageCommand command) {
		log.info(
			"[HTTP Request] verification event memberId={}, planetId={}",
			command.memberId(),
			command.planetId()
		);
		try {
			flaskApiClient.verifyChallengeImg(command.standardImg(), command.targetImg());
			OutboxEvent outboxEvent = eventRecorder.findById(command.eventId());
			outboxEvent.published();
		} catch (FlaskConnectionFailedException e) {
			log.error("[HTTP Request] Flask Connection Failed", e);
		}
	}
}

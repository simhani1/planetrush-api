package com.planetrush.planetrush.infra.publisher;

import com.planetrush.planetrush.verification.event.publisher.VerificationMessagePublisher;
import com.planetrush.planetrush.verification.service.dto.MessageCommand;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NoopVerificationMessageSender implements VerificationMessagePublisher {

	@Override
	public void publish(MessageCommand command) {
		log.warn(
			"No MQ sender configured; skipping publish for verification event memberId={}, planetId={}",
			command.memberId(),
			command.planetId()
		);
	}
}

package com.planetrush.planetrush.verification.event.publisher;

import com.planetrush.planetrush.verification.service.dto.MessageCommand;

@FunctionalInterface
public interface VerificationMessagePublisher {
	void publish(MessageCommand command);
}

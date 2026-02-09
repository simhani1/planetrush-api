package com.planetrush.planetrush.verification.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.planetrush.planetrush.verification.service.dto.VerificationEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationEventService {

	private final ApplicationEventPublisher eventPublisher;

	public void publish(VerificationEvent event) {
		log.info("Publishing verification event: {}", event);
		eventPublisher.publishEvent(event);
	}
}

package com.planetrush.planetrush.outbox;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planetrush.planetrush.outbox.domain.EventType;
import com.planetrush.planetrush.outbox.domain.OutboxEvent;
import com.planetrush.planetrush.outbox.dto.OutboxRecordCommand;
import com.planetrush.planetrush.outbox.repository.OutboxRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationExternalEventRecorder {

	private final OutboxRepository outboxRepository;
	private final ObjectMapper objectMapper;

	@Transactional
	public void save(OutboxRecordCommand command) {
		String payload = buildPayload(command);
		log.info("Recording verification event on outbox table: {}", command.eventId());
		outboxRepository.save(OutboxEvent.pending(
			command.eventId(),
			EventType.VERIFICATION_REQUEST,
			payload
		));
	}

	private String buildPayload(OutboxRecordCommand command) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("standardImgUrl", command.standardImg());
		payload.put("verificationImgUrl", command.targetImg());
		payload.put("memberId", command.memberId());
		payload.put("planetId", command.planetId());
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to serialize verification outbox payload", e);
		}
	}
}

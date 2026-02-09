package com.planetrush.planetrush.infra.publisher;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.planetrush.planetrush.outbox.VerificationExternalEventRecorder;
import com.planetrush.planetrush.outbox.domain.OutboxEvent;
import com.planetrush.planetrush.verification.event.publisher.VerificationMessagePublisher;
import com.planetrush.planetrush.verification.service.dto.MessageCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "verification.publisher.type", havingValue = "redis-stream")
public class VerificationRedisStreamPublisher implements VerificationMessagePublisher {

	private final StringRedisTemplate redisTemplate;
	private final VerificationExternalEventRecorder eventRecorder;

	@Value("${verification.redis.stream-key:verification:stream}")
	private String streamKey;

	@Transactional
	@Override
	public void publish(MessageCommand command) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("eventId", command.eventId());
		fields.put("memberId", String.valueOf(command.memberId()));
		fields.put("planetId", String.valueOf(command.planetId()));
		fields.put("standardImg", command.standardImg());
		fields.put("targetImg", command.targetImg());

		try {
			RecordId recordId = redisTemplate.opsForStream().add(streamKey, fields);
			log.info(
				"[Redis Stream] published streamKey={}, recordId={}, eventId={}",
				streamKey,
				recordId.getValue(),
				command.eventId()
			);
			OutboxEvent outboxEvent = eventRecorder.findById(command.eventId());
			outboxEvent.published();
		} catch (RuntimeException e) {
			log.error("[Redis Stream] publish failed streamKey={}, eventId={}", streamKey, command.eventId(), e);
		}
	}
}

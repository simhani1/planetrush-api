package com.planetrush.planetrush.infra.publisher;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.planetrush.planetrush.outbox.VerificationExternalEventRecorder;
import com.planetrush.planetrush.verification.service.dto.MessageCommand;

class VerificationRedisStreamPublisherTest {

	private StringRedisTemplate redisTemplate;
	private VerificationExternalEventRecorder eventRecorder;
	@SuppressWarnings("rawtypes")
	private org.springframework.data.redis.core.StreamOperations streamOperations;
	private VerificationRedisStreamPublisher publisher;

	@BeforeEach
	void setUp() {
		redisTemplate = mock(StringRedisTemplate.class);
		streamOperations = mock(org.springframework.data.redis.core.StreamOperations.class);
		when(redisTemplate.opsForStream()).thenReturn(streamOperations);
		publisher = new VerificationRedisStreamPublisher(redisTemplate, eventRecorder);
		ReflectionTestUtils.setField(publisher, "streamKey", "verification:stream");
	}

	@Test
	void publishAddsRecordToStream() {
		MessageCommand command = new MessageCommand(
			"event-1",
			"http://example.com/target.jpg",
			"http://example.com/standard.jpg",
			1L,
			2L
		);
		when(streamOperations.add(eq("verification:stream"), anyMap()))
			.thenReturn(RecordId.of("1-0"));

		publisher.publish(command);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
		verify(streamOperations).add(eq("verification:stream"), captor.capture());
		Map<String, String> fields = captor.getValue();
		assertEquals("event-1", fields.get("eventId"));
		assertEquals("1", fields.get("memberId"));
		assertEquals("2", fields.get("planetId"));
		assertEquals("http://example.com/standard.jpg", fields.get("standardImg"));
		assertEquals("http://example.com/target.jpg", fields.get("targetImg"));
	}
}

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
		ReflectionTestUtils.setField(publisher, "streamKey", "verify:requests");
	}

	@Test
	void publishAddsRecordToStreamWithBriefContractKeys() {
		// Spec 005 — phase3-review §P1-1. stream entry 키는 BRIEF §3-1 정합.
		MessageCommand command = new MessageCommand(
			"req-1",
			"http://example.com/target.jpg",
			"http://example.com/standard.jpg",
			1L,
			2L,
			"http://host.docker.internal:8080/api/v1/internal/verification-results",
			"0.088"
		);
		when(streamOperations.add(eq("verify:requests"), anyMap()))
			.thenReturn(RecordId.of("1-0"));

		publisher.publish(command);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
		verify(streamOperations).add(eq("verify:requests"), captor.capture());
		Map<String, String> fields = captor.getValue();
		assertEquals("req-1", fields.get("requestId"));
		assertEquals("http://example.com/standard.jpg", fields.get("standardImgUrl"));
		assertEquals("http://example.com/target.jpg", fields.get("targetImgUrl"));
		assertEquals("http://host.docker.internal:8080/api/v1/internal/verification-results",
			fields.get("callbackUrl"));
		assertEquals("0.088", fields.get("threshold"));
		// memberId/planetId 는 BRIEF 계약에 없으므로 stream entry 에서 제외.
		assertNull(fields.get("memberId"));
		assertNull(fields.get("planetId"));
	}
}

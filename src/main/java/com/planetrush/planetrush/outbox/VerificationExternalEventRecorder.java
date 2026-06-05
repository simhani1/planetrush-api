package com.planetrush.planetrush.outbox;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planetrush.planetrush.outbox.domain.EventType;
import com.planetrush.planetrush.outbox.domain.OutboxEvent;
import com.planetrush.planetrush.outbox.dto.OutboxRecordCommand;
import com.planetrush.planetrush.outbox.event.OutboxEventRecordedEvent;
import com.planetrush.planetrush.outbox.exception.NoOutboxEventException;
import com.planetrush.planetrush.outbox.repository.OutboxRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 인증 외부 이벤트의 Outbox 적재 어댑터.
 *
 * <p>Spec 005 — R-004. {@link OutboxRecordCommand#requestId()} 를 {@code OutboxEvent.id} 로 사용해
 * {@code VerificationRequest.id} ↔ {@code OutboxEvent.id} ↔ stream {@code requestId} ↔ callback {@code requestId}
 * 4면을 한 UUID 로 통합한다. payload 는 BRIEF §3-1 컨슈머 계약을 그대로 인코딩한다.
 *
 * <h3>헌법 IV 정합</h3>
 * <p>본 컴포넌트는 OutboxRepository 만 호출한다. Redis Stream 직접 발행 0 — 발행은 AFTER_COMMIT 리스너 →
 * {@code VerificationRedisStreamPublisher} 에 위임한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationExternalEventRecorder {

	private final OutboxRepository outboxRepository;
	private final ObjectMapper objectMapper;
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * 컨슈머 callback URL. {@code application.yml} 의 {@code verification.callback-url} 키로 외부화.
	 */
	@Value("${verification.callback-url}")
	private String callbackUrl;

	/**
	 * 컨슈머 유사도 판정 임계값. 부재 시 컨슈머 기본값과 동일한 {@code "0.088"}.
	 */
	@Value("${verification.threshold:0.088}")
	private String threshold;

	/**
	 * 인증 이벤트를 Outbox 에 PENDING 으로 적재하고 stream 발행 트리거(AFTER_COMMIT) 를 publish 한다.
	 *
	 * <p>Spec 005 — Phase 3. {@link OutboxEventRecordedEvent} 를 publish 해
	 * {@code VerificationOutboxPublishListener} (AFTER_COMMIT) 가 Redis Stream 으로 발행하게 한다.
	 * 본 메서드는 메인 트랜잭션 안에서 호출되므로, 커밋이 실패하면 outbox INSERT 와 이벤트 publish 가
	 * 함께 롤백된다 — 헌법 IV 의 원자성 보장.
	 */
	@Transactional
	public void save(OutboxRecordCommand command) {
		String payload = buildPayload(command);
		// 헌법 V — requestId 만 출력. payload 전체 평문 로그 금지.
		log.info("Recording verification event on outbox table: {}", command.requestId());
		outboxRepository.save(OutboxEvent.pending(
			command.requestId(),
			EventType.VERIFICATION_REQUEST,
			payload
		));
		// AFTER_COMMIT 트리거. publish 자체는 메인 트랜잭션 커밋 이후로 지연된다(헌법 IV).
		eventPublisher.publishEvent(new OutboxEventRecordedEvent(command));
	}

	private String buildPayload(OutboxRecordCommand command) {
		// BRIEF §3-1: requestId / standardImgUrl / targetImgUrl / callbackUrl / threshold.
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("requestId", command.requestId());
		payload.put("standardImgUrl", command.standardImg());
		payload.put("targetImgUrl", command.targetImg());
		payload.put("callbackUrl", callbackUrl);
		payload.put("threshold", threshold);
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to serialize verification outbox payload", e);
		}
	}

	@Transactional(readOnly = true)
	public OutboxEvent findById(String eventId) {
		return outboxRepository.findById(eventId)
			.orElseThrow(() -> new NoOutboxEventException("No outbox event with id " + eventId));
	}
}

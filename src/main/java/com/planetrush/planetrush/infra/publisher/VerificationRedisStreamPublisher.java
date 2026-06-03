package com.planetrush.planetrush.infra.publisher;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
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

	@Value("${verification.redis.stream-key:verify:requests}")
	private String streamKey;

	/**
	 * Spec 005 — Phase 3 P2-A fix. AFTER_COMMIT 리스너 컨텍스트에서 진입하는 본 메서드는
	 * 메인 트랜잭션이 닫힌 후 호출된다 — REQUIRED 기본 propagation 으로는 새 트랜잭션이
	 * 의도대로 활성화되지 못해 {@code findById} 로 가져온 OutboxEvent 가 detached 상태가
	 * 되고 {@code published()} 의 dirty 변경이 flush 되지 않는다(통합 테스트로 실측 확인).
	 * REQUIRES_NEW 로 새 트랜잭션을 강제 시작해 영속 컨텍스트를 확보한다.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	@Override
	public void publish(MessageCommand command) {
		// Spec 005 — phase3-review §P1-1 fix. stream entry 키 set 을 BRIEF §3-1 (컨슈머 계약)
		// 정합으로 매핑한다. memberId/planetId 는 컨슈머 미사용이므로 stream entry 에서 제외.
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("requestId", command.eventId());
		fields.put("standardImgUrl", command.standardImg());
		fields.put("targetImgUrl", command.targetImg());
		fields.put("callbackUrl", command.callbackUrl());
		fields.put("threshold", command.threshold());

		try {
			RecordId recordId = redisTemplate.opsForStream().add(streamKey, fields);
			log.info(
				"[Redis Stream] published streamKey={}, recordId={}, requestId={}",
				streamKey,
				recordId.getValue(),
				command.eventId()
			);
			OutboxEvent outboxEvent = eventRecorder.findById(command.eventId());
			outboxEvent.published();
		} catch (RuntimeException e) {
			log.error("[Redis Stream] publish failed streamKey={}, requestId={}", streamKey, command.eventId(), e);
		}
	}
}

package com.planetrush.planetrush.infra.publisher;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.planetrush.planetrush.verification.event.publisher.VerificationMessagePublisher;
import com.planetrush.planetrush.verification.service.dto.MessageCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis Stream 발행 어댑터 (헌법 II — 외부 의존 어댑터 격리).
 *
 * <p>Spec 005 — Phase 4 후속 fix. 본 메서드는 트랜잭션을 만들지 않는다 — 두 호출 경로
 * (AFTER_COMMIT 리스너 / OutboxRepublisher) 의 트랜잭션 컨텍스트가 본질적으로 달라
 * publisher 가 자체 트랜잭션을 들면 한쪽이 반드시 깨진다. 트랜잭션 책임은 호출자에게
 * 위임하며, 본 메서드는 Redis XADD 만 수행한다.
 *
 * <p>outbox status 갱신(PENDING → PUBLISHED) 도 호출자가 책임진다. 본 메서드가 정상
 * 종료(예외 미발생) 하면 호출자가 같은 트랜잭션의 managed OutboxEvent 에 {@code
 * published()} 를 호출해 dirty checking 으로 flush 한다. 헬퍼는 {@code
 * OutboxPublishingHelper} 참조.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "verification.publisher.type", havingValue = "redis-stream")
public class VerificationRedisStreamPublisher implements VerificationMessagePublisher {

	private final StringRedisTemplate redisTemplate;

	@Value("${verification.redis.stream-key:verify:requests}")
	private String streamKey;

	@Override
	public void publish(MessageCommand command) {
		// Spec 005 — phase3-review §P1-1. stream entry 키 set 은 BRIEF §3-1 (컨슈머 계약) 정합.
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("requestId", command.eventId());
		fields.put("standardImgUrl", command.standardImg());
		fields.put("targetImgUrl", command.targetImg());
		fields.put("callbackUrl", command.callbackUrl());
		fields.put("threshold", command.threshold());

		RecordId recordId = redisTemplate.opsForStream().add(streamKey, fields);
		log.info(
			"[Redis Stream] published streamKey={}, recordId={}, requestId={}",
			streamKey,
			recordId.getValue(),
			command.eventId()
		);
	}
}

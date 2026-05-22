package com.planetrush.planetrush.outbox.republisher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planetrush.planetrush.outbox.domain.OutboxEvent;
import com.planetrush.planetrush.outbox.domain.OutboxStatus;
import com.planetrush.planetrush.outbox.repository.OutboxRepository;
import com.planetrush.planetrush.verification.event.publisher.VerificationMessagePublisher;
import com.planetrush.planetrush.verification.service.dto.MessageCommand;

import lombok.extern.slf4j.Slf4j;

/**
 * PENDING 상태로 남은 {@link OutboxEvent}를 재발행하는 폴러 로직.
 *
 * <p>Spec 002 — At-Least-Once 발행 보장(US1). 발행 직전 앱이 죽거나
 * `VerificationRedisStreamPublisher` 발행이 실패해 PENDING으로 남은 outbox를
 * 자동 복구한다.
 *
 * <p>본 클래스는 스케줄 트리거를 포함하지 않는다 — {@link OutboxRepublisherScheduler}가
 * `@Scheduled`로 {@link #republishPending()}을 호출한다. 로직 빈과 트리거를 분리한
 * 이유: `test` 프로필은 스케줄 트리거를 비활성화(FR-006)하되, 통합 테스트는 본
 * 빈을 주입받아 `@Transactional` 프록시가 적용된 채로 직접 호출해야 SKIP LOCKED
 * 락 동작까지 결정론적으로 검증할 수 있기 때문이다.
 */
@Slf4j
@Component
@EnableConfigurationProperties(OutboxRepublisherProperties.class)
public class OutboxRepublisher {

	private final OutboxRepository outboxRepository;
	private final VerificationMessagePublisher messagePublisher;
	private final ObjectMapper objectMapper;
	private final OutboxRepublisherProperties properties;

	public OutboxRepublisher(
			OutboxRepository outboxRepository,
			VerificationMessagePublisher messagePublisher,
			ObjectMapper objectMapper,
			OutboxRepublisherProperties properties) {
		this.outboxRepository = outboxRepository;
		this.messagePublisher = messagePublisher;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	/**
	 * 폴러 사이클 1회. 컷오프 이내의 PENDING outbox를 SKIP LOCKED로 조회해 재발행한다.
	 *
	 * <p>사이클 전체가 단일 트랜잭션이다(research R-002) — 조회된 행의 락이 커밋까지
	 * 유지되어 다른 폴러 인스턴스는 해당 행을 건너뛴다. 한 건의 발행 실패가 사이클
	 * 전체를 롤백시키지 않도록, 발행 실패 건은 PENDING으로 남아 다음 사이클에
	 * 재시도된다(FR-003).
	 */
	@Transactional
	public void republishPending() {
		Instant cutoff = Instant.now().minus(properties.cutoffMinutes(), ChronoUnit.MINUTES);
		List<OutboxEvent> batch = outboxRepository.findRepublishableForUpdateSkipLocked(
				OutboxStatus.PENDING.ordinal(), cutoff, properties.batchSize());
		if (batch.isEmpty()) {
			return;
		}
		int succeeded = 0;
		for (OutboxEvent event : batch) {
			if (republishOne(event)) {
				succeeded++;
			}
		}
		log.info("[OutboxRepublisher] republish cycle done: {}/{} succeeded", succeeded, batch.size());
	}

	/**
	 * outbox 1건 재발행. payload를 역직렬화해 {@link MessageCommand}로 변환 후
	 * 기존 발행 경로({@link VerificationMessagePublisher})에 위임한다.
	 * 발행이 성공하면 위임 대상이 내부에서 {@link OutboxEvent#published()}를 호출한다.
	 *
	 * @return 발행 후 상태가 PUBLISHED이면 true
	 */
	private boolean republishOne(OutboxEvent event) {
		try {
			VerificationOutboxPayload payload =
					objectMapper.readValue(event.getPayload(), VerificationOutboxPayload.class);
			messagePublisher.publish(payload.toMessageCommand(event));
			return event.getStatus() == OutboxStatus.PUBLISHED;
		} catch (Exception e) {
			log.warn("[OutboxRepublisher] failed to republish outbox event id={}", event.getId(), e);
			return false;
		}
	}
}

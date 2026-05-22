package com.planetrush.planetrush.outbox.republisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.planetrush.planetrush.IntegrationTest;
import com.planetrush.planetrush.outbox.domain.EventType;
import com.planetrush.planetrush.outbox.domain.OutboxEvent;
import com.planetrush.planetrush.outbox.domain.OutboxStatus;
import com.planetrush.planetrush.outbox.repository.OutboxRepository;
import com.planetrush.planetrush.verification.event.publisher.VerificationMessagePublisher;
import com.planetrush.planetrush.verification.service.dto.MessageCommand;

/**
 * Spec 002 US1·US3 인수 기준 검증.
 *
 * <p>SC-001: Publisher 일시 장애 시 폴러가 자동 재시도하여 메시지가 결국 발행됨.
 * <p>SC-003: createdAt 컷오프 초과 PENDING은 폴링 대상에서 제외됨.
 *
 * <p>발행 경로(`VerificationMessagePublisher`)는 `@MockBean`으로 대체해 결정론적으로
 * 검증한다. mock은 실제 `VerificationRedisStreamPublisher`의 계약 — "발행 성공 시
 * 해당 OutboxEvent를 published()로 전환" — 을 흉내 낸다.
 */
class OutboxRepublisherIntegrationTest extends IntegrationTest {

	@Autowired
	private OutboxRepublisher outboxRepublisher;

	@Autowired
	private OutboxRepository outboxRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockBean
	private VerificationMessagePublisher messagePublisher;

	@AfterEach
	void cleanUp() {
		outboxRepository.deleteAll();
	}

	@Test
	@DisplayName("SC-001: PENDING outbox를 폴러가 발견해 재발행하면 PUBLISHED로 전환된다")
	void pendingEventIsRepublishedToPublished() {
		// given
		String eventId = UUID.randomUUID().toString();
		outboxRepository.save(OutboxEvent.pending(eventId, EventType.VERIFICATION_REQUEST, payloadJson()));
		stubPublishSuccess();

		// when
		outboxRepublisher.republishPending();

		// then
		assertThat(outboxRepository.findById(eventId).orElseThrow().getStatus())
				.isEqualTo(OutboxStatus.PUBLISHED);
	}

	@Test
	@DisplayName("SC-001: 일시 장애 시 PENDING으로 남고, 장애 해소 후 사이클에서 재발행에 성공한다")
	void transientFailureKeepsPendingThenSucceedsOnRetry() {
		// given
		String eventId = UUID.randomUUID().toString();
		outboxRepository.save(OutboxEvent.pending(eventId, EventType.VERIFICATION_REQUEST, payloadJson()));

		// when: 1차 사이클 — 발행이 일시 장애 (published() 미호출)
		doNothing().when(messagePublisher).publish(any(MessageCommand.class));
		outboxRepublisher.republishPending();

		// then: 여전히 PENDING
		assertThat(outboxRepository.findById(eventId).orElseThrow().getStatus())
				.isEqualTo(OutboxStatus.PENDING);

		// when: 2차 사이클 — 장애 해소
		stubPublishSuccess();
		outboxRepublisher.republishPending();

		// then: PUBLISHED
		assertThat(outboxRepository.findById(eventId).orElseThrow().getStatus())
				.isEqualTo(OutboxStatus.PUBLISHED);
	}

	@Test
	@DisplayName("SC-003: createdAt이 컷오프를 초과한 오래된 PENDING은 재발행되지 않고 PENDING으로 잔존한다")
	void staleEventBeyondCutoffIsExcludedFromPolling() {
		// given: 컷오프(기본 5분)보다 오래된 PENDING + 컷오프 이내 PENDING
		String staleId = UUID.randomUUID().toString();
		String freshId = UUID.randomUUID().toString();
		outboxRepository.save(OutboxEvent.pending(staleId, EventType.VERIFICATION_REQUEST, payloadJson()));
		outboxRepository.save(OutboxEvent.pending(freshId, EventType.VERIFICATION_REQUEST, payloadJson()));
		// created_at은 @CreationTimestamp라 JdbcTemplate native update로 60분 전 시각 주입
		forceCreatedAt(staleId, 60);
		stubPublishSuccess();

		// when
		outboxRepublisher.republishPending();

		// then: 컷오프 이내 건만 PUBLISHED, 오래된 건은 PENDING 잔존
		assertThat(outboxRepository.findById(freshId).orElseThrow().getStatus())
				.isEqualTo(OutboxStatus.PUBLISHED);
		assertThat(outboxRepository.findById(staleId).orElseThrow().getStatus())
				.isEqualTo(OutboxStatus.PENDING);
	}

	@Test
	@DisplayName("SC-005: 폴러 사이클 1회가 조회된 PENDING 배치를 모두 그 사이클 내에 발행한다")
	void singleCyclePublishesEntireBatch() {
		// given: batch-size(기본 100) 이내의 PENDING 10건
		for (int i = 0; i < 10; i++) {
			outboxRepository.save(OutboxEvent.pending(
					UUID.randomUUID().toString(), EventType.VERIFICATION_REQUEST, payloadJson()));
		}
		stubPublishSuccess();

		// when: 폴러 사이클 1회
		outboxRepublisher.republishPending();

		// then: 조회된 배치 전량이 같은 사이클 안에서 PUBLISHED
		// (운영 환경의 실제 발행 지연은 폴링 주기에 의해 결정됨 — spec.md Assumptions)
		assertThat(outboxRepository.findAll())
				.hasSize(10)
				.allMatch(event -> event.getStatus() == OutboxStatus.PUBLISHED);
	}

	/**
	 * mock 발행기가 실제 `VerificationRedisStreamPublisher`의 계약을 흉내 낸다:
	 * 발행이 성공하면 해당 OutboxEvent를 published()로 전환한다.
	 */
	private void stubPublishSuccess() {
		doAnswer(invocation -> {
			MessageCommand command = invocation.getArgument(0);
			outboxRepository.findById(command.eventId()).ifPresent(OutboxEvent::published);
			return null;
		}).when(messagePublisher).publish(any(MessageCommand.class));
	}

	/**
	 * `@CreationTimestamp`로 자동 설정되는 created_at을 테스트에서 과거 시각으로
	 * 강제 변경한다(컷오프 시나리오용).
	 *
	 * <p>MySQL {@code UTC_TIMESTAMP()} 기반으로 갱신한다 — `java.sql.Timestamp`는
	 * JDBC 전송 시 JVM 기본 시간대(KST)로 해석되어 `@CreationTimestamp`의 UTC 저장과
	 * 어긋나므로, DB 서버가 UTC datetime을 직접 계산하도록 위임한다.
	 */
	private void forceCreatedAt(String eventId, long minutesAgo) {
		jdbcTemplate.update(
				"UPDATE outbox_event SET created_at = DATE_SUB(UTC_TIMESTAMP(), INTERVAL ? MINUTE) WHERE id = ?",
				minutesAgo, eventId);
	}

	private String payloadJson() {
		return "{\"standardImgUrl\":\"https://img.example/std.jpg\","
				+ "\"verificationImgUrl\":\"https://img.example/target.jpg\","
				+ "\"memberId\":1,\"planetId\":2}";
	}
}

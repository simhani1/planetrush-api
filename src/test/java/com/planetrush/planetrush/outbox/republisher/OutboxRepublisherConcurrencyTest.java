package com.planetrush.planetrush.outbox.republisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import com.planetrush.planetrush.IntegrationTest;
import com.planetrush.planetrush.outbox.domain.EventType;
import com.planetrush.planetrush.outbox.domain.OutboxEvent;
import com.planetrush.planetrush.outbox.domain.OutboxStatus;
import com.planetrush.planetrush.outbox.repository.OutboxRepository;
import com.planetrush.planetrush.verification.event.publisher.VerificationMessagePublisher;
import com.planetrush.planetrush.verification.service.dto.MessageCommand;

/**
 * Spec 002 US2 인수 기준 검증.
 *
 * <p>SC-002: PENDING outbox 100건에 대해 폴러 2개를 동시 실행해도 각 outbox가
 * 정확히 1회만 발행된다. `FOR UPDATE SKIP LOCKED`가 동일 행의 이중 처리를
 * 차단함을 검증한다.
 *
 * <p>`batch-size`를 20으로 낮춰(`@TestPropertySource`) 한 사이클이 전체를
 * 독식하지 못하게 하고, 두 폴러 스레드가 여러 사이클에 걸쳐 실제로 경합하도록
 * 한다. `@RepeatedTest(10)`으로 경합 안정성을 반복 확인한다.
 */
@TestPropertySource(properties = "outbox.republisher.batch-size=20")
class OutboxRepublisherConcurrencyTest extends IntegrationTest {

	private static final int PENDING_COUNT = 100;
	private static final int POLLER_THREADS = 2;
	private static final int CYCLES_PER_THREAD = 20; // 2 threads × 20 cycles × 20 batch = 800 capacity > 100

	@Autowired
	private OutboxRepublisher outboxRepublisher;

	@Autowired
	private OutboxRepository outboxRepository;

	@MockBean
	private VerificationMessagePublisher messagePublisher;

	@AfterEach
	void cleanUp() {
		outboxRepository.deleteAll();
	}

	@RepeatedTest(10)
	@DisplayName("SC-002: 폴러 2개 동시 실행에서 각 outbox는 정확히 1회만 발행된다")
	void concurrentPollersPublishEachOutboxExactlyOnce() throws Exception {
		// given: PENDING outbox 100건 + eventId별 발행 횟수를 세는 mock
		for (int i = 0; i < PENDING_COUNT; i++) {
			outboxRepository.save(OutboxEvent.pending(
					UUID.randomUUID().toString(), EventType.VERIFICATION_REQUEST, payloadJson()));
		}
		ConcurrentHashMap<String, Integer> publishCount = new ConcurrentHashMap<>();
		doAnswer(invocation -> {
			MessageCommand command = invocation.getArgument(0);
			publishCount.merge(command.eventId(), 1, Integer::sum);
			outboxRepository.findById(command.eventId()).ifPresent(OutboxEvent::published);
			return null;
		}).when(messagePublisher).publish(any(MessageCommand.class));

		// when: 폴러 2개를 동시에 출발시킨다
		ExecutorService executor = Executors.newFixedThreadPool(POLLER_THREADS);
		CountDownLatch startLatch = new CountDownLatch(1);
		Future<?>[] futures = new Future<?>[POLLER_THREADS];
		for (int t = 0; t < POLLER_THREADS; t++) {
			futures[t] = executor.submit(() -> {
				startLatch.await();
				for (int cycle = 0; cycle < CYCLES_PER_THREAD; cycle++) {
					outboxRepublisher.republishPending();
				}
				return null;
			});
		}
		startLatch.countDown();
		for (Future<?> future : futures) {
			future.get(30, TimeUnit.SECONDS);
		}
		executor.shutdown();

		// then: 전부 PUBLISHED, 각 outbox는 정확히 1회만 발행
		assertThat(outboxRepository.findAll())
				.hasSize(PENDING_COUNT)
				.allMatch(event -> event.getStatus() == OutboxStatus.PUBLISHED);
		assertThat(publishCount)
				.as("모든 outbox가 발행되어야 한다")
				.hasSize(PENDING_COUNT);
		assertThat(publishCount.values())
				.as("어떤 outbox도 두 번 발행되어선 안 된다 (SKIP LOCKED 이중 처리 차단)")
				.allMatch(count -> count == 1);
	}

	private String payloadJson() {
		return "{\"standardImgUrl\":\"https://img.example/std.jpg\","
				+ "\"verificationImgUrl\":\"https://img.example/target.jpg\","
				+ "\"memberId\":1,\"planetId\":2}";
	}
}

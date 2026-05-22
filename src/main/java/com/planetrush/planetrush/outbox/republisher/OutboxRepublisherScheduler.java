package com.planetrush.planetrush.outbox.republisher;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * {@link OutboxRepublisher#republishPending()}를 주기적으로 호출하는 스케줄 트리거.
 *
 * <p>Spec 002 — FR-006. `outbox.republisher.enabled=true`일 때만 빈으로 등록된다
 * (`test` 프로필은 `false`라 미등록 → 자동 폴링이 통합 테스트와 간섭하지 않음).
 * 폴링 주기는 `outbox.republisher.polling-interval-ms` 설정값을 따른다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "outbox.republisher.enabled", havingValue = "true")
public class OutboxRepublisherScheduler {

	private final OutboxRepublisher outboxRepublisher;

	@Scheduled(fixedDelayString = "${outbox.republisher.polling-interval-ms:5000}")
	public void schedule() {
		outboxRepublisher.republishPending();
	}
}

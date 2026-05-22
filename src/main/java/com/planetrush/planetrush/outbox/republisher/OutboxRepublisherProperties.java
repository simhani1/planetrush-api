package com.planetrush.planetrush.outbox.republisher;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outbox 재발행 폴러 설정.
 *
 * <p>Spec 002 — FR-005. 컷오프·폴링 주기·배치 크기를 코드 수정 없이 조정할 수
 * 있도록 외부화한다. `application.yml`의 {@code outbox.republisher.*}에 바인딩된다.
 *
 * @param enabled            폴러 활성 여부 (test 프로필은 false)
 * @param pollingIntervalMs  폴링 주기(밀리초)
 * @param cutoffMinutes      createdAt 컷오프(분) — 초과한 PENDING은 폴링 제외
 * @param batchSize          사이클당 처리 상한
 */
@ConfigurationProperties(prefix = "outbox.republisher")
public record OutboxRepublisherProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("60000") long pollingIntervalMs,
		@DefaultValue("5") long cutoffMinutes,
		@DefaultValue("100") int batchSize
) {

	public OutboxRepublisherProperties {
		if (pollingIntervalMs <= 0) {
			throw new IllegalArgumentException("outbox.republisher.polling-interval-ms must be positive");
		}
		if (cutoffMinutes <= 0) {
			throw new IllegalArgumentException("outbox.republisher.cutoff-minutes must be positive");
		}
		if (batchSize <= 0) {
			throw new IllegalArgumentException("outbox.republisher.batch-size must be positive");
		}
	}
}

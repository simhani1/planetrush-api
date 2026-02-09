package com.planetrush.planetrush.outbox.domain;

public enum OutboxStatus {
	PENDING,
	PUBLISHED,
	RETRY_REQUIRED
}

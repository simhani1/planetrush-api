package com.planetrush.planetrush.outbox.domain;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "outbox_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

	/**
	 * 아웃박스 이벤트의 고유 식별자입니다.
	 */
	@Id
	@Column(name = "id", length = 36, nullable = false)
	private String id;

	/**
	 * 이벤트의 종류를 나타냅니다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", length = 64, nullable = false)
	private EventType eventType;

	/**
	 * 메시지로 발행될 이벤트 본문(JSON 문자열)입니다.
	 */
	@Column(name = "payload", columnDefinition = "TEXT", nullable = false)
	private String payload;

	/**
	 * 아웃박스 처리 상태를 나타냅니다.
	 */
	@Column(name = "status", nullable = false)
	private OutboxStatus status;

	/**
	 * 레코드가 저장된 시각입니다.
	 */
	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected OutboxEvent(String id, EventType eventType, String payload, OutboxStatus status) {
		this.id = id;
		this.eventType = eventType;
		this.payload = payload;
		this.status = status;
	}

	public static OutboxEvent pending(String id, EventType eventType, String payload) {
		return new OutboxEvent(id, eventType, payload, OutboxStatus.PENDING);
	}
}

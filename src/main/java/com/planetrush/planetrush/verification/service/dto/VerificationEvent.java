package com.planetrush.planetrush.verification.service.dto;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.planetrush.planetrush.outbox.dto.OutboxRecordCommand;

public record VerificationEvent(
	String targetImg,
	String standardImg,
	Long memberId,
	Long planetId
) {

	public OutboxRecordCommand toRecordCommand() {
		// Spec 005 — T004 호환: requestId/eventId 모두 deterministic eventId 로 주입.
		// Phase 3 (T023) 에서 신규 흐름으로 교체되며 본 호출 경로 자체가 제거된다 (R-008 cutover).
		String eventId = createEventId();
		return new OutboxRecordCommand(
			eventId,
			eventId,
			targetImg,
			standardImg,
			memberId,
			planetId
		);
	}

	public MessageCommand toMessageCommand() {
		return new MessageCommand(
			createEventId(),
			targetImg,
			standardImg,
			memberId,
			planetId
		);
	}

	private String createEventId() {
		String seed = memberId
			+ ":" + planetId
			+ ":" + standardImg
			+ ":" + targetImg;
		return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
	}
}

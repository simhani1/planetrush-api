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
		return new OutboxRecordCommand(
			createEventId(),
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

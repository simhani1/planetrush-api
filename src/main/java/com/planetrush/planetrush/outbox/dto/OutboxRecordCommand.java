package com.planetrush.planetrush.outbox.dto;

public record OutboxRecordCommand(
	String eventId,
	String targetImg,
	String standardImg,
	Long memberId,
	Long planetId
) {
}

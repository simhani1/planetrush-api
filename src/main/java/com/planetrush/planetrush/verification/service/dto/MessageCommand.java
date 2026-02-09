package com.planetrush.planetrush.verification.service.dto;

public record MessageCommand(
	String eventId,
	String targetImg,
	String standardImg,
	Long memberId,
	Long planetId
) {
}

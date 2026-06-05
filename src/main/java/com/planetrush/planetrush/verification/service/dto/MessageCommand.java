package com.planetrush.planetrush.verification.service.dto;

/**
 * Redis Stream 발행용 명령 DTO.
 *
 * <p>Spec 005 — phase3-review §P1-1 fix. publisher 가 stream entry 키를 BRIEF §3-1 정합
 * ({@code requestId/standardImgUrl/targetImgUrl/callbackUrl/threshold}) 으로 매핑하기 위해
 * {@code callbackUrl}/{@code threshold} 필드를 추가했다. {@code eventId} 는 publisher 단에서
 * {@code requestId} 로 변환된다 (동일 UUID — R-004).
 *
 * <p>{@code memberId/planetId} 는 BRIEF 계약에 없지만 Spec 002 republisher 의 기존 호출 호환을
 * 위해 유지한다 — publisher 가 stream entry 로 발행하지 않는다.
 */
public record MessageCommand(
	String eventId,
	String targetImg,
	String standardImg,
	Long memberId,
	Long planetId,
	String callbackUrl,
	String threshold
) {
}

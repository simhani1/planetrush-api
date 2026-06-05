package com.planetrush.planetrush.outbox.dto;

/**
 * Outbox record 적재 명령 DTO.
 *
 * <p>Spec 005 — R-004. {@code requestId} 필드 추가. {@code requestId} 는
 * {@code VerificationRequest.id} 와 동일 UUID 이며, {@link
 * com.planetrush.planetrush.outbox.VerificationExternalEventRecorder} 가 이를
 * {@code OutboxEvent.id} 로 사용한다(entity·outbox·stream·callback 4면 동일 UUID).
 *
 * <p>{@code eventId} 는 기존 호환성을 위해 유지 — 일부 호출처(예: {@code VerificationEvent.toRecordCommand})
 * 가 점진 마이그레이션 중. Phase 3 (T023) 에서 호출처가 {@code requestId} 만 주입하도록 일원화될 예정.
 */
public record OutboxRecordCommand(
	String requestId,
	String eventId,
	String targetImg,
	String standardImg,
	Long memberId,
	Long planetId
) {
}

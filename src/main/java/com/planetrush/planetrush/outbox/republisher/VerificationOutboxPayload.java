package com.planetrush.planetrush.outbox.republisher;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.planetrush.planetrush.outbox.domain.OutboxEvent;
import com.planetrush.planetrush.verification.service.dto.MessageCommand;

/**
 * {@link OutboxEvent#getPayload()}(JSON)의 역직렬화 대상.
 *
 * <p>Spec 002 — research R-003. 기존 {@code VerificationExternalEventRecorder.buildPayload()} 의
 * {@code {standardImgUrl, verificationImgUrl, memberId, planetId}} 와 일치했으나, Spec 005 R-004 에서
 * 컨슈머 계약(BRIEF §3-1) 정합을 위해 키 명을 {@code verificationImgUrl → targetImgUrl} 로 변경하고
 * {@code requestId/callbackUrl/threshold} 를 추가했다.
 *
 * <p>본 record 는 {@link JsonAlias} 로 신/구 키를 모두 받아 폴러의 호환성을 유지한다.
 * {@code memberId/planetId} 는 신규 payload 에 더는 포함되지 않으므로 null 일 수 있다 —
 * Spec 005 의 새 흐름에서는 {@link #toMessageCommand}가 호출되지 않고, 발행 경로 자체가
 * 새 stream 메시지 계약을 따른다(Spec 005 Phase 3 에서 publisher 정합 보강).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerificationOutboxPayload(
		String requestId,
		String standardImgUrl,
		@JsonAlias({"targetImgUrl", "verificationImgUrl"}) String targetImgUrl,
		String callbackUrl,
		String threshold,
		Long memberId,
		Long planetId
) {

	/**
	 * OutboxEvent와 역직렬화된 payload로 발행용 MessageCommand를 조립한다.
	 * {@code eventId}는 OutboxEvent의 id, 이미지 URL은 payload에서 가져온다.
	 *
	 * <p>본 메서드는 Spec 002 흐름의 호환 경로다 — Spec 005 신규 흐름은 publisher 가
	 * payload 의 5필드(BRIEF §3-1)를 직접 stream 으로 발행한다(Phase 3).
	 */
	public MessageCommand toMessageCommand(OutboxEvent event) {
		return new MessageCommand(
				event.getId(),
				targetImgUrl,
				standardImgUrl,
				memberId,
				planetId,
				callbackUrl,
				threshold
		);
	}
}

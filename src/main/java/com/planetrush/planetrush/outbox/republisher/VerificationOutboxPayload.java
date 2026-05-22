package com.planetrush.planetrush.outbox.republisher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.planetrush.planetrush.outbox.domain.OutboxEvent;
import com.planetrush.planetrush.verification.service.dto.MessageCommand;

/**
 * {@link OutboxEvent#getPayload()}(JSON)의 역직렬화 대상.
 *
 * <p>Spec 002 — research R-003. `VerificationExternalEventRecorder.buildPayload()`가
 * 생성한 {@code {standardImgUrl, verificationImgUrl, memberId, planetId}} 구조와
 * 일치한다. 폴러는 이 record로 payload를 역직렬화한 뒤 {@link #toMessageCommand}로
 * 발행용 {@link MessageCommand}를 조립한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerificationOutboxPayload(
		String standardImgUrl,
		String verificationImgUrl,
		Long memberId,
		Long planetId
) {

	/**
	 * OutboxEvent와 역직렬화된 payload로 발행용 MessageCommand를 조립한다.
	 * {@code eventId}는 OutboxEvent의 id, 이미지 URL은 payload에서 가져온다.
	 */
	public MessageCommand toMessageCommand(OutboxEvent event) {
		return new MessageCommand(
				event.getId(),
				verificationImgUrl,
				standardImgUrl,
				memberId,
				planetId
		);
	}
}

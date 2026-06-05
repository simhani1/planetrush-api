package com.planetrush.planetrush.verification.service.dto;

import com.planetrush.planetrush.verification.domain.VerificationRequestStatus;

import lombok.Getter;

/**
 * 인증 요청 접수 응답 DTO.
 *
 * <p>Spec 005 — T022 / contracts/rest-api.md §1. {@code POST /api/v1/verify/planets/{id}}
 * 의 202 응답 본문. 입력용 {@link VerificationDto} 와 의미가 달라(입력 vs 접수 응답) 별도 DTO 로 분리.
 *
 * @param requestId 새로 발행된 UUID — {@code VerificationRequest.id} / {@code OutboxEvent.id} /
 *                  stream {@code requestId} 와 동일 값.
 * @param status    접수 직후 상태 (항상 {@link VerificationRequestStatus#PENDING}).
 */
@Getter
public class VerificationAcceptedDto {

	private final String requestId;
	private final VerificationRequestStatus status;

	public VerificationAcceptedDto(String requestId, VerificationRequestStatus status) {
		this.requestId = requestId;
		this.status = status;
	}

	/**
	 * PENDING 상태로 접수된 응답의 정적 팩토리.
	 */
	public static VerificationAcceptedDto pending(String requestId) {
		return new VerificationAcceptedDto(requestId, VerificationRequestStatus.PENDING);
	}
}

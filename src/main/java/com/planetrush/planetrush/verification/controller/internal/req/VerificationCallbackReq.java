package com.planetrush.planetrush.verification.controller.internal.req;

import com.planetrush.planetrush.verification.service.dto.VerificationCallbackCommand;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 컨슈머 callback 페이로드 (HTTP 인입 표면).
 *
 * <p>Spec 005 — T018 / contracts/rest-api.md §3. Bean Validation 만 본 DTO 의 책임 —
 * 페이로드 분기 규칙(정상/오류 패턴 둘 중 하나여야 함) 위반은 서비스 레이어에서 400 처리한다
 * (T020 / FR-011 정합).
 *
 * <p>모든 필드를 nullable 로 두는 이유: 정상 결과 페이로드와 오류 결과 페이로드가 서로
 * 다른 필드 셋을 가지기 때문. 분기 규칙은 {@link VerificationCallbackCommand#isNormalPayload}
 * / {@link VerificationCallbackCommand#isErrorPayload} 가 서비스에서 판정한다.
 *
 * <p>헌법 V — 본 DTO 자체에 시크릿 평문이 들어올 일은 없으나(컨슈머 추론 결과만), 로깅 시
 * {@code message} 본문은 INFO 레벨 평문 출력 금지(T020 가드).
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerificationCallbackReq {

	/**
	 * 인증 요청 UUID. {@code VerificationRequest.id} 와 동일.
	 */
	@NotBlank
	@Pattern(
		regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
		message = "requestId must be a valid UUID"
	)
	private String requestId;

	/**
	 * 유사도 점수 (0~100). 정상 결과 시 필수.
	 */
	@Min(0)
	@Max(100)
	private Integer similarityScore;

	/**
	 * 인증 통과 여부. 정상 결과 시 필수.
	 */
	private Boolean verified;

	/**
	 * 컨슈머 오류 코드 (예: {@code image_load_failed}). 오류 결과 시 필수.
	 */
	@Size(max = 100)
	private String error;

	/**
	 * 오류 상세 메시지. 오류 결과 시 선택.
	 */
	@Size(max = 500)
	private String message;

	/**
	 * HTTP 표면 → 도메인 인입 어댑터. 분기 판정은 본 메서드를 호출한 서비스에서 수행.
	 */
	public VerificationCallbackCommand toCommand() {
		return new VerificationCallbackCommand(
			requestId,
			similarityScore,
			verified,
			error,
			message
		);
	}
}

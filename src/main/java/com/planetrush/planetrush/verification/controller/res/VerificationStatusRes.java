package com.planetrush.planetrush.verification.controller.res;

import com.planetrush.planetrush.verification.domain.VerificationRequestStatus;
import com.planetrush.planetrush.verification.service.dto.VerificationStatusDto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 인증 요청 상태 조회 응답 (HTTP 표면).
 *
 * <p>Spec 005 — T013 / contracts/rest-api.md §2. {@code BaseResponse.ofSuccess(...)} 의
 * {@code data} 본문에 동봉된다. {@link VerificationRequestStatus} 는 Jackson 의 기본
 * {@code @JsonValue} 부재 시 enum name (PENDING/SUCCESS/FAIL/ERROR) 으로 직렬화된다.
 *
 * <p>본 클래스는 {@link VerificationStatusDto} (도메인 DTO) 의 1:1 매핑 표면 — 매핑은
 * {@link #from} 정적 팩토리가 담당한다 (헌법 III 정합: 도메인 DTO 는 QueryDSL Projections
 * 로 SQL 레벨에서 생성된 결과를 그대로 노출).
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerificationStatusRes {

	private String requestId;
	private VerificationRequestStatus status;
	private Integer similarityScore;
	private Boolean verified;
	private String errorMessage;

	/**
	 * 도메인 DTO → HTTP 응답 표면 변환.
	 */
	public static VerificationStatusRes from(VerificationStatusDto dto) {
		return VerificationStatusRes.builder()
			.requestId(dto.getRequestId())
			.status(dto.getStatus())
			.similarityScore(dto.getSimilarityScore())
			.verified(dto.getVerified())
			.errorMessage(dto.getErrorMessage())
			.build();
	}
}

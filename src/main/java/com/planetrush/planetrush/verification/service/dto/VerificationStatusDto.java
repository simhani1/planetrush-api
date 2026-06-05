package com.planetrush.planetrush.verification.service.dto;

import com.planetrush.planetrush.verification.domain.VerificationRequestStatus;
import com.querydsl.core.annotations.QueryProjection;

import lombok.Getter;

/**
 * 인증 요청 상태 조회 응답 도메인 DTO.
 *
 * <p>Spec 005 — T012 / R-009. {@link com.planetrush.planetrush.verification.domain.VerificationRequest}
 * 의 폴링 응답 표면. 헌법 III(Entity→DTO 매핑은 QueryDSL Projections 만) 정합 —
 * {@code VerificationRequestRepositoryCustomImpl} 의
 * {@code Projections.constructor(VerificationStatusDto.class, ...)} 으로 SQL 레벨에서 매핑된다.
 *
 * <p>필드 의미는 라이프사이클별로 다르다 (data-model.md §VerificationRequest 불변식):
 * <ul>
 *   <li>{@link VerificationRequestStatus#PENDING PENDING} — {@code similarityScore}/{@code verified}/{@code errorMessage} 모두 null.</li>
 *   <li>{@link VerificationRequestStatus#SUCCESS SUCCESS} / {@link VerificationRequestStatus#FAIL FAIL} — {@code similarityScore}/{@code verified} non-null, {@code errorMessage} null.</li>
 *   <li>{@link VerificationRequestStatus#ERROR ERROR} — {@code errorMessage} non-null, {@code similarityScore}/{@code verified} null.</li>
 * </ul>
 *
 * <p>JSON 직렬화 시 {@link VerificationRequestStatus} 는 enum name (PENDING/SUCCESS/FAIL/ERROR) 으로 출력된다.
 */
@Getter
public class VerificationStatusDto {

	private final String requestId;
	private final VerificationRequestStatus status;
	private final Integer similarityScore;
	private final Boolean verified;
	private final String errorMessage;

	/**
	 * QueryDSL {@code Projections.constructor} 매핑용 생성자. 필드 순서는
	 * {@code VerificationRequestRepositoryCustomImpl} 의 select 절 순서와 일치해야 한다.
	 */
	@QueryProjection
	public VerificationStatusDto(
		String requestId,
		VerificationRequestStatus status,
		Integer similarityScore,
		Boolean verified,
		String errorMessage
	) {
		this.requestId = requestId;
		this.status = status;
		this.similarityScore = similarityScore;
		this.verified = verified;
		this.errorMessage = errorMessage;
	}
}

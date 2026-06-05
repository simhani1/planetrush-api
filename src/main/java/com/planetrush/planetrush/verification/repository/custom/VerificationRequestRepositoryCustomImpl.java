package com.planetrush.planetrush.verification.repository.custom;

import static com.planetrush.planetrush.verification.domain.QVerificationRequest.*;

import java.time.LocalDateTime;
import java.util.Optional;

import com.planetrush.planetrush.verification.domain.VerificationRequestStatus;
import com.planetrush.planetrush.verification.service.dto.VerificationStatusDto;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;

import lombok.RequiredArgsConstructor;

/**
 * {@link VerificationRequestRepositoryCustom} QueryDSL 구현.
 *
 * <p>Spec 005 — T014 / R-009. Spring Data JPA 의 custom fragment 규약을 따라
 * 구현 클래스명이 {@code VerificationRequestRepositoryCustomImpl} 로 끝나야 자동 결합된다.
 *
 * <p>헌법 III 정합: 본 클래스는 Entity 를 반환하지 않고 {@code Projections.constructor} 로
 * {@link VerificationStatusDto} 만 생성한다 — N+1/lazy 트리거 0.
 */
@RequiredArgsConstructor
public class VerificationRequestRepositoryCustomImpl implements VerificationRequestRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	@Override
	public Optional<VerificationStatusDto> findStatusById(String requestId, Long memberId) {
		VerificationStatusDto dto = queryFactory
			.select(Projections.constructor(
				VerificationStatusDto.class,
				verificationRequest.id,
				verificationRequest.status,
				verificationRequest.similarityScore,
				verificationRequest.verified,
				verificationRequest.errorMessage
			))
			.from(verificationRequest)
			.where(
				verificationRequest.id.eq(requestId),
				verificationRequest.memberId.eq(memberId)
			)
			.fetchOne();
		return Optional.ofNullable(dto);
	}

	@Override
	public long updateToTerminalIfPending(
		String requestId,
		VerificationRequestStatus status,
		Integer similarityScore,
		Boolean verified,
		String errorMessage
	) {
		// Spec 005 R-002 — UPDATE ... WHERE id=? AND status='PENDING' 의 영향 행 수로
		// 멱등/race/종착 가드를 한 번에 해결. SQL 단계의 atomic compare-and-set.
		return queryFactory.update(verificationRequest)
			.set(verificationRequest.status, status)
			.set(verificationRequest.similarityScore, similarityScore)
			.set(verificationRequest.verified, verified)
			.set(verificationRequest.errorMessage, errorMessage)
			.set(verificationRequest.completedAt, LocalDateTime.now())
			.where(
				verificationRequest.id.eq(requestId),
				verificationRequest.status.eq(VerificationRequestStatus.PENDING)
			)
			.execute();
	}
}

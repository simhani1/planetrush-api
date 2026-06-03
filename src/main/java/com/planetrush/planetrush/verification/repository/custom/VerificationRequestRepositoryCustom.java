package com.planetrush.planetrush.verification.repository.custom;

import java.util.Optional;

import com.planetrush.planetrush.verification.domain.VerificationRequestStatus;
import com.planetrush.planetrush.verification.service.dto.VerificationStatusDto;

/**
 * {@link com.planetrush.planetrush.verification.domain.VerificationRequest VerificationRequest}
 * 의 custom QueryDSL 인터페이스.
 *
 * <p>Spec 005 — T014 / R-009. {@code VerificationRequestRepository} 가 본 인터페이스를
 * {@code extends} 하여 Spring Data JPA 의 fragment 결합 메커니즘으로 노출된다.
 * 헌법 III(Entity→DTO 매핑은 QueryDSL Projections 만) 정합 — 본 인터페이스의 모든 메서드는
 * Entity 가 아닌 DTO 를 반환한다.
 */
public interface VerificationRequestRepositoryCustom {

	/**
	 * 인증 요청의 현재 상태를 본인 가드와 함께 조회한다.
	 *
	 * <p>{@code memberId} 일치 row 만 매칭 — 다른 사용자의 request-id 로 조회한 경우 비어 있는
	 * {@link Optional} 을 반환한다. 호출 측({@code VerificationStatusService}) 은 비어 있으면
	 * {@code VerificationRequestNotFoundException} 으로 변환해 404 로 통일한다(존재 정보 유출 방지).
	 *
	 * @param requestId 인증 요청 UUID
	 * @param memberId  요청자 본인의 memberId (JWT 컨텍스트에서 전달)
	 * @return 본인 소유 인증 요청의 상태 DTO, 미존재 시 빈 Optional
	 */
	Optional<VerificationStatusDto> findStatusById(String requestId, Long memberId);

	/**
	 * Optimistic update — PENDING 상태인 인증 요청을 종착 상태로 전이한다.
	 *
	 * <p>Spec 005 — R-002 / FR-004a. {@code WHERE id=? AND status='PENDING'} 절이
	 * race / 멱등 / 종착 가드를 한 번에 해결한다. 영향 행 수로 호출 측이 분기:
	 * <ul>
	 *   <li>0 rows — 이미 종착 상태 또는 미존재 ID. 멱등 흡수 (200 응답).</li>
	 *   <li>1 rows — 정상 전이. 후속 {@code VerificationRecord} 저장 단계 진행 (SUCCESS/FAIL 만).</li>
	 * </ul>
	 *
	 * <p>{@code completedAt} 은 호출 시점의 DB 서버 시각이 아닌 애플리케이션 시각을 주입한다
	 * (테스트 결정성 + JPA 일관성).
	 *
	 * @param requestId       전이 대상 인증 요청 UUID
	 * @param status          전이할 종착 상태 (SUCCESS/FAIL/ERROR)
	 * @param similarityScore 유사도 점수 (ERROR 시 null)
	 * @param verified        인증 통과 여부 (ERROR 시 null)
	 * @param errorMessage    오류 사유 (SUCCESS/FAIL 시 null)
	 * @return 영향 행 수 (0 또는 1)
	 */
	long updateToTerminalIfPending(
		String requestId,
		VerificationRequestStatus status,
		Integer similarityScore,
		Boolean verified,
		String errorMessage
	);
}

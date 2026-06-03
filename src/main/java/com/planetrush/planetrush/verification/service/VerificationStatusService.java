package com.planetrush.planetrush.verification.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.planetrush.planetrush.verification.exception.VerificationRequestNotFoundException;
import com.planetrush.planetrush.verification.repository.VerificationRequestRepository;
import com.planetrush.planetrush.verification.service.dto.VerificationStatusDto;

import lombok.RequiredArgsConstructor;

/**
 * 인증 요청 상태 조회 서비스 (클라이언트 폴링 진입점).
 *
 * <p>Spec 005 — T015 / contracts/rest-api.md §2. {@code GET /api/v1/verify/{request-id}} 의
 * 도메인 로직.
 *
 * <h3>헌법 III 정합</h3>
 * <p>본 서비스는 Entity getter 를 호출하지 않는다 — custom repository 가 반환한
 * {@link VerificationStatusDto} (QueryDSL {@code Projections.constructor} 로 SQL 단계에서 생성)
 * 만 사용한다.
 *
 * <h3>본인 가드 정책</h3>
 * <p>{@code memberId} 일치 row 만 매칭되는 쿼리(custom repo)를 사용한다. 다른 사용자의
 * request-id 로 조회한 경우에도 비어 있는 Optional 이 반환되어 404 로 통일된다 — 존재
 * 정보 유출 방지(analyze I1 결정). 403 으로 분기하지 않음.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VerificationStatusService {

	private final VerificationRequestRepository verificationRequestRepository;

	/**
	 * 본인 소유 인증 요청의 현재 상태를 조회한다.
	 *
	 * @param requestId 인증 요청 UUID
	 * @param memberId  요청자 본인의 memberId (JWT 컨텍스트)
	 * @return 상태 DTO
	 * @throws VerificationRequestNotFoundException 미존재 또는 본인 소유 아님
	 */
	public VerificationStatusDto findById(String requestId, Long memberId) {
		return verificationRequestRepository.findStatusById(requestId, memberId)
			.orElseThrow(() -> new VerificationRequestNotFoundException(
				"VerificationRequest not found: id=" + requestId));
	}
}

package com.planetrush.planetrush.verification.service;

import com.planetrush.planetrush.verification.event.SaveVerificationResultEvent;
import com.planetrush.planetrush.verification.service.dto.VerificationAcceptedDto;
import com.planetrush.planetrush.verification.service.dto.VerificationDto;

public interface VerificationService {

	/**
	 * 행성의 고유 id로 행성의 인증 기준 이미지 url을 가져옵니다.
	 * @param planetId 행성의 고유 id
	 * @return 인증 기준 이미지 url
	 */
	String getStandardImgUrlByPlanetId(Long planetId);

	/**
	 * 사진 url과 유사도, 인증 여부를 저장합니다.
	 *
	 * <p>Spec 005 — R-008. 본 메서드는 기존 동기 흐름의 {@code SaveVerificationResultEvent}
	 * 리스너로 호출되었으나, Phase 3 의 신규 비동기 흐름이 도입되면 {@code SaveVerificationResultEvent}
	 * 는 publish 되지 않는 dead path 가 된다. 코드 자체는 호환 안전 마진을 위해 유지하되, 운영
	 * 트래픽은 즉시 {@code VerificationResultService.handleCallback} 경로로 전환된다.
	 *
	 * @param event 유사도 측정 후 결과가 담긴 이벤트
	 */
	void saveVerificationResult(SaveVerificationResultEvent event);

	/**
	 * 오늘 유저가 행성에 대한 인증 여부를 반환합니다.
	 * @param memberId 유저의 고유 id
	 * @param planetId 행성의 고유 id
	 * @return 인증 여부
	 */
	boolean getTodayRecord(Long memberId, Long planetId);

	/**
	 * 오늘 챌린지 인증 요청을 접수한다 (Spec 005 비동기 흐름).
	 *
	 * <p>Spec 005 — T022 / R-007. 처리 결과를 기다리지 않고 즉시 PENDING 응답을 반환한다.
	 * 흐름:
	 * <ol>
	 *   <li>중복 인증 가드 (오늘 종착된 {@code VerificationRecord} 보유 시 {@code AlreadyVerifiedException}).</li>
	 *   <li>UUID 발행 → {@code VerificationRequest(PENDING)} 영속 + {@code OutboxEvent} 영속 (동일 트랜잭션).</li>
	 *   <li>트랜잭션 커밋 후 AFTER_COMMIT 리스너가 Redis Stream 발행 (별도 메커니즘).</li>
	 *   <li>{@code VerificationAcceptedDto(requestId, PENDING)} 반환.</li>
	 * </ol>
	 *
	 * @param dto 인증 요청 도메인 입력
	 * @return 접수 응답 ({@code requestId}, {@code status=PENDING})
	 */
	VerificationAcceptedDto verifyTodayChallenge(VerificationDto dto);
}

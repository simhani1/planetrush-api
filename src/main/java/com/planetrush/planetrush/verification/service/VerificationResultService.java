package com.planetrush.planetrush.verification.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.planetrush.planetrush.member.domain.Member;
import com.planetrush.planetrush.member.exception.MemberNotFoundException;
import com.planetrush.planetrush.member.repository.MemberRepository;
import com.planetrush.planetrush.planet.domain.Planet;
import com.planetrush.planetrush.planet.exception.PlanetNotFoundException;
import com.planetrush.planetrush.planet.repository.PlanetRepository;
import com.planetrush.planetrush.verification.domain.VerificationRecord;
import com.planetrush.planetrush.verification.domain.VerificationRequest;
import com.planetrush.planetrush.verification.domain.VerificationRequestStatus;
import com.planetrush.planetrush.verification.repository.VerificationRecordRepository;
import com.planetrush.planetrush.verification.repository.VerificationRequestRepository;
import com.planetrush.planetrush.verification.service.dto.VerificationCallbackCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 컨슈머 callback 결과 처리 진입점.
 *
 * <p>Spec 005 — T020 / R-002·R-003. {@code POST /api/v1/internal/verification-results}
 * 의 도메인 로직. 두 종류 멱등(requestId 단위 / 사용자·챌린지·날짜 단위) 을 모두 보장한다.
 *
 * <h3>처리 흐름</h3>
 * <ol>
 *   <li><b>페이로드 분기 판정</b> — {@link VerificationCallbackCommand#isErrorPayload}
 *       / {@link VerificationCallbackCommand#isNormalPayload} 가 모두 false 면
 *       {@link IllegalArgumentException} → 컨트롤러에서 400.</li>
 *   <li><b>Optimistic UPDATE</b> (R-002) — {@code UPDATE ... WHERE id=? AND status='PENDING'}
 *       의 영향 행 수로 멱등 가드.
 *       <ul>
 *         <li>0 rows — 이미 종착 또는 미존재 ID. {@code requestId} 만 INFO 로그 후 정상 종료 (멱등 흡수 + FR-011).</li>
 *         <li>1 rows — 정상 전이. 2단계 진행.</li>
 *       </ul>
 *   </li>
 *   <li><b>VerificationRecord 저장</b> (R-003 / FR-007)
 *       <ul>
 *         <li>SUCCESS/FAIL — {@code verificationRecordRepository.save(...)} 시도.
 *             {@link DataIntegrityViolationException} 발생 시(사용자·챌린지·날짜 unique 위반) skip.
 *             status 전이는 1단계에서 이미 완료.</li>
 *         <li>ERROR — 저장 단계 자체 건너뛰기 (clarify Q2 — 사용자 재시도 권한 보존).</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h3>헌법 V 정합</h3>
 * <p>모든 로그는 {@code requestId} (UUID) 만 출력한다. callback payload 본문
 * ({@code error}/{@code message} 등) INFO 평문 로그 금지.
 *
 * <h3>헌법 II 정합</h3>
 * <p>본 서비스는 외부 시스템(Redis/HTTP/S3) 을 직접 호출하지 않는다 — repository 만 의존.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationResultService {

	private final VerificationRequestRepository verificationRequestRepository;
	private final VerificationRecordRepository verificationRecordRepository;
	private final MemberRepository memberRepository;
	private final PlanetRepository planetRepository;

	/**
	 * 컨슈머 callback 처리. 페이로드 분기 위반 시 {@link IllegalArgumentException} throw.
	 *
	 * @param command 컨트롤러에서 매핑한 도메인 커맨드
	 * @throws IllegalArgumentException 분기 규칙 위반 (정상/오류 패턴 둘 다 어긋남 또는 둘 다 만족)
	 */
	@Transactional
	public void handleCallback(VerificationCallbackCommand command) {
		validatePayloadBranch(command);

		VerificationRequestStatus terminal = decideTerminalStatus(command);
		Integer similarityScore = command.isNormalPayload() ? command.similarityScore() : null;
		Boolean verified = command.isNormalPayload() ? command.verified() : null;
		String errorMessage = command.isErrorPayload() ? command.error() : null;

		long affected = verificationRequestRepository.updateToTerminalIfPending(
			command.requestId(),
			terminal,
			similarityScore,
			verified,
			errorMessage
		);

		if (affected == 0) {
			// 멱등 흡수 — 이미 종착 상태 또는 미존재 ID. FR-004a + FR-011.
			// 헌법 V — requestId 만 출력, payload 본문(error/message) 평문 미출현.
			log.info("idempotent or unknown callback: {}", command.requestId());
			return;
		}

		if (terminal == VerificationRequestStatus.ERROR) {
			// ERROR 종착은 VerificationRecord 저장하지 않는다 (clarify Q2).
			return;
		}

		// SUCCESS/FAIL 종착 — VerificationRecord 저장 시도. unique 위반은 catch 후 skip.
		persistVerificationRecord(command, verified);
	}

	private void validatePayloadBranch(VerificationCallbackCommand command) {
		boolean error = command.isErrorPayload();
		boolean normal = command.isNormalPayload();
		if (error == normal) {
			// 둘 다 true 또는 둘 다 false — 분기 규칙 위반.
			// 헌법 V — payload 본문 출력 금지, requestId 만.
			throw new IllegalArgumentException(
				"verification callback payload branch violation: requestId=" + command.requestId());
		}
	}

	private VerificationRequestStatus decideTerminalStatus(VerificationCallbackCommand command) {
		if (command.isErrorPayload()) {
			return VerificationRequestStatus.ERROR;
		}
		// 정상 페이로드: verified=true → SUCCESS, false → FAIL.
		return Boolean.TRUE.equals(command.verified())
			? VerificationRequestStatus.SUCCESS
			: VerificationRequestStatus.FAIL;
	}

	private void persistVerificationRecord(VerificationCallbackCommand command, Boolean verified) {
		// VerificationRequest 의 memberId/planetId/targetImgUrl 을 가져오기 위해 한 번 더 조회.
		// (custom repo 의 findStatusById 는 본인 가드 + 응답 표면 5필드만 — 본 컨텍스트엔 부적합.)
		// callback 은 본인 가드 비대상(컨슈머 → Spring 내부 호출) — JpaRepository.findById 사용.
		VerificationRequest request = verificationRequestRepository.findById(command.requestId())
			.orElseThrow(() -> new IllegalStateException(
				"VerificationRequest disappeared after update: " + command.requestId()));

		Member member = memberRepository.findById(request.getMemberId())
			.orElseThrow(() -> new MemberNotFoundException(
				"Member not found with ID: " + request.getMemberId()));
		Planet planet = planetRepository.findById(request.getPlanetId())
			.orElseThrow(() -> new PlanetNotFoundException(
				"Planet not found with ID: " + request.getPlanetId()));

		try {
			verificationRecordRepository.save(VerificationRecord.builder()
				.verified(Boolean.TRUE.equals(verified))
				.similarityScore(command.similarityScore() != null ? command.similarityScore() : 0)
				.planet(planet)
				.member(member)
				.imgUrl(request.getTargetImgUrl())
				.build());
		} catch (DataIntegrityViolationException e) {
			// R-003 — 사용자·챌린지·날짜 unique 위반. 다른 PENDING 의 callback 이 record 를 먼저 저장.
			// status 전이는 1단계에서 이미 완료. 본 callback 은 record 추가 저장 skip.
			// 헌법 V — requestId 만 출력.
			log.info("verification record uniq guard hit, skip insert: {}", command.requestId());
		}
	}
}

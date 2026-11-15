package com.planetrush.planetrush.verification.service;

import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.planetrush.planetrush.member.domain.Member;
import com.planetrush.planetrush.member.exception.MemberNotFoundException;
import com.planetrush.planetrush.member.repository.MemberRepository;
import com.planetrush.planetrush.outbox.VerificationExternalEventRecorder;
import com.planetrush.planetrush.outbox.dto.OutboxRecordCommand;
import com.planetrush.planetrush.planet.domain.Planet;
import com.planetrush.planetrush.planet.exception.PlanetNotFoundException;
import com.planetrush.planetrush.planet.repository.PlanetRepository;
import com.planetrush.planetrush.verification.domain.VerificationRecord;
import com.planetrush.planetrush.verification.domain.VerificationRequest;
import com.planetrush.planetrush.verification.event.SaveVerificationResultEvent;
import com.planetrush.planetrush.verification.exception.AlreadyVerifiedException;
import com.planetrush.planetrush.verification.repository.VerificationRequestRepository;
import com.planetrush.planetrush.verification.repository.custom.VerificationRecordRepositoryCustom;
import com.planetrush.planetrush.verification.service.dto.VerificationAcceptedDto;
import com.planetrush.planetrush.verification.service.dto.VerificationCallbackCommand;
import com.planetrush.planetrush.verification.service.dto.VerificationDto;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Transactional(readOnly = true)
@Service
public class VerificationServiceImpl implements VerificationService {

	private final MemberRepository memberRepository;
	private final PlanetRepository planetRepository;
	private final VerificationResultService verificationResultService;
	private final VerificationRecordRepositoryCustom verificationRecordRepositoryCustom;
	private final VerificationRequestRepository verificationRequestRepository;
	private final VerificationExternalEventRecorder verificationExternalEventRecorder;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getStandardImgUrlByPlanetId(Long planetId) {
		Planet planet = planetRepository.findById(planetId)
			.orElseThrow(() -> new PlanetNotFoundException(("Planet not found with ID: " + planetId)));
		return planet.getStandardVerificationImg();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean getTodayRecord(Long memberId, Long planetId) {
		Member member = memberRepository.findById(memberId)
			.orElseThrow(() -> new MemberNotFoundException("Member not found with ID: " + memberId));
		Planet planet = planetRepository.findById(planetId)
			.orElseThrow(() -> new PlanetNotFoundException("Planet not found with ID: " + planetId));
		VerificationRecord verificationRecord = verificationRecordRepositoryCustom.findTodayRecord(member, planet);
		return verificationRecord != null;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Spec 005 — T023 / R-007 / R-008. 비동기 흐름:
	 * <ol>
	 *   <li>오늘 종착된 record 가드 — PENDING 가드는 추가하지 않음 (clarify Q3).</li>
	 *   <li>UUID 발행 → entity·outbox·stream·callback 4면 동일 ID.</li>
	 *   <li>{@code VerificationRequest(PENDING)} INSERT + {@code OutboxEvent} INSERT 를 동일 트랜잭션에 묶는다.</li>
	 *   <li>트랜잭션 커밋 후 AFTER_COMMIT 리스너가 stream 발행 (외부 메커니즘).</li>
	 *   <li>{@code VerificationAcceptedDto(requestId, PENDING)} 즉시 반환.</li>
	 * </ol>
	 *
	 * <p>커밋 후 발행 방식은 {@code verification.publisher.type} 이 정한다 — {@code redis-stream}
	 * (컨슈머 callback) 또는 {@code async-event}({@code AsyncVerificationProcessor} 워커 +
	 * {@code SaveVerificationResultEvent}).
	 */
	@Transactional
	@Override
	public VerificationAcceptedDto verifyTodayChallenge(VerificationDto dto) {
		Member member = memberRepository.findById(dto.getMemberId())
			.orElseThrow(() -> new MemberNotFoundException("Member not found with ID: " + dto.getMemberId()));
		Planet planet = planetRepository.findById(dto.getPlanetId())
			.orElseThrow(() -> new PlanetNotFoundException("Planet not found with ID: " + dto.getPlanetId()));

		// (1) 오늘 종착된 record 가드. PENDING 가드는 spec 결정에 따라 두지 않는다 (clarify Q3).
		VerificationRecord verificationRecord = verificationRecordRepositoryCustom.findTodayRecord(member, planet);
		if (verificationRecord != null) {
			throw new AlreadyVerifiedException(
				"Member: " + member.getId() + ", Planet : " + planet.getId() + " already verified today");
		}

		// (2) UUID 발행 — entity·outbox·stream·callback 4면 동일 ID (R-004).
		String requestId = UUID.randomUUID().toString();

		// (3) VerificationRequest(PENDING) INSERT — 동일 트랜잭션.
		verificationRequestRepository.save(VerificationRequest.pending(
			requestId,
			member.getId(),
			planet.getId(),
			dto.getVerificationImgUrl(),
			planet.getStandardVerificationImg()
		));

		// (4) OutboxEvent INSERT — 동일 트랜잭션. AFTER_COMMIT 리스너가 stream 발행 (헌법 IV).
		//     OutboxRecordCommand 의 eventId 필드는 호환 잔존 — requestId 와 동일 값을 주입해 한 UUID 로 통합.
		verificationExternalEventRecorder.save(new OutboxRecordCommand(
			requestId,
			requestId,
			dto.getVerificationImgUrl(),
			planet.getStandardVerificationImg(),
			member.getId(),
			planet.getId()
		));

		// (5) R-008 cutover — 기존 eventService.publish(VerificationEvent) 호출은 제거됨.

		return VerificationAcceptedDto.pending(requestId);
	}

	/**
	 * {@inheritDoc}
	 */
	@EventListener
	@Transactional
	@Override
	public void saveVerificationResult(SaveVerificationResultEvent event) {
		// 워커 스레드(async-event 경로)의 검사 결과를 저장한다. 상태 전이·멱등·record 저장 규칙은
		// 컨슈머 callback 과 동일해야 하므로 VerificationResultService 에 위임한다.
		// 클래스 기본값이 readOnly 이므로 쓰기 트랜잭션을 여기서 명시한다.
		// Flask 점수(double)는 VerificationRequest 계약(정수)에 맞춰 반올림한다.
		verificationResultService.handleCallback(new VerificationCallbackCommand(
			event.requestId(),
			event.similarityScore() == null ? null : (int)Math.round(event.similarityScore()),
			event.verified(),
			event.error(),
			null
		));
	}
}

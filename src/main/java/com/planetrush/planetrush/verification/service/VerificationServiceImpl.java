package com.planetrush.planetrush.verification.service;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.planetrush.planetrush.member.domain.Member;
import com.planetrush.planetrush.member.exception.MemberNotFoundException;
import com.planetrush.planetrush.member.repository.MemberRepository;
import com.planetrush.planetrush.planet.domain.Planet;
import com.planetrush.planetrush.planet.exception.PlanetNotFoundException;
import com.planetrush.planetrush.planet.repository.PlanetRepository;
import com.planetrush.planetrush.verification.domain.VerificationRecord;
import com.planetrush.planetrush.verification.event.SaveVerificationResultEvent;
import com.planetrush.planetrush.verification.exception.AlreadyVerifiedException;
import com.planetrush.planetrush.verification.repository.VerificationRecordRepository;
import com.planetrush.planetrush.verification.repository.custom.VerificationRecordRepositoryCustom;
import com.planetrush.planetrush.verification.service.dto.VerificationDto;
import com.planetrush.planetrush.verification.service.dto.VerificationEvent;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Transactional(readOnly = true)
@Service
public class VerificationServiceImpl implements VerificationService {

	private final VerificationEventService eventService;

	private final MemberRepository memberRepository;
	private final PlanetRepository planetRepository;
	private final VerificationRecordRepository verificationRecordRepository;
	private final VerificationRecordRepositoryCustom verificationRecordRepositoryCustom;

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
	 */
	@Override
	public void verifyTodayChallenge(VerificationDto dto) {
		Member member = memberRepository.findById(dto.getMemberId())
			.orElseThrow(() -> new MemberNotFoundException("Member not found with ID: " + dto.getMemberId()));
		Planet planet = planetRepository.findById(dto.getPlanetId())
			.orElseThrow(() -> new PlanetNotFoundException("Planet not found with ID: " + dto.getPlanetId()));

		VerificationRecord verificationRecord = verificationRecordRepositoryCustom.findTodayRecord(member, planet);
		if (verificationRecord != null) {
			throw new AlreadyVerifiedException(
				"Member: " + member.getId() + ", Planet : " + planet.getId() + " already verified today");
		}

		eventService.publish(new VerificationEvent(
			dto.getVerificationImgUrl(),
			dto.getStandardImgUrl(),
			member.getId(),
			planet.getId()
		));
	}

	/**
	 * {@inheritDoc}
	 */
	@EventListener
	@Transactional
	@Override
	public void saveVerificationResult(SaveVerificationResultEvent event) {
		Member member = memberRepository.findById(event.getMemberId())
			.orElseThrow(() -> new MemberNotFoundException("Member not found with ID: " + event.getMemberId()));
		Planet planet = planetRepository.findById(event.getPlanetId())
			.orElseThrow(() -> new PlanetNotFoundException("Planet not found with ID: " + event.getPlanetId()));
		verificationRecordRepository.save(VerificationRecord.builder()
			.verified(event.isVerified())
			.planet(planet)
			.member(member)
			.similarityScore(event.getSimilarityScore())
			.imgUrl(event.getVerificationImgUrl())
			.build());
	}
}

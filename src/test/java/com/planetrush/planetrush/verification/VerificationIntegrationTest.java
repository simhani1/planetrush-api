package com.planetrush.planetrush.verification;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.planetrush.planetrush.IntegrationTest;
import com.planetrush.planetrush.member.repository.MemberRepository;
import com.planetrush.planetrush.planet.repository.PlanetRepository;
import com.planetrush.planetrush.verification.repository.VerificationRecordRepository;
import com.planetrush.planetrush.verification.repository.custom.VerificationRecordRepositoryCustom;
import com.planetrush.planetrush.verification.service.VerificationService;

public abstract class VerificationIntegrationTest extends IntegrationTest {

	@Autowired
	VerificationService verificationService;

	@MockBean
	MemberRepository memberRepository;

	@MockBean
	PlanetRepository planetRepository;

	@MockBean
	VerificationRecordRepository verificationRecordRepository;

	@MockBean
	VerificationRecordRepositoryCustom verificationRecordRepositoryCustom;
}

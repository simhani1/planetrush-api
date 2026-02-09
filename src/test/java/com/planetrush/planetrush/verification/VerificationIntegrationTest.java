package com.planetrush.planetrush.verification;

import org.springframework.boot.test.mock.mockito.MockBean;

import com.planetrush.planetrush.IntegrationTest;
import com.planetrush.planetrush.member.repository.MemberRepository;
import com.planetrush.planetrush.planet.repository.PlanetRepository;
import com.planetrush.planetrush.verification.repository.VerificationRecordRepository;
import com.planetrush.planetrush.verification.repository.custom.VerificationRecordRepositoryCustom;

public abstract class VerificationIntegrationTest extends IntegrationTest {

	@MockBean
	MemberRepository memberRepository;

	@MockBean
	PlanetRepository planetRepository;

	@MockBean
	VerificationRecordRepository verificationRecordRepository;

	@MockBean
	VerificationRecordRepositoryCustom verificationRecordRepositoryCustom;
}

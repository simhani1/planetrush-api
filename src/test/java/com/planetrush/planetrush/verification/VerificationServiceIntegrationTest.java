package com.planetrush.planetrush.verification;

import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

import com.planetrush.planetrush.fixture.MemberFixture;
import com.planetrush.planetrush.fixture.PlanetFixture;
import com.planetrush.planetrush.member.domain.Member;
import com.planetrush.planetrush.planet.domain.Planet;
import com.planetrush.planetrush.verification.service.VerificationService;
import com.planetrush.planetrush.verification.service.dto.VerificationDto;

public class VerificationServiceIntegrationTest extends VerificationIntegrationTest {

	@Autowired
	private VerificationService verificationService;

	@DisplayName("verifyTodayChallenge 호출 시 이벤트가 발행되고 결과가 저장된다.")
	@Test
	void should_publish_event_and_save_verification_result() {
		// GIVEN
		Member member = Mockito.spy(MemberFixture.activeMember());
		Planet planet = Mockito.spy(PlanetFixture.readyPlanet());
		when(member.getId()).thenReturn(1L);
		when(planet.getId()).thenReturn(10L);

		when(memberRepository.findById(member.getId())).thenReturn(Optional.of(member));
		when(planetRepository.findById(planet.getId())).thenReturn(Optional.of(planet));
		when(verificationRecordRepositoryCustom.findTodayRecord(member, planet)).thenReturn(null);

		VerificationDto dto = VerificationDto.builder()
			.memberId(member.getId())
			.planetId(planet.getId())
			.verificationImgUrl("https://verification-img.com")
			.standardImgUrl(planet.getStandardVerificationImg())
			.build();

		// WHEN
		verificationService.verifyTodayChallenge(dto);

		// THEN
	}
}

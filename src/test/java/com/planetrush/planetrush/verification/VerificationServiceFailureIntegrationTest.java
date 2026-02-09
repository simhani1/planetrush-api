package com.planetrush.planetrush.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.planetrush.planetrush.fixture.MemberFixture;
import com.planetrush.planetrush.fixture.PlanetFixture;
import com.planetrush.planetrush.infra.flask.exception.FlaskConnectionFailedException;
import com.planetrush.planetrush.infra.flask.util.FlaskApiClient;
import com.planetrush.planetrush.member.domain.Member;
import com.planetrush.planetrush.outbox.domain.OutboxEvent;
import com.planetrush.planetrush.outbox.domain.OutboxStatus;
import com.planetrush.planetrush.planet.domain.Planet;
import com.planetrush.planetrush.verification.service.VerificationService;
import com.planetrush.planetrush.verification.service.dto.VerificationDto;

public class VerificationServiceFailureIntegrationTest extends VerificationIntegrationTest {

	@Autowired
	private VerificationService verificationService;

	@MockBean
	private FlaskApiClient flaskApiClient;

	@DisplayName("이벤트 발행에 실패할 경우 outbox 상태가 PENDING으로 저장된다.")
	@Test
	void should_keep_outbox_status_pending_when_event_publish_fails() {
		// GIVEN
		Member member = Mockito.spy(MemberFixture.activeMember());
		Planet planet = Mockito.spy(PlanetFixture.readyPlanet());
		when(member.getId()).thenReturn(1L);
		when(planet.getId()).thenReturn(10L);

		when(memberRepository.findById(member.getId())).thenReturn(Optional.of(member));
		when(planetRepository.findById(planet.getId())).thenReturn(Optional.of(planet));
		when(verificationRecordRepositoryCustom.findTodayRecord(member, planet)).thenReturn(null);
		doThrow(new FlaskConnectionFailedException())
			.when(flaskApiClient).verifyChallengeImg(any(), any());

		VerificationDto dto = VerificationDto.builder()
			.memberId(member.getId())
			.planetId(planet.getId())
			.verificationImgUrl("https://verification-img.com")
			.standardImgUrl(planet.getStandardVerificationImg())
			.build();

		// WHEN
		verificationService.verifyTodayChallenge(dto);

		// THEN
		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(outboxRepository).save(captor.capture());
		assertThat(captor.getValue().getStatus()).isEqualTo(OutboxStatus.PENDING);
		verify(outboxRepository, never()).findById(anyString());
	}
}

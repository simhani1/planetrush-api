package com.planetrush.planetrush.verification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.planetrush.planetrush.fixture.MemberFixture;
import com.planetrush.planetrush.fixture.PlanetFixture;
import com.planetrush.planetrush.member.domain.Member;
import com.planetrush.planetrush.outbox.VerificationExternalEventRecorder;
import com.planetrush.planetrush.planet.domain.Planet;
import com.planetrush.planetrush.verification.event.publisher.VerificationMessagePublisher;
import com.planetrush.planetrush.verification.service.VerificationService;
import com.planetrush.planetrush.verification.service.dto.VerificationDto;

public class VerificationServiceIntegrationTest extends VerificationIntegrationTest {

	@Autowired
	private VerificationService verificationService;

	@SpyBean
	private VerificationExternalEventRecorder verificationExternalEventRecorder;

	@SpyBean
	private VerificationMessagePublisher verificationMessagePublisher;

	@DisplayName("verifyTodayChallenge 커밋 직전/직후에 트랜잭션 이벤트가 실행된다.")
	@Test
	void should_invoke_transactional_event_listeners_before_and_after_commit() {
		// GIVEN
		Member member = Mockito.spy(MemberFixture.activeMember());
		Planet planet = Mockito.spy(PlanetFixture.readyPlanet());
		when(member.getId()).thenReturn(1L);
		when(planet.getId()).thenReturn(10L);

		when(memberRepository.findById(member.getId())).thenReturn(Optional.of(member));
		when(planetRepository.findById(planet.getId())).thenReturn(Optional.of(planet));
		when(verificationRecordRepositoryCustom.findTodayRecord(member, planet)).thenReturn(null);

		AtomicReference<Boolean> beforeCommitActive = new AtomicReference<>();
		AtomicReference<Boolean> afterCommitActive = new AtomicReference<>();

		doAnswer(invocation -> {
			beforeCommitActive.set(TransactionSynchronizationManager.isActualTransactionActive());
			return invocation.callRealMethod();
		}).when(verificationExternalEventRecorder).save(any());

		doAnswer(invocation -> {
			afterCommitActive.set(TransactionSynchronizationManager.isActualTransactionActive());
			return invocation.callRealMethod();
		}).when(verificationMessagePublisher).publish(any());

		VerificationDto dto = VerificationDto.builder()
			.memberId(member.getId())
			.planetId(planet.getId())
			.verificationImgUrl("https://verification-img.com")
			.standardImgUrl(planet.getStandardVerificationImg())
			.build();

		// WHEN
		verificationService.verifyTodayChallenge(dto);

		// THEN
		assertThat(beforeCommitActive.get()).isTrue();
		assertThat(afterCommitActive.get()).isNotNull();

		InOrder inOrder = inOrder(verificationExternalEventRecorder, verificationMessagePublisher);
		inOrder.verify(verificationExternalEventRecorder).save(any());
		inOrder.verify(verificationMessagePublisher).publish(any());
	}
}

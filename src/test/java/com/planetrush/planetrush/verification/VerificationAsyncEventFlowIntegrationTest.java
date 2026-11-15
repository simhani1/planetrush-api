package com.planetrush.planetrush.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import com.planetrush.planetrush.IntegrationTest;
import com.planetrush.planetrush.fixture.MemberFixture;
import com.planetrush.planetrush.fixture.PlanetFixture;
import com.planetrush.planetrush.infra.flask.exception.FlaskConnectionFailedException;
import com.planetrush.planetrush.infra.flask.res.FlaskResponseDto;
import com.planetrush.planetrush.infra.flask.util.FlaskApiClient;
import com.planetrush.planetrush.member.domain.Member;
import com.planetrush.planetrush.member.repository.MemberRepository;
import com.planetrush.planetrush.outbox.repository.OutboxRepository;
import com.planetrush.planetrush.planet.domain.Planet;
import com.planetrush.planetrush.planet.repository.PlanetRepository;
import com.planetrush.planetrush.verification.domain.VerificationRecord;
import com.planetrush.planetrush.verification.domain.VerificationRequest;
import com.planetrush.planetrush.verification.domain.VerificationRequestStatus;
import com.planetrush.planetrush.verification.repository.VerificationRecordRepository;
import com.planetrush.planetrush.verification.repository.VerificationRequestRepository;
import com.planetrush.planetrush.verification.service.VerificationService;
import com.planetrush.planetrush.verification.service.dto.VerificationAcceptedDto;
import com.planetrush.planetrush.verification.service.dto.VerificationDto;

/**
 * {@code verification.publisher.type=async-event} 경로 검증 — 유사도 판별 서버 호출이 요청 스레드가 아닌
 * 워커 스레드에서 수행되고, Spring 내부 이벤트 리스너가 결과를 DB 에 저장한다.
 */
@TestPropertySource(properties = "verification.publisher.type=async-event")
class VerificationAsyncEventFlowIntegrationTest extends IntegrationTest {

	@Autowired
	private VerificationService verificationService;
	@Autowired
	private MemberRepository memberRepository;
	@Autowired
	private PlanetRepository planetRepository;
	@Autowired
	private VerificationRequestRepository verificationRequestRepository;
	@Autowired
	private VerificationRecordRepository verificationRecordRepository;
	@Autowired
	private OutboxRepository outboxRepository;

	@MockBean
	private FlaskApiClient flaskApiClient;

	private Member member;
	private Planet planet;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.activeMember());
		planet = planetRepository.save(PlanetFixture.readyPlanet());
	}

	@AfterEach
	void tearDown() {
		verificationRecordRepository.deleteAll();
		verificationRequestRepository.deleteAll();
		outboxRepository.deleteAll();
		planetRepository.deleteAll();
		memberRepository.deleteAll();
	}

	@DisplayName("접수는 즉시 PENDING 을 반환하고, 워커 스레드의 검사 결과가 내부 이벤트로 저장된다")
	@Test
	void workerThreadVerifiesAndEventListenerPersists() {
		AtomicReference<String> flaskCallThread = new AtomicReference<>();
		given(flaskApiClient.verifyChallengeImg(anyString(), anyString())).willAnswer(invocation -> {
			flaskCallThread.set(Thread.currentThread().getName());
			return FlaskResponseDto.builder().verified(true).similarityScore(85.0).build();
		});

		VerificationAcceptedDto accepted = verificationService.verifyTodayChallenge(request());

		assertThat(accepted.getStatus()).isEqualTo(VerificationRequestStatus.PENDING);
		awaitStatus(accepted.getRequestId(), VerificationRequestStatus.SUCCESS);
		assertThat(flaskCallThread.get())
			.as("판별 서버 호출은 요청 스레드가 아닌 워커 스레드에서 수행")
			.isNotEqualTo(Thread.currentThread().getName());
		VerificationRequest saved = verificationRequestRepository.findById(accepted.getRequestId()).orElseThrow();
		assertThat(saved.getSimilarityScore()).isEqualTo(85);
		List<VerificationRecord> records = verificationRecordRepository.findAll();
		assertThat(records).hasSize(1);
		assertThat(records.get(0).isVerified()).isTrue();
	}

	@DisplayName("판별 서버 호출이 재시도 끝에 실패하면 요청은 ERROR 로 종착하고 인증 기록은 저장되지 않는다")
	@Test
	void flaskFailureEndsInError() {
		given(flaskApiClient.verifyChallengeImg(anyString(), anyString()))
			.willThrow(new FlaskConnectionFailedException());

		VerificationAcceptedDto accepted = verificationService.verifyTodayChallenge(request());

		awaitStatus(accepted.getRequestId(), VerificationRequestStatus.ERROR);
		assertThat(verificationRecordRepository.findAll()).isEmpty();
	}

	private VerificationDto request() {
		return VerificationDto.builder()
			.memberId(member.getId())
			.planetId(planet.getId())
			.verificationImgUrl("https://img.example/target.jpg")
			.build();
	}

	private void awaitStatus(String requestId, VerificationRequestStatus expected) {
		Awaitility.await()
			.atMost(Duration.ofSeconds(10))
			.pollInterval(Duration.ofMillis(100))
			.untilAsserted(() -> assertThat(verificationRequestRepository.findById(requestId))
				.get()
				.extracting(VerificationRequest::getStatus)
				.isEqualTo(expected));
	}
}

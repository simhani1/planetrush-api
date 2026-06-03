package com.planetrush.planetrush.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.planetrush.planetrush.IntegrationTest;
import com.planetrush.planetrush.core.jwt.JwtTokenProvider;
import com.planetrush.planetrush.core.template.response.BaseResponse;
import com.planetrush.planetrush.fixture.MemberFixture;
import com.planetrush.planetrush.fixture.PlanetFixture;
import com.planetrush.planetrush.member.domain.Member;
import com.planetrush.planetrush.member.repository.MemberRepository;
import com.planetrush.planetrush.outbox.domain.OutboxEvent;
import com.planetrush.planetrush.outbox.domain.OutboxStatus;
import com.planetrush.planetrush.outbox.repository.OutboxRepository;
import com.planetrush.planetrush.planet.domain.Planet;
import com.planetrush.planetrush.planet.repository.PlanetRepository;
import com.planetrush.planetrush.verification.domain.VerificationRecord;
import com.planetrush.planetrush.verification.domain.VerificationRequest;
import com.planetrush.planetrush.verification.domain.VerificationRequestStatus;
import com.planetrush.planetrush.verification.repository.VerificationRecordRepository;
import com.planetrush.planetrush.verification.repository.VerificationRequestRepository;
import com.planetrush.planetrush.verification.service.dto.VerificationAcceptedDto;
import com.planetrush.planetrush.verification.testsupport.FakeVerificationConsumer;
import com.planetrush.planetrush.verification.testsupport.FakeVerificationConsumer.CallbackResult;

/**
 * 인증 요청 → 결과 조회 종착까지의 단대단(end-to-end) 통합 테스트.
 *
 * <p>Spec 005 — T011 / SC-001.
 *
 * <h3>인수 기준 매핑</h3>
 * <ul>
 *   <li><b>SC-001 (정상 흐름, US1)</b> — 정상 환경에서 인증 요청 → 결과 조회 종착까지의 전체 흐름이
 *       통합 테스트로 검증된다. 발행 → callback → 상태 전이까지 단일 테스트가 단대단으로 통과해야 한다.
 *       본 테스트는 {@link FakeVerificationConsumer} 가 stream 메시지를 소비하고 callback 을 자동 발행해
 *       SUCCESS 종착까지 도달하는 전 과정을 검증한다.</li>
 * </ul>
 *
 * <h3>헌법 정합</h3>
 * <ul>
 *   <li><b>I (Testcontainers, NON-NEGOTIABLE)</b> — {@link IntegrationTest} 베이스 상속.
 *       MySQL/Redis 컨테이너 자동 부팅, 로컬 데몬 의존 0.</li>
 *   <li><b>VII (인수 기준 자동 테스트)</b> — SC-001 ↔ 본 클래스 1:1 매핑.</li>
 * </ul>
 *
 * <h3>분리된 검증 — C1 (Outbox 원자성)</h3>
 * <p>analyze C1 (FR-002 원자성) 검증은 본 phase 범위에서 가산점 영역 — `@SpyBean` + `doThrow` 패턴으로
 * 트랜잭션 롤백을 검증하면 Spring 컨텍스트가 분기되어 별도 테스트 클래스가 더 안전하다. 본 테스트는
 * 정상 흐름 단대단만 다루고, C1 보강은 후속 phase 또는 별도 PR 로 분리한다(phase3-test-report 참조).
 */
@Import(FakeVerificationConsumer.class)
class VerificationAsyncFlowIntegrationTest extends IntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

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

	@Autowired
	private FakeVerificationConsumer fakeConsumer;

	private Member member;
	private Planet planet;
	private String accessToken;

	@BeforeEach
	void setUp() {
		// 새 Member + Planet 영속 — 매 테스트가 빈 라이프사이클로 시작.
		member = memberRepository.save(MemberFixture.activeMember());
		planet = planetRepository.save(PlanetFixture.readyPlanet());

		// JWT 발급 → Authorization 헤더에 그대로 사용 (이미 "Bearer " prefix 포함).
		accessToken = jwtTokenProvider.createToken(member.getId()).getAccessToken();

		// FakeVerificationConsumer 가 callback 으로 사용할 base URL — Stream entry 의 callbackUrl 키와 무관.
		fakeConsumer.setCallbackBaseUrl("http://localhost:" + port);
		fakeConsumer.clearGroup();
	}

	@AfterEach
	void tearDown() {
		fakeConsumer.stop();
		// 컨테이너가 싱글톤이므로 데이터만 정리(외래키 순서 주의).
		verificationRecordRepository.deleteAll();
		verificationRequestRepository.deleteAll();
		outboxRepository.deleteAll();
		planetRepository.deleteAll();
		memberRepository.deleteAll();
	}

	@DisplayName("SC-001: 인증 요청 → stream 발행 → 컨슈머 callback → 상태 SUCCESS 종착까지 단대단 흐름이 통과한다")
	@Test
	void endToEndPendingToSuccess() {
		// given: 컨슈머가 정상 SUCCESS 결과를 callback 할 예정
		fakeConsumer.setNextResult(CallbackResult.success(85));
		fakeConsumer.start();

		// when: POST /api/v1/verify/planets/{id} — 인증 요청 발행
		HttpHeaders headers = new HttpHeaders();
		headers.set("Authorization", accessToken);
		headers.setContentType(MediaType.APPLICATION_JSON);
		String body = "{\"verificationImgUrl\":\"https://img.example/target.jpg\"}";
		ResponseEntity<BaseResponse<VerificationAcceptedDto>> postRes = restTemplate.exchange(
			"/api/v1/verify/planets/" + planet.getId(),
			HttpMethod.POST,
			new HttpEntity<>(body, headers),
			new ParameterizedTypeReference<BaseResponse<VerificationAcceptedDto>>() {
			}
		);

		// then: 202 Accepted + PENDING 응답
		assertThat(postRes.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(postRes.getBody()).isNotNull();
		assertThat(postRes.getBody().getData()).isNotNull();
		String requestId = postRes.getBody().getData().getRequestId();
		assertThat(requestId).isNotBlank();
		assertThat(postRes.getBody().getData().getStatus()).isEqualTo(VerificationRequestStatus.PENDING);

		// when: FakeVerificationConsumer 가 stream 메시지를 소비 + callback 자동 발행 → 폴링으로 SUCCESS 도달까지 대기
		// 비동기 흐름이므로 SLO 정량값은 없으나(spec clarify Q1), CI 안정성 위해 충분한 timeout 부여.
		Awaitility.await()
			.atMost(Duration.ofSeconds(10))
			.pollInterval(Duration.ofMillis(200))
			.untilAsserted(() -> {
				ResponseEntity<String> pollRes = pollStatus(requestId);
				assertThat(pollRes.getStatusCode()).isEqualTo(HttpStatus.OK);
				assertThat(pollRes.getBody()).contains("\"status\":\"SUCCESS\"");
			});

		// then: DB 상태 검증
		// (1) verification_request: status=SUCCESS, similarityScore/verified/completedAt NOT NULL
		Optional<VerificationRequest> savedRequest = verificationRequestRepository.findById(requestId);
		assertThat(savedRequest).as("VerificationRequest persisted").isPresent();
		VerificationRequest req = savedRequest.get();
		assertThat(req.getStatus()).isEqualTo(VerificationRequestStatus.SUCCESS);
		assertThat(req.getSimilarityScore()).isEqualTo(85);
		assertThat(req.getVerified()).isTrue();
		assertThat(req.getCompletedAt()).as("completedAt is set on terminal").isNotNull();
		assertThat(req.getErrorMessage()).isNull();

		// (2) outbox_event: row 존재 + status=PUBLISHED 전이 검증 (가설 1 진단 — P2-A).
		// Awaitility 폴링 — AFTER_COMMIT 리스너의 publisher 트랜잭션이 동기 commit 될 때까지.
		Awaitility.await()
			.atMost(Duration.ofSeconds(5))
			.pollInterval(Duration.ofMillis(200))
			.untilAsserted(() -> {
				Optional<OutboxEvent> oe = outboxRepository.findById(requestId);
				assertThat(oe).as("OutboxEvent persisted with requestId as id (R-004)").isPresent();
				assertThat(oe.get().getStatus())
					.as("outbox status=PUBLISHED 전이 — Phase 3 P2-A 진단")
					.isEqualTo(OutboxStatus.PUBLISHED);
			});

		// (3) verification_record: 1건 저장 (verified=true, similarityScore=85 — int → double 캐스팅)
		List<VerificationRecord> records = verificationRecordRepository.findAll();
		assertThat(records).as("VerificationRecord 1건 저장 (SUCCESS 종착의 도메인 효과)").hasSize(1);
		VerificationRecord record = records.get(0);
		assertThat(record.isVerified()).isTrue();
		assertThat(record.getSimilarityScore()).isEqualTo(85.0);
		assertThat(record.getMember().getId()).isEqualTo(member.getId());
		assertThat(record.getPlanet().getId()).isEqualTo(planet.getId());
	}

	private ResponseEntity<String> pollStatus(String requestId) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Authorization", accessToken);
		return restTemplate.exchange(
			"/api/v1/verify/" + requestId,
			HttpMethod.GET,
			new HttpEntity<>(headers),
			String.class
		);
	}
}

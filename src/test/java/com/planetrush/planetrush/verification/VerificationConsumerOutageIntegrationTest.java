package com.planetrush.planetrush.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
import com.planetrush.planetrush.outbox.repository.OutboxRepository;
import com.planetrush.planetrush.planet.domain.Planet;
import com.planetrush.planetrush.planet.repository.PlanetRepository;
import com.planetrush.planetrush.verification.domain.VerificationRequest;
import com.planetrush.planetrush.verification.domain.VerificationRequestStatus;
import com.planetrush.planetrush.verification.repository.VerificationRecordRepository;
import com.planetrush.planetrush.verification.repository.VerificationRequestRepository;
import com.planetrush.planetrush.verification.service.dto.VerificationAcceptedDto;
import com.planetrush.planetrush.verification.testsupport.FakeVerificationConsumer;
import com.planetrush.planetrush.verification.testsupport.FakeVerificationConsumer.CallbackResult;

/**
 * 컨슈머 다운/복구 chaos 통합 테스트.
 *
 * <p>Spec 005 — T025 / SC-003 / US2.
 *
 * <h3>인수 기준 매핑</h3>
 * <ul>
 *   <li><b>SC-003 — 컨슈머 미가용 시 PENDING 보존 + 복구 후 자동 종착</b>
 *       — 컨슈머가 다운된 상황에서도 사용자 인증 요청은 5xx 가 아닌 PENDING 으로
 *       안전 보관되고(클라이언트 응답 실패율 0%), 컨슈머 복구 후 누적된 요청들이
 *       자동으로 종착(SUCCESS/FAIL/ERROR) 상태에 도달함을 단대단으로 검증한다.</li>
 * </ul>
 *
 * <h3>US2 narrative 표현</h3>
 * <p>본 테스트는 "컨슈머가 단일 장애 지점이 되지 않는다" 는 narrative 의 핵심 증거다 —
 * 메시지 큐(Redis Stream) + Outbox + 비동기 callback 의 조합이 컨슈머 가용성과
 * 사용자 요청 처리 가능성을 분리해 컨슈머 장애를 사용자 가시 5xx 로 전파하지
 * 않는다는 사실을 자동 회귀로 박제한다.
 *
 * <h3>헌법 정합</h3>
 * <ul>
 *   <li><b>I (Testcontainers, NON-NEGOTIABLE)</b> — {@link IntegrationTest} 베이스 상속.
 *       MySQL/Redis Testcontainer 자동 부팅, 로컬 데몬 의존 0.</li>
 *   <li><b>V (시크릿 로그 금지)</b> — 본 테스트 코드의 어느 로그/단언 메시지에도 시크릿
 *       키워드(secret/token/password/jwt/credential) 평문 0.</li>
 *   <li><b>VII (인수 기준 자동 테스트, P1)</b> — SC-003 ↔ 본 클래스 1:1 매핑.</li>
 * </ul>
 *
 * <h3>chaos 시나리오 (US2 Acceptance Scenario 1·2 매핑)</h3>
 * <ol>
 *   <li>{@link FakeVerificationConsumer#start()} — 정상 가동.</li>
 *   <li>인증 요청 5건 발행 + 컨슈머가 메시지를 일부 처리할 시간을 부여.</li>
 *   <li>{@link FakeVerificationConsumer#stop()} — 컨슈머 다운 모의.</li>
 *   <li>추가 인증 요청 5건 발행 → 모두 202 응답 (실패율 0% — Acceptance Scenario 1).</li>
 *   <li>폴링으로 다운 시점 누적 요청들의 status 가 PENDING 으로 유지됨을 확인.</li>
 *   <li>{@link FakeVerificationConsumer#start()} 재기동 — 컨슈머 복구 모의.</li>
 *   <li>Awaitility 로 모든 요청이 SUCCESS 종착에 도달함을 확인 (Acceptance Scenario 2).</li>
 * </ol>
 */
@Import(FakeVerificationConsumer.class)
class VerificationConsumerOutageIntegrationTest extends IntegrationTest {

	/** 컨슈머 가용 상태에서 발행하는 인증 요청 수 — Phase 1. */
	private static final int FIRST_BATCH = 5;
	/** 컨슈머 다운 상태에서 발행하는 인증 요청 수 — Phase 2 (5xx 발생 0 검증). */
	private static final int SECOND_BATCH = 5;
	private static final int TOTAL_REQUESTS = FIRST_BATCH + SECOND_BATCH;

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

	private Planet planet;
	/** 각 인증 요청은 서로 다른 사용자가 보낸다 — 같은 날 같은 챌린지 unique 제약(R-003) 회피. */
	private final List<Member> members = new ArrayList<>();
	private final List<String> accessTokens = new ArrayList<>();

	@BeforeEach
	void setUp() {
		planet = planetRepository.save(PlanetFixture.readyPlanet(TOTAL_REQUESTS + 5,
			java.time.LocalDate.now().plusDays(5),
			java.time.LocalDate.now().plusDays(10),
			com.planetrush.planetrush.planet.domain.Category.ETC));

		// 사용자별 토큰 사전 생성 — 같은 사용자가 같은 챌린지에 다중 요청을 보내면
		// SUCCESS 종착 시 `uniq_verification_record_member_planet_date` 가 두 번째부터
		// 거절(R-003 안전망) 되어 chaos 시나리오의 본질(컨슈머 가용성↔요청 처리) 이 가려진다.
		// 본 테스트는 사용자별 1요청 모델로 컨슈머 장애 흡수 시멘틱만 단언한다.
		for (int i = 1; i <= TOTAL_REQUESTS; i++) {
			Member member = memberRepository.save(MemberFixture.activeMember(i));
			members.add(member);
			accessTokens.add(jwtTokenProvider.createToken(member.getId()).getAccessToken());
		}

		fakeConsumer.setCallbackBaseUrl("http://localhost:" + port);
		fakeConsumer.clearGroup();
	}

	@AfterEach
	void tearDown() {
		fakeConsumer.stop();
		// 외래키 순서 — VerificationRecord(member/planet FK) → VerificationRequest → outbox → planet → member
		verificationRecordRepository.deleteAll();
		verificationRequestRepository.deleteAll();
		outboxRepository.deleteAll();
		planetRepository.deleteAll();
		memberRepository.deleteAll();
		members.clear();
		accessTokens.clear();
	}

	@DisplayName("SC-003: 컨슈머 다운 중 인증 요청 5xx 0건 → PENDING 보존 → 복구 후 모두 종착")
	@Test
	void consumerOutageDoesNotFailRequestsAndRecoveryDrainsBacklog() {
		// given: 컨슈머가 정상 가동, SUCCESS 결과만 enqueue (similarityScore 85)
		List<CallbackResult> results = new ArrayList<>();
		for (int i = 0; i < TOTAL_REQUESTS; i++) {
			results.add(CallbackResult.success(85));
		}
		fakeConsumer.enqueueResults(results);
		fakeConsumer.start();

		// when (1): 컨슈머 가용 상태에서 첫 배치 5건 발행 — 모두 202 응답
		List<String> firstBatchIds = new ArrayList<>();
		for (int i = 0; i < FIRST_BATCH; i++) {
			firstBatchIds.add(submitVerifyRequest(i));
		}

		// then (1): 모든 응답이 202 — 발행 자체는 컨슈머 가용성과 무관
		assertThat(firstBatchIds)
			.as("Phase 1 — 컨슈머 가용 상태에서의 인증 요청 5건 모두 202 응답")
			.hasSize(FIRST_BATCH)
			.allMatch(id -> id != null && !id.isBlank());

		// when (2): 일부 메시지가 처리되도록 잠시 대기 → 컨슈머 다운 모의
		// runUntilProcessed 가 최소 1건 처리될 때까지 기다리고, 그 후 stop()
		// (정확히 N건만 처리되는 시점을 잡으려 하지 않는다 — chaos 의 핵심은 다운 중 누적분의
		// PENDING 보존과 복구 후의 종착이지, 다운 직전 정확한 처리 수가 아니다.)
		fakeConsumer.runUntilProcessed(1, Duration.ofSeconds(5));
		fakeConsumer.stop();

		// when (3): 컨슈머 다운 상태에서 두 번째 배치 5건 발행 — 핵심 검증: 응답 실패율 0%
		List<String> secondBatchIds = new ArrayList<>();
		for (int i = 0; i < SECOND_BATCH; i++) {
			secondBatchIds.add(submitVerifyRequest(FIRST_BATCH + i));
		}

		// then (3): 다운 상태에서도 모든 202 응답 (Acceptance Scenario 1 — 사용자 요청은 5xx 가 아님)
		assertThat(secondBatchIds)
			.as("SC-003 핵심: 컨슈머 다운 상태에서도 인증 요청 5건 모두 202 응답 — 실패율 0%")
			.hasSize(SECOND_BATCH)
			.allMatch(id -> id != null && !id.isBlank());

		// then (4): 다운 직후 미처리 요청들의 status 가 PENDING 으로 보존 ─ 폴링으로 확인
		// (이미 callback 이 도착한 일부는 SUCCESS 일 수 있다 — 적어도 한 건은 PENDING 잔존이어야 한다.)
		List<String> allIds = new ArrayList<>();
		allIds.addAll(firstBatchIds);
		allIds.addAll(secondBatchIds);

		long pendingCount = verificationRequestRepository.findAll().stream()
			.filter(req -> req.getStatus() == VerificationRequestStatus.PENDING)
			.count();
		assertThat(pendingCount)
			.as("컨슈머 다운 중 PENDING 잔존 요청이 최소 1건 — 컨슈머 미가용 흡수 증거")
			.isGreaterThanOrEqualTo(1L);

		// when (5): 컨슈머 복구 ─ start() 재호출 (Phase 2 review 의 P2 항목 — stop 후 start 재가동 정합성)
		fakeConsumer.start();

		// then (5): Awaitility 로 모든 요청이 종착(SUCCESS) 도달까지 대기 ─ Acceptance Scenario 2
		// 비동기 흐름이므로 정량 SLO 는 없으나(spec clarify Q1), CI 안정성을 위해 충분한 timeout 부여.
		Awaitility.await()
			.atMost(Duration.ofSeconds(20))
			.pollInterval(Duration.ofMillis(250))
			.untilAsserted(() -> {
				long terminalCount = verificationRequestRepository.findAll().stream()
					.filter(req -> isTerminal(req.getStatus()))
					.count();
				assertThat(terminalCount)
					.as("복구 후 모든 누적 요청이 종착에 도달")
					.isEqualTo(TOTAL_REQUESTS);
			});

		// then (6): 모든 요청이 SUCCESS 종착 + 폴링 응답도 실패율 0% ─ 클라이언트 가시 동작 검증
		for (String requestId : allIds) {
			VerificationRequest req = verificationRequestRepository.findById(requestId).orElseThrow();
			assertThat(req.getStatus())
				.as("requestId=%s 의 최종 상태가 SUCCESS", requestId)
				.isEqualTo(VerificationRequestStatus.SUCCESS);
			assertThat(req.getCompletedAt()).isNotNull();

			// 폴링 응답 자체도 OK
			ResponseEntity<String> pollRes = pollStatus(requestId, accessTokens.get(allIds.indexOf(requestId)));
			assertThat(pollRes.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(pollRes.getBody()).contains("\"status\":\"SUCCESS\"");
		}
	}

	private static boolean isTerminal(VerificationRequestStatus status) {
		return status == VerificationRequestStatus.SUCCESS
			|| status == VerificationRequestStatus.FAIL
			|| status == VerificationRequestStatus.ERROR;
	}

	/** 사용자 {@code index} 의 JWT 로 POST /api/v1/verify/planets/{planetId} 호출 → 202 + requestId 반환. */
	private String submitVerifyRequest(int index) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Authorization", accessTokens.get(index));
		headers.setContentType(MediaType.APPLICATION_JSON);
		String body = "{\"verificationImgUrl\":\"https://img.example/target-" + index + ".jpg\"}";
		ResponseEntity<BaseResponse<VerificationAcceptedDto>> res = restTemplate.exchange(
			"/api/v1/verify/planets/" + planet.getId(),
			HttpMethod.POST,
			new HttpEntity<>(body, headers),
			new ParameterizedTypeReference<BaseResponse<VerificationAcceptedDto>>() {
			}
		);
		assertThat(res.getStatusCode())
			.as("인증 요청 #%d 응답이 202 ACCEPTED — 컨슈머 가용성과 무관해야 함", index)
			.isEqualTo(HttpStatus.ACCEPTED);
		assertThat(res.getBody()).isNotNull();
		assertThat(res.getBody().getData()).isNotNull();
		String requestId = res.getBody().getData().getRequestId();
		assertThat(requestId).isNotBlank();
		assertThat(res.getBody().getData().getStatus()).isEqualTo(VerificationRequestStatus.PENDING);
		return requestId;
	}

	private ResponseEntity<String> pollStatus(String requestId, String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Authorization", token);
		return restTemplate.exchange(
			"/api/v1/verify/" + requestId,
			HttpMethod.GET,
			new HttpEntity<>(headers),
			String.class
		);
	}
}

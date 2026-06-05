package com.planetrush.planetrush.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import com.planetrush.planetrush.verification.domain.VerificationRecord;
import com.planetrush.planetrush.verification.domain.VerificationRequest;
import com.planetrush.planetrush.verification.domain.VerificationRequestStatus;
import com.planetrush.planetrush.verification.repository.VerificationRecordRepository;
import com.planetrush.planetrush.verification.repository.VerificationRequestRepository;
import com.planetrush.planetrush.verification.service.dto.VerificationAcceptedDto;

/**
 * 사용자·챌린지·날짜 단위 멱등(SC-005b) 통합 테스트.
 *
 * <p>Spec 005 — T028 / SC-005b / R-003 / US3. Testcontainers MySQL 위에서 실행한다.
 *
 * <h3>인수 기준 매핑</h3>
 * <ul>
 *   <li><b>SC-005b (사용자·챌린지·날짜 단위 멱등, US3)</b> — 같은 사용자가 같은 챌린지에 서로 다른
 *       {@code requestId} 로 두 인증 요청을 발행하고 둘 다 SUCCESS callback 이 (직렬로) 도착해도
 *       {@code VerificationRecord} 는 1건만 보존된다. 두 인증 요청은 각각 SUCCESS 로 종착 표시된다.</li>
 * </ul>
 *
 * <h3>멱등 메커니즘 — 조회 후 저장</h3>
 * <p>{@code VerificationResultService} 는 record 저장 전에 {@code existsTodayRecord} 로 사용자·챌린지·날짜
 * 단위 기존 기록(성공/실패 무관)을 조회해, 이미 있으면 저장을 건너뛴다. 따라서 두 번째 callback 은 record 를
 * 추가 저장하지 않고 status 전이(SUCCESS)만 수행한다 — record 1건 보존.
 *
 * <p><b>전제(spec US3)</b>: 같은 사용자가 같은 날 인증 요청을 거의 동시에 두 번 보내는 callback 동시
 * 도착(race) 은 발생하지 않는다고 가정한다. 따라서 본 테스트는 직렬 시나리오만 검증한다.
 *
 * <h3>왜 H2/mock 이 아니라 실제 MySQL 인가</h3>
 * <p>사용자·챌린지·날짜 unique 제약과 {@code existsTodayRecord} 조회 동작은 실제 MySQL 위에서만
 * 의미가 있다. 본 테스트는 {@link IntegrationTest} (Testcontainers MySQL) 위에서 실행한다 —
 * 헌법 I (Testcontainers, NON-NEGOTIABLE) 정합.
 *
 * <h3>헌법 정합</h3>
 * <ul>
 *   <li><b>I (Testcontainers)</b> — {@link IntegrationTest} 베이스 상속. 로컬 데몬 의존 0.</li>
 *   <li><b>VII (인수 기준 자동 테스트)</b> — SC-005b ↔ 본 클래스 1:1 매핑.</li>
 * </ul>
 *
 * <h3>시나리오 설계 — FakeVerificationConsumer 미사용</h3>
 * <p>두 PENDING 요청을 같은 사용자·planet 으로 만들려면, 첫 요청의 callback 이 record 를 만들기
 * <b>전에</b> 두 요청을 모두 발행해야 한다({@code verifyTodayChallenge} 의 오늘-종착-record 가드가
 * 두 번째 발행을 막지 않도록). 따라서 컨슈머를 띄우지 않고, 두 PENDING 을 API 로 발행한 뒤 callback 을
 * 내부 엔드포인트로 직접 POST 해 순서를 결정론적으로 제어한다. callback 경로 자체는 production
 * 컨트롤러·서비스를 그대로 통과하므로 검증 대상의 동작은 100% 실제 흐름이다.
 */
class VerificationDailyIdempotencyTest extends IntegrationTest {

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

	private Member member;
	private Planet planet;
	private String accessToken;

	@BeforeEach
	void setUp() {
		// 같은 사용자·같은 planet — SC-005b 의 사용자·챌린지·날짜 unique 제약(R-003) 발동 조건.
		member = memberRepository.save(MemberFixture.activeMember());
		planet = planetRepository.save(PlanetFixture.readyPlanet());
		accessToken = jwtTokenProvider.createToken(member.getId()).getAccessToken();
	}

	@AfterEach
	void tearDown() {
		// 외래키 순서 — VerificationRecord(member/planet FK) → VerificationRequest → outbox → planet → member
		verificationRecordRepository.deleteAll();
		verificationRequestRepository.deleteAll();
		outboxRepository.deleteAll();
		planetRepository.deleteAll();
		memberRepository.deleteAll();
	}

	@DisplayName("SC-005b: 같은 사용자·planet PENDING 2건에 직렬 SUCCESS callback 2회 → 둘 다 200 + 둘 다 SUCCESS 종착 + record 1건")
	@Test
	void serialSuccessCallbacks_bothTerminalSuccess_singleRecord() {
		// given: 컨슈머를 띄우지 않고 같은 사용자·planet 으로 PENDING 2건 발행 (서로 다른 requestId).
		// (첫 callback 이 record 를 만들기 전에 둘 다 발행 — 오늘-종착-record 가드 회피.)
		String requestId1 = submitPendingRequest("a");
		String requestId2 = submitPendingRequest("b");
		assertThat(requestId1).isNotBlank();
		assertThat(requestId2).isNotBlank();
		assertThat(requestId1).isNotEqualTo(requestId2);

		// when: 두 PENDING 에 SUCCESS callback 을 직렬로 도착시킨다.
		ResponseEntity<String> callback1 = postSuccessCallback(requestId1, 85);
		ResponseEntity<String> callback2 = postSuccessCallback(requestId2, 90);

		// then: 두 callback 모두 2xx — 두 번째도 정상 흡수(롤백/500 아님).
		assertThat(callback1.getStatusCode().is2xxSuccessful())
			.as("첫 번째 SUCCESS callback 이 2xx")
			.isTrue();
		assertThat(callback2.getStatusCode().is2xxSuccessful())
			.as("두 번째 SUCCESS callback 도 2xx — 조회-후-저장 가드로 record 추가 저장 없이 흡수")
			.isTrue();

		// then: 두 요청 모두 SUCCESS 종착.
		VerificationRequest req1 = verificationRequestRepository.findById(requestId1).orElseThrow();
		VerificationRequest req2 = verificationRequestRepository.findById(requestId2).orElseThrow();
		assertThat(req1.getStatus())
			.as("첫 번째 요청 SUCCESS 종착")
			.isEqualTo(VerificationRequestStatus.SUCCESS);
		assertThat(req2.getStatus())
			.as("두 번째 요청 SUCCESS 종착")
			.isEqualTo(VerificationRequestStatus.SUCCESS);
		assertThat(req1.getCompletedAt()).isNotNull();
		assertThat(req2.getCompletedAt()).isNotNull();

		// then (SC-005b 핵심): VerificationRecord 정확히 1건만 보존.
		List<VerificationRecord> records = verificationRecordRepository.findAll();
		assertThat(records)
			.as("같은 사용자·planet·날짜 → VerificationRecord 1건만 (R-003)")
			.hasSize(1);
		assertThat(records.get(0).getMember().getId()).isEqualTo(member.getId());
		assertThat(records.get(0).getPlanet().getId()).isEqualTo(planet.getId());
		assertThat(records.get(0).isVerified()).isTrue();
	}

	// ---------------------------------------------------------------------
	// helpers
	// ---------------------------------------------------------------------

	/** 같은 사용자·planet 으로 PENDING 인증 요청 발행 → 202 + requestId 반환. */
	private String submitPendingRequest(String tag) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Authorization", accessToken);
		headers.setContentType(MediaType.APPLICATION_JSON);
		String body = "{\"verificationImgUrl\":\"https://img.example/target-" + tag + ".jpg\"}";
		ResponseEntity<BaseResponse<VerificationAcceptedDto>> res = restTemplate.exchange(
			"/api/v1/verify/planets/" + planet.getId(),
			HttpMethod.POST,
			new HttpEntity<>(body, headers),
			new ParameterizedTypeReference<BaseResponse<VerificationAcceptedDto>>() {
			}
		);
		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(res.getBody()).isNotNull();
		assertThat(res.getBody().getData()).isNotNull();
		assertThat(res.getBody().getData().getStatus()).isEqualTo(VerificationRequestStatus.PENDING);
		return res.getBody().getData().getRequestId();
	}

	/** 내부 callback 엔드포인트로 SUCCESS payload 를 직접 POST (컨슈머 시뮬레이션). */
	private ResponseEntity<String> postSuccessCallback(String requestId, int similarityScore) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		String body = "{\"requestId\":\"" + requestId + "\",\"similarityScore\":" + similarityScore
			+ ",\"verified\":true}";
		return restTemplate.exchange(
			"/api/v1/internal/verification-results",
			HttpMethod.POST,
			new HttpEntity<>(body, headers),
			String.class
		);
	}
}

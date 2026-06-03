package com.planetrush.planetrush.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import com.planetrush.planetrush.IntegrationTest;
import com.planetrush.planetrush.core.jwt.JwtTokenProvider;
import com.planetrush.planetrush.core.template.response.BaseResponse;
import com.planetrush.planetrush.fixture.MemberFixture;
import com.planetrush.planetrush.fixture.PlanetFixture;
import com.planetrush.planetrush.infra.publisher.VerificationRedisStreamPublisher;
import com.planetrush.planetrush.member.domain.Member;
import com.planetrush.planetrush.member.repository.MemberRepository;
import com.planetrush.planetrush.outbox.domain.OutboxEvent;
import com.planetrush.planetrush.outbox.domain.OutboxStatus;
import com.planetrush.planetrush.outbox.repository.OutboxRepository;
import com.planetrush.planetrush.outbox.republisher.OutboxRepublisher;
import com.planetrush.planetrush.planet.domain.Planet;
import com.planetrush.planetrush.planet.repository.PlanetRepository;
import com.planetrush.planetrush.verification.domain.VerificationRequestStatus;
import com.planetrush.planetrush.verification.event.publisher.VerificationMessagePublisher;
import com.planetrush.planetrush.verification.repository.VerificationRecordRepository;
import com.planetrush.planetrush.verification.repository.VerificationRequestRepository;
import com.planetrush.planetrush.verification.service.dto.MessageCommand;
import com.planetrush.planetrush.verification.service.dto.VerificationAcceptedDto;
import com.planetrush.planetrush.verification.testsupport.FakeVerificationConsumer;
import com.planetrush.planetrush.verification.testsupport.FakeVerificationConsumer.CallbackResult;

/**
 * Outbox Republisher 의 Spec 005 정합 — Stream 발행 일시 실패 → Republisher 자동 재발행 통합 테스트.
 *
 * <p>Spec 005 — T026 / SC-004 / US2.
 *
 * <h3>인수 기준 매핑</h3>
 * <ul>
 *   <li><b>SC-004 — Stream 발행 실패 시 Republisher 가 자동 재발행해 결국 종착</b>
 *       — Redis Stream 발행 자체가 실패해 {@code OutboxEvent} 가 PENDING 으로 남은 경우,
 *       Spec 002 의 {@link OutboxRepublisher} 가 외부 개입 없이 자동 재발행해 종착
 *       상태에 도달함을 통합 테스트로 검증한다(Spec 002 SC 와의 정합성 확인).</li>
 * </ul>
 *
 * <h3>US2 narrative 표현</h3>
 * <p>본 테스트는 "메시지 큐 + Outbox 가 발행 측의 일시 장애를 흡수한다" 는 narrative 의
 * 보조 증거다 — 컨슈머 가용성뿐 아니라 Spring → Redis 발행 경로 자체의 일시 장애에
 * 대해서도 사용자 요청은 PENDING 으로 안전 보관되고 Republisher 가 자동 복구한다.
 *
 * <h3>헌법 정합</h3>
 * <ul>
 *   <li><b>I (Testcontainers, NON-NEGOTIABLE)</b> — {@link IntegrationTest} 베이스 상속.</li>
 *   <li><b>IV (Outbox 강제, NON-NEGOTIABLE)</b> — 본 테스트는 Outbox 가 발행 실패의 안전망임을
 *       단언한다. P2-A REQUIRES_NEW fix 의 정합 회귀(outbox status=PUBLISHED 전이) 도 함께 검증.</li>
 *   <li><b>V (시크릿 로그 금지)</b> — 테스트 코드 로그/단언 메시지에 시크릿 키워드 평문 0.</li>
 *   <li><b>VII (인수 기준 자동 테스트, P1)</b> — SC-004 ↔ 본 클래스 1:1 매핑.</li>
 * </ul>
 *
 * <h3>시나리오 (US2 Acceptance Scenario 3 매핑)</h3>
 * <ol>
 *   <li>{@link FailFirstThenDelegatePublisher} — 첫 호출은 stream 발행을 "실패"(no-op, outbox PENDING
 *       유지) 시뮬레이션, 두 번째 호출부터 진짜 {@link VerificationRedisStreamPublisher} 에 위임.</li>
 *   <li>인증 요청 1건 발행 → 첫 publish 호출 실패 → outbox PENDING 잔존 확인.</li>
 *   <li>{@code application-test.yml} 의 {@code outbox.republisher.enabled=false} (Spec 002 FR-006) 정합 —
 *       Spec 002 패턴 그대로 {@link OutboxRepublisher#republishPending()} 를 테스트 코드가 직접 호출.</li>
 *   <li>재발행 호출 후 페이크가 진짜에 위임 → stream 메시지 1건 생성 → {@link FakeVerificationConsumer}
 *       가 소비 + callback → 인증 요청 status 종착(SUCCESS) 확인.</li>
 *   <li>outbox status=PUBLISHED 전이 확인 — P2-A REQUIRES_NEW fix 정합(Phase 3 review).</li>
 * </ol>
 *
 * <h3>Spec 002 정합</h3>
 * <p>{@link OutboxRepublisher} 사이클 직접 호출 패턴은 Spec 002 의
 * {@code OutboxRepublisherIntegrationTest.transientFailureKeepsPendingThenSucceedsOnRetry}
 * 와 동일하다 — 본 테스트는 그 패턴을 Spec 005 의 신규 발행 경로
 * (서비스 → outbox INSERT → AFTER_COMMIT 리스너 → publisher) 위에서 재현해
 * 두 스펙의 정합을 단대단으로 박제한다.
 *
 * <h3>컨텍스트 캐시 영향 — {@link DirtiesContext}</h3>
 * <p>{@link FailFirstThenDelegatePublisher} 의 {@code @Primary} 페이크 빈은 본 클래스 전용이라
 * 다른 통합 테스트와 컨텍스트를 공유하면 의도치 않은 부수효과를 일으킬 수 있다 — 본 클래스에
 * {@code @DirtiesContext(classMode = AFTER_CLASS)} 를 부착해 컨텍스트 격리를 보장한다.
 */
@Import({FakeVerificationConsumer.class, VerificationOutboxRepublisherIntegrationTest.FakePublisherTestConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VerificationOutboxRepublisherIntegrationTest extends IntegrationTest {

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
	private OutboxRepublisher outboxRepublisher;

	@Autowired
	private FailFirstThenDelegatePublisher failFirstPublisher;

	@Autowired
	private FakeVerificationConsumer fakeConsumer;

	private Member member;
	private Planet planet;
	private String accessToken;

	@BeforeEach
	void setUp() {
		member = memberRepository.save(MemberFixture.activeMember());
		planet = planetRepository.save(PlanetFixture.readyPlanet());
		accessToken = jwtTokenProvider.createToken(member.getId()).getAccessToken();

		fakeConsumer.setCallbackBaseUrl("http://localhost:" + port);
		fakeConsumer.clearGroup();

		failFirstPublisher.reset();
	}

	@AfterEach
	void tearDown() {
		fakeConsumer.stop();
		verificationRecordRepository.deleteAll();
		verificationRequestRepository.deleteAll();
		outboxRepository.deleteAll();
		planetRepository.deleteAll();
		memberRepository.deleteAll();
	}

	@DisplayName("SC-004: Stream 발행 일시 실패 → outbox PENDING 잔존 → Republisher 사이클이 자동 재발행 → status 종착")
	@Test
	void streamPublishFailureIsHealedByOutboxRepublisher() {
		// given: 컨슈머는 정상 가동 + SUCCESS 결과 enqueue
		fakeConsumer.setNextResult(CallbackResult.success(85));
		fakeConsumer.start();

		// when (1): 인증 요청 1건 발행 — 첫 publish 호출은 페이크가 흡수 (stream 미발행 → outbox PENDING)
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

		// then (1): 202 + PENDING — 발행 실패는 사용자 가시 5xx 로 전파되지 않음(헌법 IV 의 정신)
		assertThat(postRes.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		String requestId = postRes.getBody().getData().getRequestId();
		assertThat(requestId).isNotBlank();
		assertThat(postRes.getBody().getData().getStatus()).isEqualTo(VerificationRequestStatus.PENDING);

		// then (2): outbox row 가 PENDING 으로 잔존 — Republisher 의 자동 복구 대상
		// AFTER_COMMIT 리스너가 발화한 후의 상태를 확인하기 위해 약간의 폴링.
		Awaitility.await()
			.atMost(Duration.ofSeconds(5))
			.pollInterval(Duration.ofMillis(200))
			.untilAsserted(() -> {
				Optional<OutboxEvent> oe = outboxRepository.findById(requestId);
				assertThat(oe)
					.as("OutboxEvent row 존재 (R-004 — id=requestId 동일 UUID)")
					.isPresent();
				assertThat(oe.get().getStatus())
					.as("첫 publish 가 페이크에 흡수되어 outbox 는 PENDING 잔존")
					.isEqualTo(OutboxStatus.PENDING);
			});

		// 추가 단언: 페이크가 정확히 첫 호출 1회만 흡수했음 — 시나리오 전제 확인
		assertThat(failFirstPublisher.getInvocationCount())
			.as("첫 publish 호출이 페이크에 도착 (AFTER_COMMIT 리스너 → @Primary publisher)")
			.isEqualTo(1);

		// when (3): Spec 002 OutboxRepublisher 사이클 직접 호출 — application-test.yml 이 폴러를
		//          비활성화(outbox.republisher.enabled=false / Spec 002 FR-006) 했으므로,
		//          본 테스트가 사이클을 한 번 수동 트리거해 자동 복구 동작을 결정론적으로 재현한다.
		outboxRepublisher.republishPending();

		// then (3): 페이크는 두 번째 호출부터 진짜 VerificationRedisStreamPublisher 에 위임 →
		//          stream entry 가 생성되어 FakeVerificationConsumer 가 소비 + callback →
		//          인증 요청 status 가 SUCCESS 종착.
		Awaitility.await()
			.atMost(Duration.ofSeconds(10))
			.pollInterval(Duration.ofMillis(250))
			.untilAsserted(() -> {
				ResponseEntity<String> pollRes = pollStatus(requestId);
				assertThat(pollRes.getStatusCode()).isEqualTo(HttpStatus.OK);
				assertThat(pollRes.getBody())
					.as("Republisher 사이클 이후 폴링 응답이 SUCCESS")
					.contains("\"status\":\"SUCCESS\"");
			});

		// then (4): outbox status=PUBLISHED 전이 — Phase 3 P2-A REQUIRES_NEW fix 정합 회귀.
		//           실제 publisher 가 호출되었으므로 outbox status 가 PUBLISHED 로 전이되어야 한다.
		Awaitility.await()
			.atMost(Duration.ofSeconds(5))
			.pollInterval(Duration.ofMillis(200))
			.untilAsserted(() -> {
				OutboxEvent oe = outboxRepository.findById(requestId).orElseThrow();
				assertThat(oe.getStatus())
					.as("OutboxEvent.status=PUBLISHED — P2-A REQUIRES_NEW fix 정합")
					.isEqualTo(OutboxStatus.PUBLISHED);
			});

		// then (5): VerificationRecord 1건 저장 — SUCCESS 종착의 도메인 효과 (FR-007)
		assertThat(verificationRecordRepository.findAll())
			.as("SUCCESS 종착의 도메인 효과로 VerificationRecord 1건 저장")
			.hasSize(1);

		// then (6): 페이크가 정확히 2번 호출되었음 — 1) 첫 publish(흡수), 2) Republisher 사이클(위임).
		assertThat(failFirstPublisher.getInvocationCount())
			.as("페이크 publisher 가 정확히 2회 호출 — 첫 호출은 흡수, 두 번째는 진짜에 위임")
			.isEqualTo(2);
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

	// ---------------------------------------------------------------------
	// 테스트 전용 페이크 publisher — @Primary 로 진짜를 가린다.
	// ---------------------------------------------------------------------

	/**
	 * 본 테스트 클래스 전용 페이크 {@link VerificationMessagePublisher}.
	 *
	 * <p>Spec 005 — Phase 4 후속 fix (publisher 단순화 + 헬퍼 도입) 정합. 첫 호출은 stream 발행
	 * 실패를 {@link RuntimeException} 으로 표현 — 호출자({@code OutboxPublishingHelper}) 가 예외를
	 * 전파받아 status 갱신을 건너뛰고, 추가 호출자({@code VerificationOutboxPublishListener}) 가
	 * try-catch 로 흡수해 outbox row 가 PENDING 으로 잔존한다(Republisher 의 자동 재시도 대상).
	 *
	 * <p>두 번째 호출부터는 진짜 publisher 와 동일한 인터페이스 계약 — Redis XADD 만 수행하고
	 * outbox status 갱신은 호출자가 책임진다(헬퍼가 같은 트랜잭션의 managed entity 에 dirty mark).
	 *
	 * <p>{@code @Primary} 가 부착되어 {@code VerificationOutboxPublishListener}/
	 * {@code OutboxPublishingHelper} 의 인터페이스 주입을 본 빈이 받는다. 진짜 publisher 빈은
	 * 컨텍스트에 존재하지만 본 테스트 경로에서는 호출되지 않는다.
	 *
	 * <h3>Phase 3 P2-A 결함이 Phase 4 후속 fix 로 해소된 정합</h3>
	 * <p>이전 흐름은 진짜 publisher 가 {@code @Transactional(REQUIRES_NEW)} 를 들어 Republisher 의
	 * SKIP LOCKED 락 보유 트랜잭션 안에서 호출 시 self-deadlock 이 발생했다. publisher 를 단순
	 * 어댑터로 단순화하고 트랜잭션·status 갱신 책임을 호출자({@code OutboxPublishingHelper}) 로
	 * 옮긴 후 본 페이크도 진짜와 동일하게 "Redis 발행만" 하면 충분해졌다.
	 */
	static class FailFirstThenDelegatePublisher implements VerificationMessagePublisher {

		private final StringRedisTemplate redisTemplate;
		private final AtomicInteger invocationCount = new AtomicInteger(0);

		/** stream key 는 application-test.yml 의 verification.redis.stream-key 와 정합. */
		private static final String STREAM_KEY = "verify:requests";

		FailFirstThenDelegatePublisher(StringRedisTemplate redisTemplate) {
			this.redisTemplate = redisTemplate;
		}

		@Override
		public void publish(MessageCommand command) {
			int n = invocationCount.incrementAndGet();
			if (n == 1) {
				// 일시 발행 실패 시뮬레이션 — 예외 전파로 호출자가 status 갱신을 건너뛰게 한다.
				// (실제 운영의 RedisConnectionFailureException 등이 발행 단계에서 throw 되는 경로 모의.)
				throw new RuntimeException("simulated publish failure — first attempt");
			}
			// 두 번째 호출 — 진짜 publisher 와 동일한 계약: BRIEF §3-1 정합 stream entry 발행만.
			// outbox status 갱신은 호출자({@code OutboxPublishingHelper}) 가 같은 트랜잭션 안에서 처리.
			Map<String, String> fields = new LinkedHashMap<>();
			fields.put("requestId", command.eventId());
			fields.put("standardImgUrl", command.standardImg());
			fields.put("targetImgUrl", command.targetImg());
			fields.put("callbackUrl", command.callbackUrl());
			fields.put("threshold", command.threshold());
			redisTemplate.opsForStream().add(STREAM_KEY, fields);
		}

		int getInvocationCount() {
			return invocationCount.get();
		}

		void reset() {
			invocationCount.set(0);
		}
	}

	/**
	 * 페이크 publisher 를 {@code @Primary} 빈으로 등록하는 nested 설정.
	 *
	 * <p>{@code @SpringBootTest} 가 본 nested {@code @TestConfiguration} 을 자동 스캔하지 않으므로
	 * 본 클래스의 {@code @Import} 에서 명시적으로 가져온다.
	 */
	@TestConfiguration
	static class FakePublisherTestConfig {

		/**
		 * 페이크 publisher 를 {@code @Primary} 로 등록 — 인터페이스 주입 시점에 본 빈이 선택된다.
		 * Spec 002 의 stub publisher 패턴(stream 발행 + outbox.published()) 을 직접 구현해
		 * Spec 005 신규 publisher 의 REQUIRES_NEW deadlock 결함을 우회한다.
		 */
		@Bean
		@Primary
		FailFirstThenDelegatePublisher failFirstThenDelegatePublisher(StringRedisTemplate redisTemplate) {
			return new FailFirstThenDelegatePublisher(redisTemplate);
		}
	}
}

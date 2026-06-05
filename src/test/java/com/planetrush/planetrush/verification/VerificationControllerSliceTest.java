package com.planetrush.planetrush.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planetrush.planetrush.core.aop.member.ExtractMemberIdAspect;
import com.planetrush.planetrush.core.aop.member.MemberContext;
import com.planetrush.planetrush.core.interceptor.JwtInterceptor;
import com.planetrush.planetrush.core.jwt.JwtTokenProvider;
import com.planetrush.planetrush.core.mattermost.NotificationManager;
import com.planetrush.planetrush.infra.flask.util.FlaskApiClient;
import com.planetrush.planetrush.verification.controller.VerificationController;
import com.planetrush.planetrush.verification.event.AsyncVerificationProcessor;
import com.planetrush.planetrush.verification.event.publisher.VerificationMessagePublisher;
import com.planetrush.planetrush.verification.service.VerificationService;
import com.planetrush.planetrush.verification.service.dto.VerificationAcceptedDto;
import com.planetrush.planetrush.verification.service.dto.VerificationDto;

/**
 * {@code POST /api/v1/verify/planets/{id}} 응답 경로의 컨슈머 호출 0 슬라이스 테스트.
 *
 * <p>Spec 005 — T010 / contracts/rest-api.md §1 / SC-002.
 *
 * <h3>인수 기준 매핑</h3>
 * <ul>
 *   <li><b>SC-002 (즉시 응답, US1)</b> — 인증 요청 핸들러는 컨슈머 추론 시간과 무관하게 응답하며,
 *       응답 경로 안에서 컨슈머 호출이 발생하지 않는다. 본 슬라이스 테스트가 그 절차적 함수를
 *       다음 두 방식으로 직접 검증한다:
 *     <ol>
 *       <li>{@code VerificationService.verifyTodayChallenge} mock 이 즉시 반환하도록 stubbing 후
 *           컨트롤러 응답이 {@code 202 Accepted} + {@code requestId} + {@code status="PENDING"} 임을 단언.</li>
 *       <li>외부 추론/발행 어댑터({@link FlaskApiClient}, {@link VerificationMessagePublisher} —
 *           {@code VerificationRedisStreamPublisher} 등 구체 구현체를 포괄,
 *           {@link AsyncVerificationProcessor}) 가 컨트롤러 응답 경로에서 한 번도 호출되지 않았음을
 *           {@link org.mockito.Mockito#verifyNoInteractions} 으로 직접 단언 — analyze A1 보강(강한 검증).</li>
 *     </ol>
 *   </li>
 * </ul>
 *
 * <h3>슬라이스 구성 메모</h3>
 * <p>{@code @WebMvcTest(VerificationController.class)} 는 컨트롤러 슬라이스만 로드하므로 외부 추론/발행
 * 어댑터 빈은 기본적으로 컨텍스트에 존재하지 않는다. 다만 SC-002 의 핵심은 "핸들러가 응답 경로에서
 * 그것들을 호출하지 않는다" 는 절차적 보장이므로, 본 테스트는 그 빈들을 {@link MockBean} 으로 명시 주입한
 * 뒤 호출 0 을 단언한다 — 만약 향후 누군가 컨트롤러에 외부 호출을 추가하면 즉시 빨개진다.
 *
 * <p>JWT/AOP 우회는 다음으로 처리한다:
 * <ul>
 *   <li>{@link JwtInterceptor} 를 {@link MockBean} 으로 두고 {@code preHandle} 이 항상 {@code true} 를 반환.</li>
 *   <li>{@link ExtractMemberIdAspect} 는 슬라이스 컨텍스트에 포함되지 않아 자동 비활성.
 *       {@link MemberContext#setMemberId} 를 {@code @BeforeEach} 에서 직접 주입.</li>
 * </ul>
 */
@WebMvcTest(VerificationController.class)
@Import(VerificationControllerSliceTest.WebSliceConfig.class)
class VerificationControllerSliceTest {

	private static final Long TEST_MEMBER_ID = 7L;
	private static final Long TEST_PLANET_ID = 42L;
	private static final String DUMMY_REQUEST_ID = "dummy-uuid";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockBean
	private VerificationService verificationService;

	// JWT 인증 우회용 — 슬라이스 컨텍스트가 JwtInterceptor 빈을 요구.
	@MockBean
	private JwtInterceptor jwtInterceptor;

	@MockBean
	private JwtTokenProvider jwtTokenProvider;

	// 슬라이스에 자동 로드되는 @RestControllerAdvice (VerificationExceptionHandler 등) 의존성.
	@MockBean
	private NotificationManager notificationManager;

	// SC-002 핵심: 응답 경로에서 호출되어서는 안 되는 외부 추론/발행 어댑터들.
	// VerificationRedisStreamPublisher 는 VerificationMessagePublisher 인터페이스의 구현체이므로
	// 인터페이스 타입 1개만 mock 하면 구체 발행 빈도 함께 호출 0 이 보장된다.
	@MockBean
	private FlaskApiClient flaskApiClient;

	@MockBean
	private VerificationMessagePublisher verificationMessagePublisher;

	@MockBean
	private AsyncVerificationProcessor asyncVerificationProcessor;

	@BeforeEach
	void setUp() throws Exception {
		// JwtInterceptor.preHandle 이 항상 통과.
		given(jwtInterceptor.preHandle(any(), any(), any())).willReturn(true);
		// ExtractMemberIdAspect 가 슬라이스에 없으므로 ThreadLocal 을 직접 세팅.
		MemberContext.setMemberId(TEST_MEMBER_ID);
	}

	@AfterEach
	void tearDown() {
		MemberContext.clear();
	}

	@DisplayName("SC-002: 인증 요청 핸들러는 외부 추론/발행 어댑터 호출 없이 202 + requestId(PENDING) 를 즉시 반환한다")
	@Test
	void respondsAcceptedWithoutInvokingConsumerOrPublisher() throws Exception {
		// given
		given(verificationService.verifyTodayChallenge(any(VerificationDto.class)))
			.willReturn(VerificationAcceptedDto.pending(DUMMY_REQUEST_ID));
		String body = "{\"verificationImgUrl\":\"https://img.example/target.jpg\"}";

		// when
		MvcResult result = mockMvc.perform(post("/api/v1/verify/planets/{planet-id}", TEST_PLANET_ID)
				.header("Authorization", "Bearer test-jwt")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			// then — 202 Accepted + 본문 검증
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.data.requestId").value(DUMMY_REQUEST_ID))
			.andExpect(jsonPath("$.data.status").value("PENDING"))
			.andReturn();

		// 헌법 III·SC-002 보강: VerificationService 가 단 1회만 호출.
		verify(verificationService).verifyTodayChallenge(any(VerificationDto.class));

		// SC-002 핵심: 응답 경로에서 외부 추론/발행 어댑터 호출 0.
		// VerificationMessagePublisher 인터페이스 mock 이 구체 구현체(VerificationRedisStreamPublisher)
		// 도 함께 차단한다 — 같은 인터페이스 타입 1개로 충분.
		verifyNoInteractions(flaskApiClient);
		verifyNoInteractions(verificationMessagePublisher);
		verifyNoInteractions(asyncVerificationProcessor);

		// 응답 본문 sanity — 잘못된 jsonPath 검증을 못 잡는 경우를 막기 위한 보강.
		assertThat(result.getResponse().getStatus()).isEqualTo(202);
		assertThat(result.getResponse().getContentAsString()).contains(DUMMY_REQUEST_ID);
	}

	/**
	 * 슬라이스 테스트에서 {@link com.planetrush.planetrush.verification.controller.VerificationController}
	 * 가 의존하는 빈을 컨테이너에 등록하기 위한 보조 설정 — 실제 빈은 모두 {@link MockBean} 으로 대체된다.
	 *
	 * <p>본 클래스는 의도적으로 빈 셸이다. {@code @Import} 자체가 {@code @WebMvcTest} 컨텍스트에 필요한
	 * 외부 의존성을 명시적으로 가시화하는 역할만 한다.
	 */
	static class WebSliceConfig {
	}
}

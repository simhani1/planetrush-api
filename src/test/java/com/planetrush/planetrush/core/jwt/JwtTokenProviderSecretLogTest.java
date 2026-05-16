package com.planetrush.planetrush.core.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Spec 001 US1 인수 기준 검증.
 *
 * <p>SC-005: {@code JwtTokenProvider}의 정상 흐름에서 시크릿이 평문 로그로
 * 출력되지 않는다. (과거 사고 패턴
 * {@code log.info("secret key: {}", SECRET_KEY)} 재발 방지)
 *
 * <p>Logback {@link ListAppender}로 {@code JwtTokenProvider} 로거의 모든
 * 이벤트를 캡처한 뒤, 어떤 메시지에도 SECRET_KEY 평문이 등장하지 않음을
 * 보증한다.
 */
class JwtTokenProviderSecretLogTest {

	private static final String SECRET_KEY = "secret-key-must-be-at-least-256-bits-long-for-hs256-test";

	private JwtTokenProvider provider;
	private ListAppender<ILoggingEvent> appender;
	private Logger jwtLogger;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
		ValueOperations<String, String> valueOps = mock(ValueOperations.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOps);
		doNothing().when(valueOps)
				.set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

		provider = new JwtTokenProvider(redisTemplate);
		ReflectionTestUtils.setField(provider, "SECRET_KEY", SECRET_KEY);
		ReflectionTestUtils.setField(provider, "ACCESS_TOKEN_EXPRIATION_TIME", 60_000);
		ReflectionTestUtils.setField(provider, "REFRESH_TOKEN_EXPIRATION_TIME", 600_000);

		jwtLogger = (Logger) LoggerFactory.getLogger(JwtTokenProvider.class);
		appender = new ListAppender<>();
		appender.start();
		jwtLogger.addAppender(appender);
		jwtLogger.setLevel(Level.TRACE);
	}

	@AfterEach
	void tearDown() {
		jwtLogger.detachAppender(appender);
		appender.stop();
	}

	@Test
	@DisplayName("createToken 정상 흐름에서 SECRET_KEY 평문이 어떤 로그에도 등장하지 않는다")
	void createTokenMustNotLogSecretInPlaintext() {
		provider.createToken(1L);

		List<ILoggingEvent> events = appender.list;
		for (ILoggingEvent event : events) {
			String rendered = event.getFormattedMessage();
			assertThat(rendered)
					.as("이벤트 \"%s\"에 SECRET_KEY 평문이 포함되어선 안 된다", rendered)
					.doesNotContain(SECRET_KEY);
		}
	}

	@Test
	@DisplayName("validateToken 정상 흐름(잘못된 토큰 포함)에서도 SECRET_KEY 평문이 등장하지 않는다")
	void validateTokenMustNotLogSecretInPlaintext() {
		provider.validateToken("Bearer invalid.token.here");
		provider.validateToken("not-a-bearer-token");

		List<ILoggingEvent> events = appender.list;
		for (ILoggingEvent event : events) {
			String rendered = event.getFormattedMessage();
			assertThat(rendered)
					.as("이벤트 \"%s\"에 SECRET_KEY 평문이 포함되어선 안 된다", rendered)
					.doesNotContain(SECRET_KEY);
		}
	}
}

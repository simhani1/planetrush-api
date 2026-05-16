package com.planetrush.planetrush.core.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;

/**
 * Spec 001 US1 인수 기준 검증.
 *
 * <p>SC-001: 시크릿 키워드 5종(secret/token/password/jwt/credential) 모두에
 * 대한 마스킹이 100% 통과해야 한다.
 */
class MaskingPatternConverterTest {

	private final MaskingPatternConverter converter = new MaskingPatternConverter();

	@ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
	@MethodSource("maskingCases")
	@DisplayName("시크릿 키워드 뒤의 값이 *** 로 마스킹된다 (5 키워드 × 다중 형식)")
	void shouldMaskSecretKeywords(String formattedMessage, String expected) {
		ILoggingEvent event = newEvent(formattedMessage);
		assertThat(converter.convert(event)).isEqualTo(expected);
	}

	static Stream<Arguments> maskingCases() {
		return Stream.of(
				// secret
				Arguments.of("secret key: abc123", "secret key: ***"),
				Arguments.of("secret=abc123", "secret=***"),
				Arguments.of("secret : my-secret-value", "secret : ***"),
				// token
				Arguments.of("token=xyz789", "token=***"),
				Arguments.of("user token: eyJabc.def-ghi", "user token: ***"),
				// password
				Arguments.of("password = mypass", "password = ***"),
				Arguments.of("password:p@ssw0rd!", "password:***"),
				// jwt
				Arguments.of("jwt: eyJhbGc.payload.signature", "jwt: ***"),
				// credential
				Arguments.of("credential=AKIAIOSFODNN", "credential=***"),
				// 대소문자 무시
				Arguments.of("SECRET KEY: abc123", "SECRET KEY: ***"),
				Arguments.of("Token=xyz", "Token=***"),
				Arguments.of("Password : Secret123", "Password : ***")
		);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"user joined: bob",
			"Planet created with id 42",
			"request received from 10.0.0.1",
			"GET /api/v1/planets 200 OK",
			"loading config from application-prod.yml"
	})
	@DisplayName("마스킹 대상이 아닌 일반 로그는 변형 없이 그대로 출력된다")
	void shouldNotModifyUnrelatedLogs(String formattedMessage) {
		ILoggingEvent event = newEvent(formattedMessage);
		assertThat(converter.convert(event)).isEqualTo(formattedMessage);
	}

	@Test
	@DisplayName("멀티라인 메시지(스택트레이스 등)에서도 시크릿 값이 마스킹된다")
	void shouldMaskInMultilineMessage() {
		String input = "first line\nsecret: leak123\nlast line";
		String expected = "first line\nsecret: ***\nlast line";
		ILoggingEvent event = newEvent(input);
		assertThat(converter.convert(event)).isEqualTo(expected);
	}

	@Test
	@DisplayName("한 라인에 여러 시크릿 키워드가 있으면 모두 마스킹된다")
	void shouldMaskMultipleKeywordsInSameLine() {
		String input = "token=abc; secret=xyz; password=p1";
		String expected = "token=***; secret=***; password=***";
		ILoggingEvent event = newEvent(input);
		assertThat(converter.convert(event)).isEqualTo(expected);
	}

	@Test
	@DisplayName("키워드 자체와 구분자(=/:)는 보존되고 값만 마스킹된다")
	void shouldPreserveKeywordAndDelimiter() {
		ILoggingEvent event = newEvent("secret key=abc");
		String result = converter.convert(event);
		assertThat(result).contains("secret");
		assertThat(result).contains("=");
		assertThat(result).contains("***");
		assertThat(result).doesNotContain("abc");
	}

	@Test
	@DisplayName("null/빈 메시지는 그대로 통과한다")
	void shouldPassThroughNullOrEmpty() {
		LoggingEvent emptyEvent = new LoggingEvent();
		emptyEvent.setMessage("");
		assertThat(converter.convert(emptyEvent)).isEqualTo("");

		LoggingEvent nullEvent = new LoggingEvent();
		assertThat(converter.convert(nullEvent)).isNull();
	}

	private static ILoggingEvent newEvent(String formattedMessage) {
		LoggingEvent event = new LoggingEvent();
		event.setMessage(formattedMessage);
		return event;
	}
}

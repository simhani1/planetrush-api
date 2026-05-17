package com.planetrush.planetrush.core.logging;

import java.util.regex.Pattern;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * 시크릿 키워드(secret/token/password/jwt/credential) 뒤에 등장하는 값을
 * {@code ***}로 자동 치환하는 Logback {@link ClassicConverter}.
 *
 * <p>logback-spring.xml에서 {@code <conversionRule conversionWord="msk" ... />}로
 * 등록된 뒤, CONSOLE 패턴 안에서 {@code %msk}로 호출된다.
 *
 * <p>매칭 규칙(대소문자 무시):
 * <ol>
 *   <li>키워드 5종 중 하나로 시작 (word boundary 보장)</li>
 *   <li>구분자({@code =} 또는 {@code :})까지 최대 20자 사이 텍스트 허용
 *       (예: {@code secret key:}, {@code user token:}, {@code "token":})</li>
 *   <li>구분자 뒤 공백을 건너뛴 후 옵셔널 따옴표({@code "} 또는 {@code '}) 포함
 *       값을 캡처. quoted 형식({@code token="abc"}, {@code "jwt":"eyJ..."})과
 *       unquoted 형식({@code token=abc}) 모두 지원</li>
 *   <li>키워드·구분자·바깥 따옴표는 보존, 값만 {@code ***}로 치환</li>
 * </ol>
 *
 * <p>한 줄에 여러 키워드가 있으면 모두 치환되며 멀티라인 입력에서도
 * 각 라인의 매치가 독립적으로 처리된다.
 *
 * <p>Spec 001 — Constitution 원칙 V (NON-NEGOTIABLE).
 *
 * <p>알려진 한계 (follow-up issue로 추적):
 * Exception throwable의 스택트레이스/메시지는 Logback 기본 throwable 컨버터
 * ({@code %wEx})로 처리되어 본 컨버터를 거치지 않는다. catch 블록에서 시크릿이
 * 포함된 메시지의 throwable을 그대로 로깅하면 평문 노출 가능. throwable 전용
 * 마스킹 컨버터 도입은 별도 PR에서 처리.
 */
public class MaskingPatternConverter extends ClassicConverter {

	private static final Pattern MASK_PATTERN = Pattern.compile(
			"(?i)(\\b(?:secret|token|password|jwt|credential)[^=:\\n]{0,20}[=:]\\s*)([\"']?)([^\"'\\s,;}\\\\]+)\\2"
	);

	private static final String REPLACEMENT = "$1$2***$2";

	@Override
	public String convert(ILoggingEvent event) {
		String message = event.getFormattedMessage();
		if (message == null || message.isEmpty()) {
			return message;
		}
		return MASK_PATTERN.matcher(message).replaceAll(REPLACEMENT);
	}
}

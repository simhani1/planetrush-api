package com.planetrush.planetrush.core.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * 시크릿 키워드(secret/token/password/jwt/credential) 뒤에 등장하는 값을
 * {@code ***}로 자동 치환하는 Logback {@link ClassicConverter}.
 *
 * <p>logback-spring.xml에서 {@code <conversionRule conversionWord="msk" ... />}로
 * 등록된 뒤, CONSOLE 패턴 안에서 {@code %msk}로 호출된다. 패턴 매칭은
 * 대소문자 무시(case-insensitive), 키워드 자체는 보존하고 값 부분만 마스킹한다.
 *
 * <p>Spec 001 — Constitution 원칙 V.
 */
public class MaskingPatternConverter extends ClassicConverter {

    /**
     * Phase 2 T005 stub. 실제 마스킹 로직은 T009(US1)에서 채워진다.
     * 현재는 입력 메시지를 그대로 반환한다.
     */
    @Override
    public String convert(ILoggingEvent event) {
        return event.getFormattedMessage();
    }
}

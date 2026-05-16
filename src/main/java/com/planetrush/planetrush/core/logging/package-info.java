/**
 * 로깅 인프라 (Spec 001 — Constitution 원칙 V).
 *
 * <p>{@link com.planetrush.planetrush.core.logging.MaskingPatternConverter}가
 * Logback {@code <conversionRule conversionWord="msk"/>}로 등록되어 모든
 * 로그 메시지에서 시크릿 키워드(secret/token/password/jwt/credential)의
 * 값 부분을 {@code ***}로 자동 치환한다.
 *
 * <p>이중 방어 정책: (1) 본 컨버터 + (2) {@code verifySecretLogScan} Gradle
 * 게이트 + (3) 코드 리뷰. 평문 로그 출력은 운영 사고로 간주한다.
 */
package com.planetrush.planetrush.core.logging;

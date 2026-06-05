package com.planetrush.planetrush.core.exception.handler;

import java.util.Enumeration;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.planetrush.planetrush.core.mattermost.NotificationManager;
import com.planetrush.planetrush.core.template.response.BaseResponse;
import com.planetrush.planetrush.core.template.response.ResponseCode;
import com.planetrush.planetrush.verification.exception.AlreadyVerifiedException;
import com.planetrush.planetrush.verification.exception.VerificationRequestNotFoundException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class VerificationExceptionHandler {

	private final NotificationManager nm;

	@ExceptionHandler(AlreadyVerifiedException.class)
	public ResponseEntity<BaseResponse<Object>> handlePlanetNotFoundException(AlreadyVerifiedException e,
		HttpServletRequest req) {
		log.info(e.getMessage());
		nm.sendNotification(e, req.getRequestURI(), getParams(req));
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(BaseResponse.ofFail(ResponseCode.ALREADY_VERIFIED));
	}

	/**
	 * Spec 005 — T016. 존재하지 않는 {@code request-id} (또는 본인 소유 아님) 으로
	 * 상태 조회 시 404 응답. 본인 가드는 서비스 쿼리에서 {@code memberId} 일치 row 만
	 * 매칭하므로, 다른 사용자의 request-id 조회도 본 핸들러로 흘러와 404 로 통일된다 — 존재 정보 유출 방지.
	 */
	@ExceptionHandler(VerificationRequestNotFoundException.class)
	public ResponseEntity<BaseResponse<Object>> handleVerificationRequestNotFoundException(
		VerificationRequestNotFoundException e, HttpServletRequest req) {
		log.info(e.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(BaseResponse.ofFail(ResponseCode.VERIFICATION_REQUEST_NOT_FOUND));
	}

	private String getParams(HttpServletRequest req) {
		StringBuilder params = new StringBuilder();
		Enumeration<String> keys = req.getParameterNames();
		while (keys.hasMoreElements()) {
			String key = keys.nextElement();
			params.append("- ").append(key).append(" : ").append(req.getParameter(key)).append("/n");
		}
		return params.toString();
	}
}

package com.planetrush.planetrush.verification.exception;

/**
 * {@code request-id} 로 조회한 인증 요청이 존재하지 않을 때 발생.
 *
 * <p>Spec 005 — T016 / contracts/rest-api.md §2. {@code GET /api/v1/verify/{request-id}}
 * 응답이 404 로 매핑된다. 본인 가드(memberId 일치) 와 결합되어 — 다른 사용자의 request-id 조회 시에도
 * 404 로 통일한다(존재 정보 유출 방지 — analyze I1 결정).
 */
public class VerificationRequestNotFoundException extends RuntimeException {

	public VerificationRequestNotFoundException() {
	}

	public VerificationRequestNotFoundException(String message) {
		super(message);
	}

	public VerificationRequestNotFoundException(String message, Throwable cause) {
		super(message, cause);
	}

	public VerificationRequestNotFoundException(Throwable cause) {
		super(cause);
	}
}

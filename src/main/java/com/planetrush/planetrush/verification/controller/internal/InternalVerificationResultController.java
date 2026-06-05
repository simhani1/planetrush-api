package com.planetrush.planetrush.verification.controller.internal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.planetrush.planetrush.core.template.response.BaseResponse;
import com.planetrush.planetrush.core.template.response.ResponseCode;
import com.planetrush.planetrush.verification.controller.internal.req.VerificationCallbackReq;
import com.planetrush.planetrush.verification.service.VerificationResultService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 컨슈머 callback 컨트롤러 (내부 표면).
 *
 * <p>Spec 005 — T021 / contracts/rest-api.md §3.
 * {@code POST /api/v1/internal/verification-results}. JwtInterceptor 는 본 경로를 통과시키도록
 * 설정되어 있다(R-005 / Phase 2 T008). 외부 망 차단은 ALB/Nginx 인프라 책임 — HMAC 검증은
 * 후속 스펙.
 *
 * <h3>헌법 II 정합 (어댑터 격리)</h3>
 * <p>본 컨트롤러는 페이로드 Bean Validation + 도메인 커맨드 매핑만 담당한다. 멱등 가드,
 * status 전이, record 저장 등 모든 도메인 로직은 {@link VerificationResultService} 에 위임.
 *
 * <h3>FR-011 흡수</h3>
 * <p>존재하지 않는 {@code requestId} 도 200 응답한다(컨슈머 무한 재시도 흡수). 이는 서비스의
 * optimistic update 영향 행 수 0 분기에서 자연 처리된다(예외 throw 없음).
 *
 * <p>페이로드 분기 규칙 위반(정상/오류 패턴 둘 다 어긋남)은 서비스가
 * {@link IllegalArgumentException} 으로 거절 → 본 컨트롤러의 {@link #handlePayloadBranchViolation}
 * 에서 400 응답으로 변환한다 (HTTP 표면 책임).
 */
@Slf4j
@RequestMapping("/api/v1/internal")
@RequiredArgsConstructor
@RestController
public class InternalVerificationResultController {

	private final VerificationResultService verificationResultService;

	@PostMapping("/verification-results")
	public ResponseEntity<BaseResponse<Object>> handleCallback(
		@Valid @RequestBody VerificationCallbackReq req) {
		verificationResultService.handleCallback(req.toCommand());
		return ResponseEntity.ok(BaseResponse.ofSuccess());
	}

	/**
	 * 페이로드 분기 규칙 위반(정상/오류 패턴 둘 다 어긋남) → 400.
	 *
	 * <p>본 컨트롤러 로컬 핸들러로 한정 — 전역 {@code @ExceptionHandler} 가
	 * {@link IllegalArgumentException} 을 잡으면 다른 도메인의 같은 예외와 충돌할 수 있어
	 * 본 컨트롤러 안에서만 처리한다.
	 */
	@org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<BaseResponse<Object>> handlePayloadBranchViolation(IllegalArgumentException e) {
		// 헌법 V — payload 본문 출력 금지. 서비스가 던진 메시지엔 requestId 만 포함되도록 제어됨.
		log.info(e.getMessage());
		return ResponseEntity.badRequest()
			.body(BaseResponse.ofFail(ResponseCode.INVALID_VERIFICATION_CALLBACK_PAYLOAD));
	}
}

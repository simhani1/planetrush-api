package com.planetrush.planetrush.verification.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.planetrush.planetrush.core.annotation.RequireJwtToken;
import com.planetrush.planetrush.core.aop.member.MemberContext;
import com.planetrush.planetrush.core.template.response.BaseResponse;
import com.planetrush.planetrush.verification.controller.res.VerificationStatusRes;
import com.planetrush.planetrush.verification.service.VerificationStatusService;
import com.planetrush.planetrush.verification.service.dto.VerificationStatusDto;

import lombok.RequiredArgsConstructor;

/**
 * 인증 요청 상태 조회 컨트롤러 (클라이언트 폴링 표면).
 *
 * <p>Spec 005 — T017 / contracts/rest-api.md §2.
 * {@code GET /api/v1/verify/{request-id}} 의 HTTP 어댑터. 본인 가드는 서비스 쿼리(memberId 일치 row 만)
 * 가 담당한다 — 컨트롤러는 {@code MemberContext.getMemberId()} 를 전달만 한다.
 */
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@RestController
public class VerificationStatusController {

	private final VerificationStatusService verificationStatusService;

	/**
	 * 인증 요청 상태 조회 — 본인의 요청만 조회 가능.
	 * 미존재 또는 다른 사용자 소유 시 404 ({@code VerificationRequestNotFoundException}).
	 */
	@RequireJwtToken
	@GetMapping("/verify/{request-id}")
	public ResponseEntity<BaseResponse<VerificationStatusRes>> getStatus(
		@PathVariable("request-id") String requestId) {
		Long memberId = MemberContext.getMemberId();
		VerificationStatusDto dto = verificationStatusService.findById(requestId, memberId);
		return ResponseEntity.ok(BaseResponse.ofSuccess(VerificationStatusRes.from(dto)));
	}
}

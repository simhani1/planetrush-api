package com.planetrush.planetrush.verification.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.planetrush.planetrush.core.annotation.RequireJwtToken;
import com.planetrush.planetrush.core.aop.member.MemberContext;
import com.planetrush.planetrush.core.template.response.BaseResponse;
import com.planetrush.planetrush.verification.controller.req.VerificationReq;
import com.planetrush.planetrush.verification.service.VerificationService;
import com.planetrush.planetrush.verification.service.dto.VerificationAcceptedDto;
import com.planetrush.planetrush.verification.service.dto.VerificationDto;

import lombok.RequiredArgsConstructor;

@RequestMapping("/api/v1")
@RequiredArgsConstructor
@RestController
public class VerificationController {

	private final VerificationService verificationService;

	/**
	 * 사진 유사도 검사를 통해 미션을 인증 요청합니다 (비동기 접수).
	 *
	 * <p>Spec 005 — T024 / contracts/rest-api.md §1. 처리 결과를 기다리지 않고 즉시
	 * 202 Accepted + {@code requestId} 를 반환한다. 클라이언트는 {@code GET /api/v1/verify/{request-id}}
	 * 로 폴링해 결과를 수신한다.
	 *
	 * @param planetId 행성의 고유 id
	 * @param req      인증 사진 주소
	 * @return 202 Accepted + 접수 DTO (requestId, PENDING)
	 */
	@RequireJwtToken
	@PostMapping("/verify/planets/{planet-id}")
	public ResponseEntity<BaseResponse<VerificationAcceptedDto>> verifyChallenge(
		@PathVariable("planet-id") Long planetId,
		@RequestBody VerificationReq req) {
		Long memberId = MemberContext.getMemberId();
		VerificationAcceptedDto accepted = verificationService.verifyTodayChallenge(VerificationDto.builder()
			.memberId(memberId)
			.planetId(planetId)
			.verificationImgUrl(req.getVerificationImgUrl())
			.build());
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(BaseResponse.ofSuccess(accepted));
	}

}

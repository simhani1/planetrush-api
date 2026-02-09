package com.planetrush.planetrush.member.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.planetrush.planetrush.core.annotation.RequireJwtToken;
import com.planetrush.planetrush.core.aop.member.MemberContext;
import com.planetrush.planetrush.core.template.response.BaseResponse;
import com.planetrush.planetrush.member.controller.request.KakaoLoginReq;
import com.planetrush.planetrush.member.controller.request.KakaoLogoutReq;
import com.planetrush.planetrush.member.controller.request.ReissueReq;
import com.planetrush.planetrush.member.service.OAuthService;
import com.planetrush.planetrush.member.service.dto.LoginDto;
import com.planetrush.planetrush.member.service.dto.ReissueDto;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class OAuthController {

	private final OAuthService oAuthService;

	/**
	 * 카카오 로그인을 진행합니다.
	 * @param req 카카오에서 발급 받은 accessToken
	 * @return 새로 발급한 accessToken, refreshToken을 포함한 ResponseEntity
	 */
	@PostMapping("/login/kakao")
	public ResponseEntity<BaseResponse<LoginDto>> kakaoLogin(@RequestBody KakaoLoginReq req) {
		LoginDto res = oAuthService.kakaoLogin(req.getAccessToken());
		return ResponseEntity.ok(BaseResponse.ofSuccess(res));
	}

	/**
	 * 카카오 로그아웃을 진행합니다.
	 * @param req 로그인 시 발급해준 refreshToken
	 * @return ResponseEntity
	 */
	@RequireJwtToken
	@PostMapping("/logout/kakao")
	public ResponseEntity<BaseResponse<?>> kakaoLogout(@RequestBody KakaoLogoutReq req) {
		Long memberId = MemberContext.getMemberId();
		oAuthService.kakaoLogout(memberId, req.getRefreshToken());
		return ResponseEntity.ok(BaseResponse.ofSuccess());
	}

	/**
	 * 회원을 탈퇴시킵니다.
	 * @return ResponseEntity
	 */
	@RequireJwtToken
	@PatchMapping("/exit")
	public ResponseEntity<BaseResponse<?>> withdrawnMember() {
		Long memberId = MemberContext.getMemberId();
		oAuthService.withdrawnMember(memberId);
		return ResponseEntity.ok(BaseResponse.ofSuccess());
	}

	/**
	 * 사용자의 리프레시 토큰을 받아 새로운 액세스 토큰을 발급합니다.
	 * @param req 리프레시 토큰을 담고 있는 요청 객체 (ReissueReq)
	 * @return 새로운 액세스 토큰이 포함된 ReissueDto 객체를 감싸는 ResponseEntity 객체
	 */
	@PostMapping("/reissue")
	public ResponseEntity<BaseResponse<ReissueDto>> reissueToken(@RequestBody ReissueReq req) {
		ReissueDto newToken = oAuthService.reissueToken(req.getRefreshToken());
		return ResponseEntity.ok(BaseResponse.ofSuccess(newToken));
	}
}

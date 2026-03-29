package com.planetrush.planetrush.member.service;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.planetrush.planetrush.core.jwt.JwtTokenProvider;
import com.planetrush.planetrush.core.jwt.dto.JwtToken;
import com.planetrush.planetrush.infra.oauth.dto.KakaoUserInfo;
import com.planetrush.planetrush.infra.oauth.util.KakaoUtil;
import com.planetrush.planetrush.member.domain.Member;
import com.planetrush.planetrush.member.domain.Nickname;
import com.planetrush.planetrush.member.domain.ProgressAvg;
import com.planetrush.planetrush.member.domain.Provider;
import com.planetrush.planetrush.member.domain.Status;
import com.planetrush.planetrush.member.exception.MemberNotFoundException;
import com.planetrush.planetrush.member.repository.MemberRepository;
import com.planetrush.planetrush.member.repository.ProgressAvgRepository;
import com.planetrush.planetrush.member.service.dto.LoginDto;
import com.planetrush.planetrush.member.service.dto.ReissueDto;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Transactional
@Service
public class OAuthServiceImpl implements OAuthService {

	@Value("${jwt.refresh-token.expiretime}")
	private int REFRESH_TOKEN_EXPIRATION_TIME;
	private final KakaoUtil kakaoUtil;
	private final JwtTokenProvider jwtTokenProvider;
	private final MemberRepository memberRepository;
	private final ProgressAvgRepository progressAvgRepository;

	@Override
	public LoginDto login(String email, String nickname) {
		Member member = Member.builder()
			.email(email)
			.nickname(nickname)
			.ci(UUID.randomUUID().toString())
			.provider(Provider.NORMAL)
			.status(Status.ACTIVE)
			.build();
		memberRepository.save(member);

		ProgressAvg progress = ProgressAvg.builder()
			.member(member)
			.totalAvg(-1.0)
			.beautyAvg(-1.0)
			.exerciseAvg(-1.0)
			.lifeAvg(-1.0)
			.studyAvg(-1.0)
			.etcAvg(-1.0)
			.build();
		progressAvgRepository.save(progress);

		JwtToken jwtToken = jwtTokenProvider.createToken(member.getId());
		return LoginDto.builder()
			.nickname(member.getNickname())
			.accessToken(jwtToken.getAccessToken())
			.refreshToken(jwtToken.getRefreshToken())
			.build();
	}

	/**
	 * {@inheritDoc}
	 * <br/>
	 * 첫 로그인 시 회원가입을 진행합니다.
	 */
	@Override
	public LoginDto kakaoLogin(String accessToken) {
		KakaoUserInfo kakaoUserInfo = kakaoUtil.getUserInfo(accessToken);
		String email = kakaoUserInfo.getKakaoAccount().getEmail();
		Member member = memberRepository.findByEmailAndProviderAndStatus(email, Provider.KAKAO, Status.ACTIVE);
		/* 회원가입 진행 */
		if (member == null) {
			member = memberRepository.save(Member.builder()
				.email(email)
				.ci(kakaoUserInfo.getId().toString())
				.nickname(Nickname.getRandomKoreanNickname())
				.provider(Provider.KAKAO)
				.status(Status.ACTIVE)
				.build());
			progressAvgRepository.save(ProgressAvg.builder()
				.member(member)
				.totalAvg(-1.0)
				.beautyAvg(-1.0)
				.exerciseAvg(-1.0)
				.lifeAvg(-1.0)
				.studyAvg(-1.0)
				.etcAvg(-1.0)
				.build());
		}
		/* 로그인 */
		JwtToken jwtToken = jwtTokenProvider.createToken(member.getId());
		return LoginDto.builder()
			.nickname(member.getNickname())
			.accessToken(jwtToken.getAccessToken())
			.refreshToken(jwtToken.getRefreshToken())
			.build();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws MemberNotFoundException 유저를 찾을 수 없을 때 발생
	 */
	@Override
	public void kakaoLogout(Long memberId, String refreshToken) {
		Member member = memberRepository.findById(memberId)
			.orElseThrow(() -> new MemberNotFoundException("Member not found with ID: " + memberId));
		jwtTokenProvider.deleteRefreshToken(refreshToken);
		kakaoUtil.kakaoLogout(member.getCi());
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws MemberNotFoundException 유저를 찾을 수 없을 때 발생
	 */
	@Override
	public void withdrawnMember(Long memberId) {
		Member member = memberRepository.findById(memberId)
			.orElseThrow(() -> new MemberNotFoundException("Member not found with ID: " + memberId));
		member.withdrawn();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public ReissueDto reissueToken(String refreshToken) {
		Long memberId = jwtTokenProvider.getMemberIdFromRefreshToken(refreshToken);
		jwtTokenProvider.deleteRefreshToken(refreshToken);

		JwtToken newToken = jwtTokenProvider.createToken(memberId);

		return ReissueDto.builder()
			.accessToken(newToken.getAccessToken())
			.refreshToken(newToken.getRefreshToken())
			.build();
	}

}

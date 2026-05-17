package com.planetrush.planetrush.core.jwt;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import com.planetrush.planetrush.core.jwt.dto.JwtToken;
import com.planetrush.planetrush.core.jwt.exception.UnAuthorizedException;
import com.planetrush.planetrush.core.jwt.exception.UnSupportedJwtException;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

	private final RedisTemplate<String, String> redisTemplate;

	@Value("${jwt.secret.key}")
	private String SECRET_KEY;

	@Value("${jwt.access-token.expiretime}")
	private int ACCESS_TOKEN_EXPRIATION_TIME;

	@Value("${jwt.refresh-token.expiretime}")
	private int REFRESH_TOKEN_EXPIRATION_TIME;

	/**
	 * 토큰을 생성합니다.
	 * @param memberId 유저의 고유 id
	 * @return 새로 발급한 accessToken, refreshToken
	 */
	public JwtToken createToken(Long memberId) {
		String accessToken = createAccessToken(memberId);
		String refreshToken = createRefreshToken();
		redisTemplate.opsForValue().set(
			refreshToken,
			memberId.toString(),
			REFRESH_TOKEN_EXPIRATION_TIME,
			TimeUnit.MILLISECONDS
		);
		return JwtToken.builder().accessToken(accessToken).refreshToken(refreshToken).build();
	}

	/**
	 * AccessToken을 생성합니다.
	 * @param memberId 유저의 고유 id
	 * @return 새로운 accessToken
	 */
	private String createAccessToken(Long memberId) {
		StringBuilder sb = new StringBuilder();
		sb.append("Bearer ");
		sb.append(
			Jwts.builder()
				.setSubject(memberId.toString())
				.claim("memberId", memberId)
				.claim("authorities", "MEMBER")
				.setExpiration(
					new Date(System.currentTimeMillis() + ACCESS_TOKEN_EXPRIATION_TIME))
				.signWith(
					Keys.hmacShaKeyFor(SECRET_KEY.getBytes()), SignatureAlgorithm.HS256)
				.compact());
		return sb.toString();
	}

	/**
	 * 현재 시간을 포함하여 refreshToken을 생성합니다.
	 * @return 새로운 refreshToken
	 */
	private String createRefreshToken() {
		return UUID.randomUUID().toString() + "-" + System.currentTimeMillis();
	}

	/**
	 * redis에서 키 값인 refreshToken을 사용해 토큰을 만료시킵니다.
	 * @param refreshToken 발급해 준 refreshToken
	 */
	public void deleteRefreshToken(String refreshToken) {
		redisTemplate.delete(refreshToken);
	}

	/**
	 * 토큰의 유효성을 확인합니다.
	 * @param accessToken 발급해 준 accessToken
	 * @return 토큰의 유효성
	 * @throws SecurityException 잘못된 JWT 서명일 때 발생
	 * @throws MalformedJwtException 잘못된 JWT 서명일 때 발생
	 * @throws UnSupportedJwtException 지원하지 않는 JWT 토근일 때 발생
	 * @throws IllegalArgumentException JWT 토큰이 없거나 잘못되었을 때 발생
	 * @throws ExpiredJwtException JWT 토큰이 만료되었을 때 발생
	 */
	public boolean validateToken(String accessToken) {
		if (!accessToken.startsWith("Bearer ")) {
			return false;
		}
		try {
			return parseClaims(accessToken).getExpiration().after(new Date());
		} catch (io.jsonwebtoken.security.SecurityException | MalformedJwtException e) {
			log.error("잘못된 JWT 서명입니다.", e);
		} catch (UnSupportedJwtException e) {
			log.error("지원하지 않는 JWT 토큰입니다.", e);
		} catch (IllegalArgumentException e) {
			log.error("JWT 토큰이 없거나 잘못되었습니다.", e);
		} catch (ExpiredJwtException e) {
			log.error("만료된 JWT 토큰입니다.");
		}
		return false;
	}

	/**
	 * AccessToken으로 유저의 고유 id를 가져옵니다.
	 * @param accessToken 발급해 준 accessToken
	 * @return 유저의 고유 id
	 * @throws UnAuthorizedException 허가되지 않은 토큰일 때 발생
	 */
	public Long getMemberId(String accessToken) {
		Claims claims = null;
		try {
			claims = parseClaims(accessToken);
		} catch (Exception e) {
			log.error(e.getMessage());
			throw new UnAuthorizedException();
		}
		return claims.get("memberId", Long.class);
	}

	/**
	 * 주어진 리프레시 토큰을 키로 사용하여 redis에서 저장된 회원 ID를 검색해 반환합니다.
	 * @param refreshToken 유효성을 검사할 리프레시 토큰
	 * @return 리프레시 토큰에 해당하는 회원 ID
	 * @throws UnAuthorizedException 리프레시 토큰이 유효하지 않거나 존재하지 않는 경우
	 */
	public Long getMemberIdFromRefreshToken(String refreshToken) {
		String memberId = redisTemplate.opsForValue().get(refreshToken);
		if (memberId == null) {
			throw new UnAuthorizedException("Invalid refresh token");
		}
		return Long.parseLong(memberId);
	}

	/**
	 * JWT의 정보(claims)를 파싱합니다.
	 * @param accessToken 발급해 준 accessToken
	 * @return JWT의 정보(claims)
	 */
	private Claims parseClaims(String accessToken) {
		return Jwts.parserBuilder()
			.setSigningKey(Keys.hmacShaKeyFor(SECRET_KEY.getBytes()))
			.build()
			.parseClaimsJws(accessToken.replace("Bearer ", ""))
			.getBody();
	}
}
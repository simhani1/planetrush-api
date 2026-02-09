package com.planetrush.planetrush.core.aop.member;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.planetrush.planetrush.core.annotation.RequireJwtToken;
import com.planetrush.planetrush.core.jwt.JwtTokenProvider;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@Aspect
@RequiredArgsConstructor
@Component
public class ExtractMemberIdAspect {

	private final String AUTHORIZATION_HEADER = "Authorization";
	private final JwtTokenProvider jwtTokenProvider;
	private final ThreadPoolTaskExecutorBuilder threadPoolTaskExecutorBuilder;

	/**
	 * <p>{@link RequireJwtToken} 어노테이션이 적용된 메서드가 호출되기 전에 실행됩니다.</p>
	 * <p>요청 헤더에서 JWT 토큰을 추출하고, 이를 이용하여 memberId를 조회한 후, {@link MemberContext}에 설정합니다.</p>
	 * <p>메서드 실행 후에는 {@link MemberContext}에 저장된 memberId를 제거합니다.</p>
	 * <p>인터셉터에서 JWT null 체크를 수행합니다.</p>
	 *
	 * @see com.planetrush.planetrush.core.interceptor.JwtInterceptor
	 *
	 * @param pjp 대상 메서드의 정보와 제어를 제공하는 객체
	 * @param requireJwtToken Aspect를 트리거하는 커스텀 어노테이션
	 * @return 대상 메서드의 실행 결과
	 * @throws Throwable 대상 메서드에서 발생한 예외
	 */
	@Around("@annotation(requireJwtToken)")
	public Object before(ProceedingJoinPoint pjp, RequireJwtToken requireJwtToken) throws Throwable {
		HttpServletRequest request = ((ServletRequestAttributes)RequestContextHolder.currentRequestAttributes()).getRequest();
		String accessToken = request.getHeader(AUTHORIZATION_HEADER);
		Long memberId = jwtTokenProvider.getMemberId(accessToken);
		MemberContext.setMemberId(memberId);
		try {
			return pjp.proceed();
		} catch (Throwable e) {
			throw e;
		} finally {
			MemberContext.clear();
		}
	}

}

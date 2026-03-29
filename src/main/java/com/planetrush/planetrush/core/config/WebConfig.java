package com.planetrush.planetrush.core.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.planetrush.planetrush.core.interceptor.JwtInterceptor;

import io.netty.handler.codec.http.HttpMethod;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@EnableWebMvc
@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final JwtInterceptor jwtInterceptor;

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry
			.addMapping("/**")
			.allowedOrigins("*")
			.allowedMethods(HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.PUT.name(),
				HttpMethod.DELETE.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name(),
				HttpMethod.PATCH.name())
			.maxAge(1800);
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		List<String> patterns = List.of(
			"/api/v1/auth/login/**",
			"/api/v1/auth/reissue",
			"/api/v1/planets/detail/**"
		);
		registry.addInterceptor(jwtInterceptor)
			.addPathPatterns("/api/**")
			.excludePathPatterns(patterns);
	}
}

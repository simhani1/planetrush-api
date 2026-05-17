package com.planetrush.planetrush.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Spec 001 US3 인수 기준 검증.
 *
 * <p>SC-004: {@code prod} 프로필 부팅 직후 로그에 SQL 출력이 0건이고 web 패키지
 * 로그가 INFO 이상으로 제한된다.
 *
 * <p>본 테스트는 {@code @SpringBootTest} 풀 부팅 대신 yaml 파일을 직접 로드하여
 * Constitution Additional Constraints "운영 안티패턴 즉시 차단" 키가 명시적으로
 * 비활성화되어 있는지 검증한다. 풀 부팅은 외부 환경변수(JWT/Kakao/AWS) 의존이
 * 커 본 PR 범위에 부적합 — 후속 스펙에서 풀 부팅 통합 테스트가 필요해지면
 * Testcontainers 베이스에 prod 프로필 변형을 추가한다.
 */
class ProdProfileBootTest {

	@Test
	@DisplayName("application-prod.yml이 운영 안티패턴 키(show-sql, format_sql, web=DEBUG)를 비활성화한다")
	void prodProfileOverridesOperationalAntiPatterns() {
		YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
		factory.setResources(new ClassPathResource("application-prod.yml"));
		factory.afterPropertiesSet();
		Properties props = factory.getObject();

		assertThat(props)
				.as("application-prod.yml은 클래스패스에 존재해야 한다")
				.isNotNull();

		assertThat(props.getProperty("spring.jpa.show-sql"))
				.as("SC-004: prod 부팅 시 SQL 출력 0건")
				.isEqualTo("false");

		assertThat(props.getProperty("spring.jpa.properties.hibernate.format_sql"))
				.as("SQL format 출력도 비활성화되어 시크릿 파라미터가 새지 않는다")
				.isEqualTo("false");

		assertThat(props.getProperty("logging.level.org.springframework.web"))
				.as("SC-004: web 패키지 로그 레벨이 INFO 이상")
				.isEqualTo("INFO");
	}

	@Test
	@DisplayName("application-prod.yml은 prod 프로필에서만 활성화된다")
	void prodProfileIsScopedToProdActivation() {
		YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
		factory.setResources(new ClassPathResource("application-prod.yml"));
		factory.afterPropertiesSet();
		Properties props = factory.getObject();

		assertThat(props.getProperty("spring.config.activate.on-profile"))
				.as("on-profile=prod 명시되어야 dev/test 환경 회귀 0")
				.isEqualTo("prod");
	}
}

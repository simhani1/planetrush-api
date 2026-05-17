package com.planetrush.planetrush;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트 공통 베이스.
 *
 * <p>Spec 001 — Constitution 원칙 I (Testcontainers, NON-NEGOTIABLE).
 * MySQL/Redis 컨테이너를 자동 부팅하고 Spring DataSource·Redis 설정을 동적으로
 * 주입한다. 로컬에 데몬이 없어도 {@code ./gradlew test}가 통과해야 한다.
 *
 * <p>컨테이너 재사용은 {@code ~/.testcontainers.properties}에
 * {@code testcontainers.reuse.enable=true}로 활성화한다(quickstart.md §1.2 참조).
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

	@Container
	static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.36"))
			.withDatabaseName("planetrush")
			.withUsername("test")
			.withPassword("test")
			.withReuse(true)
			.withLabel("project", "planetrush-api");

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379)
			.withReuse(true)
			.withLabel("project", "planetrush-api");

	@DynamicPropertySource
	static void registerContainerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@LocalServerPort
	private int port;

}

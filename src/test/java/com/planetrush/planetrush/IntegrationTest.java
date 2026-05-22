package com.planetrush.planetrush;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트 공통 베이스.
 *
 * <p>Spec 001 — Constitution 원칙 I (Testcontainers, NON-NEGOTIABLE).
 * MySQL/Redis 컨테이너를 자동 부팅하고 Spring DataSource·Redis 설정을 동적으로
 * 주입한다. 로컬에 데몬이 없어도 {@code ./gradlew test}가 통과해야 한다.
 *
 * <p><b>싱글톤 컨테이너 패턴</b> — {@code @Testcontainers}/{@code @Container}
 * 라이프사이클을 쓰지 않고 static 초기화 블록에서 컨테이너를 한 번만 시작한다.
 * 이유: {@code @Container}는 테스트 클래스 종료 시 컨테이너를 stop하는데,
 * Spring TestContext는 컨텍스트를 캐시하므로 — 다음 클래스가 캐시된 컨텍스트를
 * 재사용하면 그 HikariCP 풀이 이미 stop된 컨테이너를 가리켜
 * "No operations allowed after connection closed" 오류가 난다. static 블록으로
 * 시작한 컨테이너는 JVM 생존 내내 단일 인스턴스로 유지되어 이 문제가 없다.
 * (JVM 종료 시 Testcontainers Ryuk가 정리한다.)
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

	static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0.36"))
			.withDatabaseName("planetrush")
			.withUsername("test")
			.withPassword("test")
			.withReuse(true);

	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379)
			.withReuse(true);

	static {
		MYSQL.start();
		REDIS.start();
	}

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

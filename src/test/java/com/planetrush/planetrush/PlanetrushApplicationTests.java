package com.planetrush.planetrush;

import org.junit.jupiter.api.Test;

/**
 * 애플리케이션 컨텍스트 로딩 스모크 테스트.
 *
 * <p>{@link IntegrationTest} 베이스를 상속하여 Testcontainers MySQL/Redis로 부팅한다.
 * 직접 {@code @SpringBootTest}를 선언하면 로컬 데몬(localhost:3306)에 의존해
 * Constitution 원칙 I을 위반하고, 전체 테스트 실행 시 컨텍스트 로딩이 실패한다.
 */
class PlanetrushApplicationTests extends IntegrationTest {

	@Test
	void contextLoads() {
	}

}

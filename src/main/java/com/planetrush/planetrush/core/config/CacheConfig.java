package com.planetrush.planetrush.core.config;

import java.time.Duration;

import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * 통계 캐시 설정.
 *
 * <p>Spec 006 — T005 / R1·R5·R8. 기존 Caffeine 로컬 캐시를 다중 인스턴스 공유를 위해
 * Redis 백엔드({@link RedisCacheManager})로 이관한다. 버전 키 전환만으로 신선도를 확보하므로
 * 전수 evict 가 불필요하다.
 *
 * <ul>
 *   <li>cacheName {@code challenge-avg} — TTL 25시간(버전 1세대 수명 + 여유). 과거 버전 키는
 *       참조되지 않아 자연 만료된다(FR-005).</li>
 *   <li>value 직렬화 — {@link GenericJackson2JsonRedisSerializer}(JSON). 키는 기본
 *       {@code StringRedisSerializer} → 물리 키 {@code challenge-avg::{memberId}:{version}}.</li>
 *   <li>{@link RedisCacheWriter#lockingRedisCacheWriter(RedisConnectionFactory) lockingRedisCacheWriter}
 *       — 동일 키 동시 적재를 직렬화해 single-flight(R5/FR-006) 를 보장. 메서드의
 *       {@code sync = true} 와 결합해 캐시 스탬피드를 방지한다.</li>
 * </ul>
 *
 * <p>{@code @EnableCaching} 은 {@code RedisConfig} 에 이미 선언되어 있어 여기서 중복하지 않는다.
 */
@Configuration
public class CacheConfig {

	private static final String CHALLENGE_AVG_CACHE = "challenge-avg";
	private static final Duration CHALLENGE_AVG_TTL = Duration.ofHours(25);

	@Bean
	public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
		RedisCacheConfiguration challengeAvgConfig = RedisCacheConfiguration.defaultCacheConfig()
			.entryTtl(CHALLENGE_AVG_TTL)
			.serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
				new GenericJackson2JsonRedisSerializer()));

		return RedisCacheManager
			.builder(RedisCacheWriter.lockingRedisCacheWriter(connectionFactory))
			.withCacheConfiguration(CHALLENGE_AVG_CACHE, challengeAvgConfig)
			.build();
	}
}

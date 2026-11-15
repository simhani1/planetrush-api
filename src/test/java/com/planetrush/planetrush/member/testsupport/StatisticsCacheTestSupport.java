package com.planetrush.planetrush.member.testsupport;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.planetrush.planetrush.fixture.MemberFixture;
import com.planetrush.planetrush.member.domain.Member;
import com.planetrush.planetrush.member.repository.MemberRepository;
import com.planetrush.planetrush.scheduler.log.JobLog;
import com.planetrush.planetrush.scheduler.log.JobLogRepository;

/**
 * Spec 006 — 통계 캐시 버전 키 통합 테스트 지원 헬퍼.
 *
 * <p>JobLog(완료 배치) 시드 · Member 시드 · {@code challenge-avg} Redis 키/TTL 조회를
 * 한곳에 모은다. Spring 빈이 아니라 통합 테스트가 autowired 의존성으로 직접 생성해 쓰는
 * 순수 헬퍼다(컨텍스트 오염 없음).
 *
 * <p><b>모델링 주의</b>: {@code JobLog.end_time} 은 NOT NULL 이고 setter 가 없으며 production
 * 은 {@code finish()}(endTime=now) 후에만 저장한다. 특정 날짜의 "완료" 배치를 재현하려면
 * {@link ReflectionTestUtils#setField}로 endTime 을 직접 지정해야 한다. 통계 버전은
 * {@code MAX(endTime).toLocalDate()}(Asia/Seoul) 이므로, 서로 다른 날짜의 완료 JobLog 를
 * 시드해 버전 전환을 재현한다.
 */
public class StatisticsCacheTestSupport {

	public static final String CACHE_NAME = "challenge-avg";
	public static final String KEY_PATTERN = CACHE_NAME + "::*";
	private static final String PROGRESS_CALCULATION = "progressCalculation";

	private final JobLogRepository jobLogRepository;
	private final MemberRepository memberRepository;
	private final StringRedisTemplate stringRedisTemplate;

	public StatisticsCacheTestSupport(JobLogRepository jobLogRepository,
		MemberRepository memberRepository,
		StringRedisTemplate stringRedisTemplate) {
		this.jobLogRepository = jobLogRepository;
		this.memberRepository = memberRepository;
		this.stringRedisTemplate = stringRedisTemplate;
	}

	/**
	 * 지정한 {@code endTime} 으로 "완료된" progressCalculation JobLog 를 시드한다.
	 * production 의 {@code finish()} 가 endTime=now 를 박는 것과 달리, 테스트는 임의 날짜의
	 * 완료 배치를 재현하기 위해 endTime 을 직접 지정한다.
	 *
	 * @param endTime 완료 시각(이 날짜가 통계 버전이 된다)
	 * @return 저장된 JobLog
	 */
	public JobLog seedCompletedBatch(LocalDateTime endTime) {
		JobLog jobLog = new JobLog(PROGRESS_CALCULATION);
		ReflectionTestUtils.setField(jobLog, "endTime", endTime);
		ReflectionTestUtils.setField(jobLog, "elapsedTime", "1ms");
		return jobLogRepository.save(jobLog);
	}

	/**
	 * 테스트용 활성 Member 를 실제 DB 에 저장하고 그 memberId 를 반환한다.
	 * (cache service 의 {@code memberRepository.findById} 가 성립하도록 실제 행을 시드한다.)
	 */
	public Long seedMember() {
		Member member = memberRepository.save(MemberFixture.activeMember());
		return member.getId();
	}

	/** {@code challenge-avg::} 물리 키 표현(메서드ID 기준). */
	public String cacheKey(Long memberId, String version) {
		return CACHE_NAME + "::" + memberId + ":" + version;
	}

	/** 현재 Redis 에 존재하는 모든 {@code challenge-avg::*} 키. */
	public Set<String> challengeAvgKeys() {
		Set<String> keys = stringRedisTemplate.keys(KEY_PATTERN);
		return keys == null ? Collections.emptySet() : keys;
	}

	/** 특정 물리 키 존재 여부. */
	public boolean keyExists(String physicalKey) {
		return Boolean.TRUE.equals(stringRedisTemplate.hasKey(physicalKey));
	}

	/**
	 * 특정 물리 키의 잔여 TTL(초). 키 없음=-2, TTL 미설정=-1 (Redis 규약).
	 */
	public long ttlSeconds(String physicalKey) {
		Long ttl = stringRedisTemplate.getExpire(physicalKey, TimeUnit.SECONDS);
		return ttl == null ? -2L : ttl;
	}

	/**
	 * 테스트 간 격리: {@code challenge-avg::*} Redis 키 + JobLog/Member 행 정리.
	 * (challenge-avg 키 잔존이 다른 테스트를 오염시키지 않게 한다.)
	 */
	public void cleanUp() {
		Set<String> keys = challengeAvgKeys();
		if (!keys.isEmpty()) {
			stringRedisTemplate.delete(keys);
		}
		jobLogRepository.deleteAll();
		memberRepository.deleteAll();
	}
}

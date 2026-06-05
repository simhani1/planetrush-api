package com.planetrush.planetrush.member;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.planetrush.planetrush.IntegrationTest;
import com.planetrush.planetrush.infra.flask.util.FlaskApiClient;
import com.planetrush.planetrush.member.repository.MemberRepository;
import com.planetrush.planetrush.member.service.MemberService;
import com.planetrush.planetrush.member.service.StatisticsVersionProvider;
import com.planetrush.planetrush.member.service.dto.GetMyProgressAvgDto;
import com.planetrush.planetrush.member.testsupport.StatisticsCacheTestSupport;
import com.planetrush.planetrush.scheduler.log.JobLogRepository;

/**
 * Spec 006 — 통계 캐시 버전 키 인수 테스트 (SC-001~005 · FR-007/008 · 콜드스타트).
 *
 * <p>헌법 I(Testcontainers MySQL+Redis) · VII(인수기준=자동테스트). 외부 통계 서버(Flask)는
 * {@code @MockBean} 으로 대체하고 <b>호출 횟수</b>로 버전 전환 시 재계산 여부를 검증한다.
 *
 * <p><b>명세 도출</b>: 버전 = 직전 완료 progressCalculation 배치의 {@code endTime} 기준일
 * (Asia/Seoul, {@code yyyy-MM-dd}). 서로 다른 날짜의 완료 JobLog 를 시드해 버전 전환을 재현한다
 * (data-model.md 상태 전이 · contracts/cache-key.md F1).
 */
class StatisticsCacheVersionIntegrationTest extends IntegrationTest {

	@Autowired
	private MemberService memberService;

	@Autowired
	private StatisticsVersionProvider statisticsVersionProvider;

	@Autowired
	private JobLogRepository jobLogRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	@Autowired
	private CacheManager cacheManager;

	@MockBean
	private FlaskApiClient flaskApiClient;

	private StatisticsCacheTestSupport support;

	@BeforeEach
	void setUp() {
		support = new StatisticsCacheTestSupport(jobLogRepository, memberRepository, stringRedisTemplate);
		// 격리: Redis challenge-avg 키 + JobLog/Member 정리(두 백엔드 모두 대비해 CacheManager 도 clear).
		support.cleanUp();
		if (cacheManager.getCache(StatisticsCacheTestSupport.CACHE_NAME) != null) {
			cacheManager.getCache(StatisticsCacheTestSupport.CACHE_NAME).clear();
		}
		reset(flaskApiClient);
	}

	/**
	 * SC-001: 일별 배치 완료 후 첫 조회 시 갱신값이 100% 반영된다(옛 값 노출 0%).
	 *
	 * <p>한 코드 경로의 세 단계를 한 테스트로 박제한다.
	 * <ol>
	 *   <li>(US1 시나리오2) 완료 배치 endTime=D 시드 후 첫 조회 → Flask 1회 호출·v1 반환·캐싱.</li>
	 *   <li>같은 버전 재조회 → 캐시 HIT, Flask 추가 호출 0(v1 유지).</li>
	 *   <li>(US1 시나리오1) 완료 배치 endTime=D+1 추가 시드 → 버전 전환 → 재조회 시 Flask 추가 1회·v2 반환.</li>
	 * </ol>
	 * Flask mock 은 distinct DTO 두 개(myTotalAvg 10.0 → 20.0)로 stub 해 버전 전환 시 값이 실제로 바뀌는지 확인한다.
	 */
	@DisplayName("SC-001: 배치 버전 전환(D→D+1) 후 첫 조회는 재계산되고, 전환 전 재조회는 캐시 HIT 다.")
	@Test
	void should_recompute_on_first_read_after_version_transition_and_hit_within_same_version() {
		// GIVEN: 직전 완료 배치 기준일 D(2026-06-05)
		support.seedCompletedBatch(LocalDateTime.of(2026, 6, 5, 23, 50));
		Long memberId = support.seedMember();

		GetMyProgressAvgDto dtoVersionD = dtoWithMyTotalAvg(10.0);
		GetMyProgressAvgDto dtoVersionDPlus1 = dtoWithMyTotalAvg(20.0);
		// 1번째 실제 Flask 호출 → v1, 2번째 → v2 (HIT 구간은 Flask 미호출)
		when(flaskApiClient.getMyProgressAvg(memberId)).thenReturn(dtoVersionD, dtoVersionDPlus1);

		// WHEN/THEN (1) 버전 D 첫 조회 = MISS → Flask 1회, v1 반환·캐싱
		GetMyProgressAvgDto firstRead = memberService.getMyProgressAvgPer(memberId);
		assertThat(firstRead.getMyTotalAvg()).isEqualTo(10.0);
		verify(flaskApiClient, times(1)).getMyProgressAvg(memberId);

		// WHEN/THEN (2) 동일 버전 재조회 = HIT → Flask 추가 호출 없음, v1 유지
		GetMyProgressAvgDto secondRead = memberService.getMyProgressAvgPer(memberId);
		assertThat(secondRead.getMyTotalAvg()).isEqualTo(10.0);
		verify(flaskApiClient, times(1)).getMyProgressAvg(memberId);

		// WHEN/THEN (3) 새 완료 배치 endTime=D+1 → 버전 전환 → 새 키 MISS → 재계산·v2 반환
		support.seedCompletedBatch(LocalDateTime.of(2026, 6, 6, 23, 50));
		GetMyProgressAvgDto afterTransition = memberService.getMyProgressAvgPer(memberId);
		assertThat(afterTransition.getMyTotalAvg()).isEqualTo(20.0);
		verify(flaskApiClient, times(2)).getMyProgressAvg(memberId);
	}

	/**
	 * FR-008(전역 일관, analyze C1 근사): 동일 DB 상태에서 {@code getCurrentVersion()} 을 연속 호출하면
	 * 동일 문자열을 반환한다(결정성). 다중 인스턴스 동일 인식은 단일 JVM 통합 테스트로 직접 검증 불가하므로,
	 * "동일 입력(DB MAX(endTime)) → 동일 출력" 결정성으로 대체한다. 버전은 매 요청 DB 파생(메모 캐시 없음)이라
	 * 결정성이 곧 모든 인스턴스 동일 수렴의 근거다(contracts/cache-key.md C3).
	 */
	@DisplayName("FR-008: 동일 DB 상태에서 getCurrentVersion 은 결정적으로 같은 버전을 반환한다.")
	@Test
	void should_return_deterministic_version_for_same_db_state() {
		// GIVEN: 직전 완료 배치 기준일 2026-06-05
		support.seedCompletedBatch(LocalDateTime.of(2026, 6, 5, 23, 50));

		// WHEN: 동일 상태에서 연속 2회 해석
		String first = statisticsVersionProvider.getCurrentVersion();
		String second = statisticsVersionProvider.getCurrentVersion();

		// THEN: 동일 버전(= 직전 완료 배치 기준일)
		assertThat(first).isEqualTo(second);
		assertThat(first).isEqualTo("2026-06-05");
	}

	/**
	 * SC-002 (US2 중간상태 미캐싱): "배치 진행 중" = 해당 날짜의 완료 JobLog row 부재
	 * (production 은 finish() 후에만 저장 → endTime=null row 는 영속되지 않는다).
	 *
	 * <p>완료 D 만 있는 상태에서 D+1 배치가 "진행 중"(완료 row 미시드)인 동안 들어온 조회는
	 * 여전히 version=D 로 HIT 되어 Flask 를 추가 호출하지 않고, D+1 버전 키를 생성하지 않는다
	 * (중간상태가 새 버전으로 캐싱되지 않음). D+1 배치가 실제 완료된 뒤에야 재계산된다.
	 * 이 행위가 곧 "endTime IS NOT NULL(진행중 제외)" 불변식의 증명이다.
	 */
	@DisplayName("SC-002: 배치 진행 중(완료 row 부재) 조회는 직전 버전으로 HIT 되고 중간상태가 새 버전으로 캐싱되지 않는다.")
	@Test
	void should_not_cache_intermediate_state_while_batch_in_progress() {
		// GIVEN: 직전 완료 배치 기준일 D(2026-06-05)
		support.seedCompletedBatch(LocalDateTime.of(2026, 6, 5, 23, 50));
		Long memberId = support.seedMember();

		GetMyProgressAvgDto dtoVersionD = dtoWithMyTotalAvg(10.0);
		GetMyProgressAvgDto dtoVersionDPlus1 = dtoWithMyTotalAvg(20.0);
		when(flaskApiClient.getMyProgressAvg(memberId)).thenReturn(dtoVersionD, dtoVersionDPlus1);

		String keyD = support.cacheKey(memberId, "2026-06-05");
		String keyDPlus1 = support.cacheKey(memberId, "2026-06-06");

		// WHEN/THEN (1) 버전 D 첫 조회 → Flask 1회, :D 키 생성
		GetMyProgressAvgDto firstRead = memberService.getMyProgressAvgPer(memberId);
		assertThat(firstRead.getMyTotalAvg()).isEqualTo(10.0);
		verify(flaskApiClient, times(1)).getMyProgressAvg(memberId);
		assertThat(support.keyExists(keyD)).isTrue();

		// WHEN/THEN (2) D+1 "배치 진행 중"(완료 row 미시드) → version 여전히 D → HIT
		// → Flask 추가 호출 0, D+1 버전 키 미생성(중간상태 미캐싱)
		GetMyProgressAvgDto duringInProgress = memberService.getMyProgressAvgPer(memberId);
		assertThat(duringInProgress.getMyTotalAvg()).isEqualTo(10.0);
		verify(flaskApiClient, times(1)).getMyProgressAvg(memberId);
		assertThat(support.keyExists(keyDPlus1)).isFalse();

		// WHEN/THEN (3) D+1 배치 실제 완료 → 버전 전환 → 재계산·새 값
		support.seedCompletedBatch(LocalDateTime.of(2026, 6, 6, 23, 50));
		GetMyProgressAvgDto afterComplete = memberService.getMyProgressAvgPer(memberId);
		assertThat(afterComplete.getMyTotalAvg()).isEqualTo(20.0);
		verify(flaskApiClient, times(2)).getMyProgressAvg(memberId);
		assertThat(support.keyExists(keyDPlus1)).isTrue();
	}

	/**
	 * SC-003 (US3 evict 0회): 버전 전환은 키 변경만으로 신선도를 확보한다. 새 버전 키 생성 후에도
	 * 구 버전 키는 명시적으로 삭제되지 않고 Redis 에 그대로 남는다(전수 evict 0회 — TTL 로 자연 만료).
	 * 본 테스트는 사용자별 evict/cache.evict 를 일절 호출하지 않는다.
	 */
	@DisplayName("SC-003: 버전 전환 후 구 버전 키와 새 버전 키가 모두 잔존한다(사용자별 evict 0회).")
	@Test
	void should_keep_old_version_key_without_evict_on_version_transition() {
		// GIVEN: 완료 D 시드 + 멤버
		support.seedCompletedBatch(LocalDateTime.of(2026, 6, 5, 23, 50));
		Long memberId = support.seedMember();
		when(flaskApiClient.getMyProgressAvg(memberId)).thenReturn(dtoWithMyTotalAvg(10.0), dtoWithMyTotalAvg(20.0));

		String keyD = support.cacheKey(memberId, "2026-06-05");
		String keyDPlus1 = support.cacheKey(memberId, "2026-06-06");

		// WHEN (1) 버전 D 조회 → :D 키 생성
		memberService.getMyProgressAvgPer(memberId);
		assertThat(support.keyExists(keyD)).isTrue();

		// WHEN (2) 완료 D+1 시드 후 조회 → :D+1 키 생성(전수 evict 없이 키 변경만으로 신선도 확보)
		support.seedCompletedBatch(LocalDateTime.of(2026, 6, 6, 23, 50));
		memberService.getMyProgressAvgPer(memberId);

		// THEN: 구 키(:D)와 새 키(:D+1) 둘 다 Redis 에 잔존(구 키 미삭제)
		assertThat(support.keyExists(keyD)).isTrue();
		assertThat(support.keyExists(keyDPlus1)).isTrue();
		assertThat(support.challengeAvgKeys()).contains(keyD, keyDPlus1);
	}

	/**
	 * SC-005 (과거 버전 자연 만료): 캐시 항목에 만료 시간(TTL ≈ 25h)이 설정되어, 더 이상 참조되지 않는
	 * 과거 버전 키가 무한 누적되지 않고 TTL 경과 후 제거된다. 여기서는 TTL 이 25h 근사로 설정됐는지 단언한다.
	 */
	@DisplayName("SC-005: 캐시 키에 25h 근사 TTL 이 설정된다(과거 버전 자연 만료).")
	@Test
	void should_set_ttl_around_25h_on_cache_key() {
		// GIVEN
		support.seedCompletedBatch(LocalDateTime.of(2026, 6, 5, 23, 50));
		Long memberId = support.seedMember();
		when(flaskApiClient.getMyProgressAvg(memberId)).thenReturn(dtoWithMyTotalAvg(10.0));

		// WHEN: 조회로 키 생성
		memberService.getMyProgressAvgPer(memberId);
		String keyD = support.cacheKey(memberId, "2026-06-05");

		// THEN: TTL 이 25h(90000s) 근사 — 생성 직후 소폭 감소 여유 포함 23h~25h(> 82800s, <= 90000s)
		long ttl = support.ttlSeconds(keyD);
		assertThat(ttl).isGreaterThan(82800L).isLessThanOrEqualTo(90000L);
	}

	/**
	 * SC-004 (single-flight): 버전이 고정된 상태(완료 JobLog 1건 시드)에서 동일 member·동일 버전의
	 * 첫 조회가 동시에 N 건 발생해도 외부 Flask 계산은 1회로 수렴한다(locking RedisCacheWriter + sync=true).
	 */
	@DisplayName("SC-004: 동일 member·동일 버전 동시 N 요청에도 Flask 호출은 1회로 수렴한다.")
	@Test
	void should_converge_flask_call_to_once_on_concurrent_first_reads() throws Exception {
		// GIVEN: 버전 고정(완료 D 시드) + 멤버
		support.seedCompletedBatch(LocalDateTime.of(2026, 6, 5, 23, 50));
		Long memberId = support.seedMember();
		GetMyProgressAvgDto dto = dtoWithMyTotalAvg(10.0);

		int concurrency = 16;
		java.util.concurrent.ExecutorService executor =
			java.util.concurrent.Executors.newFixedThreadPool(concurrency);
		java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(concurrency);
		java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
		java.util.List<java.util.concurrent.Future<GetMyProgressAvgDto>> futures = new java.util.ArrayList<>();

		when(flaskApiClient.getMyProgressAvg(memberId)).thenAnswer(invocation -> {
			Thread.sleep(200);
			return dto;
		});

		// WHEN: N 스레드가 동시에 동일 키 첫 조회
		for (int i = 0; i < concurrency; i++) {
			futures.add(executor.submit(() -> {
				readyLatch.countDown();
				assertThat(startLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
				return memberService.getMyProgressAvgPer(memberId);
			}));
		}
		assertThat(readyLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		startLatch.countDown();
		executor.shutdown();
		for (java.util.concurrent.Future<GetMyProgressAvgDto> future : futures) {
			// Redis round-trip 역직렬화로 새 인스턴스 반환 → 값 동등성 비교
			assertThat(future.get(5, java.util.concurrent.TimeUnit.SECONDS))
				.usingRecursiveComparison().isEqualTo(dto);
		}
		assertThat(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

		// THEN: 동일 키 중복 계산 없이 Flask 1회 수렴
		verify(flaskApiClient, times(1)).getMyProgressAvg(memberId);
	}

	/**
	 * FR-007 (실패 비캐싱): Flask 호출이 실패하면 실패·불완전 결과가 해당 버전 키로 캐싱되지 않아야 하며,
	 * 이후 요청이 재계산을 시도할 수 있어야 한다.
	 *
	 * <p>주의: {@code @MockBean} 이라 production {@code @Retryable} 은 적용되지 않고 mock 이 즉시 throw 한다.
	 */
	@DisplayName("FR-007: Flask 예외 시 키 미생성, 다음 요청이 재계산을 다시 시도한다.")
	@Test
	void should_not_cache_failure_and_allow_retry_on_next_request() {
		// GIVEN: 버전 고정(완료 D 시드) + 멤버
		support.seedCompletedBatch(LocalDateTime.of(2026, 6, 5, 23, 50));
		Long memberId = support.seedMember();
		when(flaskApiClient.getMyProgressAvg(memberId))
			.thenThrow(new com.planetrush.planetrush.infra.flask.exception.FlaskConnectionFailedException("flask down"));

		String keyD = support.cacheKey(memberId, "2026-06-05");

		// WHEN/THEN (1) 첫 호출 → 예외 전파, 실패 결과 미캐싱(키 미생성)
		assertThatThrownBy(() -> memberService.getMyProgressAvgPer(memberId))
			.isInstanceOf(com.planetrush.planetrush.infra.flask.exception.FlaskConnectionFailedException.class);
		assertThat(support.keyExists(keyD)).isFalse();

		// WHEN/THEN (2) 다음 요청 → 캐시 없으므로 Flask 재시도(추가 호출 발생)
		assertThatThrownBy(() -> memberService.getMyProgressAvgPer(memberId))
			.isInstanceOf(com.planetrush.planetrush.infra.flask.exception.FlaskConnectionFailedException.class);
		verify(flaskApiClient, times(2)).getMyProgressAvg(memberId);
		assertThat(support.keyExists(keyD)).isFalse();
	}

	/**
	 * 콜드스타트 (Q3): 완료된 progressCalculation 배치가 0건이면(최초 배포 직후) 오늘 기준일(Asia/Seoul)을
	 * 잠정 버전으로 사용해 실시간 계산값을 제공·캐싱한다.
	 */
	@DisplayName("콜드스타트: 완료 배치 0건이면 version=오늘(Asia/Seoul)로 계산·캐싱한다.")
	@Test
	void should_use_today_as_version_on_cold_start_with_no_completed_batch() {
		// GIVEN: JobLog 미시드(완료 0건) — cleanUp 으로 보장됨
		Long memberId = support.seedMember();
		when(flaskApiClient.getMyProgressAvg(memberId)).thenReturn(dtoWithMyTotalAvg(10.0));

		String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
			.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));

		// THEN (1) 버전 = 오늘
		assertThat(statisticsVersionProvider.getCurrentVersion()).isEqualTo(today);

		// WHEN/THEN (2) 조회 → Flask 1회, 오늘 버전 키 생성
		GetMyProgressAvgDto read = memberService.getMyProgressAvgPer(memberId);
		assertThat(read.getMyTotalAvg()).isEqualTo(10.0);
		verify(flaskApiClient, times(1)).getMyProgressAvg(memberId);
		assertThat(support.keyExists(support.cacheKey(memberId, today))).isTrue();
	}

	private GetMyProgressAvgDto dtoWithMyTotalAvg(double myTotalAvg) {
		return GetMyProgressAvgDto.builder()
			.completionCnt(10)
			.challengeCnt(10)
			.myTotalAvg(myTotalAvg)
			.myTotalPer(1.0)
			.totalAvg(1.0)
			.myExerciseAvg(1.0)
			.myExercisePer(1.0)
			.exerciseAvg(1.0)
			.myBeautyAvg(1.0)
			.myBeautyPer(1.0)
			.beautyAvg(1.0)
			.myLifeAvg(1.0)
			.myLifePer(1.0)
			.lifeAvg(1.0)
			.myStudyAvg(1.0)
			.myStudyPer(1.0)
			.studyAvg(1.0)
			.myEtcAvg(1.0)
			.myEtcPer(1.0)
			.etcAvg(1.0)
			.build();
	}
}

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
 * Spec 006 — 통계 캐시 버전 키 인수 테스트 (Wave 1: US1 / SC-001 + FR-008 결정성).
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

package com.planetrush.planetrush.member;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import com.planetrush.planetrush.IntegrationTest;
import com.planetrush.planetrush.fixture.MemberFixture;
import com.planetrush.planetrush.infra.flask.util.FlaskApiClient;
import com.planetrush.planetrush.member.domain.Member;
import com.planetrush.planetrush.member.repository.MemberRepository;
import com.planetrush.planetrush.member.service.MemberService;
import com.planetrush.planetrush.member.service.dto.GetMyProgressAvgDto;

public class MemberIntegrationTest extends IntegrationTest {

	@Autowired
	private MemberService memberService;

	@Autowired
	private CacheManager cacheManager;

	@MockBean
	private MemberRepository memberRepository;

	@MockBean
	private FlaskApiClient flaskApiClient;

	@BeforeEach
	void setUp() {
		Cache cache = cacheManager.getCache("challenge-avg");
		if (cache != null) {
			cache.clear();
		}
		reset(memberRepository, flaskApiClient);
	}

	@DisplayName("통계 데이터를 조회할 때 캐싱된 값을 반환해야 한다.")
	@Test
	void should_use_cache_when_call_get_my_progress_avg() {
		// GIVEN
		Member member = Mockito.spy(MemberFixture.activeMember());
		GetMyProgressAvgDto dto = GetMyProgressAvgDto.builder()
			.challengeCnt(10)
			.completionCnt(10)

			.beautyAvg(1.0)
			.lifeAvg(1.0)
			.exerciseAvg(1.0)
			.studyAvg(1.0)
			.etcAvg(1.0)
			.totalAvg(1.0)

			.myBeautyAvg(1.0)
			.myLifeAvg(1.0)
			.myExerciseAvg(1.0)
			.myStudyAvg(1.0)
			.myEtcAvg(1.0)
			.myTotalAvg(1.0)

			.myBeautyPer(1.0)
			.myLifePer(1.0)
			.myExercisePer(1.0)
			.myStudyPer(1.0)
			.myEtcPer(1.0)
			.myTotalPer(1.0)

			.build();

		// WHEN
		when(member.getId()).thenReturn(1L);
		when(memberRepository.findById(anyLong())).thenReturn(Optional.of(member));
		when(flaskApiClient.getMyProgressAvg(anyLong())).thenReturn(dto);

		for (int i = 0; i < 10; i++) {
			memberService.getMyProgressAvgPer(member.getId());
		}

		// THEN
		Cache cache = cacheManager.getCache("challenge-avg");
		assertThat(cache).isNotNull()
			.extracting(it -> it.get(member.getId())).isNotNull()
			.extracting(it -> it.get()).isNotNull()
			.isInstanceOf(GetMyProgressAvgDto.class);
		verify(flaskApiClient, times(1)).getMyProgressAvg(member.getId());
	}

	@DisplayName("동시에 같은 회원 통계 조회가 들어와도 캐시 로더는 한 번만 실행되어야 한다.")
	@Test
	void should_load_cache_once_when_concurrent_requests_miss_same_key() throws Exception {
		// GIVEN
		Member member = Mockito.spy(MemberFixture.activeMember());
		GetMyProgressAvgDto dto = GetMyProgressAvgDto.builder()
			.challengeCnt(10)
			.completionCnt(10)
			.beautyAvg(1.0)
			.lifeAvg(1.0)
			.exerciseAvg(1.0)
			.studyAvg(1.0)
			.etcAvg(1.0)
			.totalAvg(1.0)
			.myBeautyAvg(1.0)
			.myLifeAvg(1.0)
			.myExerciseAvg(1.0)
			.myStudyAvg(1.0)
			.myEtcAvg(1.0)
			.myTotalAvg(1.0)
			.myBeautyPer(1.0)
			.myLifePer(1.0)
			.myExercisePer(1.0)
			.myStudyPer(1.0)
			.myEtcPer(1.0)
			.myTotalPer(1.0)
			.build();
		int concurrency = 16;
		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		CountDownLatch readyLatch = new CountDownLatch(concurrency);
		CountDownLatch startLatch = new CountDownLatch(1);
		List<Future<GetMyProgressAvgDto>> futures = new ArrayList<>();

		when(member.getId()).thenReturn(1L);
		when(memberRepository.findById(member.getId())).thenReturn(Optional.of(member));
		when(flaskApiClient.getMyProgressAvg(member.getId())).thenAnswer(invocation -> {
			Thread.sleep(200);
			return dto;
		});

		// WHEN
		for (int i = 0; i < concurrency; i++) {
			futures.add(executor.submit(() -> {
				readyLatch.countDown();
				try {
					assertThat(startLatch.await(2, TimeUnit.SECONDS)).isTrue();
					return memberService.getMyProgressAvgPer(member.getId());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new RuntimeException(e);
				}
			}));
		}

		assertThat(readyLatch.await(2, TimeUnit.SECONDS)).isTrue();
		startLatch.countDown();
		executor.shutdown();
		for (Future<GetMyProgressAvgDto> future : futures) {
			assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(dto);
		}
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

		// THEN
		verify(memberRepository, times(1)).findById(member.getId());
		verify(flaskApiClient, times(1)).getMyProgressAvg(member.getId());
	}
}

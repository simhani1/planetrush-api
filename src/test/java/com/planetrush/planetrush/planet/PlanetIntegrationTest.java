package com.planetrush.planetrush.planet;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.planetrush.planetrush.IntegrationTest;
import com.planetrush.planetrush.fixture.MemberFixture;
import com.planetrush.planetrush.fixture.PlanetFixture;
import com.planetrush.planetrush.fixture.ResidentFixture;
import com.planetrush.planetrush.member.domain.Member;
import com.planetrush.planetrush.member.repository.MemberRepository;
import com.planetrush.planetrush.planet.domain.Planet;
import com.planetrush.planetrush.planet.domain.PlanetStatus;
import com.planetrush.planetrush.planet.domain.Resident;
import com.planetrush.planetrush.planet.exception.PlanetDestroyedException;
import com.planetrush.planetrush.planet.repository.PlanetRepository;
import com.planetrush.planetrush.planet.repository.ResidentRepository;
import com.planetrush.planetrush.planet.service.PlanetServiceImpl;
import com.planetrush.planetrush.planet.service.dto.PlanetSubscriptionDto;

public class PlanetIntegrationTest extends IntegrationTest {

	@Autowired
	PlanetServiceImpl planetService;
	@Autowired
	MemberRepository memberRepository;
	@Autowired
	PlanetRepository planetRepository;
	@Autowired
	ResidentRepository residentRepository;

	private Member member1;
	private Member member2;
	private Planet planet;

	@BeforeEach
	void setUp() {
		List<Member> members = MemberFixture.activeMembers(2);
		List<Member> savedMembers = memberRepository.saveAll(members);
		member1 = savedMembers.get(0);
		member2 = savedMembers.get(1);

		planet = planetRepository.save(PlanetFixture.readyPlanet());

		Resident resident = ResidentFixture.creator(member1, planet);
		residentRepository.save(resident);
	}

	@AfterEach
	void clear() {
		residentRepository.deleteAll(residentRepository.findByPlanetId(planet.getId()));
		planetRepository.deleteById(planet.getId());
		memberRepository.deleteAllById(List.of(member1.getId(), member2.getId()));
	}

	@DisplayName("가입 및 탈퇴 요청의 순서가 보장된다.")
	@RepeatedTest(100)
	void should_guarantee_order_between_register_and_delete_requests() throws InterruptedException {
		PlanetSubscriptionDto registerDto = PlanetSubscriptionDto.builder()
			.planetId(planet.getId())
			.memberId(member2.getId())
			.build();

		PlanetSubscriptionDto deleteDto = PlanetSubscriptionDto.builder()
			.planetId(planet.getId())
			.memberId(member1.getId())
			.build();

		AtomicBoolean isDestroyed = new AtomicBoolean(false);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch doneLatch = new CountDownLatch(2);

		Runnable registerResidentTask = () -> {
			try {
				startLatch.await();
				planetService.registerResident(registerDto);
			} catch (Exception e) {
				e.printStackTrace();
				if (e instanceof PlanetDestroyedException) {
					isDestroyed.set(true);
				}
			} finally {
				doneLatch.countDown();
			}
		};

		Runnable deleteResidentTask = () -> {
			try {
				startLatch.await();
				planetService.deleteResident(deleteDto);
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				doneLatch.countDown();
			}
		};

		// WHEN
		ExecutorService executor = Executors.newFixedThreadPool(2);
		executor.submit(registerResidentTask);
		executor.submit(deleteResidentTask);

		startLatch.countDown();
		doneLatch.await();

		// THEN
		planet = planetRepository.findById(planet.getId()).get();
		if (isDestroyed.get()) {
			assertThat(planet.getStatus()).isEqualTo(PlanetStatus.DESTROYED);
			assertThat(planet.getCurrentParticipants()).isEqualTo(0);
		} else {
			assertThat(planet.getStatus()).isEqualTo(PlanetStatus.READY);
			assertThat(planet.getCurrentParticipants()).isEqualTo(1);
		}
	}

	@DisplayName("행성 가입이 탈퇴보다 먼저 요청될 경우 한 명의 거주자만 남는다.")
	@Test
	void should_leave_one_resident_when_register_before_delete() {
		PlanetSubscriptionDto registerDto = PlanetSubscriptionDto.builder()
			.planetId(planet.getId())
			.memberId(member2.getId())
			.build();

		PlanetSubscriptionDto deleteDto = PlanetSubscriptionDto.builder()
			.planetId(planet.getId())
			.memberId(member1.getId())
			.build();

		// WHEN
		planetService.registerResident(registerDto);
		planetService.deleteResident(deleteDto);

		// THEN
		planet = planetRepository.findById(planet.getId()).get();

		assertThat(planet.getCurrentParticipants()).isEqualTo(1);
		assertThat(planet.getStatus()).isEqualTo(PlanetStatus.READY);
	}

	@DisplayName("행성 탈퇴가 가입보다 먼저 요청될 경우 거주자는 0명이고 행성은 파괴된다.")
	@Test
	void should_destroy_planet_when_delete_before_register() {
		PlanetSubscriptionDto registerDto = PlanetSubscriptionDto.builder()
			.planetId(planet.getId())
			.memberId(member2.getId())
			.build();

		PlanetSubscriptionDto deleteDto = PlanetSubscriptionDto.builder()
			.planetId(planet.getId())
			.memberId(member1.getId())
			.build();

		// WHEN
		planetService.deleteResident(deleteDto);

		// THEN
		planet = planetRepository.findById(planet.getId()).get();

		assertThatThrownBy(() -> planetService.registerResident(registerDto))
			.isInstanceOf(PlanetDestroyedException.class);
		assertThat(planet.getCurrentParticipants()).isEqualTo(0);
		assertThat(planet.getStatus()).isEqualTo(PlanetStatus.DESTROYED);
	}

	@DisplayName("10초 이내로 중복된 행성 가입 요청은 멱등성을 보장한다.")
	@Test
	void should_ensure_idempotency_when_duplicate_register_resident_within_ten_seconds() {
		PlanetSubscriptionDto registerDto = PlanetSubscriptionDto.builder()
			.planetId(planet.getId())
			.memberId(member2.getId())
			.build();

		// WHEN
		int loop = 10;
		ExecutorService executor = Executors.newFixedThreadPool(loop);
		CountDownLatch startLatch = new CountDownLatch(1);
		Callable<Void> task = () -> {
			startLatch.await();
			planetService.registerResident(registerDto);
			return null;
		};

		List<Future<Void>> futures = IntStream.range(0, loop)
			.mapToObj(i -> executor.submit(task))
			.toList();
		startLatch.countDown();

		int successCnt = 0;
		int failedCnt = 0;
		for (Future<Void> future : futures) {
			try {
				future.get();
				successCnt++;
			} catch (Exception e) {
				failedCnt++;
			}
		}

		// THEN
		assertThat(successCnt).isEqualTo(1);
		assertThat(failedCnt).isEqualTo(loop - 1);
	}

	@DisplayName("10초 이내로 중복된 행성 탈퇴 요청은 멱등성을 보장한다.")
	@Test
	void should_ensure_idempotency_when_duplicate_delete_resident_within_ten_seconds() {
		PlanetSubscriptionDto deleteDto = PlanetSubscriptionDto.builder()
			.planetId(planet.getId())
			.memberId(member1.getId())
			.build();

		// WHEN
		int loop = 10;
		ExecutorService executor = Executors.newFixedThreadPool(loop);
		CountDownLatch startLatch = new CountDownLatch(1);
		Callable<Void> task = () -> {
			startLatch.await();
			planetService.deleteResident(deleteDto);
			return null;
		};

		List<Future<Void>> futures = IntStream.range(0, loop)
			.mapToObj(i -> executor.submit(task))
			.toList();
		startLatch.countDown();

		int successCnt = 0;
		int failedCnt = 0;
		for (Future<Void> future : futures) {
			try {
				future.get();
				successCnt++;
			} catch (Exception e) {
				failedCnt++;
			}
		}

		// THEN
		assertThat(successCnt).isEqualTo(1);
		assertThat(failedCnt).isEqualTo(loop - 1);
	}
}

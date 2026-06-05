package com.planetrush.planetrush.member.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.planetrush.planetrush.scheduler.log.JobLogRepository;

import lombok.RequiredArgsConstructor;

/**
 * 현재 유효한 통계 버전(직전 완료 {@code progressCalculation} 배치의 기준일)을 해석한다.
 *
 * <p>Spec 006 — T004 / R3·R4·R9. 통계 캐시 키의 {@code version} 구성요소를 매 요청 DB 에서
 * 직접 파생한다(별도 버전 캐시 없음 — 인덱스된 {@code MAX(endTime)} 1건 읽기로 충분, 조기
 * 최적화 회피). 배치 완료 즉시 전 인스턴스가 동일 버전으로 수렴한다.
 *
 * <p>헌법 II 정합: 버전의 진실 공급원은 DB({@code JobLog}) — Redis/{@code RedisTemplate} 직접
 * 접근 없음. 타임존은 {@code Asia/Seoul} 로 고정(R9)해 다중 인스턴스 버전 경계 일관성을 보장한다.
 */
@Component
@RequiredArgsConstructor
public class StatisticsVersionProvider {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter VERSION_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private final JobLogRepository jobLogRepository;

	/**
	 * 현재 통계 버전을 {@code yyyy-MM-dd} 형식으로 반환한다.
	 *
	 * <p>직전 완료 배치가 있으면 그 {@code endTime} 을 {@code Asia/Seoul} 기준일로 변환한다.
	 * 완료 0건(콜드스타트)이면 {@code Asia/Seoul} 의 오늘 날짜를 잠정 버전으로 사용한다.
	 *
	 * @return 통계 버전 문자열(예: {@code 2026-06-06})
	 */
	public String getCurrentVersion() {
		LocalDate version = jobLogRepository.findLatestCompletedProgressCalculationEndTime()
			.map(endTime -> endTime.atZone(SEOUL).toLocalDate())
			.orElseGet(() -> LocalDate.now(SEOUL));
		return version.format(VERSION_FORMAT);
	}
}

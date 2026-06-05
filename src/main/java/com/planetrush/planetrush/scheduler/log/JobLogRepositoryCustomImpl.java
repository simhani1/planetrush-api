package com.planetrush.planetrush.scheduler.log;

import static com.planetrush.planetrush.scheduler.log.QJobLog.*;

import java.time.LocalDateTime;
import java.util.Optional;

import com.querydsl.jpa.impl.JPAQueryFactory;

import lombok.RequiredArgsConstructor;

/**
 * {@link JobLogRepositoryCustom} QueryDSL 구현.
 *
 * <p>Spec 006 — T003. Spring Data JPA 의 custom fragment 규약을 따라 구현 클래스명이
 * {@code JobLogRepositoryCustomImpl} 로 끝나야 {@code JobLogRepository} 에 자동 결합된다.
 *
 * <p>헌법 III 정합: 스칼라 집계({@code MAX(endTime)}) 만 수행 — Entity getter 수동 매핑 없음.
 */
@RequiredArgsConstructor
public class JobLogRepositoryCustomImpl implements JobLogRepositoryCustom {

	private static final String PROGRESS_CALCULATION = "progressCalculation";

	private final JPAQueryFactory queryFactory;

	@Override
	public Optional<LocalDateTime> findLatestCompletedProgressCalculationEndTime() {
		LocalDateTime endTimeMax = queryFactory
			.select(jobLog.endTime.max())
			.from(jobLog)
			.where(
				jobLog.jobType.eq(PROGRESS_CALCULATION),
				jobLog.endTime.isNotNull()
			)
			.fetchOne();
		return Optional.ofNullable(endTimeMax);
	}
}

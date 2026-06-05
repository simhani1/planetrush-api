package com.planetrush.planetrush.scheduler.log;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * {@link JobLog} 의 custom QueryDSL 인터페이스.
 *
 * <p>Spec 006 — T003. {@code JobLogRepository} 가 본 인터페이스를 {@code extends} 하여
 * Spring Data JPA 의 fragment 결합 메커니즘으로 노출된다. 구현 클래스명은 규약상
 * {@code JobLogRepositoryCustomImpl} 로 끝나야 자동 결합된다.
 *
 * <p>헌법 III 정합: 본 인터페이스는 Entity 를 반환하지 않고 스칼라 집계({@code MAX(endTime)}) 만
 * 반환한다 — Entity→DTO 수동 매핑 없음, N+1 무관.
 */
public interface JobLogRepositoryCustom {

	/**
	 * 직전에 완료된 {@code progressCalculation} 배치의 {@code endTime} 최댓값을 조회한다.
	 *
	 * <p>Spec 006 — R3/R4. 통계 버전 산정의 진실 공급원. {@code jobType = 'progressCalculation'}
	 * 이고 {@code endTime IS NOT NULL} 인 JobLog 중 {@code MAX(endTime)} 을
	 * {@code (job_type, end_time)} 복합 인덱스의 끝값 1건 읽기로 처리한다.
	 *
	 * @return 직전 완료 배치의 endTime, 완료 0건이면 빈 {@link Optional}
	 */
	Optional<LocalDateTime> findLatestCompletedProgressCalculationEndTime();
}

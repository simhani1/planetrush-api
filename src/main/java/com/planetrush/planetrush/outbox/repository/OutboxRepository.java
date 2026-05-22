package com.planetrush.planetrush.outbox.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.planetrush.planetrush.outbox.domain.OutboxEvent;

public interface OutboxRepository extends JpaRepository<OutboxEvent, String> {

	/**
	 * 재발행 대상 OutboxEvent를 행 단위 락(FOR UPDATE SKIP LOCKED)으로 조회한다.
	 *
	 * <p>Spec 002 — FR-001/FR-002/FR-004, research R-001. 잠긴 행은 대기 없이
	 * 즉시 건너뛰어 다중 폴러 인스턴스 간 동일 outbox 이중 처리를 방지한다.
	 * {@code created_at > :cutoff} 조건으로 컷오프를 초과한 오래된 PENDING은
	 * 폴링 대상에서 제외된다.
	 *
	 * <p><b>주의</b>: {@code OutboxEvent.status}가 {@code @Enumerated} 미지정으로
	 * ORDINAL 매핑되어 DB에 정수로 저장된다. 따라서 {@code status} 파라미터는
	 * {@code OutboxStatus.PENDING.ordinal()} 값을 전달해야 한다.
	 *
	 * @param status    OutboxStatus의 ORDINAL 값 (PENDING)
	 * @param cutoff    이 시각보다 이후에 생성된 outbox만 조회
	 * @param batchSize 사이클당 조회 상한
	 */
	@Query(value = """
			SELECT * FROM outbox_event
			WHERE status = :status AND created_at > :cutoff
			ORDER BY created_at
			LIMIT :batchSize
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<OutboxEvent> findRepublishableForUpdateSkipLocked(
			@Param("status") int status,
			@Param("cutoff") Instant cutoff,
			@Param("batchSize") int batchSize);
}

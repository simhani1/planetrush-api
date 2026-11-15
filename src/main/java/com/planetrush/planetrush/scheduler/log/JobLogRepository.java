package com.planetrush.planetrush.scheduler.log;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link JobLog} JPA 리포지토리.
 *
 * <p>Spec 006 — T003 에서 QueryDSL custom 인터페이스 {@link JobLogRepositoryCustom} 와 결합됨.
 * Spring Data JPA 의 fragment 결합 메커니즘이 {@code JobLogRepositoryCustomImpl} 을 런타임에
 * 합쳐 {@code MAX(endTime)} 버전 조회를 본 인터페이스로 노출한다 — 헌법 III 정합.
 */
public interface JobLogRepository extends JpaRepository<JobLog, Long>, JobLogRepositoryCustom {

}

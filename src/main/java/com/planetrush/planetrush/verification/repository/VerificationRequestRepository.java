package com.planetrush.planetrush.verification.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.planetrush.planetrush.verification.domain.VerificationRequest;
import com.planetrush.planetrush.verification.repository.custom.VerificationRequestRepositoryCustom;

/**
 * {@link VerificationRequest} JPA 리포지토리.
 *
 * <p>Spec 005 — Phase 3 (T014) 에서 QueryDSL Projections 기반 custom 인터페이스
 * {@link VerificationRequestRepositoryCustom} 와 결합됨 — 헌법 III(Entity→DTO 매핑은
 * Projections 만) 정합. Spring Data JPA 의 fragment 결합 메커니즘이
 * {@code VerificationRequestRepositoryCustomImpl} 을 런타임에 합쳐 본 인터페이스를 완성한다.
 */
public interface VerificationRequestRepository
	extends JpaRepository<VerificationRequest, String>, VerificationRequestRepositoryCustom {
}

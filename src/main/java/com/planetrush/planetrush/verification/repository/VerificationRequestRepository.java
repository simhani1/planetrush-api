package com.planetrush.planetrush.verification.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.planetrush.planetrush.verification.domain.VerificationRequest;

/**
 * {@link VerificationRequest} JPA 리포지토리.
 *
 * <p>Spec 005 — Phase 2 단계에서는 기본 {@link JpaRepository} 메서드만 제공한다.
 * Phase 3 (T014) 에서 QueryDSL Projections 기반 custom 인터페이스
 * {@code VerificationRequestRepositoryCustom} 가 결합될 예정 — 헌법 III(Entity→DTO 매핑은
 * Projections 만) 정합.
 */
public interface VerificationRequestRepository extends JpaRepository<VerificationRequest, String> {
}

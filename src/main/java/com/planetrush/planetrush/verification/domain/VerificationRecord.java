package com.planetrush.planetrush.verification.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.hibernate.annotations.CreationTimestamp;

import com.planetrush.planetrush.member.domain.Member;
import com.planetrush.planetrush.planet.domain.Planet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 챌린지 인증 기록 entity.
 *
 * <p>Spec 005 — data-model.md §VerificationRecord. 본 스펙에서 컬럼은 추가하지 않고
 * unique 인덱스만 추가한다 (R-003): 사용자·챌린지·날짜 단위 멱등 단일 안전망.
 *
 * <p>{@code upload_date_only} 는 MySQL generated column —
 * {@code GENERATED ALWAYS AS (DATE(upload_date)) STORED}. JPA {@code ddl-auto} 의
 * generated column 자동 생성 신뢰도가 낮으므로 운영은 수동 DDL
 * ({@code schema-mysql-uniq.sql}), 테스트는 {@code VerificationRecordSchemaInitializer}
 * ({@code @TestConfiguration} + {@code ApplicationRunner} 패턴 — INFORMATION_SCHEMA 가드
 * 후 동적 ALTER) 로 멱등 적용한다. (quickstart §6-2 참조.)
 */
@Getter
@Entity
@Table(
	name = "verification_record",
	uniqueConstraints = @UniqueConstraint(
		name = "uniq_verification_record_member_planet_date",
		columnNames = {"member_id", "planet_id", "upload_date_only"}
	)
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerificationRecord {

	/**
	 * 인증 기록의 고유 식별자
	 */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "verification_record_id")
	private Long id;

	/**
	 * 사용자의 정보를 나타냅니다.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id")
	private Member member;

	/**
	 * planet의 정보를 나타냅니다.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "planet_id")
	private Planet planet;

	/**
	 * 인증 여부를 나타냅니다.
	 */
	@Column(name = "verified")
	private boolean verified;

	/**
	 * 인증사진 유사도를 나타냅니다.
	 */
	@Column(name = "similarity_score")
	private double similarityScore;

	/**
	 * 챌린지 인증 이미지의 파일 url입니다.
	 */
	@Column(name = "img_url", length = 256, nullable = false)
	private String imgUrl;

	/**
	 * 챌린지 인증 이미지의 파일 업로드 일자입니다.
	 * 자동으로 현재 타임스탬프로 설정됩니다.
	 */
	@CreationTimestamp
	@Column(name = "upload_date", length = 100, nullable = false)
	private LocalDateTime uploadDate;

	/**
	 * 챌린지 인증 기록 객체를 생성하는 생성자입니다.
	 *
	 * @param member 인증한 회원 id
	 * @param planet 진행중인 행성 id
	 * @param verified 인증 결과
	 * @param similarityScore 유사도 점수
	 * @param imgUrl 이미지 URL
	 */
	@Builder
	public VerificationRecord(Member member, Planet planet, boolean verified, double similarityScore, String imgUrl) {
		this.member = member;
		this.planet = planet;
		this.verified = verified;
		this.similarityScore = similarityScore;
		this.imgUrl = imgUrl;
	}

	public boolean isDifferenceGreaterThanFourDays() {
		LocalDate currentDate = LocalDate.now();
		LocalDate uploadDateOnly = this.uploadDate.toLocalDate();
		long daysDifference = ChronoUnit.DAYS.between(uploadDateOnly, currentDate);
		return daysDifference >= 4;
	}

}

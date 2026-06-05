package com.planetrush.planetrush.verification.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 인증 요청 라이프사이클 추적 entity.
 *
 * <p>Spec 005 — data-model.md §VerificationRequest. 클라이언트 폴링(GET /api/v1/verify/{requestId})
 * 의 source-of-truth.
 *
 * <h3>식별자</h3>
 * <p>{@code id}는 서비스 레이어에서 UUID v4 로 생성되어 영속 시 주입된다.
 * 동일 값이 {@link com.planetrush.planetrush.outbox.domain.OutboxEvent#getId() OutboxEvent.id}
 * 와 stream entry 의 {@code requestId} 로 함께 흐른다(R-004 — entity·outbox·stream·callback 4면이 한 UUID).
 *
 * <h3>외래키 미사용</h3>
 * <p>{@code memberId}/{@code planetId} 는 외래키를 두지 않는다. 이유:
 * <ul>
 *   <li>컨슈머 callback 처리 단계는 사용자 검증을 컨트롤러 인증 단계로 이관 — lazy load 부담 회피.</li>
 *   <li>QueryDSL Projections 매핑(헌법 III) 시 join 불필요.</li>
 * </ul>
 *
 * <h3>라이프사이클 불변식</h3>
 * <ul>
 *   <li>PENDING — {@code similarityScore}/{@code verified}/{@code errorMessage}/{@code completedAt} 모두 NULL.</li>
 *   <li>SUCCESS/FAIL — {@code similarityScore}/{@code verified}/{@code completedAt} NON-NULL, {@code errorMessage} NULL.</li>
 *   <li>ERROR — {@code errorMessage}/{@code completedAt} NON-NULL, {@code similarityScore}/{@code verified} NULL.</li>
 *   <li>종착 → PENDING 역전 없음. {@code UPDATE ... WHERE status='PENDING'} 가 SQL 레벨에서 강제 (R-002).</li>
 * </ul>
 */
@Getter
@Entity
@Table(
	name = "verification_request",
	indexes = @Index(name = "idx_verification_request_member_status", columnList = "member_id,status")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VerificationRequest {

	/**
	 * 인증 요청 UUID. {@code OutboxEvent.id} 및 stream {@code requestId} 와 동일 값.
	 */
	@Id
	@Column(name = "id", length = 36, nullable = false, updatable = false)
	private String id;

	/**
	 * 사용자 식별자. 외래키 미사용(설계 메모 참조).
	 */
	@Column(name = "member_id", nullable = false)
	private Long memberId;

	/**
	 * 챌린지 식별자. 외래키 미사용.
	 */
	@Column(name = "planet_id", nullable = false)
	private Long planetId;

	/**
	 * 사용자가 업로드한 인증 이미지 URL.
	 */
	@Column(name = "target_img_url", length = 500, nullable = false)
	private String targetImgUrl;

	/**
	 * 기준 이미지 URL — {@code Planet.standardVerificationImg} 의 스냅샷.
	 * (요청 시점 값을 보존해 추후 기준 이미지 변경의 영향을 차단)
	 */
	@Column(name = "standard_img_url", length = 500, nullable = false)
	private String standardImgUrl;

	/**
	 * 라이프사이클 상태. ORDINAL 직렬화는 금지(Spec 002 OutboxEvent.status 버그 회피) — STRING 으로 강제.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 20, nullable = false)
	private VerificationRequestStatus status;

	/**
	 * 유사도 점수(0~100). SUCCESS/FAIL 종착 시 기록, PENDING/ERROR 는 NULL.
	 */
	@Column(name = "similarity_score")
	private Integer similarityScore;

	/**
	 * 인증 통과 여부. SUCCESS=true, FAIL=false, PENDING/ERROR 는 NULL.
	 */
	@Column(name = "verified")
	private Boolean verified;

	/**
	 * ERROR 종착 시 컨슈머가 보고한 사유. SUCCESS/FAIL/PENDING 은 NULL.
	 */
	@Column(name = "error_message", length = 500)
	private String errorMessage;

	/**
	 * 요청 영속 시각.
	 */
	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	/**
	 * 종착 상태 진입 시각. PENDING 동안 NULL.
	 */
	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Builder
	private VerificationRequest(
		String id,
		Long memberId,
		Long planetId,
		String targetImgUrl,
		String standardImgUrl,
		VerificationRequestStatus status
	) {
		this.id = id;
		this.memberId = memberId;
		this.planetId = planetId;
		this.targetImgUrl = targetImgUrl;
		this.standardImgUrl = standardImgUrl;
		this.status = status;
	}

	/**
	 * PENDING 상태의 새 인증 요청을 생성한다. 서비스 레이어에서 UUID 를 외부 주입.
	 */
	public static VerificationRequest pending(
		String id,
		Long memberId,
		Long planetId,
		String targetImgUrl,
		String standardImgUrl
	) {
		return VerificationRequest.builder()
			.id(id)
			.memberId(memberId)
			.planetId(planetId)
			.targetImgUrl(targetImgUrl)
			.standardImgUrl(standardImgUrl)
			.status(VerificationRequestStatus.PENDING)
			.build();
	}
}

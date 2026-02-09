package com.planetrush.planetrush.member.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.planetrush.planetrush.member.exception.AlreadyWithdrawnException;
import com.planetrush.planetrush.member.exception.NicknameOverflowException;
import com.planetrush.planetrush.planet.domain.Resident;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자 정보를 나타내는 엔티티 클래스입니다.
 * 사용자 아이디, 닉네임, 이메일, 고유값, 소셜 로그인 제공자, 탈퇴여부 등을 포함합니다.
 */
@Getter
@Entity
@Table(name = "member")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {

	/**
	 * 사용자의 고유 식별자입니다.
	 */
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "member_id")
	private Long id;

	/**
	 * 사용자의 닉네임입니다.
	 */
	@Column(name = "nickname", length = 10, nullable = false)
	private String nickname;

	/**
	 * 사용자의 이메일입니다.
	 */
	@Column(name = "email", nullable = false)
	private String email;

	/**
	 * 사용자의 고유값입니다.
	 */
	@Column(name = "ci", nullable = false)
	private String ci;

	/**
	 * 사용자 소셜 로그인 제공 업체 정보입니다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false)
	private Provider provider;

	/**
	 * 사용자의 탈퇴여부입니다.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private Status status;

	/**
	 * 사용자의 행성 참여 정보입니다.
	 */
	@OneToMany(mappedBy = "member")
	private List<Resident> residents = new ArrayList<>();

	/**
	 * 사용자의 완료된 행성 정보입니다.
	 */
	@OneToMany(mappedBy = "member")
	private List<ChallengeHistory> challengeHistories = new ArrayList<>();

	/**
	 * 사용자의 가입일자입니다.
	 * 자동으로 현재 타임스탬프로 설정됩니다.
	 */
	@CreationTimestamp
	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "daily_verification_limit", nullable = false)
	private int dailyVerificationLimit;

	/**
	 * 사용자의 정보가 마지막으로 수정된 일자입니다.
	 */
	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	/**
	 * 주어진 속성들로 새로운 멤버 객체를 생성합니다.
	 *
	 * @param nickname 멤버의 닉네임
	 * @param email 멤버의 이메일 주소
	 * @param ci 멤버의 CI (연계정보)
	 * @param provider 멤버의 제공자 (Provider)
	 * @param status 멤버의 상태 (Status)
	 */
	@Builder
	public Member(String nickname, String email, String ci, Provider provider, Status status) {
		this.nickname = nickname;
		this.email = email;
		this.ci = ci;
		this.provider = provider;
		this.status = status;
		this.dailyVerificationLimit = 5;
	}

	/**
	 * 유저의 닉네임을 변경합니다.
	 * @param newNickname 변경할 닉네임
	 */
	public void updateNickname(String newNickname) {
		if (checkNicknameNull(newNickname)) {
			throw new IllegalArgumentException();
		}

		if (checkNicknameLength(newNickname)) {
			throw new NicknameOverflowException();
		}

		this.nickname = newNickname;
	}

	/**
	 * 변경할 닉네임이 null인지 확인합니다.
	 * @param nickname 변경할 닉네임
	 * @return null 여부
	 */
	private boolean checkNicknameNull(String nickname) {
		return nickname == null;
	}

	/**
	 * 변경할 닉네임의 길이를 확인합니다.
	 * @param nickname 변경할 닉네임
	 * @return 10글자 초과 또는 비어있는지 여부
	 */
	private boolean checkNicknameLength(String nickname) {
		return nickname.trim().isEmpty() || nickname.length() > 10;
	}

	/**
	 * 회원을 탈퇴시키며 상태를 INACTIVE로 변경합니다.
	 *
	 * @throws AlreadyWithdrawnException 이미 탈퇴한 회원일 때 발생
	 */
	public void withdrawn() {
		if (alreadyWithdrawn()) {
			throw new AlreadyWithdrawnException();
		}
		this.status = Status.INACTIVE;
	}

	/**
	 * 이미 탈퇴한 회원인지 확인합니다.
	 * @return 회원의 탈퇴 여부
	 */
	private boolean alreadyWithdrawn() {
		return this.status.equals(Status.INACTIVE);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		Member member = (Member)o;
		return Objects.equals(getId(), member.getId());
	}

	@Override
	public int hashCode() {
		return Objects.hash(getId(), getNickname(), getEmail(), getCi(), getProvider(), getStatus(),
			getResidents(), getCreatedAt(), getUpdatedAt(), getDailyVerificationLimit());
	}
	
}

package com.planetrush.planetrush.member.service.dto;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 마이페이지 통계 응답 DTO.
 *
 * <p>Spec 006 — T006. Redis JSON 캐시(R8, {@code GenericJackson2JsonRedisSerializer}) round-trip
 * 대상. setter 가 없으므로 역직렬화 시 Jackson 이 private 필드에 직접 값을 주입하도록
 * {@link JsonAutoDetect}(fieldVisibility = ANY) 를 부여한다. no-arg 생성자는 Jackson 이
 * 인스턴스 생성에 사용한다(protected 가시성은 Jackson 리플렉션으로 접근 가능). 모든 필드가
 * 원시 타입이라 추가 Jackson 모듈은 불필요하다.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class GetMyProgressAvgDto {

	private long completionCnt;
	private long challengeCnt;
	private double myTotalAvg;
	private double myTotalPer;
	private double totalAvg;
	private double myExerciseAvg;
	private double myExercisePer;
	private double exerciseAvg;
	private double myBeautyAvg;
	private double myBeautyPer;
	private double beautyAvg;
	private double myLifeAvg;
	private double myLifePer;
	private double lifeAvg;
	private double myStudyAvg;
	private double myStudyPer;
	private double studyAvg;
	private double myEtcAvg;
	private double myEtcPer;
	private double etcAvg;

}

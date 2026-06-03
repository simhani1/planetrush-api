package com.planetrush.planetrush.core.template.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ResponseCode {

	// 2000 - 성공
	OK("2000", "성공"),

	// 1000 - SECURITY
	UNAUTHENTICATED("1000", "인증되지 않은 사용자입니다."),
	EXPIRED_JWT_EXCEPTION("1001", "jwt 토큰이 만료되었습니다."),
	UNSUPPORTED_JWT_EXCEPTION("1002", "토큰 발급자가 일치하지 않습니다."),
	UNAUTHORIZED_EXCEPTION("1003", "토큰이 없거나 인증 과정에서 오류가 발생헀습니다."),

	// 3000 - MEMBER
	MEMBER_NOT_FOUND("3000", "회원이 존재하지 않습니다."),

	// 4000 - S3
	FILE_SIZE_OVERFLOW("4000", "개별 사진 사이즈는 최대 10MB, 총합 사이즈는 최대 100MB를 초과할 수 없습니다."),
	FAIL_TO_UPLOAD_FILE("4001", "AWS 서비스가 원활하지 않아 사진 업로드에 실패했습니다."),
	FILE_NOT_FOUND("4002", "이미지 파일을 찾을 수 없습니다."),

	// 5000 - PLANET
	PLANET_NOT_FOUND("5000", "행성이 존재하지 않습니다."),
	PARTICIPANTS_OVERFLOW("5001", "최대 참여 인원 수를 초과했습니다."),
	NEGATIVE_PARTICIPANT_COUNT("5002", "현재 참여 인원 수는 음수가 될 수 없습니다."),
	PLANET_IS_DESTROYED("5003", "이미 파괴된 행성입니다."),
	INVALID_START_DATE("5004", "행성의 시작 날짜가 제한 범위를 벗어났습니다."),

	// 6000 - RESIDENT
	RESIDENT_NOT_FOUND("6000", "거주자가 존재하지 않습니다."),
	ALREADY_EXIST_RESIDENT("6001", "이미 행성에 거주 중입니다."),
	RESIDENT_EXIT_TIMEOUT("6002", "챌린지가 시작되어 행성을 떠날 수 없습니다."),
	REGISTER_RESIDENT_TIMEOUT("6003", "챌린지가 시작되어 참여할 수 없습니다."),
	RESIDENT_OVERFLOW("6004", "최대 참여 행성 수를 초과했습니다."),

	// 7000 - FLASK
	INVALID_IMAGE_URL_COUNT("7000", "유사도 검사에 필요한 이미지 개수가 부족합니다."),
	FLASK_SERVER_NOT_CONNECTED("7001", "플라스크 서버 연결에 실패했습니다."),
	IMAGE_SIMILARITY_CHECK_ERROR("7002", "이미지 유사도 측정 과정에서 에러가 발생했습니다."),
	PROGRESS_AVG_NOT_FOUND("7003", "평균 진행률 데이터가 존재하지 않습니다."),

	// 8000 - VERIFICATION
	ALREADY_VERIFIED("8000", "오늘 이미 인증을 완료한 사용자입니다."),
	VERIFICATION_REQUEST_NOT_FOUND("8001", "인증 요청을 찾을 수 없습니다."),
	INVALID_VERIFICATION_CALLBACK_PAYLOAD("8002", "인증 결과 콜백 페이로드 형식이 올바르지 않습니다."),
	;

	private String code;
	private String message;

}
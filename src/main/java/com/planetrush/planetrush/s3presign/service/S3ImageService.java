package com.planetrush.planetrush.s3presign.service;

import org.springframework.web.multipart.MultipartFile;

import com.planetrush.planetrush.s3presign.service.dto.FileMetaInfo;

/**
 * @deprecated presigned url을 사용하여 이미지 업로드를 진행합니다.
 * {@link com.planetrush.planetrush.s3presign.service.S3ImageServiceV2}를 사용하세요.
 */
@Deprecated
public interface S3ImageService {

	/**
	 * 행성 이미지 업로드
	 * @param file 이미지 파일
	 * @param memberId 유저의 고유 id
	 * @return 저장된 이미지 파일에 대한 정보
	 */
	FileMetaInfo uploadPlanetImg(MultipartFile file, long memberId);

	/**
	 * 인증 기준이 되는 이미지 업로드
	 * @param file 이미지 파일
	 * @param memberId 유저의 고유 id
	 * @return 저장된 이미지 파일에 대한 정보
	 */
	FileMetaInfo uploadStandardVerificationImg(MultipartFile file, long memberId);

	/**
	 * 인증 사진 업로드
	 * @param file 이미지 파일
	 * @param memberId 유저의 고유 id
	 * @return 저장된 이미지 파일에 대한 정보
	 */
	FileMetaInfo
	uploadVerificationImg(MultipartFile file, Long memberId);

	/**
	 * 파일 확장자 추출
	 * @param fileName 파일명
	 * @return 추출된 확장자 반환
	 */
	default String getFileExtension(String fileName) {
		if (fileName == null || !fileName.contains(".")) {
			return "";
		}
		return fileName.substring(fileName.lastIndexOf(".") + 1);
	}

}

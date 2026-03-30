package com.planetrush.planetrush.s3presign.service;

import java.util.Date;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.planetrush.planetrush.s3presign.service.dto.ImageType;
import com.planetrush.planetrush.s3presign.service.dto.PresignedUrlDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class S3ImageServiceV2 {

	private final AmazonS3 amazonS3;
	@Value("${cloud.aws.s3.bucket}")
	private String bucket;

	private final static int MINUTE = 1000 * 60;

	public PresignedUrlDto getPresignedUrl(PresignedUrlDto dto) {
		ImageType imgType = dto.getType();
		String path = imgType.buildPath(dto.getMemberId(), dto.getOriginFileName());
		Date expiration = new Date(System.currentTimeMillis() + 10 * MINUTE);

		GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(bucket, path)
			.withMethod(HttpMethod.PUT)
			.withExpiration(expiration);

		String url = amazonS3.generatePresignedUrl(req).toString();

		return PresignedUrlDto.builder()
			.preSignedUrl(url)
			.originFileName(dto.getOriginFileName())
			.build();
	}
}
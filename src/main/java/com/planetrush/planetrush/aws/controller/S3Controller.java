package com.planetrush.planetrush.aws.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.planetrush.planetrush.aws.controller.req.PresignedUrlReq;
import com.planetrush.planetrush.aws.controller.res.PresignedUrlRes;
import com.planetrush.planetrush.aws.service.S3ImageServiceV2;
import com.planetrush.planetrush.aws.service.dto.ImageType;
import com.planetrush.planetrush.aws.service.dto.PresignedUrlDto;
import com.planetrush.planetrush.core.annotation.RequireJwtToken;
import com.planetrush.planetrush.core.aop.member.MemberContext;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/aws")
public class S3Controller {

	private final S3ImageServiceV2 s3ImageServiceV2;

	@RequireJwtToken
	@PostMapping("/s3/presigned-url")
	public ResponseEntity<PresignedUrlRes> getPresignedUrl(@RequestBody PresignedUrlReq req) {
		Long memberId = MemberContext.getMemberId();
		PresignedUrlDto dto = s3ImageServiceV2.getPresignedUrl(PresignedUrlDto.builder()
			.type(ImageType.of(req.getImageType()))
			.originFileName(req.getFileName())
			.memberId(memberId)
			.build());
		return ResponseEntity.ok(PresignedUrlRes.builder()
			.presignedUrl(dto.getPreSignedUrl())
			.fileName(dto.getOriginFileName())
			.build());
	}
}

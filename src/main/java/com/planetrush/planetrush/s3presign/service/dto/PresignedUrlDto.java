package com.planetrush.planetrush.s3presign.service.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PresignedUrlDto {

	private Long memberId;
	private String originFileName;
	private ImageType type;
	private String preSignedUrl;
}

package com.planetrush.planetrush.s3presign.controller.res;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PresignedUrlRes {

	private String presignedUrl;
	private String fileName;
}

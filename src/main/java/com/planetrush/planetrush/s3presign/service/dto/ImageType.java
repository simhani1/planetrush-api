package com.planetrush.planetrush.s3presign.service.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ImageType {

	CUSTOM_PLANET_IMG("planet", "custom_planet_img"),
	STANDARD_IMG("standard", "standard_img"),
	VERIFICATION_IMG("verification", "verification_img"),;

	private String type;
	private String directory;

	public static ImageType of(String code) {
		for (ImageType it : values()) {
			if (it.type.equals(code)) {
				return it;
			}
		}
		throw new IllegalArgumentException("Unknown image type: " + code);
	}

	public String buildPath(Long memberId, String originalFilename) {
		String extension = getExtension(originalFilename);
		String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
		return "%s/%d/%s_%s%s".formatted(directory, memberId, time, UUID.randomUUID(), extension);
	}

	private static String getExtension(String filename) {
		int dot = filename.lastIndexOf('.');
		return (dot >= 0) ? filename.substring(dot).toLowerCase(Locale.KOREAN) : "";
	}
}

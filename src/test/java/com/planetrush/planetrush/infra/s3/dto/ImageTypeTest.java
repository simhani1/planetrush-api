package com.planetrush.planetrush.infra.s3.dto;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.planetrush.planetrush.s3presign.service.dto.ImageType;

class ImageTypeTest {

	@DisplayName("지정되지 않은 코드로 presigned url 발급을 시도하면 예외를 던진다")
	@Test
	void of_should_fail_when_request_with_unknown_code() {
		assertThatThrownBy(() -> ImageType.of("dump"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@DisplayName("code가 정확히 일치하면 지정된 클래스를 반환한다")
	@ParameterizedTest
	@CsvSource({
		"planet,custom_planet_img",
		"standard,standard_img",
		"verification,verification_img"
	})
	void of_should_return_directory_name_when_code_matched(String code, String expectedDirectory) {
		ImageType it = ImageType.of(code);
		assertThat(it.getDirectory()).isEqualTo(expectedDirectory);
	}

	@DisplayName("유일한 파일 경로를 반환한다")
	@Test
	void buildPath_should_make_unique_path() {
		// GIVEN
		final Long MAX = 100L;
		final String originalFilename = "fileName.png";
		final List<String> codes = new ArrayList<>();
		codes.add("planet");
		codes.add("standard");
		codes.add("verification");
		final Set<String> pathSet = new HashSet<>();

		// WHEN
		for (String code : codes) {
			for (Long memberId = 1L; memberId <= MAX; memberId++) {
				pathSet.add(ImageType.of(code).buildPath(memberId, originalFilename));
			}
		}

		// THEN
		assertThat(pathSet.size()).isEqualTo(codes.size() * MAX);
	}

	@DisplayName("path의 패턴이 {디렉토리/회원ID/현재시간_UUID/원본파일명}과 일치해야 한다")
	@ParameterizedTest
	@CsvSource({
		"planet,123,test-image.JPG,.jpg",
		"standard,456,test-image.PNG,.png",
		"verification,789,test-image.JPG,.jpg"
	})
	void buildPath_should_pass_when_path_pattern_matched(String code, Long memberId, String originalFileName, String expectedExt) {
		// GIVEN
		final ImageType it = ImageType.of(code);
		final String path = it.buildPath(memberId, originalFileName);
		final String currentTime = "[0-9]{14}";  // yyyyMMddHHmmss
		final String uuid = "[0-9a-fA-F\\-]{36}";  // UUID

		final Pattern p = Pattern.compile(
			"^" + Pattern.quote(it.getDirectory() + "/" + memberId + "/") + currentTime + "_" + uuid +
				Pattern.quote(expectedExt) + "$");

		// WHEN & THEN
		assertThat(p.matcher(path).matches()).isTrue();
	}
}
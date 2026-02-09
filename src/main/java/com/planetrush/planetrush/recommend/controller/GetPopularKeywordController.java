package com.planetrush.planetrush.recommend.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.planetrush.planetrush.core.annotation.RequireJwtToken;
import com.planetrush.planetrush.core.template.response.BaseResponse;
import com.planetrush.planetrush.recommend.service.GetPopularKeywordService;
import com.planetrush.planetrush.recommend.service.dto.GetPopularKeywordDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/v1/recommend")
@RestController
public class GetPopularKeywordController {

	private final GetPopularKeywordService getPopularKeywordService;

	/**
	 * 카테고리별 키워드를 가져옵니다.
	 * @param category 카테고리
	 * @return ResponseEntity
	 */
	@RequireJwtToken
	@GetMapping("/keyword")
	public ResponseEntity<BaseResponse<List<GetPopularKeywordDto>>> getPopularKeywords(
		@RequestParam("category") String category) {
		return ResponseEntity.ok(BaseResponse.ofSuccess(getPopularKeywordService.getPopularKeywords(category)));
	}

}
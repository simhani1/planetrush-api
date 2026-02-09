package com.planetrush.planetrush.planet.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.planetrush.planetrush.core.annotation.RequireJwtToken;
import com.planetrush.planetrush.core.aop.member.MemberContext;
import com.planetrush.planetrush.core.template.response.BaseResponse;
import com.planetrush.planetrush.planet.controller.request.RegisterPlanetReq;
import com.planetrush.planetrush.planet.controller.response.SearchPlanetRes;
import com.planetrush.planetrush.planet.service.PlanetService;
import com.planetrush.planetrush.planet.service.dto.GetDefaultPlanetImgDto;
import com.planetrush.planetrush.planet.service.dto.GetMainPlanetListDto;
import com.planetrush.planetrush.planet.service.dto.GetMyPlanetListDto;
import com.planetrush.planetrush.planet.service.dto.OngoingPlanetDto;
import com.planetrush.planetrush.planet.service.dto.PlanetDetailDto;
import com.planetrush.planetrush.planet.service.dto.PlanetSubscriptionDto;
import com.planetrush.planetrush.planet.service.dto.RegisterPlanetDto;
import com.planetrush.planetrush.planet.service.dto.SearchCond;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/planets")
public class PlanetController {

	private final PlanetService planetService;

	/**
	 * 모든 기본 행성 이미지 URL을 불러옵니다.
	 * @return 모든 기본 행성 이미지의 URL을 담은 List를 포함한 ResponseEntity
	 */
	@GetMapping("/images")
	public ResponseEntity<BaseResponse<List<GetDefaultPlanetImgDto>>> getAllPlanetImgs() {
		return ResponseEntity.ok(BaseResponse.ofSuccess(planetService.getAllImgUrls()));
	}

	/**
	 * 행성을 생성합니다.
	 * @param req 행성 등록에 필요한 데이터
	 * @return
	 */
	@RequireJwtToken
	@PostMapping
	public ResponseEntity<BaseResponse<Void>> registerPlanet(@RequestBody RegisterPlanetReq req) {
		Long memberId = MemberContext.getMemberId();
		planetService.registerPlanet(RegisterPlanetDto.builder()
			.name(req.getName())
			.content(req.getContent())
			.category(req.getCategory())
			.startDate(req.getStartDate())
			.endDate(req.getEndDate())
			.maxParticipants(req.getMaxParticipants())
			.authCond(req.getAuthCond())
			.memberId(memberId)
			.planetImgUrl(req.getPlanetImgUrl())
			.standardVerificationImgUrl(req.getStandardVerificationImgUrl())
			.build());
		return ResponseEntity.ok(BaseResponse.ofSuccess());
	}

	/**
	 * 행성을 검색합니다.
	 * @param category 카테고리
	 * @param keyword 키워드
	 * @param lastPlanetId 마지막 행성의 id
	 * @param size 크기
	 * @return 검색 결과를 담은 ResponseEntity
	 */
	@RequireJwtToken
	@GetMapping
	public ResponseEntity<BaseResponse<SearchPlanetRes>> searchPlanet(
		@RequestParam(value = "category", required = false) String category,
		@RequestParam(value = "keyword", required = false) String keyword,
		@RequestParam(value = "lp-id", required = false) Long lastPlanetId,
		@RequestParam("size") int size) {
		List<PlanetDetailDto> planets = planetService.searchPlanet(SearchCond.builder()
			.category(category)
			.keyword(keyword)
			.size(size)
			.lastPlanetId(lastPlanetId)
			.build());
		boolean hasNext = false;
		if (planets.size() > size) {
			planets.remove(size);
			hasNext = true;
		}
		return ResponseEntity.ok(
			BaseResponse.ofSuccess(
				SearchPlanetRes.builder()
					.planets(planets)
					.hasNext(hasNext)
					.build()));
	}

	/**
	 * 시작 전인 행성을 상세 조회합니다.
	 * @param planetId 상세 조회할 행성의 고유 id
	 * @return 행성의 정보를 담은 ResponseEntity
	 */
	@RequireJwtToken
	@GetMapping("/detail")
	public ResponseEntity<BaseResponse<PlanetDetailDto>> getPlanetDetail(@RequestParam("planet-id") Long planetId) {
		Long memberId = MemberContext.getMemberId();
		return ResponseEntity.ok(BaseResponse.ofSuccess(planetService.getPlanetDetail(memberId, planetId)));
	}

	/**
	 * 현재 진행 중인 행성을 상세 조회합니다.
	 * @param planetId 상세 조회할 행성의 고유 id
	 * @return 행성의 정보를 담은 ResponseEntity
	 */
	@RequireJwtToken
	@GetMapping("/ongoing")
	public ResponseEntity<BaseResponse<OngoingPlanetDto>> getOngoingPlanetDto(
		@RequestParam("planet-id") Long planetId) {
		Long memberId = MemberContext.getMemberId();
		OngoingPlanetDto res = planetService.getOngoingPlanet(memberId, planetId);
		return ResponseEntity.ok(BaseResponse.ofSuccess(res));
	}

	/**
	 * 현재 사용자의 마이페이지를 위한 참여 중이면서 종료되지 않은 행성 목록을 가져옵니다.
	 * @return 현재 사용자의 행성 목록을 포함한 ResponseEntity 객체
	 */
	@RequireJwtToken
	@GetMapping("/me/list")
	public ResponseEntity<BaseResponse<List<GetMyPlanetListDto>>> getMyPlanetList() {
		Long memberId = MemberContext.getMemberId();
		return ResponseEntity.ok(BaseResponse.ofSuccess(planetService.getMyPlanetList(memberId)));
	}

	/**
	 * 현재 사용자의 메인페이지를 위한 참여 중이면서 종료되지 않은 행성 목록을 가져옵니다.
	 * @return 현재 사용자의 행성 목록을 포함한 ResponseEntity 객체
	 */
	@RequireJwtToken
	@GetMapping("/main/list")
	public ResponseEntity<BaseResponse<List<GetMainPlanetListDto>>> getMainPlanetList() {
		Long memberId = MemberContext.getMemberId();
		return ResponseEntity.ok(BaseResponse.ofSuccess(planetService.getMainPlanetList(memberId)));
	}

	/**
	 * 행성 입주 기록을 등록합니다.
	 * @param planetId 행성의 고유 id
	 * @return ResponseEntity
	 */
	@RequireJwtToken
	@PostMapping("/{planet-id}")
	public ResponseEntity<BaseResponse<?>> registerResident(@PathVariable("planet-id") Long planetId) {
		Long memberId = MemberContext.getMemberId();
		planetService.registerResident(
			PlanetSubscriptionDto.builder().memberId(memberId).planetId(planetId).build());
		return ResponseEntity.ok(BaseResponse.ofSuccess());
	}

	/**
	 * 행성 입주 기록을 삭제합니다.
	 * @param planetId 행성의 고유 id
	 * @return ResponseEntity
	 */
	@RequireJwtToken
	@DeleteMapping("/{planet-id}")
	public ResponseEntity<BaseResponse<?>> deleteResident(@PathVariable("planet-id") Long planetId) {
		Long memberId = MemberContext.getMemberId();
		planetService.deleteResident(
			PlanetSubscriptionDto.builder().memberId(memberId).planetId(planetId).build());
		return ResponseEntity.ok(BaseResponse.ofSuccess());
	}
}

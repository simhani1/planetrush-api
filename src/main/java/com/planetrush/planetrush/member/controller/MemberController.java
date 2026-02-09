package com.planetrush.planetrush.member.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.planetrush.planetrush.core.annotation.RequireJwtToken;
import com.planetrush.planetrush.core.aop.member.MemberContext;
import com.planetrush.planetrush.core.template.response.BaseResponse;
import com.planetrush.planetrush.member.controller.response.GetMyCollectionRes;
import com.planetrush.planetrush.member.service.MemberService;
import com.planetrush.planetrush.member.service.dto.CollectionSearchCond;
import com.planetrush.planetrush.member.service.dto.GetMyProgressAvgDto;
import com.planetrush.planetrush.member.service.dto.PlanetCollectionDto;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members")
public class MemberController {

	private final MemberService memberService;

	/**
	 * 사용자의 완료한 행성 컬렉션을 가져옵니다.
	 *
	 * @return ResponseEntity
	 */
	@RequireJwtToken
	@GetMapping("/collections")
	public ResponseEntity<BaseResponse<GetMyCollectionRes>> getPlanetCollections(
		@RequestParam(value = "lh-id", required = false) Long lastHistoryId,
		@RequestParam("size") int size) {
		Long memberId = MemberContext.getMemberId();
		List<PlanetCollectionDto> planetCollection = memberService.getPlanetCollections(
			CollectionSearchCond.builder().memberId(memberId).lastHistoryId(lastHistoryId).size(size).build());

		boolean hasNext = false;
		if (planetCollection.size() > size) {
			planetCollection.remove(size);
			hasNext = true;
		}

		return ResponseEntity.ok(
			BaseResponse.ofSuccess(GetMyCollectionRes.builder()
				.planetCollection(planetCollection)
				.hasNext(hasNext)
				.build()));
	}

	/**
	 * 마이페이지를 위한 사용자의 통계 정보를 가져옵니다.
	 *
	 * @return ResponseEntity
	 */
	@RequireJwtToken
	@GetMapping("/mypage")
	public ResponseEntity<BaseResponse<GetMyProgressAvgDto>> getMyProgressAvg() {
		Long memberId = MemberContext.getMemberId();
		GetMyProgressAvgDto getMyProgressAvgDto = memberService.getMyProgressAvgPer(memberId);
		return ResponseEntity.ok(BaseResponse.ofSuccess(getMyProgressAvgDto));
	}

	/**
	 * 유저의 닉네임을 변경합니다.
	 * @param nickname 변경할 닉네임
	 * @return ResponseEntity
	 */
	@RequireJwtToken
	@PatchMapping("/profile")
	public ResponseEntity<BaseResponse<?>> updateMemberNickname(@RequestParam("nickname") String nickname) {
		Long memberId = MemberContext.getMemberId();
		memberService.updateMemberNickname(memberId, nickname);
		return ResponseEntity.ok(BaseResponse.ofSuccess());
	}
}

package com.planetrush.planetrush.member.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.planetrush.planetrush.member.domain.ChallengeHistory;
import com.planetrush.planetrush.member.domain.Member;
import com.planetrush.planetrush.member.exception.MemberNotFoundException;
import com.planetrush.planetrush.member.repository.MemberRepository;
import com.planetrush.planetrush.member.repository.custom.ChallengeHistoryRepositoryCustom;
import com.planetrush.planetrush.member.service.dto.CollectionSearchCond;
import com.planetrush.planetrush.member.service.dto.GetMyProgressAvgDto;
import com.planetrush.planetrush.member.service.dto.PlanetCollectionDto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberServiceImpl implements MemberService {

	private final MemberRepository memberRepository;
	private final ChallengeHistoryRepositoryCustom challengeHistoryRepositoryCustom;
	private final StatisticsVersionProvider statisticsVersionProvider;
	private final MemberStatisticsCacheService memberStatisticsCacheService;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<PlanetCollectionDto> getPlanetCollections(CollectionSearchCond searchCond) {
		List<ChallengeHistory> historyList = challengeHistoryRepositoryCustom.getMyChallengeHistory(searchCond);
		return historyList.stream()
			.map(history -> PlanetCollectionDto.builder()
				.historyId(history.getId())
				.name(history.getPlanetName())
				.category(history.getCategory())
				.content(history.getChallengeContent())
				.imageUrl(history.getPlanetImgUrl())
				.progress(Double.parseDouble(String.format("%.2f", history.getProgress())))
				.build())
			.collect(Collectors.toList());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Spec 006 — T009 / R7. 현재 통계 버전을 먼저 해석한 뒤, 버전 키 기반 캐시 서비스
	 * ({@link MemberStatisticsCacheService}) 에 위임한다. 캐시 어드바이스는 별도 빈에 적용되며
	 * (자기호출 캐시 우회 방지) 버전이 바뀌면 키가 바뀌어 자연 miss → 재계산된다.</p>
	 */
	@Override
	public GetMyProgressAvgDto getMyProgressAvgPer(Long memberId) {
		String version = statisticsVersionProvider.getCurrentVersion();
		return memberStatisticsCacheService.getStatistics(memberId, version);
	}

	/**
	 * {@inheritDoc}
	 *
	 * @throws MemberNotFoundException 유저를 찾을 수 없을 때 발생
	 */
	@Override
	public void updateMemberNickname(Long memberId, String nickname) {
		Member member = memberRepository.findById(memberId)
			.orElseThrow(() -> new MemberNotFoundException("Member not found with ID: " + memberId));
		member.updateNickname(nickname);
	}
}

package com.planetrush.planetrush.member.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.planetrush.planetrush.infra.flask.util.FlaskApiClient;
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

	private final FlaskApiClient flaskApiClient;
	private final MemberRepository memberRepository;
	private final ChallengeHistoryRepositoryCustom challengeHistoryRepositoryCustom;

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
	 * <p>이 메서드는 반환값을 캐싱하여 관리합니다.</p>
	 * <p>캐시 미스가 발생하는 경우에만 플라스크 서버로 API 요청을 전송하여 새로운 데이터로 캐시에 저장합니다.</p>
	 */
	@Cacheable(cacheNames = "challenge-avg", key = "#memberId", sync = true)
	@Override
	public GetMyProgressAvgDto getMyProgressAvgPer(Long memberId) {
		Member member = memberRepository.findById(memberId)
			.orElseThrow(() -> new MemberNotFoundException("Member not found with ID: " + memberId));
		return flaskApiClient.getMyProgressAvg(memberId);
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

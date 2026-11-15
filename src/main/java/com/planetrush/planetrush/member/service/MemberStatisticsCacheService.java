package com.planetrush.planetrush.member.service;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.planetrush.planetrush.infra.flask.util.FlaskApiClient;
import com.planetrush.planetrush.member.exception.MemberNotFoundException;
import com.planetrush.planetrush.member.repository.MemberRepository;
import com.planetrush.planetrush.member.service.dto.GetMyProgressAvgDto;

import lombok.RequiredArgsConstructor;

/**
 * 버전 키 기반 통계 캐시 서비스.
 *
 * <p>Spec 006 — T008 / R7. {@code @Cacheable} 어드바이스가 AOP 프록시로 적용되려면 캐시 메서드를
 * 호출 측({@code MemberServiceImpl})과 다른 빈으로 분리해야 한다(자기호출 캐시 우회 방지).
 *
 * <p>캐시 키는 {@code {memberId}:{version}} — 버전이 바뀌면 키가 바뀌어 자연 miss → 재계산되며
 * 과거 키는 TTL 25h 후 자연 만료된다(전수 evict 불요). {@code sync = true} 와 locking
 * RedisCacheWriter 결합으로 동일 키 동시 첫 조회 시 Flask 호출이 요청당 1회로 수렴한다(R5/FR-006).
 *
 * <p>실패 비캐싱(FR-007): member 미존재({@link MemberNotFoundException}) 와 Flask 실패 예외는
 * 그대로 전파한다 — {@code @Cacheable} 은 예외 발생 시 결과를 저장하지 않으므로 sentinel/빈 결과가
 * 캐싱되지 않고, 다음 요청이 재계산을 시도한다.
 */
@Service
@RequiredArgsConstructor
public class MemberStatisticsCacheService {

	private final FlaskApiClient flaskApiClient;
	private final MemberRepository memberRepository;

	/**
	 * 버전 키로 통계를 조회한다. 캐시 miss 시에만 Flask 를 호출해 결과를 적재한다.
	 *
	 * @param memberId 조회 대상 회원 ID
	 * @param version  현재 통계 버전({@code yyyy-MM-dd}) — 캐시 키 구성요소
	 * @return 통계 DTO
	 */
	@Cacheable(cacheNames = "challenge-avg", key = "#memberId + ':' + #version", sync = true)
	public GetMyProgressAvgDto getStatistics(Long memberId, String version) {
		memberRepository.findById(memberId)
			.orElseThrow(() -> new MemberNotFoundException("Member not found with ID: " + memberId));
		return flaskApiClient.getMyProgressAvg(memberId);
	}
}

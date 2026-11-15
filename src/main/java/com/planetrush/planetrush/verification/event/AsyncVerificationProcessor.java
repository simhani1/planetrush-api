package com.planetrush.planetrush.verification.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.planetrush.planetrush.infra.flask.res.FlaskResponseDto;
import com.planetrush.planetrush.infra.flask.util.FlaskApiClient;
import com.planetrush.planetrush.verification.service.dto.MessageCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 이미지 유사도 검사를 요청 스레드와 분리해 워커 스레드에서 수행한다.
 *
 * <p>{@code verification.publisher.type=async-event} 경로. 사진 유사도 판별 서버(Flask) 호출은
 * {@link FlaskApiClient} 의 지수 백오프 재시도(최대 5회)를 거치며, 완료되면 Spring 내부 이벤트
 * {@link SaveVerificationResultEvent} 를 발행한다. 결과 저장은 이벤트 리스너가 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncVerificationProcessor {

	static final String FLASK_CALL_FAILED = "flask_call_failed";

	private final FlaskApiClient flaskApiClient;
	private final ApplicationEventPublisher eventPublisher;

	@Async
	public void initiateSimilarityCheck(MessageCommand command) {
		String requestId = command.eventId();
		try {
			FlaskResponseDto res = flaskApiClient.verifyChallengeImg(command.standardImg(), command.targetImg());
			eventPublisher.publishEvent(
				SaveVerificationResultEvent.success(requestId, res.isVerified(), res.getSimilarityScore()));
		} catch (RuntimeException e) {
			// 재시도 소진 — 요청을 PENDING 으로 방치하지 않고 ERROR 로 종착시킨다. 로그는 requestId 만.
			log.error("[ASYNC] similarity check failed requestId={}", requestId, e);
			eventPublisher.publishEvent(SaveVerificationResultEvent.error(requestId, FLASK_CALL_FAILED));
		}
	}
}

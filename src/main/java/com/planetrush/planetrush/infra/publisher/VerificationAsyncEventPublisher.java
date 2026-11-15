package com.planetrush.planetrush.infra.publisher;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.planetrush.planetrush.verification.event.AsyncVerificationProcessor;
import com.planetrush.planetrush.verification.event.publisher.VerificationMessagePublisher;
import com.planetrush.planetrush.verification.service.dto.MessageCommand;

import lombok.RequiredArgsConstructor;

/**
 * 인증 요청을 워커 스레드에 넘기는 발행 어댑터 ({@code verification.publisher.type=async-event}).
 *
 * <p>{@link AsyncVerificationProcessor#initiateSimilarityCheck} 가 {@code @Async} 이므로 본 메서드는
 * 작업을 큐에 넣고 즉시 반환한다. 트랜잭션은 호출자에게 위임한다.
 */
// ponytail: 워커 큐는 인메모리 — 프로세스가 죽으면 진행 중 작업은 유실. 내구성이 필요하면 redis-stream 타입 사용.
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "verification.publisher.type", havingValue = "async-event")
public class VerificationAsyncEventPublisher implements VerificationMessagePublisher {

	private final AsyncVerificationProcessor asyncVerificationProcessor;

	@Override
	public void publish(MessageCommand command) {
		asyncVerificationProcessor.initiateSimilarityCheck(command);
	}
}

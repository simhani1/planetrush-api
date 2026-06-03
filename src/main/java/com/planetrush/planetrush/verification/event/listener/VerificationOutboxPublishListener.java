package com.planetrush.planetrush.verification.event.listener;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.planetrush.planetrush.outbox.OutboxPublishingHelper;
import com.planetrush.planetrush.outbox.dto.OutboxRecordCommand;
import com.planetrush.planetrush.outbox.event.OutboxEventRecordedEvent;
import com.planetrush.planetrush.verification.service.dto.MessageCommand;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Spec 005 신규 흐름의 Redis Stream 발행 AFTER_COMMIT 리스너.
 *
 * <p>{@code VerificationExternalEventRecorder.save(...)} 가 outbox row INSERT 후
 * {@link OutboxEventRecordedEvent} 를 publish 한다. 본 리스너가 AFTER_COMMIT 으로 잡아
 * {@link OutboxPublishingHelper#publishAndMarkPublished} 를 호출한다.
 *
 * <h3>트랜잭션 책임</h3>
 * <p>본 리스너 메서드 자체에 {@code @Transactional(propagation = REQUIRES_NEW)} 를 부착한다.
 * AFTER_COMMIT 시점은 메인 트랜잭션이 commit 으로 닫혔지만 동기화 매니저가 정리 중인
 * 회색지대(잔재 EntityManager 가 바인딩된 상태) 이라 기본 propagation 으로는 영속 컨텍스트가
 * 정상 작동하지 않는다 — 본 리스너가 명시적으로 새 트랜잭션 + 새 EntityManager 를 시작해
 * 회색지대를 우회한다. 헬퍼는 본 리스너의 트랜잭션을 상속받아 안전하게 dirty checking 적용.
 *
 * <h3>발행 실패 시</h3>
 * <p>{@link OutboxPublishingHelper#publishAndMarkPublished} 가 예외를 던지면 본 리스너가
 * 흡수하고 트랜잭션은 정상 commit(status 갱신 0). outbox 는 PENDING 잔존 →
 * {@code OutboxRepublisher} 가 다음 사이클에 재시도(헌법 IV — At-Least-Once).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationOutboxPublishListener {

	private final OutboxPublishingHelper outboxPublishingHelper;

	@Value("${verification.callback-url}")
	private String callbackUrl;

	@Value("${verification.threshold:0.088}")
	private String threshold;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void publishAfterCommit(OutboxEventRecordedEvent event) {
		OutboxRecordCommand command = event.command();
		// 헌법 V — requestId 만 출력. payload/콜백 URL/threshold 등 본문 평문 미출현.
		log.info("[AFTER COMMIT] requestId={}", command.requestId());
		try {
			outboxPublishingHelper.publishAndMarkPublished(new MessageCommand(
				command.requestId(),
				command.targetImg(),
				command.standardImg(),
				command.memberId(),
				command.planetId(),
				callbackUrl,
				threshold
			));
		} catch (RuntimeException e) {
			log.error("[AFTER COMMIT] publish failed requestId={}", command.requestId(), e);
			// 예외 흡수 — outbox 는 PENDING 잔존, Republisher 가 재시도.
		}
	}
}

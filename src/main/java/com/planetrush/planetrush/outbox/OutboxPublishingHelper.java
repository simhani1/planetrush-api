package com.planetrush.planetrush.outbox;

import org.springframework.stereotype.Component;

import com.planetrush.planetrush.outbox.repository.OutboxRepository;
import com.planetrush.planetrush.verification.event.publisher.VerificationMessagePublisher;
import com.planetrush.planetrush.verification.service.dto.MessageCommand;

import lombok.RequiredArgsConstructor;

/**
 * Outbox 발행 + status 갱신 도메인 행위.
 *
 * <p>Spec 005 — Phase 4 후속 fix. 두 호출 경로(AFTER_COMMIT 리스너 / OutboxRepublisher)
 * 의 트랜잭션 컨텍스트가 본질적으로 다르다(전자는 트랜잭션 없는 컨텍스트에서 진입,
 * 후자는 SKIP LOCKED 락 보유 트랜잭션). publisher 가 자체 트랜잭션을 들면 한쪽이
 * 반드시 깨지므로(REQUIRES_NEW 시 self-deadlock), 본 헬퍼는 트랜잭션 어노테이션을
 * 부착하지 않고 호출자의 트랜잭션을 그대로 사용한다.
 *
 * <h3>사용 계약</h3>
 * <p>호출 시점에 활성 트랜잭션이 필요하다. 호출자는 자기 메서드에 적절한 {@code
 * @Transactional}(또는 {@code @Transactional(propagation = REQUIRES_NEW)} — AFTER_COMMIT
 * 리스너의 경우) 을 부착해야 한다. 본 헬퍼는 호출자의 영속 컨텍스트에서 OutboxEvent 를
 * 조회·변경하며, 트랜잭션 commit 시 dirty checking 에 의해 status UPDATE 가 flush 된다.
 */
@Component
@RequiredArgsConstructor
public class OutboxPublishingHelper {

	private final VerificationMessagePublisher messagePublisher;
	private final OutboxRepository outboxRepository;

	/**
	 * Redis Stream 으로 발행 후 outbox status 를 PUBLISHED 로 전이한다.
	 *
	 * <p>발행이 예외 없이 완료된 경우에만 status 갱신을 시도한다 — 발행 실패 시 예외가
	 * 호출자에게 전파되어 호출자의 트랜잭션이 롤백되도록 한다(outbox 는 PENDING 잔존,
	 * Republisher 가 다음 사이클에 재시도).
	 *
	 * @param command 발행할 메시지 + outbox event id
	 */
	public void publishAndMarkPublished(MessageCommand command) {
		messagePublisher.publish(command);
		outboxRepository.findById(command.eventId())
			.ifPresent(event -> event.published());
	}
}

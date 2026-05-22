package com.planetrush.planetrush.outbox.republisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import com.planetrush.planetrush.IntegrationTest;

/**
 * Spec 002 US3 / SC-004 인수 기준 검증.
 *
 * <p>SC-004: `test` 프로필에서 폴러의 자동 스케줄 트리거가 비활성화된다.
 *
 * <p>로직 빈({@link OutboxRepublisher})과 스케줄 트리거({@link OutboxRepublisherScheduler})를
 * 분리한 설계에서, `test` 프로필은 `outbox.republisher.enabled=false`라
 * 트리거 빈만 부재하고 로직 빈은 존재한다 — 통합 테스트가 폴러 사이클을
 * 직접 호출해 결정론적으로 검증할 수 있도록.
 */
class OutboxRepublisherProfileTest extends IntegrationTest {

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("SC-004: test 프로필에서 OutboxRepublisherScheduler(자동 폴링 트리거) 빈이 부재한다")
	void schedulerBeanAbsentUnderTestProfile() {
		assertThatThrownBy(() -> context.getBean(OutboxRepublisherScheduler.class))
				.isInstanceOf(NoSuchBeanDefinitionException.class);
	}

	@Test
	@DisplayName("OutboxRepublisher 로직 빈은 존재한다 — 통합 테스트의 직접 호출 대상")
	void republisherLogicBeanPresentForDirectInvocation() {
		assertThat(context.getBeanProvider(OutboxRepublisher.class).getIfAvailable())
				.isNotNull();
	}
}

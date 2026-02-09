package com.planetrush.planetrush.core.aop;

import javax.sql.DataSource;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Aspect
@Component
@Profile("test")
@RequiredArgsConstructor
public class TransactionContextLoggingAspect {

	private final DataSource dataSource;

	@Before("execution(* com.planetrush.planetrush.verification.service.VerificationServiceImpl.verifyTodayChallenge(..))")
	public void logVerifyTodayContext() {
		logTransactionContext("verify-today");
	}

	@Before("execution(* com.planetrush.planetrush.outbox.VerificationExternalEventRecorder.save(..))")
	public void logOutboxRecordContext() {
		logTransactionContext("outbox-record");
	}

	private void logTransactionContext(String action) {
		boolean active = TransactionSynchronizationManager.isActualTransactionActive();
		boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
		String name = TransactionSynchronizationManager.getCurrentTransactionName();
		Object resource = TransactionSynchronizationManager.getResource(dataSource);
		Integer resourceId = resource != null ? System.identityHashCode(resource) : null;
		Integer connectionId = (resource instanceof ConnectionHolder holder)
			? System.identityHashCode(holder.getConnection())
			: null;
		log.info("TxContext action={} active={} readOnly={} name={} resourceId={} connectionId={}",
			action, active, readOnly, name, resourceId, connectionId);
	}
}

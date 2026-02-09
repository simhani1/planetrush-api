package com.planetrush.planetrush.outbox.exception;

public class NoOutboxEventException extends RuntimeException {
	public NoOutboxEventException() {
	}

	public NoOutboxEventException(String message) {
		super(message);
	}

	public NoOutboxEventException(String message, Throwable cause) {
		super(message, cause);
	}

	public NoOutboxEventException(Throwable cause) {
		super(cause);
	}
}

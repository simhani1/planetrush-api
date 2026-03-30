package com.planetrush.planetrush.s3presign.exception;

public class S3Exception extends RuntimeException {

	public S3Exception() {
	}

	public S3Exception(String message) {
		super(message);
	}

	public S3Exception(String message, Throwable cause) {
		super(message, cause);
	}

	public S3Exception(Throwable cause) {
		super(cause);
	}

	public S3Exception(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}
}

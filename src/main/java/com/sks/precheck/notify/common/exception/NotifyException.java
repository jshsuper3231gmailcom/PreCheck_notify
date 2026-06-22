package com.sks.precheck.notify.common.exception;

public class NotifyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotifyException(String message) {
        super(message);
    }

    public NotifyException(String message, Throwable cause) {
        super(message, cause);
    }
}

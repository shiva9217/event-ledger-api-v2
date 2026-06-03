package com.eventledger.gateway.exception;

/**
 * Thrown when the downstream account-service cannot be reached
 * (circuit open, timeout, connection error, or 5xx). Maps to HTTP 503.
 */
public class ServiceUnavailableException extends RuntimeException {
    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

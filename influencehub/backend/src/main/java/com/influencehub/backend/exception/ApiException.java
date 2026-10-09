package com.influencehub.backend.exception;

import org.springframework.http.HttpStatus;

/** Thrown by the service layer to signal a client-visible error (status + message). */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}

package com.influencehub.backend.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates service-layer errors into HTTP responses. Error bodies stay plain strings,
 * matching what the existing controllers return and what the React client displays.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> handleApi(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(e.getMessage());
    }

    /** A DB unique constraint caught a concurrent duplicate that slipped past an app-level check. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<String> handleConflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(409).body("Conflict: this record already exists or was modified concurrently.");
    }
}

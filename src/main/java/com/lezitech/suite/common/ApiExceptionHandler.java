package com.lezitech.suite.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ErrorAlerts alerts;

    public ApiExceptionHandler(ErrorAlerts alerts) {
        this.alerts = alerts;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(e.extra());
        body.put("code", e.code());
        body.put("message", e.getMessage());
        return ResponseEntity.status(e.status()).body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> integrity(DataIntegrityViolationException e) {
        log.warn("Integrity violation: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "CONFLICT", "message", "That clashes with an existing record (duplicate or still in use)."));
    }

    @ExceptionHandler({org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, Object>> badInput(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", "Something in the request is missing or not valid"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e, jakarta.servlet.http.HttpServletRequest request) {
        log.error("Unexpected error", e);
        // The path can carry a payment secret or a customer's one-off link, and these alerts are emailed: a secret
        // reaching one leaves the server altogether. Mask before recording.
        alerts.record(e, request.getMethod() + " "
                + com.lezitech.suite.config.RequestLogFilter.maskSecrets(request.getRequestURI()));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "SERVER_ERROR", "message", "Something went wrong on our side. Please try again."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> illegal(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", String.valueOf(e.getMessage())));
    }
}

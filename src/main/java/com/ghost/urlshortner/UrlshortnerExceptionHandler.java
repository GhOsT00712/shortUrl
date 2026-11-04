package com.ghost.urlshortner;

import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.mongodb.MongoException;

/** Keeps service failures explicit and avoids exposing database internals to clients. */
@RestControllerAdvice
public class UrlshortnerExceptionHandler {
    /** Database failures have uncertain results; they must not become cached 404 responses. */
    @ExceptionHandler(MongoException.class)
    ResponseEntity<Map<String, String>> unavailable(MongoException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("error", "Database temporarily unavailable"));
    }

    /** Preserves the service's chosen status and public reason without caching the error. */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(Map.of("error", exception.getReason() == null ? "Request failed" : exception.getReason()));
    }
}

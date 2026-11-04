package com.ghost.urlshortner;

import java.net.URI;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ghost.urlshortner.models.UrlshortnerCreateRequest;
import com.ghost.urlshortner.models.UrlshortnerCreateResponse;

import jakarta.validation.Valid;

/** Translates validated HTTP requests and service results into the public API. */
@RestController
@RequestMapping("/api/v1")
public class UrlshortnerController {
    private final UrlshortnerService service;

    public UrlshortnerController(UrlshortnerService service) {
        this.service = service;
    }

    /**
     * Creates or reuses a mapping and returns its code/timestamps with HTTP 201.
     * The relative Location header points to this service's redirect endpoint.
     */
    @PostMapping("/urls")
    public ResponseEntity<UrlshortnerCreateResponse> createShortCode(
            @Valid @RequestBody UrlshortnerCreateRequest request) {
        UrlshortnerCreateResponse response = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/" + response.shortCode()))
                .cacheControl(CacheControl.noStore()).body(response);
    }

    /**
     * Sends a temporary redirect only after service-level expiry validation.
     * no-store keeps a browser from caching this response past the link's expiry.
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        return ResponseEntity.status(HttpStatus.FOUND).location(service.resolve(shortCode))
                .cacheControl(CacheControl.noStore()).build();
    }
}

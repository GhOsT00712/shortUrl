package com.ghost.urlshortner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import com.ghost.urlshortner.models.UrlshortnerCreateResponse;
import com.mongodb.MongoTimeoutException;

@WebMvcTest(UrlshortnerController.class)
class UrlshortnerControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UrlshortnerService service;

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com/path?key=value", "http://example.com"})
    void validRequestCreatesShortUrl(String url) throws Exception {
        Date expiry = Date.from(Instant.now().plus(1, ChronoUnit.DAYS));
        when(service.create(any())).thenReturn(new UrlshortnerCreateResponse("Abc123", new Date(), expiry));
        mockMvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"url":"%s","expiryAt":"%s"}
                        """.formatted(url, Instant.now().plus(1, ChronoUnit.DAYS))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/Abc123"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.shortCode").value("Abc123"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.expiryAt").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"url\":\"\",\"expiryAt\":\"%s\"}",
            "{\"url\":\"not-a-url\",\"expiryAt\":\"%s\"}",
            "{\"url\":\"ftp://example.com\",\"expiryAt\":\"%s\"}",
            "{\"url\":\"https://example.com\"}",
            "{\"url\":\"https://example.com\",\"expiryAt\":\"2000-01-01T00:00:00Z\"}",
            "{\"url\":\"https://example.com\",\"expiryAt\":\"not-a-date\"}",
            "{"
    })
    void invalidRequestReturnsBadRequest(String body) throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.formatted(Instant.now().plus(1, ChronoUnit.DAYS))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oversizedUrlIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"url":"https://example.com/%s","expiryAt":"%s"}
                        """.formatted("a".repeat(8192), Instant.now().plus(1, ChronoUnit.DAYS))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void redirectsWithoutAllowingCaching() throws Exception {
        when(service.resolve("Abc123")).thenReturn(URI.create("https://example.com/path?x=1"));
        mockMvc.perform(get("/api/v1/Abc123"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/path?x=1"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 410})
    void missingAndExpiredLinksAreNotCached(int statusCode) throws Exception {
        when(service.resolve("Abc123")).thenThrow(new ResponseStatusException(HttpStatus.valueOf(statusCode)));
        mockMvc.perform(get("/api/v1/Abc123"))
                .andExpect(status().is(statusCode))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void databaseFailureIsNotReportedAsMissing() throws Exception {
        when(service.resolve("Abc123")).thenThrow(new MongoTimeoutException("sensitive internal connection details"));
        mockMvc.perform(get("/api/v1/Abc123"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error").value("Database temporarily unavailable"));
    }
}

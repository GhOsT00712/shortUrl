package com.ghost.urlshortner;

import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.ghost.urlshortner.models.UrlshortnerCreateRequest;
import com.ghost.urlshortner.models.UrlshortnerCreateResponse;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class UrlshortnerMongoIntegrationTests {
    private static MongoClient client;
    private MongoDatabase database;
    private UrlshortnerService service;

    @BeforeAll
    static void connect() {
        client = MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(System.getenv("MONGODB_TEST_URI")))
                .timeout(5, TimeUnit.SECONDS).build());
    }

    @BeforeEach
    void setUp() {
        database = client.getDatabase("shorturl_test_" + UUID.randomUUID().toString().replace("-", ""));
        service = new UrlshortnerService(client, database.getName());
        service.createIndexes();
    }

    @AfterEach
    void dropTestDatabase() {
        database.drop();
    }

    @AfterAll
    static void close() {
        client.close();
    }

    @Test
    void storesBsonDatesAndCreatesRequiredIndexes() {
        var request = request("https://example.com/path?query=one");
        var result = service.create(request);
        assertTrue(result.shortCode().matches("[0-9A-Za-z]{6}"));
        assertEquals(result, service.create(request));
        assertEquals(request.url(), service.resolve(result.shortCode()).toString());

        var mappings = database.getCollection("urlMappings");
        Document stored = mappings.find(eq("_id", result.shortCode())).first();
        assertNotNull(stored);
        assertEquals(request.expiryAt(), stored.getDate("expiryAt"));
        assertInstanceOf(Date.class, stored.get("createdAt"));
        var indexes = mappings.listIndexes().into(new ArrayList<>());
        assertTrue(indexes.stream().anyMatch(index -> new Document("requestHash", 1).equals(index.get("key"))
                && Boolean.TRUE.equals(index.getBoolean("unique"))));
        assertTrue(indexes.stream().anyMatch(index -> new Document("expiryAt", 1).equals(index.get("key"))
                && ((Number) index.get("expireAfterSeconds")).longValue() == 0));
        assertEquals(new Document("_id", result.shortCode()), database.getCollection("usedCodes").find().first());
    }

    @Test
    @Timeout(30)
    void concurrentIdenticalRequestsShareOneMapping() throws Exception {
        var request = request("https://example.com/concurrent");
        List<Callable<UrlshortnerCreateResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            calls.add(() -> service.create(request));
        }
        try (var executor = Executors.newFixedThreadPool(6)) {
            var responses = executor.invokeAll(calls);
            String code = responses.getFirst().get().shortCode();
            for (var response : responses) {
                assertEquals(code, response.get().shortCode());
            }
        }
        assertEquals(1, database.getCollection("urlMappings").countDocuments());
    }

    @Test
    void deletedMappingDoesNotMakeItsCodeReusable() {
        var originalService = new UrlshortnerService(database, Clock.systemUTC(), () -> "Old123");
        originalService.create(request("https://example.com/old"));
        database.getCollection("urlMappings").deleteOne(eq("_id", "Old123"));

        AtomicInteger attempts = new AtomicInteger();
        var nextService = new UrlshortnerService(database, Clock.systemUTC(),
                () -> attempts.getAndIncrement() == 0 ? "Old123" : "New123");
        assertEquals("New123", nextService.create(request("https://example.com/new")).shortCode());
        assertNotNull(database.getCollection("usedCodes").find(eq("_id", "Old123")).first());
        var missing = assertThrows(ResponseStatusException.class, () -> service.resolve("Old123"));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    }

    @Test
    void expiryIsEnforcedEvenBeforeMongoRemovesTheRecord() {
        var request = request("https://example.com/expiry");
        var created = service.create(request);
        var afterExpiry = new UrlshortnerService(database,
                Clock.fixed(request.expiryAt().toInstant(), ZoneOffset.UTC), () -> "Unused");
        var expired = assertThrows(ResponseStatusException.class, () -> afterExpiry.resolve(created.shortCode()));
        assertEquals(HttpStatus.GONE, expired.getStatusCode());
        assertNotNull(database.getCollection("urlMappings").find(eq("_id", created.shortCode())).first());
    }

    @Test
    @Timeout(90)
    void nativeTtlDeletesMappingsButKeepsReservations() throws InterruptedException {
        var created = service.create(request("https://example.com/ttl"));
        var mappings = database.getCollection("urlMappings");
        mappings.updateOne(eq("_id", created.shortCode()),
                new Document("$set", new Document("expiryAt", Date.from(Instant.now().minusSeconds(60)))));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
        while (mappings.find(eq("_id", created.shortCode())).first() != null && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        assertNull(mappings.find(eq("_id", created.shortCode())).first());
        assertNotNull(database.getCollection("usedCodes").find(eq("_id", created.shortCode())).first());
    }

    private static UrlshortnerCreateRequest request(String url) {
        return new UrlshortnerCreateRequest(url, Date.from(Instant.now().plusSeconds(3600)));
    }
}

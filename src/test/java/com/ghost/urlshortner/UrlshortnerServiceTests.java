package com.ghost.urlshortner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.ghost.urlshortner.models.UrlshortnerCreateRequest;
import com.mongodb.MongoTimeoutException;
import com.mongodb.MongoWriteException;
import com.mongodb.ReadPreference;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;

class UrlshortnerServiceTests {
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private MongoDatabase database;
    private MongoCollection<Document> mappings;
    private MongoCollection<Document> replicas;
    private MongoCollection<Document> usedCodes;
    private FindIterable<Document> primaryFind;
    private FindIterable<Document> replicaFind;
    private UrlshortnerService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        database = mock(MongoDatabase.class);
        mappings = mock(MongoCollection.class, RETURNS_SELF);
        replicas = mock(MongoCollection.class);
        usedCodes = mock(MongoCollection.class, RETURNS_SELF);
        primaryFind = mock(FindIterable.class);
        replicaFind = mock(FindIterable.class);
        when(database.getCollection("urlMappings")).thenReturn(mappings);
        when(database.getCollection("usedCodes")).thenReturn(usedCodes);
        when(mappings.withReadPreference(ReadPreference.secondaryPreferred())).thenReturn(replicas);
        when(mappings.find(any(Bson.class))).thenReturn(primaryFind);
        when(replicas.find(any(Bson.class))).thenReturn(replicaFind);
        service = new UrlshortnerService(database, CLOCK, () -> "Abc123");
    }

    @Test
    void reservesCodeBeforeCreatingImmutableMapping() {
        var response = service.create(request());
        var order = inOrder(usedCodes, mappings);
        order.verify(usedCodes).insertOne(new Document("_id", "Abc123"));
        var document = ArgumentCaptor.forClass(Document.class);
        order.verify(mappings).insertOne(document.capture());
        assertEquals("Abc123", response.shortCode());
        assertEquals(Date.from(NOW), response.createdAt());
        assertEquals(request().expiryAt(), response.expiryAt());
        assertEquals(request().url(), document.getValue().getString("url"));
        assertEquals(64, document.getValue().getString("requestHash").length());
    }

    @Test
    void identicalRequestReturnsExistingMappingWithoutAllocating() {
        when(primaryFind.first()).thenReturn(mapping());
        assertEquals("Abc123", service.create(request()).shortCode());
        verify(usedCodes, never()).insertOne(any());
        verify(mappings, never()).insertOne(any());
    }

    @Test
    void retriesCodeCollisionWithAnotherCandidate() {
        AtomicInteger count = new AtomicInteger();
        service = new UrlshortnerService(database, CLOCK, () -> count.getAndIncrement() == 0 ? "Old123" : "New123");
        doThrow(duplicate()).doReturn(null).when(usedCodes).insertOne(any(Document.class));
        assertEquals("New123", service.create(request()).shortCode());
        verify(usedCodes, times(2)).insertOne(any());
    }

    @Test
    void collisionRetriesAreBounded() {
        doThrow(duplicate()).when(usedCodes).insertOne(any(Document.class));
        var error = assertThrows(ResponseStatusException.class, () -> service.create(request()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        verify(usedCodes, times(10)).insertOne(any());
        verify(mappings, never()).insertOne(any());
    }

    @Test
    void concurrentIdenticalInsertReturnsTheWinningMapping() {
        when(primaryFind.first()).thenReturn(null, mapping());
        doThrow(duplicate()).when(mappings).insertOne(any(Document.class));
        assertEquals("Abc123", service.create(request()).shortCode());
    }

    @Test
    void replicaHitDoesNotReadPrimary() {
        when(replicaFind.first()).thenReturn(mapping());
        assertEquals(URI.create(request().url()), service.resolve("Abc123"));
        verify(mappings, never()).find(any(Bson.class));
    }

    @Test
    void replicaMissFallsBackToPrimary() {
        when(primaryFind.first()).thenReturn(mapping());
        assertEquals(URI.create(request().url()), service.resolve("Abc123"));
        verify(replicas).find(any(Bson.class));
        verify(mappings).find(any(Bson.class));
    }

    @Test
    void missingOnBothReadsReturns404() {
        var error = assertThrows(ResponseStatusException.class, () -> service.resolve("Abc123"));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void expiryIsEnforcedAtTheExactBoundaryBeforeTtlCleanup() {
        when(replicaFind.first()).thenReturn(mapping().append("expiryAt", Date.from(NOW)));
        var error = assertThrows(ResponseStatusException.class, () -> service.resolve("Abc123"));
        assertEquals(HttpStatus.GONE, error.getStatusCode());
    }

    @Test
    void failedPrimaryLookupIsNeverReportedAs404() {
        when(primaryFind.first()).thenThrow(new MongoTimeoutException("unavailable"));
        assertThrows(MongoTimeoutException.class, () -> service.resolve("Abc123"));
    }

    @Test
    void failedReservationDoesNotCreateAMapping() {
        doThrow(new MongoTimeoutException("unavailable")).when(usedCodes).insertOne(any());
        assertThrows(MongoTimeoutException.class, () -> service.create(request()));
        verify(mappings, never()).insertOne(any());
    }

    @Test
    void invalidCodeDoesNotReachDatabase() {
        assertThrows(ResponseStatusException.class, () -> service.resolve("invalid-code"));
        verify(replicas, never()).find(any(Bson.class));
        verify(mappings, never()).find(any(Bson.class));
    }

    @Test
    void rejectsInvalidDestinationAndExpiredCreation() {
        assertThrows(ResponseStatusException.class, () -> service.create(new UrlshortnerCreateRequest("javascript:alert(1)", request().expiryAt())));
        assertThrows(ResponseStatusException.class, () -> service.create(new UrlshortnerCreateRequest("https://example.com/\r\nInjected:yes", request().expiryAt())));
        assertThrows(ResponseStatusException.class, () -> service.create(new UrlshortnerCreateRequest("https://example.com/%broken", request().expiryAt())));
        assertThrows(ResponseStatusException.class, () -> service.create(new UrlshortnerCreateRequest(request().url(), Date.from(NOW))));
        verify(mappings, never()).insertOne(any());
    }

    @Test
    void configuresUniqueRequestAndAbsoluteExpiryIndexes() {
        service.createIndexes();
        var options = ArgumentCaptor.forClass(IndexOptions.class);
        verify(mappings, times(2)).createIndex(any(Bson.class), options.capture());
        assertTrue(options.getAllValues().get(0).isUnique());
        assertEquals(0L, options.getAllValues().get(1).getExpireAfter(TimeUnit.SECONDS));
    }

    private static UrlshortnerCreateRequest request() {
        return new UrlshortnerCreateRequest("https://example.com/path", Date.from(NOW.plusSeconds(3600)));
    }

    private static Document mapping() {
        return new Document("_id", "Abc123").append("url", request().url())
                .append("createdAt", Date.from(NOW)).append("expiryAt", request().expiryAt());
    }

    @SuppressWarnings("deprecation")
    private static MongoWriteException duplicate() {
        return new MongoWriteException(new WriteError(11000, "duplicate", new BsonDocument()), new ServerAddress());
    }
}

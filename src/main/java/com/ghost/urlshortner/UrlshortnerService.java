package com.ghost.urlshortner;

import static com.mongodb.client.model.Filters.eq;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Date;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.ghost.urlshortner.models.UrlshortnerCreateRequest;
import com.ghost.urlshortner.models.UrlshortnerCreateResponse;
import com.mongodb.ErrorCategory;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import jakarta.annotation.PostConstruct;

/**
 * Creates immutable short-link mappings and resolves them using MongoDB.
 * URL mappings expire, but code reservations do not: an old shared link must never
 * be reassigned to a different destination. MongoDB failures propagate to the HTTP
 * exception handler rather than being interpreted as missing links.
 */
@Service
public class UrlshortnerService {
    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_CODE_ATTEMPTS = 10;

    private final MongoCollection<Document> mappings;
    private final MongoCollection<Document> replicaMappings;
    private final MongoCollection<Document> usedCodes;
    private final Clock clock;
    private final Supplier<String> nextCode;

    /** Uses Spring's shared MongoDB client, the UTC clock, and secure random codes. */
    @Autowired
    public UrlshortnerService(MongoClient client, @Value("${spring.mongodb.database:shorturl}") String databaseName) {
        this(client.getDatabase(databaseName), Clock.systemUTC(), UrlshortnerService::randomCode);
    }

    /**
     * Configures collection handles for primary reads, preferred-secondary reads,
     * and majority-acknowledged writes. The two mapping handles access the same
     * collection with different routing policies, not separate collections.
     * A supplied clock and code generator make expiry and collision tests repeatable.
     */
    UrlshortnerService(MongoDatabase database, Clock clock, Supplier<String> nextCode) {
        this.mappings = database.getCollection("urlMappings")
                .withWriteConcern(WriteConcern.MAJORITY.withWTimeout(2, TimeUnit.SECONDS))
                .withReadConcern(ReadConcern.MAJORITY)
                .withReadPreference(ReadPreference.primary());
        this.replicaMappings = mappings.withReadPreference(ReadPreference.secondaryPreferred());
        this.usedCodes = database.getCollection("usedCodes")
                .withWriteConcern(WriteConcern.MAJORITY.withWTimeout(2, TimeUnit.SECONDS));
        this.clock = clock;
        this.nextCode = nextCode;
    }

    /**
     * Creates the request-deduplication and absolute-expiration indexes at startup.
     * A zero-second TTL on expiryAt honors each request's date; deletion can lag,
     * so resolve also checks expiry. Failure prevents startup with missing indexes.
     * MongoDB's existing unique _id index protects the permanent code reservations.
     */
    @PostConstruct
    void createIndexes() {
        mappings.createIndex(Indexes.ascending("requestHash"), new IndexOptions().unique(true));
        mappings.createIndex(Indexes.ascending("expiryAt"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
    }

    /**
     * Returns an existing mapping for the exact URL and expiration, or creates one.
     * Each candidate code is permanently reserved before inserting its mapping.
     * Unique indexes settle concurrent requests; a losing identical request reads
     * the winning mapping from the primary. Only duplicate-key errors are retried.
     *
     * <p>The two inserts are not a transaction. A failure after reservation can burn
     * an unused code; keeping it reserved is safer than reusing an uncertain write.
     * The retry limit bounds candidate attempts, not total HTTP request duration.</p>
     *
     * @param request non-null request containing an HTTP(S) URL and future expiry
     * @return the allocated code and timestamps, also reused for identical requests
     * @throws ResponseStatusException for invalid input (400) or exhausted attempts (503)
     * @throws com.mongodb.MongoException when a database operation fails
     */
    public UrlshortnerCreateResponse create(UrlshortnerCreateRequest request) {
        validateDestination(request.url());
        if (request.expiryAt() == null || !request.expiryAt().toInstant().isAfter(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expiryAt must be in the future");
        }

        String requestHash = requestHash(request);
        Document existing = mappings.find(eq("requestHash", requestHash)).first();
        if (existing != null) {
            return response(existing);
        }

        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = nextCode.get();
            try {
                // Reservations never expire: an old link must never point to a new destination.
                usedCodes.insertOne(new Document("_id", code));
            } catch (MongoWriteException exception) {
                if (isDuplicate(exception)) {
                    continue;
                }
                throw exception;
            }

            Document mapping = new Document("_id", code)
                    .append("url", request.url())
                    .append("createdAt", Date.from(clock.instant()))
                    .append("expiryAt", new Date(request.expiryAt().getTime()))
                    .append("requestHash", requestHash);
            try {
                mappings.insertOne(mapping);
                return response(mapping);
            } catch (MongoWriteException exception) {
                if (!isDuplicate(exception)) {
                    throw exception;
                }
                // A concurrent identical request may have inserted a different code first.
                existing = mappings.find(eq("requestHash", requestHash)).first();
                if (existing != null) {
                    return response(existing);
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Unable to allocate a short code");
    }

    /**
     * Looks up an immutable mapping, then checks its expiry before returning its URL.
     * Prefers a secondary and checks the primary on a successful lookup with no result.
     * The driver may use the primary directly when no secondary is available.
     * A database exception propagates; it is not a miss that triggers this fallback.
     *
     * @param shortCode six case-sensitive Base62 characters
     * @return the destination for the controller's Location header
     * @throws ResponseStatusException for a malformed/missing code (404), or an expired
     *         mapping still present before TTL cleanup (410)
     * @throws com.mongodb.MongoException when a database operation fails
     */
    public URI resolve(String shortCode) {
        if (shortCode == null || !shortCode.matches("[0-9A-Za-z]{6}")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Short URL not found");
        }
        Document mapping = replicaMappings.find(eq("_id", shortCode)).first();
        if (mapping == null) {
            mapping = mappings.find(eq("_id", shortCode)).first();
        }
        if (mapping == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Short URL not found");
        }
        if (!mapping.getDate("expiryAt").toInstant().isAfter(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Short URL has expired");
        }
        return URI.create(mapping.getString("url"));
    }

    /**
     * Protects direct service calls as well as HTTP calls: require a parseable HTTP(S)
     * URL with a host, at most 8192 characters, and no control characters in a redirect.
     * Validation does not fetch the destination or check that the remote site exists.
     */
    private static void validateDestination(String url) {
        try {
            URI destination = URI.create(url);
            if (url.length() > 8192 || url.chars().anyMatch(Character::isISOControl)
                    || destination.getHost() == null
                    || !("http".equalsIgnoreCase(destination.getScheme()) || "https".equalsIgnoreCase(destination.getScheme()))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url must be a valid HTTP or HTTPS URL");
        }
    }

    /** Identifies uniqueness conflicts without treating other write failures as collisions. */
    private static boolean isDuplicate(MongoWriteException exception) {
        return exception.getError().getCategory() == ErrorCategory.DUPLICATE_KEY;
    }

    /** Maps stored BSON dates and _id to the existing API response contract. */
    private static UrlshortnerCreateResponse response(Document mapping) {
        return new UrlshortnerCreateResponse(mapping.getString("_id"), mapping.getDate("createdAt"), mapping.getDate("expiryAt"));
    }

    /**
     * Fingerprints the exact URL text and epoch-millisecond expiry for a fixed-size
     * unique index. Newlines are rejected in URLs, so the separator is unambiguous.
     * This is a deduplication key, not the short code; changing expiry changes the key.
     */
    private static String requestHash(UrlshortnerCreateRequest request) {
        try {
            byte[] bytes = (request.url() + "\n" + request.expiryAt().getTime()).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Samples six Base62 characters uniformly. Randomness reduces collisions but
     * does not prove uniqueness; the permanent usedCodes insert is the authority.
     */
    private static String randomCode() {
        char[] code = new char[6];
        for (int i = 0; i < code.length; i++) {
            code[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }
}

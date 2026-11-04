# URL shortener

Java 21 / Spring Boot service using the MongoDB Java driver included in the existing starter.

See [the service walkthrough and Ponytail review](docs/service-design.md) for the important methods, the repository-class decision, and the purpose and cost of `usedCodes`.

## API

`POST /api/v1/urls`

```json
{
  "url": "https://example.com/a-long-path",
  "expiryAt": "2027-01-01T00:00:00Z"
}
```

Returns `201` with the existing response contract: `shortCode`, `createdAt`, and `expiryAt`, and a `Location` header pointing to `/api/v1/{shortCode}`. Open that path on the same service to use the link. Only HTTP/HTTPS destinations and future expiration times are accepted. Identical URL strings and expiration instants (millisecond precision) reuse the same active mapping. Different expiration times create separate mappings; this is request deduplication, not a general-purpose idempotency-key API.

`GET /api/v1/{shortCode}`

- `302` with the destination in `Location` for a live mapping.
- `410` for an expired mapping still awaiting MongoDB cleanup.
- `404` for an absent or malformed code, including records already removed by TTL.
- `503` when the database operation cannot complete. Database errors are not treated as missing links.

Redirects and service error responses use `Cache-Control: no-store`.

## Storage and consistency

- `urlMappings`: `_id` is a six-character random Base62 code; documents also hold `url`, `createdAt`, `expiryAt`, and `requestHash`.
- A unique `requestHash` index handles concurrent identical creation requests.
- A TTL index on `expiryAt` with `expireAfterSeconds: 0` removes expired mappings in the background. The service checks expiry on every redirect. Unlike a fixed TTL on `createdAt`, this honors each request's chosen expiration time.
- `usedCodes`: permanent documents containing only `_id` reserve every allocated code. This collection deliberately has no TTL, preventing old shared links from being reassigned after cleanup.
- Reads prefer a secondary; a missing mapping is checked again on the primary. If no secondary is available, the driver's `secondaryPreferred` policy can read the primary directly.
- Writes use majority acknowledgment; primary lookups use majority read concern. No browser consistency token is required for immutable mappings that are never reused.

Code reservation precedes mapping insertion. A crash or a racing duplicate request can leave a permanently reserved but unused code. The extra write and growing reservation collection prevent reassignment without a multi-document transaction; their cost is significant at sustained high traffic. Keep the reservations when restoring or moving mapping data. Six-character codes provide a finite namespace, and permanent reservations grow beyond the URL retention window.

Indexes are created at application startup. The application therefore needs a reachable MongoDB deployment and index-creation privileges to start. No authentication, distributed rate limiting, or production deployment configuration is added by this service implementation.

## Run

For the complete local Docker setup (NGINX, three stateless API instances, and three MongoDB replica-set members), run:

```powershell
docker compose up --build -d
docker compose logs -f api
```

NGINX listens at `http://localhost:8080` and balances requests across the three API instances using round-robin routing, without sticky sessions. All instances share MongoDB; they do not replicate application memory. NGINX refreshes Docker DNS as instances are replaced and starts after all three APIs pass their health checks. The Docker build runs the unit/controller tests; MongoDB integration tests remain opt-in. The first build downloads the container images and Java dependencies. MongoDB initialization is automatic and runs before the APIs start. `mongo-init` exiting with code `0` is expected.

See [manual testing with Docker Compose](docs/manual-testing.md) for copyable requests, load-balancing checks, database inspection, expiry and failover checks, and shutdown commands. This setup is for local development: MongoDB has no authentication; API and database containers have no published host ports. Only NGINX binds to `127.0.0.1:8080`. There is one NGINX container, and everything runs on this PC, so this is not machine-level high availability.

### Running Java outside Docker

Set `MONGODB_URI` to your MongoDB connection string and optionally `MONGODB_DATABASE` (default `shorturl`). Spring Boot 4 uses `spring.mongodb.uri`; this project maps the environment variables in `application.properties`.

For an application running inside the same Docker Compose network as `mongo1`, `mongo2`, and `mongo3`, use:

```text
mongodb://mongo1:27017,mongo2:27017,mongo3:27017/?replicaSet=rs0&serverSelectionTimeoutMS=2000&connectTimeoutMS=2000&timeoutMS=3000
```

Those service names resolve inside Docker, not from a Windows-hosted Java process. To run Java on Windows, use a replica set whose advertised member addresses are reachable from Windows.

```powershell
$env:MONGODB_URI = '<connection string reachable from the application>'
.\mvnw.cmd spring-boot:run
```

When overriding the URI, include suitable timeouts; otherwise the driver's defaults apply. Driver timeouts bound individual operations, not a complete multi-operation HTTP request. The design's latency and throughput targets still require load testing.

## Tests

For the design's 10K reads/s + 1K writes/s benchmark, see [load testing with k6](docs/load-testing.md). The Docker-based runner captures latency thresholds, offered/achieved traffic, container resources, and MongoDB counters.

Unit and controller tests do not require MongoDB:

```powershell
.\mvnw.cmd test
```

To additionally enable integration tests against a disposable MongoDB replica set:

```powershell
$env:MONGODB_TEST_URI = '<test replica-set connection string>'
.\mvnw.cmd test
Remove-Item Env:\MONGODB_TEST_URI
```

The integration tests use uniquely named databases and clean them up. Do not use production credentials. These tests verify functional behavior, not the 10K-read/1K-write throughput target or multi-AZ failover.

# Service walkthrough and implementation review

The HTTP controller handles request validation and response formatting. `UrlshortnerService` owns creation, deduplication, code reservation, lookup, and expiry checks. It uses the MongoDB Java driver directly. `UrlshortnerExceptionHandler` translates service errors into HTTP responses.

## Important methods

| Method | Responsibility and reason |
| --- | --- |
| Service constructor | Creates primary and preferred-secondary handles for the same `urlMappings` collection, plus a handle for `usedCodes`. Applies majority write concern and mapping read concern. The test constructor accepts a clock and code generator to reproduce expiry boundaries and collisions. |
| `createIndexes()` | Runs at startup. Makes `requestHash` unique and creates an absolute TTL index on `expiryAt`. Startup fails if the required indexes cannot be established. |
| `create(request)` | Validates the destination and expiry, returns an identical existing request if found, otherwise reserves a random code and inserts the immutable mapping. Handles uniqueness conflicts and concurrent duplicate requests. |
| `resolve(shortCode)` | Rejects malformed codes, looks up a preferred secondary, checks the primary on a miss, and enforces expiry before returning the destination. Database errors propagate instead of becoming 404s. |
| `validateDestination(url)` | Enforces HTTP(S), a host, a length limit, and no control characters. It protects direct service calls in addition to controller validation; it does not contact the destination. |
| `requestHash(request)` | SHA-256 of exact URL text, a newline separator, and the expiry's epoch milliseconds. This fixed-size database key identifies duplicate requests; it does not generate short codes. |
| `randomCode()` | Produces a candidate of six uniformly sampled Base62 characters. The database reservation, not probability, decides whether that code is available. |
| `isDuplicate(exception)` | Distinguishes uniqueness conflicts from other database failures so only conflicts trigger another candidate attempt. |
| `response(mapping)` | Converts the MongoDB document into the existing response record without exposing the destination or internal hash. |
| Controller `createShortCode(request)` | Returns HTTP 201, the code/timestamps, and a relative Location pointing to `/api/v1/{shortCode}`. |
| Controller `redirect(shortCode)` | Returns HTTP 302 with the resolved destination and `Cache-Control: no-store`. |
| Handler `unavailable(exception)` | Converts MongoDB exceptions to a generic, noncached 503 without leaking connection details. |
| Handler `status(exception)` | Preserves explicitly chosen service statuses such as 400, 404, 410, and allocation-exhaustion 503. |

## Creation flow

1. Validate the URL and confirm the expiry is in the future.
2. Compute `requestHash` and read the primary for an identical request.
3. If found, return its original code and timestamps. No new code is allocated.
4. Otherwise generate a candidate and insert `{ "_id": "Abc123" }` into `usedCodes`.
5. If that reservation conflicts, generate another candidate. The retry limit is ten candidates.
6. Insert the destination, dates, and request hash into `urlMappings` under the reserved code.
7. If a concurrent identical request won the unique-hash race, read and return its mapping instead.

The unique request index makes repeated identical requests converge. It is not a generic idempotency-key API: the same URL with a different expiry is a different request, and requests whose expiry is now past are rejected.

A usual new creation needs one lookup and two writes. An already-known request needs only the lookup. These operations have separate timeouts; the ten-candidate limit does not establish one overall HTTP deadline.

## Read flow

The service prefers a secondary, but the driver can choose the primary if no secondary is available. If the lookup succeeds with no document, the service queries the primary explicitly. If an operation throws, the HTTP layer returns 503; an exception is not treated as a missing document.

A found document is checked against the UTC clock. At or after its expiry it returns 410, even if the TTL worker has not deleted it. After physical deletion the result is 404. A live document becomes a 302 redirect with `no-store`.

Because destinations are immutable and codes are never reused, a replica cannot return an older destination for the same code under this service's write model. The code itself identifies the required mapping, so a missing result can be retried on the primary without a browser consistency token. This assumes the stored records and reservation history have not been lost or changed outside the service.

## Why there is no repository class

This was a scope choice, not a Spring restriction. The project already depends on `spring-boot-starter-mongodb`, which supplies the synchronous driver and Spring's client configuration. It does not include Spring Data's generated `MongoRepository` abstraction. A handwritten repository using the same driver would require no additional dependency.

There is currently one service with two public operations. Keeping the driver calls there made the first implementation small and kept uniqueness, read preference, and write concern visible alongside the flow that uses them. A repository that merely forwards every driver method would not help much.

The tradeoff is real: business decisions and MongoDB persistence are coupled, and the unit tests must mock driver types. This is acceptable for the current scope, but it is not the only reasonable design.

Extract a focused `UrlMappingRepository` when another service needs the same persistence operations, the queries/migrations grow, or this coupling makes changes difficult. Such a class should own collection handles, indexes, queries, and MongoDB options; the service should retain expiry decisions, allocation retries, and creation semantics. Do not add a generic repository framework just to create another layer.

## Why `usedCodes` exists

Suppose `Abc123` originally redirects to site A. A week later MongoDB TTL deletes that mapping. Without another record, the `_id` uniqueness constraint no longer remembers `Abc123`. A later random allocation could assign it to site B, and someone's old saved link would silently open B.

The collections therefore have different jobs:

| Collection | Example | Lifetime |
| --- | --- | --- |
| `urlMappings` | `{ "_id": "Abc123", "url": "https://example.com", "expiryAt": "...", ... }` | Until native TTL cleanup after expiry |
| `usedCodes` | `{ "_id": "Abc123" }` | Permanent |

The reservation contains no URL. Its unique `_id` insert is an atomic claim shared by all application instances. It answers “has this code ever been allocated?”, not “is this link active?” It is never queried on the redirect path and has no TTL index.

Reservation happens before mapping insertion. If the process crashes between them, or loses a duplicate-request race, the reserved code may never receive a mapping. Keeping that unused reservation avoids a transaction and preserves the no-reuse property. Releasing it after a timeout would be unsafe because a mapping write might actually have succeeded.

This is not free: it adds a write for each successful reservation and retains both document and index storage forever. Restoring URL mappings without their reservation history also breaks the guarantee.

## Ponytail review

The current implementation uses the installed driver, MongoDB uniqueness constraints, and native TTL. It has no extra repository framework, custom expiration worker, distributed lock, or browser consistency token. Those omissions fit this small service. The short helper methods each have a specific purpose; no refactoring or behavior change was needed for this documentation pass.

The following limits remain important:

1. **Permanent reservations and six-character codes limit lifetime capacity.** There are `62^6 = 56,800,235,584` possible codes. At a sustained 1,000 successful reservations/sec, allocating the entire space would take about 657 days; at 10,000/sec, about 66 days. These are illustrative sustained rates, not the design's known averages. Collision retries and their failures grow well before exhaustion: at 50% occupancy, ten independent candidates all collide with probability `0.5^10`, about 0.098%. Unused reservations also consume capacity. Plan a larger namespace or a different allocation scheme before sustained growth; deleting reservations alone would violate no-reuse.
2. **Per-operation timeouts do not enforce the HTTP latency target.** A miss can need two reads, creation needs multiple operations, and conflicts can cause retries. An overridden connection string can also replace the configured timeout defaults. An overall request deadline and realistic load measurements remain future work.
3. **The store is coupled to service logic.** This reduces scaffolding today but increases test mocking. A small handwritten repository is an available refactor when this cost outweighs keeping the flow in one class.
4. **Database error classification is intentionally broad.** All MongoDB exceptions become a generic 503, including configuration/authorization failures that retrying will not cure. Server-side diagnostic logging and more specific classification would help operation of a deployed service.
5. **Failure testing has a defined boundary.** Unit tests cover the secondary-miss/primary-read branch. The earlier integration run used one real replica-set member, so it did not test replication lag, an election, or multi-AZ failure. No load target has been proven.

Verification for this documentation pass: `mvnw.cmd -q test` completed successfully with 30 passing unit/controller/context tests. The five MongoDB integration tests were skipped because `MONGODB_TEST_URI` was not set. This pass changed documentation only; the earlier real-MongoDB results are described above, not claimed as a new integration run.

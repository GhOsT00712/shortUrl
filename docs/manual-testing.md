# Manual testing with Docker Compose

Run these commands in PowerShell from the project folder with Docker Desktop running Linux containers. Java and Maven run inside the build container; you do not need to install them to use this setup.

## Start the cluster and API

```powershell
cd C:\Users\anubis\workspace\shortUrl
docker compose up --build -d
docker compose ps -a
docker compose logs -f api
```

Compose waits for all three API instances to pass `/actuator/health` before starting NGINX. Press Ctrl+C to stop following logs; the containers keep running.

- `mongo1`, `mongo2`, and `mongo3` are data-bearing members of replica set `rs0`. An election chooses the primary; do not assume it is always `mongo1`.
- `mongo-init` initializes the set once, then waits for one primary and two secondaries. Its `Exited (0)` status is expected. Existing replica-set data is reused on subsequent starts.
- `api` runs three identical stateless containers (`shorturl-api-1`, `shorturl-api-2`, and `shorturl-api-3`), each connecting to the same MongoDB replica set. The instances have no published host ports.
- `nginx` publishes `127.0.0.1:8080` to Windows and distributes requests across the API containers using round-robin routing. No sticky sessions or proxy cache are configured. Docker DNS is refreshed every five seconds so replaced containers can be discovered.
- Each MongoDB member has a separate persistent volume. The WiredTiger cache is limited to 256 MiB per member for local use; total process memory is higher.

Check the API:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

Expected: `status` is `UP`. If startup fails, use `docker compose logs mongo-init api nginx`. If port 8080 is occupied, stop the other local service or change NGINX's host-side port mapping in `compose.yaml`.

## Check load balancing and an API failure

Send several requests through NGINX, then inspect which API handled each one:

```powershell
1..12 | ForEach-Object {
    curl.exe -sS -o NUL 'http://localhost:8080/actuator/health?balance-check'
}
docker compose logs --tail 30 nginx
```

The `upstream=...:8080` fields should show three different container IP addresses. To map each address to its API instance:

```powershell
docker compose ps -q api | ForEach-Object {
    docker inspect --format '{{.Name}} {{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $_
}
```

To simulate one API going down, stop just one replica (not the whole `api` service):

```powershell
docker stop shorturl-api-1
```

Repeat the requests and try creating/resolving a link. NGINX uses passive failure detection and can retry eligible requests on another instance after a connection error; it does not continuously poll the Docker health status. It does not automatically replay a POST already sent upstream. An in-flight creation can therefore still fail with an uncertain result; retry its exact original body if needed.

Restore the instance:

```powershell
docker start shorturl-api-1
docker compose ps
```

After it becomes healthy and DNS/failure caches refresh, check that all three upstream addresses receive traffic again. A starting instance may receive a failed connection attempt before it is ready; NGINX's retry policy handles eligible requests. The single NGINX container and this PC remain shared failure points.

## Create and resolve a short link

```powershell
$body = @{
    url = 'https://example.com/my-long-url'
    expiryAt = [DateTime]::UtcNow.AddMinutes(10).ToString('o')
} | ConvertTo-Json

$created = Invoke-RestMethod -Method Post `
    -Uri http://localhost:8080/api/v1/urls `
    -ContentType 'application/json' -Body $body
$created

$shortUrl = "http://localhost:8080/api/v1/$($created.shortCode)"
curl.exe -sS -D - -o NUL $shortUrl
```

Creation returns HTTP `201` and a JSON object with `shortCode`, `createdAt`, and `expiryAt`. The GET should return `302`, `Location: https://example.com/my-long-url`, and `Cache-Control: no-store`. `curl.exe` without `-L` shows the shortener response instead of following the redirect. Opening `$shortUrl` in a browser follows the redirect automatically.

Run the same POST again with the same `$body`. It should return the same `shortCode`. Regenerating the body with a different expiration creates a different mapping.

## Inspect the replica set and stored data

```powershell
docker compose exec mongo1 mongosh --quiet --eval 'rs.status().members.map(m => ({name: m.name, state: m.stateStr}))'
```

Expected: one `PRIMARY` and two `SECONDARY` members. For an interactive shell that discovers the current primary:

```powershell
docker compose exec mongo1 mongosh 'mongodb://mongo1:27017,mongo2:27017,mongo3:27017/shorturl?replicaSet=rs0'
```

Then run these inside `mongosh`:

```javascript
db.urlMappings.find()
db.urlMappings.getIndexes()
db.usedCodes.find()
exit
```

The `expiryAt` index should have `expireAfterSeconds: 0`. `usedCodes` retains code reservations even after mappings expire in the current implementation.

## Test expiry and validation

Create another mapping with `expiryAt = [DateTime]::UtcNow.AddSeconds(15).ToString('o')`, then GET its short link before and after those 15 seconds.

- Before expiry: `302`.
- After expiry, while its document still exists: `410`.
- After MongoDB's background TTL deletion: `404`.

The service enforces the expiration itself; TTL deletion is asynchronous. Do not expect the document to disappear at the exact expiration instant.

Also try a POST with a past expiry or an `ftp://` URL (expect `400`), and GET `/api/v1/not-a-code` (expect `404`).

## Test primary failure

First run the replica-set status command above and identify the current primary. In the example below it is `mongo1`; substitute the actual primary's service name if different.

```powershell
docker compose stop mongo1
docker compose exec mongo2 mongosh --quiet --eval 'rs.status().members.map(m => ({name: m.name, state: m.stateStr}))'
```

Run the status command on a surviving member. Wait for a new `PRIMARY`, then repeat the POST and GET examples using a future expiration. Two surviving data-bearing members can form a majority. Requests during the election may return `503` because the configured operation timeouts are shorter than the election process. Once the new primary is available, the API should recover without an API restart.

Restore the stopped member:

```powershell
docker compose start mongo1
```

Wait until the set again has one primary and two secondaries before trying another failure. Stopping two members removes the voting majority: new writes cannot succeed. Previously committed reads may still be available, so do not expect every GET to fail. A lost response during a write also does not prove that nothing was stored; retry the exact same request body.

## Rebuild, stop, or reset

After Java changes, rebuild and restart only the API:

```powershell
docker compose up --build -d api
```

Stop and remove the containers while keeping database data:

```powershell
docker compose down
```

Start again with `docker compose up -d`. To deliberately erase all local MongoDB data for this project, use `docker compose down -v` instead; the next start initializes an empty replica set.

This is a local development setup without database authentication or TLS. It can demonstrate replica-set behavior, but all members share one PC and it does not establish production latency, throughput, or availability.

References: [Compose startup dependencies](https://docs.docker.com/compose/how-tos/startup-order/) and [MongoDB replica-set deployment](https://www.mongodb.com/docs/manual/tutorial/deploy-replica-set/).

# k6 benchmark

See [the initial local benchmark results](load-test-results.md) for completed runs, resource observations, and measurement limitations.

The design's initial peak target is **10,000 redirects/s plus 1,000 new URLs/s**, concurrently. The latency limits are read p95 < 20 ms / p99 < 100 ms, and write p95 < 200 ms / p99 < 1,000 ms.

The user-confirmed timer is the complete k6 HTTP call to NGINX and back, including connection acquisition and establishment. Custom `read_latency` and `write_latency` metrics measure this. k6's HTTP duration excludes some connection phases, so the report includes it separately for diagnosis. Redirect following is disabled: the benchmark never visits the destination website.

## Run

Use PowerShell from the project directory. Docker Desktop must be running; k6 itself runs in Docker on the application's Compose network.

```powershell
docker compose up --build -d
docker compose --profile loadtest pull k6

# Validate the workload at low traffic first.
.\load-tests\run.ps1 -ReadRate 100 -WriteRate 10 -DurationSeconds 15 -WarmupSeconds 5 -SeedCount 100

# Separate measurement windows let us see where latency starts rising.
.\load-tests\run.ps1 -ReadRate 1000 -WriteRate 100
.\load-tests\run.ps1 -ReadRate 5000 -WriteRate 500

# Default: the complete design target, held for 60 seconds.
.\load-tests\run.ps1
```

Run these sequentially, not at the same time. Each normal run has setup, 15 seconds of warm-up at 10% of its target rates, up to five seconds to drain warm-up, 60 seconds of measured traffic, and up to ten seconds to drain measured requests. Warm-up and setup do not enter the custom latency/error/request metrics. Reported rates divide measured completed-request counts by the scheduled measurement duration; some requests can finish during the drain period. An aborted/incomplete run cannot establish sustained capacity.

The runner prints its results directory and k6 exit code. A threshold failure normally exits with code 99 and is a benchmark finding; it does not mean the script failed to execute. Check the stderr log if no summary was written.

## Workload and thresholds

- Read and write scenarios use independent **constant-arrival-rate** executors. Each iteration makes exactly one HTTP request. This preserves offered traffic when latency rises, unlike fixed-concurrency loops that slow down alongside the service.
- Setup creates 1,000 valid mappings through the public API by default. Reads choose uniformly from this pool. Successful reads must return 302, the correct destination, and `Cache-Control: no-store`.
- Every write uses a destination unique to the run, scenario, and iteration. It therefore exercises code reservation and mapping insertion instead of repeatedly hitting the cheaper deduplication path. Successful creations must return 201, a six-character code, the matching `/api/v1/{code}` Location, and no-store.
- The latency thresholds come from the diagram. **The error budget is an initial benchmark assumption:** less than 0.1% invalid/failed responses per operation type. It is configurable in `benchmark.js`; the diagram did not specify an error budget.
- A run also requires zero dropped iterations and at least 99.9% of the scheduled requests to complete. It aborts if either operation's error rate exceeds 5% after the initial evaluation delay, to avoid prolonged overload.
- VUs are preallocated: defaults are `max(10, ceil(readRate * 0.1))` and `max(5, ceil(writeRate * 0.5))`. At target this is 1,000 read VUs and 500 write VUs. These are load-generator concurrency limits, not request rates. There is no automatic VU growth during measurement.

If iterations are dropped, the requested traffic was not fully delivered. Check generator CPU/memory and observed latency before raising VUs:

```powershell
.\load-tests\run.ps1 -ReadVUs 2000 -WriteVUs 1000
```

More VUs cannot fix a saturated load generator or service. Do not report a target-rate pass based only on latency from the requests that did get sent.

## Isolate a suspected bottleneck

```powershell
# Compare independent read and write pressure.
.\load-tests\run.ps1 -ReadRate 10000 -WriteRate 0
.\load-tests\run.ps1 -ReadRate 0 -WriteRate 1000

# Bypass NGINX, targeting the Docker service directly (diagnostic only).
.\load-tests\run.ps1 -ReadRate 1000 -WriteRate 100 -BaseUrl http://api:8080

# Longer run, or a larger seeded working set.
.\load-tests\run.ps1 -DurationSeconds 300
.\load-tests\run.ps1 -SeedCount 10000
```

The direct-API comparison has different DNS/connection-based distribution and is not a controlled proof that NGINX is the bottleneck. Inspect distribution and repeat comparable runs before attributing a difference to the proxy. A longer run can reveal GC, storage, replication, or TTL effects that a short run misses.

## Read the results

Each run writes `load-tests/results/<timestamp-rates-id>/` (ignored by Git):

| Artifact | Purpose |
| --- | --- |
| `summary.txt` | Operation rates, p95/p99, errors, drops, and failed thresholds |
| `summary.json` | Configuration, per-operation results, and raw k6 summary metrics |
| `k6.stdout.log`, `k6.stderr.log` | Execution warnings, dropped-VU warnings, and failures |
| `docker-stats.jsonl` | Timestamped container CPU, memory, network, and block-I/O samples, including k6 and unrelated containers |
| `before-mongo*.json`, `sample-*-mongo*.json`, `after-mongo*.json` | Role, replication lag estimate, operation counters/latency, queues, connections, and WiredTiger cache counters |
| `containers.txt`, `docker-host.txt` | Container layout and Docker CPU/memory allocation |
| `service-tail.log` | Last API/NGINX log lines during the run |

Container CPU percentages can exceed 100%; 100% represents roughly one CPU core. MongoDB counters are cumulative: subtract earlier values from later ones. For example, `delta(latency.writes.microseconds) / delta(latency.writes.operations)` is mean MongoDB write-operation time, not HTTP write latency. Snapshots include setup and warm-up as well as measurement; use timestamped samples when correlating with the measured window. The replication lag estimate uses member optime dates visible from that member, has coarse resolution, and is not a measure of every request's replication delay.

| Observation | Next investigation |
| --- | --- |
| k6 CPU/memory pressure or dropped iterations | Generator capacity, VU sizing, and shared-host contention |
| High API CPU; MongoDB relatively idle | JVM GC, allocation, request processing, and thread pools; profiling is needed to identify the cause |
| Write latency rises with primary CPU, storage I/O, or flow-control waits | Primary storage/replication; each new mapping normally does one lookup and two majority writes |
| Read latency rises with secondary queues or cache eviction | Secondary query/index/cache capacity and working-set size |
| NGINX CPU or logging I/O dominates | Proxy connection handling and per-request access logging; compare with a controlled follow-up |
| All processes slow together | Docker CPU/memory allocation, disk limits, and other workloads on this PC |

These correlations identify candidates, not proof. The runner does not collect Java stack profiles, GC traces, MongoDB query profiles, or exact primary-fallback counts.

## Scope and test data

This is a local baseline: k6, NGINX, all API instances, and all MongoDB members share one Docker host. It includes Docker-network round-trip time, not a remote client's internet/TLS latency. Other running applications compete for the same resources. Confirm production capacity with a separate load-generator host and representative deployment resources.

The initial workload is a small, uniform, warm working set. It does not simulate the diagram's 604.8M retained mappings, a Zipf/hot-link distribution, immediate read-after-create, missing-code traffic, TTL churn, failover, or week-long steady state. Those need separate runs; the benchmark should not be interpreted as validating them.

Runs create real mappings in the configured local database, using `https://benchmark.invalid/...` destinations. Mappings expire after at least one hour (long runs extend this); **`usedCodes` reservations remain permanently** under the current design. No test or existing application data is deleted automatically. Repeated benchmarks therefore grow the database and change the baseline. Prefer a dedicated deployment/database for extended testing.

References: [arrival-rate execution](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/), [VU allocation and dropped iterations](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/arrival-rate-vu-allocation/), and [k6 execution identifiers](https://grafana.com/docs/k6/latest/javascript-api/k6-execution/).

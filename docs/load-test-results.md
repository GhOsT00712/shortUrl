# Local benchmark results — 4 November 2026

The service completed a five-minute measurement window at **10,000 reads/s plus 1,000 new mappings/s**, with **zero failed response checks and zero dropped iterations**. All configured k6 thresholds passed. No capacity bottleneck was demonstrated at this workload. The latency measurements have a timing anomaly described below and should be treated as provisional.

## Measured runs

Requests went from k6 through NGINX to the API; redirects were not followed. Setup and warm-up are excluded from this table.

| Reads/s | Writes/s | Measurement | Read p95 / p99 | Write p95 / p99 | Errors / dropped iterations |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 10 | 15 s | 3 / 4 ms | 9 / 11 ms | 0 / 0 |
| 1,000 | 100 | 60 s | 1 / 2 ms | 6 / 7 ms | 0 / 0 |
| 5,000 | 500 | 60 s | 1 / 2 ms | 7 / 9 ms | 0 / 0 |
| 10,000 | 1,000 | 60 s | 3 / 7 ms | 12 / 23 ms | 0 / 0 |
| 10,000 | 1,000 | 300 s | 2 / 6 ms | 11 / 20 ms | 0 / 0 |

The design limits are read p95/p99 below 20/100 ms and write p95/p99 below 200/1,000 ms. The five-minute run completed **3,000,009 reads and 300,000 writes**; the slight excess read count comes from execution at the measurement boundary. k6 exited with code 0 and reported no failed thresholds.

## Resource observations

Docker Desktop reported 32 logical CPUs and 30.19 GiB RAM. NGINX, three API instances, three MongoDB members, and k6 shared this host with other running applications. Each MongoDB member had a 256 MiB WiredTiger cache. Mongo2 was primary during these runs.

The following CPU figures are means across 53 samples for the five-minute run, including setup/warm-up/drain. 100% represents roughly one CPU core; these are container totals, including any observer or health-check processes.

| Container | Mean CPU | Last sampled memory |
| --- | ---: | ---: |
| k6 | 375.0% | 2.95 GiB |
| NGINX | 210.3% | 35.5 MiB |
| API 1 / 2 / 3 | 147.9% / 148.7% / 149.0% | 547 / 561 / 578 MiB |
| Mongo1 / Mongo2 / Mongo3 | 314.5% / 285.4% / 308.7% | 549 / 599 / 666 MiB |

- All 54 periodic MongoDB snapshots showed zero queued operations, zero estimated replication lag, and no active flow-control lag. These coarse samples cannot exclude short spikes between observations.
- WiredTiger eviction was active. Final cache occupancy was approximately 181–192 MiB per member. Eviction alone does not establish a storage bottleneck.
- The primary recorded approximately 605,000 inserts across setup, warm-up, and measurement, consistent with the two-insert path for new mappings: one reservation and one mapping. MongoDB's mean primary write-operation duration over that interval was approximately 2.95 ms, distinct from the API's multi-operation request latency.
- MongoDB used the largest aggregate CPU share among service components. That makes database cost a candidate for profiling at higher load, but these samples do not establish which component would saturate first.
- All three APIs and all three MongoDB containers were healthy after the run; the NGINX health endpoint returned `UP`. The temporary k6 container was removed automatically.

## Measurement limits and next checks

**Timing anomaly:** the five-minute run contains a minimum custom read latency of -1 ms and a minimum k6 HTTP read duration of approximately -0.94 ms. Negative elapsed times are invalid. The custom timer uses `Date.now()` with millisecond resolution; the cause of the anomaly in both metrics has not been established. Raw samples were not retained, so its frequency and effect on percentiles cannot be quantified. The reported percentiles passed the configured checks, but precise latency and available latency headroom require confirmation on a separate generator host with reliable timing. The original summaries have been preserved without clamping or altering these values.

Reads selected uniformly from only 1,000 seeded mappings (100 in the smoke test). Writes created unique mappings, but reads did not sample the growing population. This is a warm, small read working set, not a test of the design's approximately 604.8 million retained mappings. The run also does not validate TTL churn, immediate read-after-create, missing-code traffic, failover, remote internet/TLS latency, or long-term storage growth.

Useful follow-ups are a separate load-generator host and a representative database size/read distribution. After those baselines, increase offered load in steps and profile the component whose latency or queues rise first. There is no evidence here requiring an application tuning change yet.

## Reproduce and inspect

```powershell
.\load-tests\run.ps1 -DurationSeconds 300
```

See [the benchmark guide](load-testing.md) for setup, workload controls, and artifact interpretation. Runs create real local database records; mappings expire, while `usedCodes` reservations remain.

Raw artifacts are stored locally under `load-tests/results/` and ignored by Git:

| Run | Directory |
| --- | --- |
| Smoke | `20261104-231225-100r-10w-4f8139` |
| 1K / 100 | `20261104-231414-1000r-100w-ab32f1` |
| 5K / 500 | `20261104-231545-5000r-500w-c710ed` |
| Target, 60 s | `20261104-231828-10000r-1000w-fee7d4` |
| Target, 300 s | `20261104-232006-10000r-1000w-52458b` |

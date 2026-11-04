import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

function integer(name, fallback, minimum = 0) {
    const value = Number(__ENV[name] ?? fallback);
    if (!Number.isInteger(value) || value < minimum) throw new Error(`${name} must be an integer >= ${minimum}`);
    return value;
}

const baseUrl = (__ENV.BASE_URL || 'http://nginx').replace(/\/$/, '');
const readRate = integer('READ_RATE', 10000);
const writeRate = integer('WRITE_RATE', 1000);
const duration = integer('DURATION_SECONDS', 60, 1);
const warmup = integer('WARMUP_SECONDS', 15);
const seedCount = integer('SEED_COUNT', 1000, 1);
const ttl = integer('TTL_SECONDS', 3600, duration + warmup + 120);
const readVUs = integer('READ_VUS', Math.max(10, Math.ceil(readRate * 0.1)), 1);
const writeVUs = integer('WRITE_VUS', Math.max(5, Math.ceil(writeRate * 0.5)), 1);
if (!readRate && !writeRate) throw new Error('At least one request rate must be positive');
if (!__ENV.RUN_ID || !/^[a-zA-Z0-9_-]+$/.test(__ENV.RUN_ID)) throw new Error('RUN_ID must contain letters, digits, _ or -');

const metrics = {};
for (const kind of ['read', 'write']) {
    metrics[kind] = {
        latency: new Trend(`${kind}_latency`, true),
        httpDuration: new Trend(`${kind}_http_duration`, true),
        requests: new Counter(`${kind}_requests`),
        successes: new Counter(`${kind}_successes`),
        errors: new Rate(`${kind}_errors`),
    };
}

const scenarios = {};
for (const [name, rate, vus] of [['reads', readRate, readVUs], ['writes', writeRate, writeVUs]]) {
    if (!rate) continue;
    if (warmup) scenarios[`warm_${name}`] = {
        executor: 'constant-arrival-rate', exec: name, rate: Math.max(1, Math.ceil(rate / 10)),
        timeUnit: '1s', duration: `${warmup}s`, preAllocatedVUs: vus,
        gracefulStop: '5s', tags: { phase: 'warmup' },
    };
    scenarios[name] = {
        executor: 'constant-arrival-rate', exec: name, rate, timeUnit: '1s',
        duration: `${duration}s`, startTime: `${warmup ? warmup + 5 : 0}s`,
        preAllocatedVUs: vus, gracefulStop: '10s', tags: { phase: 'measure' },
    };
}

const thresholds = { dropped_iterations: ['count==0'] };
for (const [kind, rate, p95, p99] of [['read', readRate, 20, 100], ['write', writeRate, 200, 1000]]) {
    if (!rate) continue;
    thresholds[`${kind}_latency`] = [`p(95)<${p95}`, `p(99)<${p99}`];
    thresholds[`${kind}_errors`] = ['rate<0.001', {
        threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: `${warmup + 20}s`,
    }];
    thresholds[`${kind}_requests`] = [`count>=${Math.floor(rate * duration * 0.999)}`];
}

export const options = {
    scenarios, thresholds, discardResponseBodies: true, maxRedirects: 0,
    setupTimeout: '5m', batch: 20, batchPerHost: 20,
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
    // Avoid a separate metric series for every unique short code.
    systemTags: ['status', 'method', 'name', 'scenario', 'expected_response', 'error_code'],
};

const expectedRead = http.expectedStatuses(302);
const expectedWrite = http.expectedStatuses(201);
const writeParams = {
    headers: { 'Content-Type': 'application/json' }, responseType: 'text',
    timeout: '10s', responseCallback: expectedWrite, tags: { name: 'POST /api/v1/urls' },
};

function creation(response) {
    if (response.status !== 201) return null;
    try {
        const value = response.json();
        return /^[A-Za-z0-9]{6}$/.test(value.shortCode) &&
            response.headers.Location === `/api/v1/${value.shortCode}` ? value : null;
    } catch (_) { return null; }
}

export function setup() {
    const runId = `${__ENV.RUN_ID}-${Date.now()}`;
    const expiryAt = new Date(Date.now() + ttl * 1000).toISOString();
    const fixtures = [];
    if (readRate) {
        for (let start = 0; start < seedCount; start += 20) {
            const urls = Array.from({ length: Math.min(20, seedCount - start) },
                (_, index) => `https://benchmark.invalid/${runId}/seed/${start + index}`);
            const responses = http.batch(urls.map(url => ({ method: 'POST', url: `${baseUrl}/api/v1/urls`,
                body: JSON.stringify({ url, expiryAt }),
                params: { ...writeParams, tags: { name: 'seed', phase: 'setup' } },
            })));
            responses.forEach((response, index) => {
                const value = creation(response);
                if (!value) fail(`Seeding failed: HTTP ${response.status}; ${response.body}`);
                fixtures.push({ code: value.shortCode, url: urls[index] });
            });
        }
    }
    return { runId, expiryAt, fixtures };
}

function record(kind, response, elapsed, valid) {
    check(response, { [`${kind} contract`]: () => valid });
    if (exec.scenario.name.startsWith('warm_')) return;
    metrics[kind].latency.add(elapsed);
    metrics[kind].httpDuration.add(response.timings.duration);
    metrics[kind].requests.add(1);
    metrics[kind].successes.add(valid ? 1 : 0);
    metrics[kind].errors.add(!valid);
}

/** One iteration is one redirect request; never fetch the external destination. */
export function reads(data) {
    const fixture = data.fixtures[Math.floor(Math.random() * data.fixtures.length)];
    const started = Date.now();
    const response = http.get(`${baseUrl}/api/v1/${fixture.code}`, {
        redirects: 0, timeout: '10s', responseCallback: expectedRead,
        tags: { name: 'GET /api/v1/:shortCode' },
    });
    const elapsed = Date.now() - started;
    record('read', response, elapsed, response.status === 302 &&
        response.headers.Location === fixture.url && response.headers['Cache-Control'] === 'no-store');
}

/** Unique destinations exercise the new-mapping path, not the cheaper deduplication path. */
export function writes(data) {
    const url = `https://benchmark.invalid/${data.runId}/${exec.scenario.name}/${exec.scenario.iterationInTest}`;
    const body = JSON.stringify({ url, expiryAt: data.expiryAt });
    const started = Date.now();
    const response = http.post(`${baseUrl}/api/v1/urls`, body, writeParams);
    const elapsed = Date.now() - started;
    record('write', response, elapsed, creation(response) !== null && response.headers['Cache-Control'] === 'no-store');
}

export function handleSummary(data) {
    const value = name => data.metrics[name]?.values || {};
    const results = {};
    for (const [kind, targetRate] of [['read', readRate], ['write', writeRate]]) {
        if (!targetRate) continue;
        results[kind] = {
            targetRate, requests: value(`${kind}_requests`).count || 0,
            successfulRequests: value(`${kind}_successes`).count || 0,
            requestsPerSecond: (value(`${kind}_requests`).count || 0) / duration,
            errorRate: value(`${kind}_errors`).rate ?? null,
            latencyMs: value(`${kind}_latency`), httpDurationMs: value(`${kind}_http_duration`),
        };
    }
    const failedThresholds = Object.entries(data.metrics).flatMap(([name, metric]) =>
        Object.entries(metric.thresholds || {}).filter(([, result]) => !result.ok).map(([rule]) => `${name}: ${rule}`));
    const report = {
        configuration: { baseUrl, readRate, writeRate, duration, warmup, seedCount, ttl, readVUs, writeVUs },
        results, droppedIterations: value('dropped_iterations').count || 0, failedThresholds, raw: data,
    };
    const lines = Object.entries(results).map(([kind, result]) =>
        `${kind}: ${result.requestsPerSecond.toFixed(1)}/${result.targetRate} req/s, ` +
        `p95=${result.latencyMs['p(95)']?.toFixed(1)}ms, p99=${result.latencyMs['p(99)']?.toFixed(1)}ms, ` +
        `errors=${((result.errorRate || 0) * 100).toFixed(3)}%`);
    lines.push(`Dropped iterations: ${report.droppedIterations}`, `Failed thresholds: ${failedThresholds.join('; ') || 'none'}`);
    const summary = lines.join('\n') + '\n';
    return { stdout: summary, [`/results/${__ENV.RUN_ID}/summary.json`]: JSON.stringify(report, null, 2),
        [`/results/${__ENV.RUN_ID}/summary.txt`]: summary };
}

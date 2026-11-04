// Select cumulative counters for before/after comparison without enabling profiling.
const status = db.serverStatus();
const replica = rs.status();
const cache = status.wiredTiger.cache;
const number = value => value == null ? null : Number(value);
const self = replica.members.find(member => member.self);
const newest = Math.max(...replica.members.map(member => member.optimeDate ? member.optimeDate.getTime() : 0));
print(JSON.stringify({
    time: new Date().toISOString(), host: status.host, role: self.stateStr,
    replicationLagMs: newest - self.optimeDate.getTime(),
    operations: Object.fromEntries(Object.entries(status.opcounters).map(([key, value]) => [key, number(value)])),
    latency: Object.fromEntries(['reads', 'writes', 'commands'].map(kind => [kind, {
        microseconds: number(status.opLatencies[kind].latency), operations: number(status.opLatencies[kind].ops),
    }])),
    connections: { current: status.connections.current, active: status.connections.active, available: status.connections.available },
    queues: status.globalLock.currentQueue,
    cache: Object.fromEntries(['bytes currently in the cache', 'maximum bytes configured', 'tracked dirty bytes in the cache',
        'pages read into cache', 'pages written from cache', 'unmodified pages evicted', 'modified pages evicted']
        .map(key => [key, number(cache[key])])),
    flowControl: status.flowControl,
}));

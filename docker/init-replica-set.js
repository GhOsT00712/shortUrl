// Reuse an existing replica set when Compose restarts with retained volumes.
try {
    rs.status();
} catch (error) {
    if (error.code !== 94) throw error; // NotYetInitialized
    const result = rs.initiate({
        _id: "rs0",
        members: [
            { _id: 0, host: "mongo1:27017" },
            { _id: 1, host: "mongo2:27017" },
            { _id: 2, host: "mongo3:27017" }
        ]
    });
    if (!result.ok) throw new Error(JSON.stringify(result));
}

// The API creates indexes at startup, so wait for the election and initial sync.
for (let attempt = 0; attempt < 60; attempt++) {
    const members = rs.status().members;
    if (members.filter(member => member.stateStr === "PRIMARY").length === 1 &&
        members.filter(member => member.stateStr === "SECONDARY").length === 2) {
        print("rs0 ready: one primary and two secondaries");
        quit(0);
    }
    sleep(2000);
}

throw new Error("rs0 did not become ready within 120 seconds");

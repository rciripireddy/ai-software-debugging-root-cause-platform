# API reference

The ingestion, incident and analysis services expose JSON REST APIs under `/api/v1`. Examples use
the default local ports; through the dashboard proxy the same endpoints are available at
`http://localhost:3000/api/ingestion/...`, `http://localhost:3000/api/incident/...` and
`http://localhost:3000/api/analysis/...`.

The IDs, timestamps and fingerprints below come from a local run with the demo data in
[`sample-data/`](../sample-data/).

## Common behavior

- Request and response bodies are JSON (`Content-Type: application/json`).
- Timestamps are ISO-8601. Responses always use UTC (`2026-10-12T09:14:05.402Z`); requests may
  include an offset (`2026-10-12T11:14:05+02:00`).
- IDs are UUIDs.
- Every response carries an `X-Correlation-Id` header. Send your own value (letters, digits,
  `.`, `_`, `:`, `-`; up to 128 characters) to correlate a request with the service logs;
  otherwise one is generated.

### Errors

Errors use [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457)
(`Content-Type: application/problem+json`). Responses never include stack traces or internal
exception details.

```json
{
  "detail": "The request contains invalid fields.",
  "instance": "/api/v1/logs",
  "status": 400,
  "title": "Validation failed",
  "correlationId": "c65d894c-95d5-4eb4-89d4-599e8a962340",
  "errors": [
    { "field": "severity", "message": "must not be null" },
    { "field": "timestamp", "message": "must not be null" }
  ]
}
```

| Status | When |
| --- | --- |
| `400 Bad Request` | Missing or invalid fields (`errors` lists each one), malformed JSON, invalid path or query parameter |
| `404 Not Found` | The requested log event, incident or analysis does not exist |
| `409 Conflict` | The request conflicts with existing data (see `POST /api/v1/incidents`) |
| `415 Unsupported Media Type` | The request body is not `application/json` |
| `422 Unprocessable Content` | Valid JSON that refers to data that cannot be used (see `POST /api/v1/incidents`) |
| `500 Internal Server Error` | Unexpected error; details are only in the service log, under the same correlation ID |
| `503 Service Unavailable` | The database, or a service the request depends on, is temporarily unreachable; retry later |

## Ingestion service (port 8081)

### `POST /api/v1/logs`

Stores one log or error event. The application is registered automatically the first time an
event arrives for a new combination of `application`, `service` and `environment`.

| Field | Required | Rules |
| --- | --- | --- |
| `application` | yes | Up to 100 characters: letters, digits, `.`, `_`, `-`. Stored in lower case |
| `service` | yes | Same rules as `application` |
| `environment` | yes | Up to 50 characters, same character rules |
| `timestamp` | yes | ISO-8601 date-time with offset or `Z` |
| `severity` | yes | `DEBUG`, `INFO`, `WARN`, `ERROR` or `FATAL` |
| `message` | yes | Up to 10,000 characters |
| `exceptionType` | no | Up to 255 characters, e.g. `java.net.SocketTimeoutException` |
| `stackTrace` | no | Up to 100,000 characters, one frame per line |
| `source` | no | Logger or component name, up to 255 characters |
| `host` | no | Host or pod name, up to 255 characters |
| `traceId` | no | Distributed trace ID, up to 128 characters |
| `correlationId` | no | Application-level correlation ID, up to 128 characters |
| `metadata` | no | JSON object with at most 50 entries, stored as-is |

```bash
curl -i http://localhost:8081/api/v1/logs \
  -H 'Content-Type: application/json' \
  -d '{
    "application": "demo-shop",
    "service": "payment-service",
    "environment": "staging",
    "timestamp": "2026-10-12T09:14:05.402Z",
    "severity": "ERROR",
    "message": "Payment authorization failed",
    "exceptionType": "java.net.SocketTimeoutException",
    "stackTrace": "java.net.SocketTimeoutException: Read timed out\n\tat com.shop.payment.GatewayClient.authorize(GatewayClient.java:74)\n\tat com.shop.payment.PaymentService.charge(PaymentService.java:41)",
    "source": "com.shop.payment.GatewayClient",
    "host": "payment-5d8b7-fghij",
    "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
    "metadata": { "dataset": "demo" }
  }'
```

`201 Created`, with `Location: /api/v1/logs/{id}`:

```json
{
  "id": "01a159ed-2f0a-73d1-94f9-a9b84bbb1a00",
  "applicationId": "fb6ee99c-6841-4acb-b651-683d87993cae",
  "fingerprint": "1768a03e1bfbe239bfe6ea3ee3ae7495a804729552f3187a69f7515444348ae7",
  "receivedAt": "2026-10-20T15:27:58.731355Z"
}
```

`201` means the event and its Kafka message are stored durably in PostgreSQL. The message is
published to Kafka in the background (usually within a second), and incident-service correlates
it asynchronously, so the event appears in an incident shortly after the response, not before
it. If Kafka is unavailable, ingestion keeps accepting events; they are published once Kafka is
back. See [events.md](events.md).

#### Fingerprints

The fingerprint identifies "the same error" across occurrences. It is a SHA-256 hash of:

- the service name,
- the exception type,
- the message, normalized: lower case, with UUIDs, hexadecimal values and numbers replaced by
  placeholders,
- the top five stack frames without line numbers (Java `at ...` frames and Python
  `File "...", line N` frames are recognized).

So `Pool-1 timed out after 30000ms` and `Pool-2 timed out after 15000ms` from the same code path
share a fingerprint, while the same message from a different call site or exception type does
not. The environment is not part of the fingerprint.

### `GET /api/v1/logs/{id}`

Returns a stored event, including its application and `metadata`.

```json
{
  "id": "01a159ed-2f0a-73d1-94f9-a9b84bbb1a00",
  "application": {
    "id": "fb6ee99c-6841-4acb-b651-683d87993cae",
    "name": "demo-shop",
    "service": "payment-service",
    "environment": "staging"
  },
  "timestamp": "2026-10-12T09:14:05.402Z",
  "severity": "ERROR",
  "message": "Payment authorization failed",
  "exceptionType": "java.net.SocketTimeoutException",
  "stackTrace": "java.net.SocketTimeoutException: Read timed out\n\tat ...",
  "source": "com.shop.payment.GatewayClient",
  "host": "payment-5d8b7-fghij",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "correlationId": null,
  "fingerprint": "1768a03e1bfbe239bfe6ea3ee3ae7495a804729552f3187a69f7515444348ae7",
  "metadata": { "dataset": "demo" },
  "receivedAt": "2026-10-20T15:27:58.731355Z"
}
```

## Incident service (port 8082)

Incidents are created **automatically**: incident-service consumes the `ErrorEventCreated`
messages published by ingestion-service and groups events with the same application and
fingerprint into one unresolved incident. Events below `ERROR` severity do not create incidents.
The rules are described in [events.md](events.md#correlation-rules).

### `GET /api/v1/incidents/{id}`

Returns the incident with its application and up to 50 of its most recent events.
`occurrenceCount` is always the total number of events.

```json
{
  "id": "01a159ed-c78b-7985-b131-946c553d8041",
  "title": "java.net.SocketTimeoutException: Payment authorization failed",
  "status": "OPEN",
  "severity": "ERROR",
  "application": {
    "id": "fb6ee99c-6841-4acb-b651-683d87993cae",
    "name": "demo-shop",
    "service": "payment-service",
    "environment": "staging"
  },
  "occurrenceCount": 3,
  "firstSeenAt": "2026-10-12T09:14:05.402Z",
  "lastSeenAt": "2026-10-12T13:55:01.904Z",
  "fingerprint": "1768a03e1bfbe239bfe6ea3ee3ae7495a804729552f3187a69f7515444348ae7",
  "summary": "Demo incident: recurring payment gateway timeouts (sample data).",
  "createdAt": "2026-10-20T15:28:37.795190Z",
  "updatedAt": "2026-10-20T15:28:37.795227Z",
  "recentEventLimit": 50,
  "recentEvents": [
    {
      "id": "01a159ed-2f90-7564-a504-eb5b06e7f2fb",
      "timestamp": "2026-10-12T13:55:01.904Z",
      "severity": "ERROR",
      "message": "Payment authorization failed",
      "exceptionType": "java.net.SocketTimeoutException",
      "host": "payment-5d8b7-fghij",
      "traceId": "92f3577b34da6a3ce929d0e0e47364bf",
      "correlationId": null
    }
  ]
}
```

(`recentEvents` shortened to one entry.)

### `GET /api/v1/incidents`

Lists incidents, most recent activity (`lastSeenAt`) first.

| Query parameter | Default | Description |
| --- | --- | --- |
| `status` | — | `OPEN`, `INVESTIGATING` or `RESOLVED` |
| `severity` | — | `DEBUG`, `INFO`, `WARN`, `ERROR` or `FATAL` |
| `application` | — | Application name (case-insensitive) |
| `service` | — | Service name (case-insensitive) |
| `environment` | — | Environment (case-insensitive) |
| `page` | `0` | Zero-based page number |
| `size` | `20` | Page size, 1–100 |

```bash
curl 'http://localhost:8082/api/v1/incidents?application=demo-shop&status=OPEN&size=10'
```

```json
{
  "items": [
    {
      "id": "01a159ed-c78b-7985-b131-946c553d8041",
      "title": "java.net.SocketTimeoutException: Payment authorization failed",
      "status": "OPEN",
      "severity": "ERROR",
      "application": { "id": "fb6ee99c-...", "name": "demo-shop", "service": "payment-service", "environment": "staging" },
      "occurrenceCount": 3,
      "firstSeenAt": "2026-10-12T09:14:05.402Z",
      "lastSeenAt": "2026-10-12T13:55:01.904Z",
      "fingerprint": "1768a03e1bfbe239bfe6ea3ee3ae7495a804729552f3187a69f7515444348ae7",
      "createdAt": "2026-10-20T15:28:37.795190Z",
      "updatedAt": "2026-10-20T15:28:37.795227Z"
    }
  ],
  "page": 0,
  "size": 10,
  "totalItems": 1,
  "totalPages": 1
}
```

There is no endpoint to change an incident's status yet; `RESOLVED` can currently only be set
directly in the database.

### `POST /api/v1/incidents` (deprecated)

> **Deprecated since Phase 3.** Incidents are now created automatically from Kafka messages.
> The endpoint still works for existing clients and responds with an RFC 9745
> `Deprecation: @1792454400` header (2026-10-20). It will be removed in a later phase.
>
> It creates an incident from events stored by ingestion-service. Because the consumer already
> correlates every event of `ERROR` severity or above, those events normally belong to an
> incident already and the request returns `409`; in practice the endpoint is only useful for
> `WARN` and lower events, which are not correlated automatically. Events assigned through this
> endpoint are skipped by the consumer when their Kafka message arrives (`ALREADY_ASSIGNED`).
> It is the only code in incident-service that still reads ingestion-service's tables.

| Field | Required | Rules |
| --- | --- | --- |
| `errorEventIds` | yes | 1–100 IDs of stored error events |
| `title` | no | Up to 255 characters. Default: `"<exceptionType>: <message>"` of the most recent event |
| `summary` | no | Up to 10,000 characters |
| `severity` | no | Default: the highest severity among the events |

The new incident has status `OPEN`; `occurrenceCount`, `firstSeenAt` and `lastSeenAt` are
derived from the events.

```bash
curl -i http://localhost:8082/api/v1/incidents \
  -H 'Content-Type: application/json' \
  -d '{
    "errorEventIds": [
      "01a159ed-2ef9-7e05-9c04-298351ea066b"
    ],
    "summary": "Slow payment gateway responses (demo data)."
  }'
```

`201 Created`, with `Location: /api/v1/incidents/{id}` and the incident details (see `GET` below).

| Error | Status |
| --- | --- |
| An ID does not refer to a stored event | `422` |
| Events belong to different applications or have different fingerprints | `422` |
| An event already belongs to an incident | `409` |
| An unresolved (`OPEN` or `INVESTIGATING`) incident already exists for this application and fingerprint | `409` |

## Analysis service (port 8000)

Rule-based analysis of an incident: recurring patterns, related incidents and ranked **potential
root causes**, each with evidence, a confidence band (`LOW`, `MEDIUM`, `HIGH`) and limitations.
How the analysis works, including every rule and score: [analysis.md](analysis.md). The service
reads incidents and events through the incident and ingestion APIs, so both must be reachable
when an analysis is requested.

The examples come from the synthetic scenarios in
[`sample-data/analysis-scenarios/`](../sample-data/analysis-scenarios/), sent through the
ingestion API under the application `analysis-eval-e2e`.

### `POST /api/v1/analysis/incidents/{incidentId}`

Analyzes the incident now and stores the result as a new **revision** (earlier revisions are
kept). No request body.

```bash
curl -X POST http://localhost:8000/api/v1/analysis/incidents/01a16414-f904-7b87-a395-a8102fd956c5
```

`201 Created`, with `Location: /api/v1/analysis/incidents/{incidentId}`:

```json
{
  "id": "cf13b384-00d1-4212-9e0b-5e044a2005c5",
  "revision": 2,
  "incidentId": "01a16414-f904-7b87-a395-a8102fd956c5",
  "analysisVersion": "1.0.0",
  "generatedAt": "2026-10-22T14:49:18.696591Z",
  "incident": {
    "id": "01a16414-f904-7b87-a395-a8102fd956c5",
    "title": "java.net.SocketTimeoutException: Payment authorization failed",
    "status": "OPEN",
    "severity": "ERROR",
    "application": "analysis-eval-e2e",
    "service": "payment-service",
    "environment": "staging",
    "occurrenceCount": 4,
    "firstSeenAt": "2026-10-14T10:02:11.204000Z",
    "lastSeenAt": "2026-10-14T10:13:02.677000Z",
    "fingerprint": "9910dd1f3cee53f5cd2fb547aac790bfad744ca3f8ccbfde75a98d60a20af30c"
  },
  "summary": "java.net.SocketTimeoutException in payment-service (staging): 4 occurrences within 11 minutes (burst). Most likely cause based on available evidence (HIGH confidence): Timeout waiting for a downstream dependency called from GatewayClient.authorize. 3 related incidents (1 strong).",
  "confidence": "HIGH",
  "candidates": ["… ranked candidates; the first one is repeated as primaryCandidate …"],
  "patterns": {
    "recurrence": {
      "pattern": "BURST",
      "description": "4 occurrences within 11 minutes (burst).",
      "occurrenceCount": 4,
      "eventsAnalyzed": 4,
      "firstSeenAt": "2026-10-14T10:02:11.204000Z",
      "lastSeenAt": "2026-10-14T10:13:02.677000Z",
      "spanSeconds": 651,
      "windowSeconds": 900,
      "maxOccurrencesInWindow": 4,
      "medianIntervalSeconds": 216
    },
    "dominantException": { "value": "java.net.SocketTimeoutException", "count": 4, "total": 4 },
    "topApplicationFrame": { "value": "GatewayClient.authorize", "count": 4, "total": 4 },
    "repeatedFrames": [
      { "value": "GatewayClient.authorize", "count": 4, "total": 4 },
      { "value": "PaymentController.pay", "count": 4, "total": 4 },
      { "value": "PaymentService.charge", "count": 4, "total": 4 }
    ],
    "source": { "value": "com.shop.payment.GatewayClient", "count": 4, "total": 4 },
    "hosts": {
      "distinct": 2,
      "top": [
        { "value": "payment-7c9d4-abcde", "count": 3, "total": 4 },
        { "value": "payment-7c9d4-fghij", "count": 1, "total": 4 }
      ],
      "concentrated": false
    },
    "precedingErrors": [],
    "subsequentErrors": [
      {
        "incidentId": "01a16414-f905-7c3c-810d-fd1e0c4eb42c",
        "title": "com.shop.checkout.PaymentFailedException: Order could not be completed: payment failed",
        "service": "checkout-service",
        "linkedEvents": 4,
        "sharedTraceEvents": 4,
        "medianGapSeconds": 0.255,
        "basis": "TRACE"
      }
    ]
  },
  "relatedIncidents": [
    {
      "incidentId": "01a16414-fd38-7029-84d8-3ad04984b6ac",
      "title": "java.net.SocketTimeoutException: Payment authorization failed",
      "status": "OPEN",
      "service": "payment-service",
      "environment": "production",
      "score": 100,
      "strength": "STRONG",
      "matchingSignals": [
        { "signal": "SAME_FINGERPRINT", "points": 40, "description": "Same fingerprint (same error in production)" },
        { "signal": "SAME_EXCEPTION_TYPE", "points": 25, "description": "Same exception type java.net.SocketTimeoutException" },
        { "signal": "SAME_TOP_FRAME", "points": 25, "description": "Same top application frame GatewayClient.authorize" }
      ]
    }
  ],
  "dataCoverage": {
    "occurrenceCount": 4,
    "eventsAnalyzed": 4,
    "eventsWithDetails": 4,
    "eventsWithStackTrace": 4,
    "incidentsCompared": 15
  },
  "limitations": [
    "Rule-based analysis of error events only: suggested causes are hypotheses supported by the listed evidence, not confirmed root causes."
  ],
  "primaryCandidate": {
    "rank": 1,
    "category": "TIMEOUT",
    "title": "Timeout waiting for a downstream dependency called from GatewayClient.authorize",
    "confidence": "HIGH",
    "score": 5,
    "maxScore": 6,
    "confidenceBasis": [
      "+2 exception type matches the timeout rules in 4/4 events",
      "+1 messages match the timeout rules in 4/4 events",
      "+1 the same evidence appears in 4/4 analyzed events",
      "+1 same top application frame in 4/4 events with stack traces",
      "= 5 of 6 points: HIGH"
    ],
    "explanation": "The exception types or messages show that an operation exceeded its time limit. Network-client frames or a client/gateway class in the stack trace suggest that the slow component is a dependency called from GatewayClient.authorize.",
    "evidence": [
      { "signal": "EXCEPTION_TYPE", "description": "java.net.SocketTimeoutException in 4/4 events", "matched": 4, "total": 4 },
      { "signal": "MESSAGE", "description": "Messages contain \"timed out\" in 4/4 events", "matched": 4, "total": 4 },
      { "signal": "RECURRENCE", "description": "4 occurrences within 11 minutes (burst).", "matched": 4, "total": null },
      { "signal": "SERVICE", "description": "Failures affect payment-service in staging", "matched": null, "total": null },
      { "signal": "CODE_LOCATION", "description": "Top application frame of payment-service: GatewayClient.authorize in 4/4 events with stack traces", "matched": 4, "total": 4 },
      { "signal": "HOST", "description": "Events came from 2 hosts, so the failure is not limited to one host", "matched": null, "total": null }
    ],
    "affectedComponent": {
      "service": "payment-service",
      "environment": "staging",
      "location": "GatewayClient.authorize",
      "source": "com.shop.payment.GatewayClient"
    },
    "relatedIncidentIds": [],
    "relatedEventIds": ["01a16414-f251-7d40-8520-eeb9d32267e9", "…"],
    "limitations": [
      "No telemetry from the called component is available, so it cannot be confirmed whether it was slow, overloaded or unreachable."
    ]
  }
}
```

(`candidates`, `relatedIncidents`, `matchingSignals` and `relatedEventIds` shortened.)

| Error | Status |
| --- | --- |
| `incidentId` is not a UUID | `400` |
| The incident does not exist in incident-service | `404` (nothing is stored) |
| incident-service or ingestion-service is unreachable or fails, or the database is unreachable | `503` |

An incident without usable evidence (no exception type, no stack trace, no matching message) is
not an error: the result has a single `UNKNOWN` candidate with `LOW` confidence and lists the
missing evidence in `limitations`.

### `GET /api/v1/analysis/incidents/{incidentId}`

Returns the latest stored analysis (same body as above). `404`, with a hint to `POST`, if the
incident has not been analyzed yet.

### `GET /api/v1/analysis/incidents/{incidentId}/history`

Stored analyses of the incident, newest revision first. `404` if there are none.

| Query parameter | Default | Description |
| --- | --- | --- |
| `limit` | `20` | Number of revisions, 1–100 |

```json
{
  "incidentId": "01a16414-f904-7b87-a395-a8102fd956c5",
  "items": [
    {
      "id": "cf13b384-00d1-4212-9e0b-5e044a2005c5",
      "revision": 2,
      "analysisVersion": "1.0.0",
      "generatedAt": "2026-10-22T14:49:18.696591Z",
      "confidence": "HIGH",
      "primaryCategory": "TIMEOUT",
      "primaryTitle": "Timeout waiting for a downstream dependency called from GatewayClient.authorize",
      "summary": "java.net.SocketTimeoutException in payment-service (staging): …"
    }
  ]
}
```

(Shortened to one revision.)

### `GET /api/v1/analysis/incidents/{incidentId}/related`

Related incidents, computed on request and **not stored**, with the same scoring as
`relatedIncidents` above. Example for an incident that matches nothing in its application:

```json
{
  "incidentId": "01a16414-fd74-7028-8614-c97ff7b65cb0",
  "threshold": 40,
  "incidentsCompared": 15,
  "items": []
}
```

### Health

| Endpoint | Meaning |
| --- | --- |
| `GET /health` | Liveness: the process is running. Does not check dependencies |
| `GET /health/readiness` | Readiness: `200` when the database is reachable, otherwise `503` with `"checks": {"database": "DOWN"}` |

Interactive OpenAPI documentation: <http://localhost:8000/docs>.

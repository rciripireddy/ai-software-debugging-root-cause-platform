# API reference

Both backend services expose JSON REST APIs under `/api/v1`. Examples use the default local ports;
through the dashboard proxy the same endpoints are available at
`http://localhost:3000/api/ingestion/...` and `http://localhost:3000/api/incident/...`.

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
| `404 Not Found` | The requested log event or incident does not exist |
| `409 Conflict` | The request conflicts with existing data (see `POST /api/v1/incidents`) |
| `415 Unsupported Media Type` | The request body is not `application/json` |
| `422 Unprocessable Content` | Valid JSON that refers to data that cannot be used (see `POST /api/v1/incidents`) |
| `500 Internal Server Error` | Unexpected error; details are only in the service log, under the same correlation ID |
| `503 Service Unavailable` | The database is temporarily unreachable; retry later |

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

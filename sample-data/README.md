# sample-data

Example application log events for developing and demonstrating the platform. The data is
synthetic and describes a fictional e-commerce system.

## `application-logs.jsonl`

One JSON log event per line (JSON Lines). The set intentionally contains:

- a **recurring failure**: `SocketTimeoutException` in `payment-service` occurring three times with
  the same stack trace;
- **cross-service propagation**: each payment timeout is followed by a `PaymentFailedException` in
  `checkout-service` sharing the same `traceId`;
- an unrelated **database connection pool exhaustion** in `inventory-service`;
- a one-off `NullPointerException` in `catalog-service`.

| Field | Description |
| --- | --- |
| `timestamp` | ISO-8601 UTC timestamp |
| `service` | Name of the application that emitted the event |
| `environment` | Deployment environment, e.g. `staging` |
| `host` | Host or pod name |
| `level` | `DEBUG`, `INFO`, `WARN` or `ERROR` |
| `logger` | Logger / class name |
| `message` | Log message |
| `traceId` | Distributed trace ID (may be `null`) |
| `exception` | Optional: `type`, `message` and `stackTrace` frames |

This format is a draft. It will be finalized, validated and documented as the ingestion API
contract in Phase 2. Nothing in the platform reads this file yet.

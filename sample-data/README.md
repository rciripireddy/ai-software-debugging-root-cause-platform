# sample-data

**Demo data.** Synthetic log events from a fictional e-commerce system (`demo-shop`), used to
demonstrate and manually test the platform. None of it comes from a real system.

## `application-logs.jsonl`

One event per line (JSON Lines), in the request format of the ingestion API
(`POST /api/v1/logs`, see [docs/api.md](../docs/api.md)). Every event carries
`"metadata": {"dataset": "demo"}`.

The set intentionally contains:

- a **recurring failure**: three `SocketTimeoutException`s in `payment-service` from the same code
  path, which receive the same fingerprint;
- **cross-service propagation**: each payment timeout is followed by a `PaymentFailedException` in
  `checkout-service` with the same `traceId`;
- an unrelated **database connection pool exhaustion** in `inventory-service`;
- a one-off `NullPointerException` in `catalog-service`;
- a few `INFO` and `WARN` events.

## Sending the demo events

With the stack running (`docker compose up -d`):

```bash
python sample-data/send_sample_logs.py                        # default: http://localhost:8081
python sample-data/send_sample_logs.py --url http://localhost:3000/api/ingestion
```

The script uses only the Python standard library and prints each event's ID and fingerprint.
Every run stores the events again as new rows.

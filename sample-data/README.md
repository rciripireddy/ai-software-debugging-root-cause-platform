# sample-data

**Demo data.** Synthetic log events from a fictional e-commerce system (`demo-shop`), used to
demonstrate and manually test the platform. None of it comes from a real system.

## `application-logs.jsonl`

One event per line (JSON Lines), in the request format of the ingestion API
(`POST /api/v1/logs`, see [docs/api.md](../docs/api.md)). Every event carries
`"metadata": {"dataset": "demo"}`.

The set intentionally contains:

- a **recurring failure**: three `SocketTimeoutException`s in `payment-service` from the same code
  path, which receive the same fingerprint and become **one incident with three occurrences**;
- **cross-service propagation**: each payment timeout is followed by a `PaymentFailedException` in
  `checkout-service` with the same `traceId` (two of them, forming a second incident);
- an unrelated **database connection pool exhaustion** in `inventory-service`;
- a one-off `NullPointerException` in `catalog-service`;
- three `INFO` and `WARN` events, which are stored but do not create incidents.

## Sending the demo events

With the stack running (`docker compose up -d`):

```bash
python sample-data/send_sample_logs.py                              # application "demo-shop"
python sample-data/send_sample_logs.py --application demo-shop-trial
python sample-data/send_sample_logs.py --url http://localhost:3000/api/ingestion --no-wait
```

The script sends each event to the ingestion API and prints its ID and fingerprint. It then waits
until incident-service has correlated the `ERROR` events through Kafka (up to 30 seconds) and
prints the resulting incidents. `--application` sends the events under another application name,
which gives a fresh set of incidents; running it again with the same name adds occurrences to the
existing open incidents. It uses only the Python standard library.

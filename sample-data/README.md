# sample-data

**Demo data.** Synthetic log events from a fictional e-commerce system (`demo-shop`), used to
demonstrate and manually test the platform, and labelled scenarios for evaluating the analysis.
None of it comes from a real system.

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

Analyze one of the printed incidents:

```bash
curl -X POST http://localhost:8000/api/v1/analysis/incidents/<incident-id>
```

## `analysis-scenarios/`

Labelled synthetic scenarios for evaluating the analysis engine: 16 scenarios, 41 events (payment
timeouts and the checkout failures they cause, database pool exhaustion and connection refusal,
null pointer, expired JWT, malformed JSON, Python timeout and `KeyError`, out of memory, missing
configuration, a 503 from an external API, unclassifiable errors, the same error in production,
and a timeout in a different code path).

- `scenarios.json` — runtime input only: events in the ingestion API format, grouped under
  neutral IDs (`S01`…`S16`).
- `labels.json` — expected primary category per scenario and the scenario pairs expected to be
  related. Read only by the evaluation code and tests, never by the analysis service.

```bash
cd analysis-service
python -m evaluation            # engine only
python -m evaluation --live     # sends the events through the running stack and analyzes the incidents
```

Methodology and results: [docs/analysis.md](../docs/analysis.md#evaluation-on-synthetic-scenarios).
The scenarios were written together with the rules, so the results show that the rules behave as
designed, not how accurate they would be on real incidents.

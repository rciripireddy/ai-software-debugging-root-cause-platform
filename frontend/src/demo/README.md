# Demo data

`demo-data.json` is the dataset shown when the dashboard runs in demo mode
(`npm run dev:demo`, `npm run build:demo`). Demo mode makes no API requests and every page is
labelled **Demo data**, with a banner explaining that no backend is connected.

**What the data is.** Real responses of the platform's APIs, captured from a local run on
synthetic input only: the scenarios in `sample-data/analysis-scenarios/scenarios.json` (sent as
application `demo-store`) and the demo events in `sample-data/application-logs.jsonl` (sent as
`demo-webshop`). The incidents were created by the Kafka consumer and the analyses by the
analysis engine; nothing was edited by hand. It contains no event metadata or raw payloads and no
real customer or system data.

**Curated for the public demo.** The capture is reduced to 8 clearly different incidents in 8
services (`scripts/curate-demo-data.mjs`): a slow payment gateway, the payment timeout and the
failed checkout it causes, a database pool problem, a code defect, an expired token, a
configuration error, a fatal out-of-memory error and one cause the rules cannot classify, with
HIGH, MEDIUM and LOW confidence and INFO, WARN, ERROR and FATAL events. Records are only selected,
never edited; the script fails if a kept record would still reference a removed one.

**What demo mode does not do.** It cannot ingest, correlate or analyze anything. "Run analysis"
shows the analysis stored in the dataset. There is no sign-in: a synthetic "Demo visitor"
session (`demoAuth.ts`) is built in, without credentials, and makes no requests. Overview figures are computed from the bundled
incidents and events relative to the capture time (`capturedAt`); service health shows the
snapshot recorded during the capture. Relative times ("5 days ago") are computed against the
viewer's clock, so they grow as the dataset ages.

## Recapturing

With the Docker stack running (`docker compose up -d`), from the repository root:

```bash
python sample-data/send_sample_logs.py --application demo-webshop --analyze
cd analysis-service && python -m evaluation --live --application demo-store && cd ..
cd frontend && node scripts/capture-demo-data.mjs --applications demo-store,demo-webshop
node scripts/curate-demo-data.mjs     # keep the curated incidents (list at the top of the script)
```

The first two commands send the synthetic events through the ingestion API and run the analyses;
use application names that do not exist yet, or the new events are added to the existing
incidents. All three use the ingestion key and sign in with `RCP_USERNAME`/`RCP_PASSWORD`, or for
local development with the bootstrap admin from `.env`. The capture script only reads data; it
does not store credentials or the session in the dataset. Live builds (`npm run build`) do not include this
file (see `vite.config.ts`).

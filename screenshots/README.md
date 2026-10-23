# screenshots

Dashboard screenshots, taken on 2026-10-23 with Microsoft Edge (headless, via Playwright).

The **live** screenshots come from a fresh local Docker stack (production-style
`docker-compose.yml`) seeded only with the synthetic sample data: the events in
`sample-data/application-logs.jsonl` (application `demo-webshop`) and the 16 labelled scenarios in
`sample-data/analysis-scenarios/` (application `demo-store`), sent through the ingestion API,
correlated through Kafka and analyzed by the analysis service. They were taken signed in with the
local bootstrap accounts; no password or token is visible (the sign-in form is empty, and the
session cookie is not visible to the page).

| File | Page | Viewport |
| --- | --- | --- |
| `overview.png` | Overview, signed in as an `ADMIN` | 1440 px |
| `error-monitoring.png` | Error Monitoring, filtered to one service, with an expanded event and its stack trace | 1440 px |
| `incidents.png` | Incidents | 1440 px |
| `incident-detail.png` | Incident detail with the latest stack trace | 1440 px |
| `root-cause-analysis.png` | Root Cause Analysis: the stored analysis of an incident, its evidence and related incidents | 1440 px |
| `root-cause-analysis-viewer.png` | Root Cause Analysis as a `VIEWER`: the stored analysis without the run button | 1440 px |
| `service-health.png` | Service Health (readiness of the three services) | 1440 px |
| `login.png` | Sign-in page, empty form | 1440 px |
| `overview-mobile.png` | Overview on a phone | 390 px |
| `demo-mode-overview.png` | The [public demo](https://ai-root-cause-platform.pages.dev): Overview with the bundled, curated demo data | 1440 px |
| `grafana-dashboard.png` | Grafana *Root Cause Platform* dashboard of the same stack, last 15 minutes: the sample events sent five times (recurring occurrences of the same incidents) and once more as a new application (4 new incidents), with their analyses; no server errors | 1440 px |

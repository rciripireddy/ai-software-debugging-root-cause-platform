# screenshots

Dashboard screenshots referenced from the project README. They were taken on 2026-10-22 with
Microsoft Edge (headless, via Playwright) against the local Docker stack, showing **live data**
from the APIs: the synthetic demo applications `demo-store` and `demo-webshop` sent through the
ingestion API, correlated by Kafka and analyzed by the analysis service. All events are synthetic.

| File | Page | Viewport |
| --- | --- | --- |
| `overview.png` | Overview | 1440 px |
| `error-monitoring.png` | Error Monitoring, filtered, with an expanded event and its stack trace | 1440 px |
| `incidents.png` | Incidents (application `demo-store`) | 1440 px |
| `incident-detail.png` | Incident detail with the latest stack trace | 1440 px |
| `root-cause-analysis.png` | Root Cause Analysis after re-running an analysis | 1440 px |
| `service-health.png` | Service Health | 1440 px |
| `overview-mobile.png` | Overview | 390 px |
| `root-cause-analysis-mobile.png` | Root Cause Analysis | 390 px |
| `demo-mode-overview.png` | Overview of the demo build (`npm run build:demo`), labelled as demo data | 1440 px |

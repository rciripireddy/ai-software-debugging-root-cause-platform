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
| `root-cause-analysis.png` | Root Cause Analysis: the stored analysis of an incident, secondary sections collapsed | 1440 px |
| `service-health.png` | Service Health | 1440 px |
| `overview-mobile.png` | Overview | 390 px |
| `root-cause-analysis-mobile.png` | Root Cause Analysis | 390 px |
| `demo-mode-overview.png` | Overview of the demo build (`npm run build:demo`), labelled as demo data | 1440 px |
| `login.png` | Sign-in page (Phase 6), empty form | 1440 px |
| `login-mobile.png` | Sign-in page | 390 px |
| `overview-signed-in.png` | Overview signed in as an ADMIN, with the account area in the sidebar | 1440 px |
| `overview-signed-in-mobile.png` | Overview signed in as a VIEWER, navigation menu open | 390 px |
| `root-cause-analysis-viewer.png` | Root Cause Analysis as a VIEWER: the stored analysis without the run button | 1440 px |
| `grafana-dashboard.png` | Grafana *Root Cause Platform* dashboard (Phase 7) after the failure and recovery tests: the 5xx and outbox peaks are the database and Kafka outages | 1440 px |

All screenshots were retaken on 2026-10-22 after the dashboard's layout and typography were
simplified, signed in with local development accounts; they show no passwords or tokens (the
password field is empty, and the session cookie is not visible to the page). The demo-mode
screenshot comes from the demo build (`npm run build:demo`) and needs no sign-in.

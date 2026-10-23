# frontend

React 19 + TypeScript + Vite dashboard for the platform. It reads the ingestion, incident and
analysis APIs through a proxy (Vite dev server locally, nginx in Docker) after the user signs in,
and can also run as a self-contained demo with bundled synthetic data.

## Pages

| Route | Page | Data |
| --- | --- | --- |
| `/login` | Sign-in: username and password, field validation, progress and error states; returns to the page in `?next=` | `POST /api/v1/auth/login` |
| `/` | Overview: key figures, new incidents per day, unresolved incidents by service, errors received by service, recent incidents, latest analyses | incident stats, event activity, incident list, latest analyses |
| `/errors` | Error Monitoring: events newest first; filters for text, application, service, environment, severity; expandable details with stack trace | `GET /api/v1/logs`, `GET /api/v1/logs/{id}` |
| `/incidents` | Incidents: severity, status, service, occurrences, last seen and analysis state; filters for text, status, severity, application, service, environment | `GET /api/v1/incidents`, latest analyses |
| `/incidents/:id` | Incident detail: key facts, likely root cause, most recent occurrence with stack trace, recent events (expand for source and stack trace) | incident, event details |
| `/root-cause`, `/root-cause/:id` | Root Cause Analysis: incident picker; stored analysis or "Run analysis" (`ADMIN` only); likely cause with confidence, explanation, key evidence and limitations first; related incidents; other candidates; collapsed patterns, analysis details and history | analysis API |
| `/health` | Service Health: readiness checks of the three services every 15 s ("Healthy" means the service can reach its database), with response time and last check | readiness endpoints |

Filters and the page number are kept in the URL, so filtered views can be bookmarked.

### Presentation

Pages lead with what a reader needs first (titles, severity and status, service, counts, the
likely cause and its confidence, key evidence) and keep secondary detail available but collapsed:

- `Disclosure` (`src/components/Disclosure.tsx`) is an inline "Technical details" or "more"
  section for IDs, fingerprints, trace and correlation IDs, exact timestamps, the remaining
  evidence and the confidence calculation.
- `CollapsibleCard` is a card that starts closed with a one-line hint (patterns, analysis details,
  history).
- `IncidentTitle` shows `<exception type>: <message>` titles as the message with the short
  exception name beneath it (`splitIncidentTitle` in `src/utils/format.ts`); the full title is the
  hover text.

Both collapsible components are native `<details>` elements, so they work with the keyboard and
screen readers and keep their content in the page. Below 720 px, tables marked `data-table--cards`
(incidents and events) show each row as a card with labelled values.

## Signing in

All pages except `/login` require a session ([docs/security.md](../docs/security.md)):

- The access token is in an HttpOnly cookie set by incident-service; the dashboard's code never
  sees it. The session in memory (`src/auth/sessionStore.ts`) only holds the user, the expiry time
  and the CSRF token, which the HTTP client sends as `X-CSRF-Token` with state-changing requests.
- After a (re)load, `AuthProvider` asks `GET /api/v1/auth/me` whether the cookie is still valid.
- `RequireAuth` sends signed-out visitors to `/login?next=<page>`; only paths of this app are
  accepted as `next`.
- The session ends at its expiry time, when any service answers `401` (the user sees "Your
  session has expired"), or on sign-out. Pages then unmount, so their requests and polling stop.
- Controls a role cannot use are hidden (`canRunAnalysis`); the services enforce the roles
  regardless.
- The demo build has a built-in synthetic session and no sign-in page.

## Structure

```text
src/
├── api/          DashboardApi interface, HTTP implementation (httpApi.ts, http.ts), health checks
├── auth/         session store, AuthProvider/useAuth, RequireAuth, sign-in API, safe redirects
├── demo/         demo implementation of DashboardApi and the bundled dataset (see demo/README.md)
├── components/   layout, badges, charts, data states, stack trace, analysis report
├── hooks/        useQuery (loading, refresh, polling, cancellation), useUrlFilters, useServiceHealth
├── pages/        one component per route, including LoginPage
├── types/        API response types
├── utils/        time and number formatting, error messages
├── config.ts     environment-based settings
└── test/         fixtures, fake API and auth, render helper (signed in as ADMIN by default)
scripts/
├── capture-demo-data.mjs   captures the demo dataset from a running stack
├── curate-demo-data.mjs    reduces it to the curated set shown in the public demo
└── check-demo-build.mjs    safety check of the demo build (part of npm run build:demo)
deploy/
└── cloudflarePages.ts      _headers of the public demo on Cloudflare Pages (demo build only)
```

Pages use only the `DashboardApi` interface (through `useApi()`) and `useAuth()`, so live and
demo mode share all UI code.

## Commands

```bash
npm ci
npm run dev          # http://localhost:5173, proxies /api/* to the services on localhost
npm run dev:demo     # same UI with the bundled demo data, no backend needed
npm run lint
npm run typecheck    # TypeScript project check (tsc -b), also part of both builds
npm test             # Vitest + Testing Library
npm run build        # live build into dist/ (used by the Docker image)
npm run build:demo   # static demo build into dist-demo/ (no API calls), then checks the bundle;
                     # published as the public demo on Cloudflare Pages (docs/deployment.md)
```

Configuration (API base paths, refresh intervals, timeouts): see
[docs/configuration.md](../docs/configuration.md#dashboard).

## Docker image

The image serves the build with nginx (unprivileged, read-only file system) using
[`nginx.conf`](nginx.conf): it proxies `/api/<service>/` to the services, sets the security
headers, passes a validated `X-Correlation-Id` to the services and writes a JSON access log with
the same correlation ID (no query strings, headers or bodies). Metrics and actuator endpoints
other than health answer `404` through the proxy. See
[docs/operations.md](../docs/operations.md#logs).

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
| `/incidents` | Incidents: filters for text, status, severity, application, service, environment; analysis state per incident | `GET /api/v1/incidents`, latest analyses |
| `/incidents/:id` | Incident detail: summary, most recent occurrence with stack trace, recent events (expand for source and stack trace) | incident, event details |
| `/root-cause`, `/root-cause/:id` | Root Cause Analysis: incident picker; stored analysis or "Run analysis" (`ADMIN` only); primary and other candidates with evidence, confidence and limitations; related incidents; patterns; history | analysis API |
| `/health` | Service Health: live checks of the three services every 15 s | health endpoints |

Filters and the page number are kept in the URL, so filtered views can be bookmarked.

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
└── capture-demo-data.mjs   captures the demo dataset from a running stack
```

Pages use only the `DashboardApi` interface (through `useApi()`) and `useAuth()`, so live and
demo mode share all UI code.

## Commands

```bash
npm ci
npm run dev          # http://localhost:5173, proxies /api/* to the services on localhost
npm run dev:demo     # same UI with the bundled demo data, no backend needed
npm run lint
npm test             # Vitest + Testing Library
npm run build        # live build into dist/ (used by the Docker image)
npm run build:demo   # static demo build into dist-demo/ (no API calls)
```

Configuration (API base paths, refresh intervals, timeouts): see
[docs/configuration.md](../docs/configuration.md#dashboard).

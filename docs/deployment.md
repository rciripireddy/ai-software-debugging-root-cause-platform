# Deployment

## What is public

Only the **demo build of the dashboard** is published, as a static site on Cloudflare Pages
(free tier):

```text
public demo:      React dashboard + bundled synthetic data      (static files, Cloudflare Pages)
full platform:    React + nginx + Spring Boot + FastAPI + Kafka + PostgreSQL + Prometheus + Grafana
                  (runs locally with Docker Compose, see the README)
```

The public site has the same pages, navigation and layout as the real dashboard, but no backend:
its data is a curated set of synthetic incidents, events and analyses compiled into the bundle.
Every page is labelled **Demo data** and a banner says that nothing is ingested, correlated or
analyzed live. There is no sign-in; a built-in "Demo visitor" session is used, without any
credentials. "Re-run analysis" shows the stored analysis and says so, and Service Health shows the
snapshot recorded when the data was captured, labelled as a demo environment.

**Why the backend is not public.** The services, Kafka and PostgreSQL need a server running all
the time, and a public instance would accept events and sign-ins from anyone, which needs
operating, monitoring and abuse protection beyond a portfolio project. The static demo shows the
product without any of that, costs nothing and cannot leak data: it makes no requests at all.

## Build

| Setting | Value |
| --- | --- |
| Root directory | `frontend` |
| Build command | `npm run build:demo` |
| Output directory | `frontend/dist-demo` (`dist-demo` relative to the root directory) |
| Node.js | `24.20.0`, from `frontend/.node-version` (also used by CI) |
| Environment variables | none; the demo build needs no secrets or settings (`--mode demo` is part of the script) |

`npm run build:demo` type-checks, builds with Vite in demo mode and then runs
[`scripts/check-demo-build.mjs`](../frontend/scripts/check-demo-build.mjs), which fails the build
(on Cloudflare and in CI) if the bundle contains network code, the live API client, local or
Docker addresses, database URLs, file-system paths, e-mail addresses or a value from a local
`.env`, if `_headers` is missing or weakened, or if a `404.html` would break routing. Live builds
(`npm run build`, used by the Docker image) never contain the demo data (see `vite.config.ts`).

## Cloudflare Pages specifics

- **Routing.** The dashboard uses client-side routes (`/incidents/<id>`, `/root-cause/<id>`, ...).
  Pages serves `index.html` for any path that is not a file as long as the site has no top-level
  `404.html`, so opening or refreshing a nested URL works without a `_redirects` file. The build
  check guarantees there is no `404.html`.
- **Headers.** `vite.config.ts` adds a `_headers` file to the demo build only (content in
  [`frontend/deploy/cloudflarePages.ts`](../frontend/deploy/cloudflarePages.ts)): the same
  Content-Security-Policy, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`,
  `Permissions-Policy` and `Cross-Origin-Opener-Policy` as the nginx configuration, except
  `connect-src 'none'`: the browser itself would refuse any request the demo tried to make. All
  scripts, styles, fonts and images are self-hosted; there is no analytics and no external origin.
  Fingerprinted files under `/assets/` are cached for a year. HSTS is not set by the site:
  `pages.dev` (and the whole `.dev` domain) is HTTPS-only through the browsers' HSTS preload list.
- **Retained architecture references.** The Service Health cards show the services' readiness
  paths (`/api/ingestion/actuator/health/readiness`, ...) and the pages describe the components
  (Kafka, PostgreSQL); these are relative paths and names, not addresses. React Router's code
  contains `http://localhost` as a base for parsing URLs; it is never requested.

## Publishing

Cloudflare Pages builds from the GitHub repository: every push to `main` that changes `frontend/`
deploys the production site, and pull requests get preview URLs. Nothing in the repository
deploys (no tokens, no deployment step in CI); GitHub Actions only validates the demo build.

One-time setup in the Cloudflare dashboard (labels may differ slightly):

1. *Workers & Pages* → *Create application* → *Pages* → *Import an existing Git repository*.
2. Connect GitHub and give the Cloudflare Pages app access to **only this repository**.
3. Project name `ai-root-cause-platform`, production branch `main`, framework preset *None*,
   build command `npm run build:demo`, build output directory `dist-demo`, root directory
   (under *Advanced*) `frontend`. No environment variables.
4. *Save and Deploy*. The site is published at `https://<project name>.pages.dev` (Cloudflare adds
   a suffix if the name is taken).
5. Optional: *Settings → Build → Build watch paths* → include `frontend/*`, so commits that change
   only the backend or docs do not trigger a deployment.

## Reproducing the public build locally

```bash
cd frontend
npm ci
npm run build:demo                     # builds and checks dist-demo/
npx wrangler pages dev dist-demo       # Cloudflare's local Pages server, with _headers and routing
# or, without Cloudflare's tooling (no _headers):
npm run preview -- --mode demo --outDir dist-demo
```

## Updating the demo data

The dataset is captured from a local run on the synthetic scenarios and then curated to a few
distinct incidents; see [frontend/src/demo/README.md](../frontend/src/demo/README.md).

## Limitations

- The demo cannot ingest events or run the analysis engine; it shows stored results.
- Relative times ("1 week ago") are computed against the visitor's clock, so the data looks older
  as time passes; the capture date is shown on the Service Health page.
- Cloudflare serves `index.html` (status 200) for any unknown path, including a mistyped asset
  path, because of the client-side routing fallback; the dashboard shows its own "not found" page
  for unknown routes.

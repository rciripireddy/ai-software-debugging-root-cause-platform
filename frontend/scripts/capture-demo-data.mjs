#!/usr/bin/env node
// Captures the dashboard's demo dataset (src/demo/demo-data.json) from a running stack.
//
// The dataset consists of real API responses for the given applications, which must contain only
// synthetic demo data (see src/demo/README.md for the full procedure):
//
//   node scripts/capture-demo-data.mjs --applications demo-store,demo-webshop
//   node scripts/capture-demo-data.mjs --applications demo-store --base http://localhost:3000
//
// Only reads data: it never creates events or triggers analyses. Event metadata is not captured.

import { writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'

const { values } = parseArgs({
  options: {
    applications: { type: 'string' },
    base: { type: 'string', default: 'http://localhost:3000' },
    output: { type: 'string', default: resolve(dirname(fileURLToPath(import.meta.url)), '../src/demo/demo-data.json') },
  },
})
if (!values.applications) {
  console.error('Usage: node scripts/capture-demo-data.mjs --applications <name,name> [--base URL] [--output FILE]')
  process.exit(2)
}
const applicationNames = values.applications.split(',').map((name) => name.trim().toLowerCase())
const ingestion = `${values.base}/api/ingestion`
const incident = `${values.base}/api/incident`
const analysis = `${values.base}/api/analysis`

async function get(url, { allowNotFound = false } = {}) {
  const response = await fetch(url, { headers: { Accept: 'application/json', 'X-Correlation-Id': 'demo-data-capture' } })
  if (allowNotFound && response.status === 404) return null
  if (!response.ok) throw new Error(`GET ${url} failed with HTTP ${response.status}`)
  return response.json()
}

async function allPages(url) {
  const items = []
  for (let page = 0; ; page += 1) {
    const result = await get(`${url}${url.includes('?') ? '&' : '?'}size=100&page=${page}`)
    items.push(...result.items)
    if (page + 1 >= result.totalPages) return items
  }
}

const applications = (await get(`${ingestion}/api/v1/applications`)).items.filter((app) =>
  applicationNames.includes(app.name),
)
if (applications.length === 0) throw new Error(`No applications named ${applicationNames.join(', ')} exist.`)

const events = []
for (const name of applicationNames) {
  for (const summary of await allPages(`${ingestion}/api/v1/logs?application=${encodeURIComponent(name)}`)) {
    const { metadata: _metadata, ...detail } = await get(`${ingestion}/api/v1/logs/${summary.id}`)
    events.push(detail)
  }
}

const incidents = []
const analyses = {}
const history = {}
const related = {}
for (const name of applicationNames) {
  for (const summary of await allPages(`${incident}/api/v1/incidents?application=${encodeURIComponent(name)}`)) {
    incidents.push(await get(`${incident}/api/v1/incidents/${summary.id}`))
    const latest = await get(`${analysis}/api/v1/analysis/incidents/${summary.id}`, { allowNotFound: true })
    if (latest) {
      analyses[summary.id] = latest
      history[summary.id] = (await get(`${analysis}/api/v1/analysis/incidents/${summary.id}/history`)).items.map(
        (item) => ({ ...item, incidentId: summary.id }),
      )
      related[summary.id] = (await get(`${analysis}/api/v1/analysis/incidents/${summary.id}/related`)).items
    }
  }
}

const health = {}
for (const [id, path] of [
  ['ingestion-service', `${ingestion}/actuator/health`],
  ['incident-service', `${incident}/actuator/health`],
  ['analysis-service', `${analysis}/health`],
]) {
  const started = performance.now()
  try {
    const response = await fetch(path)
    const body = await response.json()
    health[id] = {
      status: response.ok && body.status === 'UP' ? 'UP' : 'DOWN',
      httpStatus: response.status,
      latencyMs: Math.round(performance.now() - started),
    }
  } catch {
    health[id] = { status: 'UNREACHABLE' }
  }
}

const dataset = {
  description:
    'Synthetic demo data: real API responses captured from a local run of the platform on the synthetic ' +
    `scenarios in sample-data/ (applications: ${applicationNames.join(', ')}). Not production data.`,
  capturedAt: new Date().toISOString(),
  applications,
  events,
  incidents,
  analyses,
  history,
  related,
  health,
}
await writeFile(values.output, `${JSON.stringify(dataset, null, 2)}\n`, 'utf8')
console.log(
  `Captured ${applications.length} applications, ${events.length} events, ${incidents.length} incidents and ` +
    `${Object.keys(analyses).length} analyses into ${values.output}`,
)

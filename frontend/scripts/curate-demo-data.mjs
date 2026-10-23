#!/usr/bin/env node
// Reduces a captured demo dataset (src/demo/demo-data.json, see capture-demo-data.mjs) to the
// curated set shown in the public demo: a few clearly different scenarios instead of every
// captured incident. Records are only selected, never edited; references to incidents that are
// left out are removed, and the script fails if any would remain.
//
//   node scripts/curate-demo-data.mjs [--input FILE] [--output FILE]

import { readFile, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'

// The incidents of these application/service pairs are kept, with their events and analyses.
const KEEP = [
  // A complete trace: a slow payment gateway (WARN), the payment timeout and the failed checkout,
  // plus a database pool problem and a code defect, with the INFO and WARN events around them.
  ['demo-webshop', 'checkout-service'],
  ['demo-webshop', 'payment-service'],
  ['demo-webshop', 'inventory-service'],
  ['demo-webshop', 'catalog-service'],
  // Other categories: authentication, configuration, resource exhaustion (FATAL) and an
  // unknown cause with LOW confidence.
  ['demo-store', 'account-service'],
  ['demo-store', 'shipping-service'],
  ['demo-store', 'report-service'],
  ['demo-store', 'loyalty-service'],
]

const datasetPath = resolve(dirname(fileURLToPath(import.meta.url)), '../src/demo/demo-data.json')
const { values } = parseArgs({
  options: {
    input: { type: 'string', default: datasetPath },
    output: { type: 'string', default: datasetPath },
  },
})

const data = JSON.parse(await readFile(values.input, 'utf8'))
const kept = (application) => KEEP.some(([name, service]) => application.name === name && application.service === service)
const pick = (record, ids) => Object.fromEntries(Object.entries(record).filter(([id]) => ids.has(id)))

const incidents = data.incidents.filter((incident) => kept(incident.application))
const incidentIds = new Set(incidents.map((incident) => incident.id))
const relatedToKept = (items) => items.filter((related) => incidentIds.has(related.incidentId))
const analyses = Object.fromEntries(
  Object.entries(pick(data.analyses, incidentIds)).map(([id, analysis]) => [
    id,
    { ...analysis, relatedIncidents: relatedToKept(analysis.relatedIncidents) },
  ]),
)
const names = [...new Set(incidents.map((incident) => incident.application.name))].sort()

const curated = {
  description:
    'Synthetic demo data: real API responses captured from a local run of the platform on the synthetic ' +
    `scenarios in sample-data/ (applications: ${names.join(', ')}), curated to ${incidents.length} incidents. ` +
    'Not production data.',
  capturedAt: data.capturedAt,
  applications: data.applications.filter(kept),
  events: data.events.filter((event) => kept(event.application)),
  incidents,
  analyses,
  history: pick(data.history, incidentIds),
  related: Object.fromEntries(Object.entries(pick(data.related, incidentIds)).map(([id, items]) => [id, relatedToKept(items)])),
  health: data.health,
}

// Nothing that was left out may still be referenced anywhere in the curated dataset.
const json = JSON.stringify(curated, null, 2)
const removedIds = [
  ...data.incidents.filter((incident) => !incidentIds.has(incident.id)).map((incident) => incident.id),
  ...data.events.filter((event) => !kept(event.application)).map((event) => event.id),
  ...Object.values(data.analyses)
    .filter((analysis) => !incidentIds.has(analysis.incidentId))
    .map((analysis) => analysis.id),
]
const dangling = removedIds.filter((id) => json.includes(id))
if (dangling.length > 0) {
  console.error(`Curated dataset still references ${dangling.length} removed record(s): ${dangling.slice(0, 5).join(', ')}`)
  process.exit(1)
}

await writeFile(values.output, `${json}\n`)
console.log(
  `Kept ${incidents.length} of ${data.incidents.length} incidents, ${curated.events.length} of ${data.events.length} events, ` +
    `${curated.applications.length} of ${data.applications.length} services.`,
)

#!/usr/bin/env node
// Checks the public demo build (dist-demo/) before it can be published. Runs as the last step of
// `npm run build:demo`, so a failing check fails the build on Cloudflare Pages and in CI.
//
//   - Cloudflare Pages files: _headers with the expected security headers, no 404.html (which
//     would turn off Pages' fallback to index.html for client-side routes);
//   - no network code: no fetch/XMLHttpRequest/WebSocket/EventSource/sendBeacon, and not the live
//     API client (its CSRF header);
//   - nothing local or internal: no localhost or 127.0.0.1 addresses with ports, no Docker service
//     addresses, database URLs, file-system paths or e-mail addresses, and no value of the local
//     .env file (if there is one; values are never printed);
//   - the live build (dist/, if present) contains neither the demo dataset nor _headers.

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const demo = join(frontend, 'dist-demo')
const live = join(frontend, 'dist')
const failures = []
const fail = (message) => failures.push(message)

function files(directory) {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name)
    return statSync(path).isDirectory() ? files(path) : [path]
  })
}

if (!existsSync(join(demo, 'index.html'))) {
  console.error('dist-demo/index.html is missing: run `npm run build:demo` first.')
  process.exit(1)
}

// Cloudflare Pages files.
if (existsSync(join(demo, '404.html'))) fail('dist-demo/404.html exists: Pages would no longer serve index.html for app routes')
const headers = existsSync(join(demo, '_headers')) ? readFileSync(join(demo, '_headers'), 'utf8') : ''
for (const expected of [
  "connect-src 'none'",
  "frame-ancestors 'none'",
  "script-src 'self'",
  'X-Content-Type-Options: nosniff',
  'Referrer-Policy:',
  'Permissions-Policy:',
]) {
  if (!headers.includes(expected)) fail(`dist-demo/_headers lacks "${expected}"`)
}
if (/https?:\/\//.test(headers)) fail('dist-demo/_headers allows an external origin')

// The bundle itself.
const forbidden = [
  [/\bfetch\(|XMLHttpRequest|new WebSocket|EventSource|sendBeacon/, 'network code'],
  [/X-CSRF-Token/, 'the live API client'],
  [/localhost:\d+|127\.0\.0\.1|0\.0\.0\.0|host\.docker\.internal/, 'a local address'],
  [/\b(ingestion-service|incident-service|analysis-service|postgres|kafka|prometheus|grafana):\d+/i, 'a Docker service address'],
  [/jdbc:|postgres(ql)?:\/\//i, 'a database URL'],
  [/[A-Za-z]:\\{1,2}Users|\/home\/[a-z]|\/Users\/[A-Za-z]/, 'a file-system path'],
  [/[\w.+-]+@[\w-]+\.(com|org|net|io|dev|de|uk|in)\b/i, 'an e-mail address'],
]
const envFile = join(frontend, '..', '.env')
const envValues = existsSync(envFile)
  ? readFileSync(envFile, 'utf8')
      .split(/\r?\n/)
      .filter((line) => line.includes('=') && !line.trimStart().startsWith('#'))
      .map((line) => [line.slice(0, line.indexOf('=')).trim(), line.slice(line.indexOf('=') + 1).trim()])
      .filter(([, value]) => value.length >= 8)
  : []

const bundle = files(demo).filter((path) => /\.(html|js|css|json|svg|txt)$/.test(path) || path.endsWith('_headers'))
for (const path of bundle) {
  const text = readFileSync(path, 'utf8')
  const name = relative(frontend, path).replaceAll('\\', '/')
  for (const [pattern, what] of forbidden) {
    if (pattern.test(text)) fail(`${name} contains ${what}`)
  }
  for (const [key, value] of envValues) {
    if (text.includes(value)) fail(`${name} contains the value of ${key} from .env`)
  }
}

// The live build stays free of demo content.
if (existsSync(live)) {
  for (const path of files(live)) {
    const name = relative(frontend, path).replaceAll('\\', '/')
    if (/demo-data/.test(name) || name.endsWith('_headers')) fail(`${name}: the live build contains demo-only files`)
  }
}

if (failures.length > 0) {
  console.error(`Public demo build check failed:\n${failures.map((f) => `  - ${f}`).join('\n')}`)
  process.exit(1)
}
console.log(
  `Public demo build check passed: ${bundle.length} files, _headers present, no network code, local addresses or secrets` +
    `${envValues.length ? ` (also checked ${envValues.length} .env values)` : ''}.`,
)

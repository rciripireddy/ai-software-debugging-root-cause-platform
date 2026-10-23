/**
 * Files for the public demo on Cloudflare Pages (see docs/deployment.md). vite.config.ts adds
 * them to the demo build (`npm run build:demo`) only; live builds are served by nginx, which sets
 * the same headers itself (security-headers.conf).
 *
 * No `_redirects` file: Pages serves index.html for every path that is not a file when the site
 * has no top-level 404.html, which is how the dashboard's client-side routes (/incidents/<id>,
 * /root-cause/<id>, ...) work when opened or refreshed directly.
 */
export const CLOUDFLARE_PAGES_FILES: Record<string, string> = {
  // Same policy as security-headers.conf, except connect-src 'none': the demo makes no requests
  // at all (its data is bundled), so the browser would also refuse any API call.
  _headers: `/*
  Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'none'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'
  X-Content-Type-Options: nosniff
  X-Frame-Options: DENY
  Referrer-Policy: same-origin
  Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=()
  Cross-Origin-Opener-Policy: same-origin

/assets/*
  Cache-Control: public, max-age=31536000, immutable
`,
}

/**
 * The page to open after signing in, from the `next` query parameter. Only paths of this app are
 * accepted, so a crafted link cannot send the user to another site after sign-in.
 */
export function safeNextPath(next: string | null): string {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\') || next.startsWith('/login')) {
    return '/'
  }
  return next
}

/** The sign-in page URL that returns to `path` afterwards. */
export function loginPath(path: string): string {
  return path === '/' ? '/login' : `/login?next=${encodeURIComponent(path)}`
}

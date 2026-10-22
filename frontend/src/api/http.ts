// Shared HTTP handling for the backend APIs: JSON requests with a timeout, RFC 9457 problem
// details turned into ApiError, and a light shape check of every response.

export type ServiceName = 'ingestion-service' | 'incident-service' | 'analysis-service'

export type ApiErrorKind =
  /** The service could not be reached (network error, timeout, or the proxy answered 502–504). */
  | 'unavailable'
  /** The service answered with an error status (problem details when available). */
  | 'http'
  /** The service answered 2xx with a body that does not have the expected shape. */
  | 'invalid-response'

export class ApiError extends Error {
  readonly kind: ApiErrorKind
  readonly service: ServiceName
  readonly status?: number
  readonly title: string
  readonly correlationId?: string

  constructor(options: {
    kind: ApiErrorKind
    service: ServiceName
    title: string
    detail: string
    status?: number
    correlationId?: string
  }) {
    super(options.detail)
    this.name = 'ApiError'
    this.kind = options.kind
    this.service = options.service
    this.status = options.status
    this.title = options.title
    this.correlationId = options.correlationId
  }

  get isNotFound(): boolean {
    return this.kind === 'http' && this.status === 404
  }

  get isUnavailable(): boolean {
    return this.kind === 'unavailable' || (this.kind === 'http' && this.status === 503)
  }
}

export type Validator<T> = (body: unknown) => body is T

export interface RequestOptions {
  method?: 'GET' | 'POST'
  signal?: AbortSignal
}

export interface HttpClient {
  request<T>(service: ServiceName, url: string, validate: Validator<T>, options?: RequestOptions): Promise<T>
}

// nginx and the Vite proxy answer these when the backend container is not reachable.
const GATEWAY_STATUSES = new Set([502, 503, 504])

function isProblem(body: unknown): body is { title?: string; detail?: string; correlationId?: string } {
  return typeof body === 'object' && body !== null && ('title' in body || 'detail' in body)
}

export function createHttpClient(fetchImpl: typeof fetch, timeoutMs: number): HttpClient {
  return {
    async request<T>(service: ServiceName, url: string, validate: Validator<T>, options: RequestOptions = {}) {
      const timeout = AbortSignal.timeout(timeoutMs)
      const signal = options.signal ? AbortSignal.any([options.signal, timeout]) : timeout
      let response: Response
      try {
        response = await fetchImpl(url, {
          method: options.method ?? 'GET',
          headers: { Accept: 'application/json' },
          signal,
        })
      } catch (error) {
        if (options.signal?.aborted) throw error // cancelled by the caller, not a failure
        throw new ApiError({
          kind: 'unavailable',
          service,
          title: `${service} unavailable`,
          detail: timeout.aborted
            ? `${service} did not respond within ${Math.round(timeoutMs / 1000)} seconds.`
            : `${service} could not be reached.`,
        })
      }

      const body: unknown = await response.json().catch(() => undefined)
      if (!response.ok) {
        const problem = isProblem(body) ? body : undefined
        if (!problem && GATEWAY_STATUSES.has(response.status)) {
          throw new ApiError({
            kind: 'unavailable',
            service,
            status: response.status,
            title: `${service} unavailable`,
            detail: `${service} could not be reached (HTTP ${response.status} from the proxy).`,
          })
        }
        throw new ApiError({
          kind: 'http',
          service,
          status: response.status,
          title: problem?.title ?? `HTTP ${response.status}`,
          detail: problem?.detail ?? `${service} answered with HTTP ${response.status}.`,
          correlationId: problem?.correlationId,
        })
      }
      if (!validate(body)) {
        throw new ApiError({
          kind: 'invalid-response',
          service,
          status: response.status,
          title: 'Unexpected response',
          detail: `${service} returned a response the dashboard does not understand.`,
        })
      }
      return body
    },
  }
}

/** Builds `path?key=value&…`, leaving out empty values; arrays become repeated parameters. */
export function withQuery(path: string, params: Record<string, string | number | string[] | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue
    if (Array.isArray(value)) value.forEach((item) => search.append(key, item))
    else search.append(key, String(value))
  }
  const query = search.toString()
  return query ? `${path}?${query}` : path
}

// Shape checks: enough to catch a wrong endpoint or an HTML error page, not a full schema.

export function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function hasFields(value: unknown, ...fields: string[]): value is Record<string, unknown> {
  return isObject(value) && fields.every((field) => field in value)
}

export function isPage<T>(itemCheck: (item: unknown) => boolean) {
  return (body: unknown): body is { items: T[]; page: number; size: number; totalItems: number; totalPages: number } =>
    hasFields(body, 'items', 'page', 'size', 'totalItems', 'totalPages') &&
    Array.isArray(body.items) &&
    body.items.every(itemCheck)
}

export function isItems<T>(itemCheck: (item: unknown) => boolean) {
  return (body: unknown): body is { items: T[] } =>
    hasFields(body, 'items') && Array.isArray(body.items) && body.items.every(itemCheck)
}

import type {
  AnalysisSummary,
  Candidate,
  EventDetail,
  EventSummary,
  IncidentAnalysis,
  IncidentDetail,
  IncidentStats,
  IncidentSummary,
  Page,
} from '../types/api'

// Test data shaped like real API responses (taken from a local run on the synthetic scenarios).

export const PAYMENT_INCIDENT_ID = '01a16414-f904-7b87-a395-a8102fd956c5'
export const CHECKOUT_INCIDENT_ID = '01a16414-f905-7c3c-810d-fd1e0c4eb42c'
export const EXPORT_INCIDENT_ID = '01a16414-fdd4-7a46-9ac5-8983170dc33b'

const application = (service: string, environment = 'staging') => ({
  id: `app-${service}-${environment}`,
  name: 'demo-store',
  service,
  environment,
})

export const STACK_TRACE = [
  'java.net.SocketTimeoutException: Read timed out',
  '\tat java.base/sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:288)',
  '\tat com.shop.payment.GatewayClient.authorize(GatewayClient.java:74)',
  '\tat com.shop.payment.PaymentService.charge(PaymentService.java:41)',
].join('\n')

export function eventSummary(overrides: Partial<EventSummary> = {}): EventSummary {
  return {
    id: 'evt-1',
    application: application('payment-service'),
    timestamp: '2026-10-14T10:13:02.677Z',
    severity: 'ERROR',
    message: 'Payment authorization failed',
    exceptionType: 'java.net.SocketTimeoutException',
    source: 'com.shop.payment.GatewayClient',
    host: 'payment-7c9d4-abcde',
    traceId: '8c5d2f4b0e6a7b12d4f5a6b7c8d9e0f1',
    correlationId: null,
    fingerprint: '9910dd1f3cee53f5cd2fb547aac790bfad744ca3f8ccbfde75a98d60a20af30c',
    hasStackTrace: true,
    receivedAt: '2026-10-22T14:47:30.120Z',
    ...overrides,
  }
}

export function eventDetail(overrides: Partial<EventDetail> = {}): EventDetail {
  const summary: Partial<EventSummary> = eventSummary()
  delete summary.hasStackTrace
  return { ...(summary as Omit<EventSummary, 'hasStackTrace'>), stackTrace: STACK_TRACE, ...overrides }
}

export function page<T>(items: T[], pageNumber = 0, size = 20, totalItems = items.length): Page<T> {
  return { items, page: pageNumber, size, totalItems, totalPages: Math.max(1, Math.ceil(totalItems / size)) }
}

export function incidentSummary(overrides: Partial<IncidentSummary> = {}): IncidentSummary {
  return {
    id: PAYMENT_INCIDENT_ID,
    title: 'java.net.SocketTimeoutException: Payment authorization failed',
    status: 'OPEN',
    severity: 'ERROR',
    application: application('payment-service'),
    occurrenceCount: 4,
    firstSeenAt: '2026-10-14T10:02:11.204Z',
    lastSeenAt: '2026-10-14T10:13:02.677Z',
    fingerprint: '9910dd1f3cee53f5cd2fb547aac790bfad744ca3f8ccbfde75a98d60a20af30c',
    createdAt: '2026-10-22T14:47:31.000Z',
    updatedAt: '2026-10-22T14:47:33.000Z',
    ...overrides,
  }
}

export function incidentDetail(overrides: Partial<IncidentDetail> = {}): IncidentDetail {
  return {
    ...incidentSummary(),
    summary: null,
    recentEventLimit: 50,
    recentEvents: [
      {
        id: 'evt-1',
        timestamp: '2026-10-14T10:13:02.677Z',
        severity: 'ERROR',
        message: 'Payment authorization failed',
        exceptionType: 'java.net.SocketTimeoutException',
        host: 'payment-7c9d4-abcde',
        traceId: '8c5d2f4b0e6a7b12d4f5a6b7c8d9e0f1',
        correlationId: 'order-1001',
      },
      {
        id: 'evt-2',
        timestamp: '2026-10-14T10:09:30.051Z',
        severity: 'ERROR',
        message: 'Payment authorization failed',
        exceptionType: 'java.net.SocketTimeoutException',
        host: 'payment-7c9d4-fghij',
        traceId: '7b4c1e3a9d5f6a01c3e4f5a6b7c8d9e0',
        correlationId: null,
      },
    ],
    ...overrides,
  }
}

export function stats(overrides: Partial<IncidentStats> = {}): IncidentStats {
  return {
    totalIncidents: 17,
    byStatus: { OPEN: 15, INVESTIGATING: 1, RESOLVED: 1 },
    unresolvedBySeverity: { DEBUG: 0, INFO: 0, WARN: 0, ERROR: 14, FATAL: 2 },
    servicesAffected: 13,
    topServices: [
      { service: 'payment-service', unresolvedIncidents: 3, occurrences: 8 },
      { service: 'checkout-service', unresolvedIncidents: 1, occurrences: 4 },
    ],
    newIncidentsPerDay: [
      { date: '2026-10-20', count: 0 },
      { date: '2026-10-21', count: 5 },
      { date: '2026-10-22', count: 2 },
    ],
    generatedAt: '2026-10-22T15:00:00Z',
    ...overrides,
  }
}

export function candidate(overrides: Partial<Candidate> = {}): Candidate {
  return {
    rank: 1,
    category: 'TIMEOUT',
    title: 'Timeout waiting for a downstream dependency called from GatewayClient.authorize',
    confidence: 'HIGH',
    score: 5,
    maxScore: 6,
    confidenceBasis: [
      '+2 exception type matches the timeout rules in 4/4 events',
      '+1 messages match the timeout rules in 4/4 events',
      '+1 the same evidence appears in 4/4 analyzed events',
      '+1 same top application frame in 4/4 events with stack traces',
      '= 5 of 6 points: HIGH',
    ],
    explanation: 'The exception types or messages show that an operation exceeded its time limit.',
    evidence: [
      { signal: 'EXCEPTION_TYPE', description: 'java.net.SocketTimeoutException in 4/4 events', matched: 4, total: 4 },
      { signal: 'MESSAGE', description: 'Messages contain "timed out" in 4/4 events', matched: 4, total: 4 },
      { signal: 'RECURRENCE', description: '4 occurrences within 11 minutes (burst).', matched: 4, total: null },
      {
        signal: 'CODE_LOCATION',
        description: 'Top application frame of payment-service: GatewayClient.authorize in 4/4 events with stack traces',
        matched: 4,
        total: 4,
      },
      { signal: 'HOST', description: 'Events came from 2 hosts, so the failure is not limited to one host', matched: null, total: null },
    ],
    affectedComponent: {
      service: 'payment-service',
      environment: 'staging',
      location: 'GatewayClient.authorize',
      source: 'com.shop.payment.GatewayClient',
    },
    relatedIncidentIds: [],
    relatedEventIds: ['evt-1'],
    limitations: [
      'No telemetry from the called component is available, so it cannot be confirmed whether it was slow, overloaded or unreachable.',
    ],
    ...overrides,
  }
}

export function analysis(overrides: Partial<IncidentAnalysis> = {}): IncidentAnalysis {
  const primary = overrides.candidates?.[0] ?? candidate()
  return {
    id: 'analysis-1',
    revision: 1,
    incidentId: PAYMENT_INCIDENT_ID,
    analysisVersion: '1.0.0',
    generatedAt: '2026-10-22T14:49:18.696Z',
    incident: {
      id: PAYMENT_INCIDENT_ID,
      title: 'java.net.SocketTimeoutException: Payment authorization failed',
      status: 'OPEN',
      severity: 'ERROR',
      application: 'demo-store',
      service: 'payment-service',
      environment: 'staging',
      occurrenceCount: 4,
      firstSeenAt: '2026-10-14T10:02:11.204Z',
      lastSeenAt: '2026-10-14T10:13:02.677Z',
      fingerprint: '9910dd1f3cee53f5cd2fb547aac790bfad744ca3f8ccbfde75a98d60a20af30c',
    },
    summary:
      'java.net.SocketTimeoutException in payment-service (staging): 4 occurrences within 11 minutes (burst). Most likely cause based on available evidence (HIGH confidence): Timeout waiting for a downstream dependency called from GatewayClient.authorize.',
    confidence: primary.confidence,
    primaryCandidate: primary,
    candidates: [primary],
    patterns: {
      recurrence: {
        pattern: 'BURST',
        description: '4 occurrences within 11 minutes (burst).',
        occurrenceCount: 4,
        eventsAnalyzed: 4,
        firstSeenAt: '2026-10-14T10:02:11.204Z',
        lastSeenAt: '2026-10-14T10:13:02.677Z',
        spanSeconds: 651,
        windowSeconds: 900,
        maxOccurrencesInWindow: 4,
        medianIntervalSeconds: 216,
      },
      dominantException: { value: 'java.net.SocketTimeoutException', count: 4, total: 4 },
      topApplicationFrame: { value: 'GatewayClient.authorize', count: 4, total: 4 },
      repeatedFrames: [],
      source: { value: 'com.shop.payment.GatewayClient', count: 4, total: 4 },
      hosts: { distinct: 2, top: [{ value: 'payment-7c9d4-abcde', count: 3, total: 4 }], concentrated: false },
      precedingErrors: [],
      subsequentErrors: [
        {
          incidentId: CHECKOUT_INCIDENT_ID,
          title: 'com.shop.checkout.PaymentFailedException: Order could not be completed: payment failed',
          service: 'checkout-service',
          linkedEvents: 4,
          sharedTraceEvents: 4,
          medianGapSeconds: 0.255,
          basis: 'TRACE',
        },
      ],
    },
    relatedIncidents: [
      {
        incidentId: '01a16414-fd38-7029-84d8-3ad04984b6ac',
        title: 'java.net.SocketTimeoutException: Payment authorization failed (production)',
        status: 'OPEN',
        service: 'payment-service',
        environment: 'production',
        score: 100,
        strength: 'STRONG',
        matchingSignals: [
          { signal: 'SAME_FINGERPRINT', points: 40, description: 'Same fingerprint (same error in production)' },
          { signal: 'SAME_EXCEPTION_TYPE', points: 25, description: 'Same exception type java.net.SocketTimeoutException' },
        ],
      },
    ],
    dataCoverage: { occurrenceCount: 4, eventsAnalyzed: 4, eventsWithDetails: 4, eventsWithStackTrace: 4, incidentsCompared: 15 },
    limitations: [
      'Rule-based analysis of error events only: suggested causes are hypotheses supported by the listed evidence, not confirmed root causes.',
    ],
    ...overrides,
  }
}

export function analysisSummary(overrides: Partial<AnalysisSummary> = {}): AnalysisSummary {
  return {
    id: 'analysis-1',
    incidentId: PAYMENT_INCIDENT_ID,
    revision: 1,
    analysisVersion: '1.0.0',
    generatedAt: '2026-10-22T14:49:18.696Z',
    confidence: 'HIGH',
    primaryCategory: 'TIMEOUT',
    primaryTitle: 'Timeout waiting for a downstream dependency called from GatewayClient.authorize',
    summary: 'java.net.SocketTimeoutException in payment-service (staging): …',
    ...overrides,
  }
}

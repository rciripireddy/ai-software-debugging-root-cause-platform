import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { createFakeApi, unavailable } from '../test/fakeApi'
import { analysis, analysisSummary, candidate, PAYMENT_INCIDENT_ID } from '../test/fixtures'
import { renderRoute } from '../test/renderRoute'

const path = `/root-cause/${PAYMENT_INCIDENT_ID}`

describe('RootCauseAnalysisPage', () => {
  it('asks to select an incident and lists incidents with their analysis state', async () => {
    renderRoute('/root-cause')

    expect(screen.getByRole('heading', { name: 'Select an incident' })).toBeInTheDocument()
    const picker = screen.getByRole('region', { name: 'Incidents' })
    expect(await within(picker).findByRole('link', { name: /Payment authorization failed/ })).toHaveAttribute('href', path)
    expect(await within(picker).findByText('HIGH')).toBeInTheDocument()
  })

  it('shows the stored analysis: primary candidate, evidence, confidence and limitations', async () => {
    renderRoute(path)

    const primary = await screen.findByRole('article', { name: /Timeout waiting for a downstream dependency/ })
    expect(within(primary).getByText('Most likely cause based on available evidence')).toBeInTheDocument()
    expect(within(primary).getByText('High confidence')).toBeInTheDocument()
    expect(within(primary).getByText('Timeout')).toBeInTheDocument()
    expect(primary).toHaveTextContent('payment-service · staging')
    expect(primary).toHaveTextContent('GatewayClient.authorize')
    expect(within(primary).getByText('java.net.SocketTimeoutException in 4/4 events')).toBeInTheDocument()
    expect(within(primary).getByText('Exception type')).toBeInTheDocument()
    expect(within(primary).getByText(/No telemetry from the called component/)).toBeInTheDocument()
    // The point calculation is available but collapsed.
    expect(within(primary).getByText(/How this confidence was calculated \(5 of 6 points\)/)).toBeInTheDocument()
    expect(within(primary).getByText('= 5 of 6 points: HIGH')).not.toBeVisible()
    expect(screen.getByRole('heading', { name: 'Limitations of this analysis' })).toBeInTheDocument()
    expect(screen.getByText(/not confirmed root causes/, { selector: 'li' })).toBeInTheDocument()
  })

  it('never presents a candidate as a confirmed root cause', async () => {
    renderRoute(path)

    await screen.findByRole('article', { name: /Timeout waiting/ })
    expect(screen.queryByText(/^Root cause:/)).not.toBeInTheDocument()
    expect(screen.getByText(/hypotheses supported by the evidence shown, not confirmed root causes/)).toBeInTheDocument()
  })

  it.each([
    ['MEDIUM', 'Medium confidence'],
    ['LOW', 'Low confidence'],
  ] as const)('labels a %s candidate as a potential root cause', async (confidence, label) => {
    const weak = candidate({ confidence, category: confidence === 'LOW' ? 'UNKNOWN' : 'DATABASE', title: 'Weaker candidate' })
    renderRoute(path, createFakeApi({ getLatestAnalysis: vi.fn(async () => analysis({ candidates: [weak], confidence })) }))

    const primary = await screen.findByRole('article', { name: 'Weaker candidate' })
    expect(within(primary).getByText('Potential root cause')).toBeInTheDocument()
    expect(within(primary).getByText(label)).toBeInTheDocument()
    expect(within(primary).queryByText('Most likely cause based on available evidence')).not.toBeInTheDocument()
  })

  it('shows related incidents with their score and matching signals', async () => {
    renderRoute(path)

    const related = (await screen.findByRole('heading', { name: 'Related incidents' })).closest('section')!
    expect(related).toHaveTextContent('100/100 · Strong')
    expect(related).toHaveTextContent('+40Same fingerprint (same error in production)')
    expect(within(related).getByRole('link', { name: /production/ })).toHaveAttribute(
      'href',
      '/root-cause/01a16414-fd38-7029-84d8-3ad04984b6ac',
    )
  })

  it('shows trace-linked errors that followed the incident', async () => {
    renderRoute(path)

    const patterns = (await screen.findByRole('heading', { name: 'Patterns' })).closest('section')!
    expect(patterns).toHaveTextContent('Errors that followed this incident')
    expect(patterns).toHaveTextContent('4 shared trace/correlation IDs')
  })

  it('runs an analysis for an incident that has none and shows the result', async () => {
    const user = userEvent.setup()
    const api = createFakeApi({
      getLatestAnalysis: vi.fn(async () => null),
      analysisHistory: vi.fn(async () => []),
    })
    renderRoute(path, api)

    expect(await screen.findByRole('heading', { name: 'Not analyzed yet' })).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'Run analysis' }))

    expect(api.analyzeIncident).toHaveBeenCalledWith(PAYMENT_INCIDENT_ID)
    expect(await screen.findByText('Analysis completed: revision 2.')).toBeInTheDocument()
    expect(screen.getByRole('article', { name: /Timeout waiting/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Re-run analysis' })).toBeInTheDocument()
  })

  it('shows the analysis history when there are several revisions', async () => {
    renderRoute(
      path,
      createFakeApi({
        analysisHistory: vi.fn(async () => [analysisSummary({ id: 'b', revision: 2 }), analysisSummary({ revision: 1 })]),
      }),
    )

    const history = (await screen.findByRole('heading', { name: 'Analysis history' })).closest('section')!
    expect(within(history).getAllByRole('listitem')).toHaveLength(2)
  })

  it('explains when the analysis service is unavailable and keeps the incident visible', async () => {
    renderRoute(path, createFakeApi({ getLatestAnalysis: vi.fn().mockRejectedValue(unavailable('analysis-service')) }))

    expect(await screen.findByText('Analysis service unavailable')).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: /Payment authorization failed/, current: false })).toBeInTheDocument()
  })

  it('reports a failed analysis run without losing the page', async () => {
    const user = userEvent.setup()
    renderRoute(path, createFakeApi({ analyzeIncident: vi.fn().mockRejectedValue(unavailable('incident-service')) }))

    await user.click(await screen.findByRole('button', { name: 'Re-run analysis' }))

    expect(await screen.findByText('Incident service unavailable')).toBeInTheDocument()
    expect(screen.getByRole('article', { name: /Timeout waiting/ })).toBeInTheDocument()
  })
})

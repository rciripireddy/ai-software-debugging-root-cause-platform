import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { createFakeApi } from '../test/fakeApi'
import { eventDetail, eventSummary, page } from '../test/fixtures'
import { renderRoute } from '../test/renderRoute'

const MARKUP = '<img src=x onerror="window.__injected = true"><script>window.__injected = true</script>'

describe('untrusted text from monitored applications', () => {
  it('renders messages and stack traces as plain text, never as HTML', async () => {
    const user = userEvent.setup()
    const api = createFakeApi({
      listEvents: vi.fn(async () => page([eventSummary({ message: `Checkout failed ${MARKUP}` })], 0, 25)),
      getEvent: vi.fn(async () => eventDetail({ message: MARKUP, stackTrace: `java.lang.IllegalStateException: ${MARKUP}` })),
    })
    const { container } = renderRoute('/errors', api)

    expect(await screen.findByText(`Checkout failed ${MARKUP}`)).toBeInTheDocument()
    await user.click(screen.getAllByRole('button', { name: 'Show event details' })[0])

    expect(await screen.findByText(new RegExp(`IllegalStateException: ${MARKUP.slice(0, 20)}`))).toBeInTheDocument()
    expect(container.querySelector('img[src="x"]')).toBeNull()
    expect(container.querySelector('main script')).toBeNull()
    expect((window as unknown as { __injected?: boolean }).__injected).toBeUndefined()
  })
})

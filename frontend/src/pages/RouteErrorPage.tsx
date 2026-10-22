import { useRouteError } from 'react-router'
import { ErrorState } from '../components/DataState'

/** Shown instead of a page that failed to render; navigation keeps working. */
export function RouteErrorPage() {
  const error = useRouteError()
  return (
    <section className="card">
      <h1>This page could not be displayed</h1>
      <ErrorState error={error} onRetry={() => window.location.reload()} />
    </section>
  )
}

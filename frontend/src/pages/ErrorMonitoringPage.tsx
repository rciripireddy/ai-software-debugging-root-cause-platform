import { Inbox } from 'lucide-react'
import { EmptyState } from '../components/EmptyState'
import { PageHeader } from '../components/PageHeader'

const COLUMNS = ['Last seen', 'Service', 'Severity', 'Error', 'Occurrences']

export function ErrorMonitoringPage() {
  return (
    <>
      <PageHeader
        title="Error Monitoring"
        description="Search and filter error events reported by monitored applications."
        status="preview"
      />

      <section className="card" aria-label="Error events">
        <div className="toolbar" aria-label="Filters (not available yet)">
          <input type="search" placeholder="Search error messages…" aria-label="Search error messages" disabled />
          <select aria-label="Filter by service" disabled defaultValue="">
            <option value="">All services</option>
          </select>
          <select aria-label="Filter by severity" disabled defaultValue="">
            <option value="">All severities</option>
          </select>
        </div>

        <div className="table-wrapper">
          <table>
            <thead>
              <tr>
                {COLUMNS.map((column) => (
                  <th key={column} scope="col">
                    {column}
                  </th>
                ))}
              </tr>
            </thead>
          </table>
        </div>
        <EmptyState icon={Inbox} title="Not connected to the error data yet">
          The ingestion API already stores error events, but this page is not connected to it yet
          (planned for Phase 5).
        </EmptyState>
      </section>
    </>
  )
}

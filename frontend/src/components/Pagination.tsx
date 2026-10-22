import { ChevronLeft, ChevronRight } from 'lucide-react'
import { formatCount } from '../utils/format'

interface PaginationProps {
  page: number
  totalPages: number
  totalItems: number
  itemLabel: string
  onPageChange: (page: number) => void
}

export function Pagination({ page, totalPages, totalItems, itemLabel, onPageChange }: PaginationProps) {
  const pages = Math.max(totalPages, 1)
  return (
    <nav className="pagination" aria-label={`${itemLabel} pages`}>
      <span className="muted">
        {formatCount(totalItems)} {itemLabel} · page {Math.min(page + 1, pages)} of {pages}
      </span>
      <div className="pagination__buttons">
        <button
          type="button"
          className="button button--small"
          onClick={() => onPageChange(page - 1)}
          disabled={page <= 0}
        >
          <ChevronLeft size={14} aria-hidden="true" />
          Previous
        </button>
        <button
          type="button"
          className="button button--small"
          onClick={() => onPageChange(page + 1)}
          disabled={page + 1 >= pages}
        >
          Next
          <ChevronRight size={14} aria-hidden="true" />
        </button>
      </div>
    </nav>
  )
}

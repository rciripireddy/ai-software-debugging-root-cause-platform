import { Info } from 'lucide-react'
import type { ReactNode } from 'react'

export function Notice({ children }: { children: ReactNode }) {
  return (
    <div className="notice" role="note">
      <Info size={18} aria-hidden="true" />
      <p>{children}</p>
    </div>
  )
}

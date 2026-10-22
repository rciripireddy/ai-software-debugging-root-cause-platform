import { Search, X } from 'lucide-react'
import { useState, type FormEvent } from 'react'

interface SelectFilterProps {
  id: string
  label: string
  value: string
  options: { value: string; label: string }[]
  allLabel: string
  onChange: (value: string) => void
}

export function SelectFilter({ id, label, value, options, allLabel, onChange }: SelectFilterProps) {
  return (
    <div className="filter">
      <label htmlFor={id}>{label}</label>
      <select id={id} value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">{allLabel}</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
        {value && !options.some((option) => option.value === value) && <option value={value}>{value}</option>}
      </select>
    </div>
  )
}

interface SearchFilterProps {
  id: string
  label: string
  placeholder: string
  value: string
  onSearch: (value: string) => void
}

/** Text search applied on Enter or with the search button, not on every keystroke. */
export function SearchFilter({ id, label, placeholder, value, onSearch }: SearchFilterProps) {
  const [draft, setDraft] = useState(value)
  const [applied, setApplied] = useState(value)
  // Follow external changes (e.g. "Clear filters") without an effect.
  if (value !== applied) {
    setApplied(value)
    setDraft(value)
  }

  const submit = (event: FormEvent) => {
    event.preventDefault()
    onSearch(draft.trim())
  }

  return (
    <form className="filter filter--search" role="search" onSubmit={submit}>
      <label htmlFor={id}>{label}</label>
      <div className="search-input">
        <input
          id={id}
          type="search"
          value={draft}
          maxLength={200}
          placeholder={placeholder}
          onChange={(event) => setDraft(event.target.value)}
        />
        <button type="submit" className="icon-button" aria-label="Apply search">
          <Search size={16} aria-hidden="true" />
        </button>
      </div>
    </form>
  )
}

export function ClearFilters({ onClear }: { onClear: () => void }) {
  return (
    <button type="button" className="text-button filter-clear" onClick={onClear}>
      <X size={14} aria-hidden="true" />
      Clear filters
    </button>
  )
}

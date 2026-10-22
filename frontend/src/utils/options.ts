/** Distinct, sorted values as select options. */
export function distinctOptions(values: string[]): { value: string; label: string }[] {
  return [...new Set(values)].sort((a, b) => a.localeCompare(b)).map((value) => ({ value, label: value }))
}

import type { DemoDataset } from './demoApi'

/** Replaces loadDemoData.ts in live builds (see vite.config.ts), which therefore contain no demo data. */
export async function loadDemoData(): Promise<DemoDataset> {
  throw new Error('This build does not include demo data. Build with `npm run build:demo`.')
}

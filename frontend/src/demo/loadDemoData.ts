import type { DemoDataset } from './demoApi'

/** The bundled demo dataset (a separate chunk, loaded only in demo mode). */
export async function loadDemoData(): Promise<DemoDataset> {
  const module = await import('./demo-data.json')
  return module.default as unknown as DemoDataset
}

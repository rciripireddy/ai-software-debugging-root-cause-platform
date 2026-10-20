import { Activity, AlertTriangle, LayoutDashboard, SearchCode, type LucideIcon } from 'lucide-react'
import type { FeatureStatus } from './components/FeatureStatusBadge'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  status: FeatureStatus
}

export const NAV_ITEMS: NavItem[] = [
  { to: '/', label: 'Overview', icon: LayoutDashboard, status: 'preview' },
  { to: '/errors', label: 'Error Monitoring', icon: AlertTriangle, status: 'preview' },
  { to: '/root-cause', label: 'Root Cause Analysis', icon: SearchCode, status: 'preview' },
  { to: '/health', label: 'Service Health', icon: Activity, status: 'live' },
]

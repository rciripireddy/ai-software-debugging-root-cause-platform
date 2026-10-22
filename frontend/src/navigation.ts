import { Activity, AlertTriangle, LayoutDashboard, ListTree, SearchCode, type LucideIcon } from 'lucide-react'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
}

export const NAV_ITEMS: NavItem[] = [
  { to: '/', label: 'Overview', icon: LayoutDashboard },
  { to: '/errors', label: 'Error Monitoring', icon: AlertTriangle },
  { to: '/incidents', label: 'Incidents', icon: ListTree },
  { to: '/root-cause', label: 'Root Cause Analysis', icon: SearchCode },
  { to: '/health', label: 'Service Health', icon: Activity },
]

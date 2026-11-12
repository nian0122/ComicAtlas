import type { AuditSummary } from '@/entities/lifecycle'
export function repairAllowed(report: AuditSummary | null, ownerStatus: string | undefined) {
  return (
    !!report &&
    report.complete &&
    report.repairAvailable &&
    !report.superseded &&
    report.ownerState === 'failed' &&
    ownerStatus === 'failed' &&
    ['trashing', 'restoring', 'purging'].includes(report.entryState)
  )
}
export function reportStamp(report: AuditSummary) {
  return JSON.stringify(report)
}

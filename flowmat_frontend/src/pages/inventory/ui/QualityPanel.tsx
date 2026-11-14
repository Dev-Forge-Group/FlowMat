import { useMemo, useState } from 'react'
import { useInventoriesQuery } from '../../../entities/inventory/api/useInventoriesQuery'
import { useLotsQuery } from '../../../entities/inventory/api/useLots'
import {
  useDefectsQuery,
  useLogDefectMutation,
  useQualitySummaryQuery,
  useRecordInspectionMutation,
  useResolveDefectMutation,
} from '../../../entities/quality/api/useQuality'
import type { InspectionTarget } from '../../../entities/quality/model/qualityModel'
import { DefectForm, DefectList, InspectionForm } from '../../../entities/quality/ui/QualityRecords'
import { useInspectionStandardsQuery } from '../../../entities/quality/api/useInspectionStandards'
import { InspectionStandardsPanel } from './InspectionStandardsPanel'
import { NonconformityPanel } from './NonconformityPanel'
import { httpClient } from '../../../shared/api/httpClient'
import { unwrapApiResponse } from '../../../shared/api/unwrapApiResponse'
import { errorMessage } from '../../../shared/lib/errorMessage'
import { formatQty } from '../../../shared/lib/formatQty'
import type { ApiEnvelope, DefectDto, ItemDto, QualityInspectionDto } from '../../../shared/types/api'
import { defectsCsv, inspectionsCsv } from '../model/qualityExportModel'
import { QUALITY_PERIODS, formatRate, qualityTargets, windowStart } from '../model/qualityOverviewModel'

const cell = { padding: '6px 6px' } as const
const num = { ...cell, textAlign: 'right' } as const
const head = { ...cell, textAlign: 'left', borderBottom: '1px solid var(--border)', fontWeight: 600 } as const

/**
 * The project's quality at a glance (docs/domain/quality-inspection.md, summary): how many inspections passed, which
 * checks fail and which defects come up, over a chosen window. Open defects are listed below from any time, so they can
 * be resolved here; new inspections and defects are recorded on a run or a LOT.
 */
export function QualityPanel({ projectId, items }: { projectId: string; items: ItemDto[] }) {
  const [days, setDays] = useState<number | null>(30)
  const from = windowStart(days)
  const summaryQuery = useQualitySummaryQuery(projectId, from)
  const openQuery = useDefectsQuery(projectId, {}, true)
  const inventoriesQuery = useInventoriesQuery(projectId)
  const resolveMutation = useResolveDefectMutation(projectId)
  const standardsQuery = useInspectionStandardsQuery(projectId)

  const itemLabel = useMemo(() => {
    const labels = new Map(items.map((item) => [item.itemId, `${item.itemCode} · ${item.itemName}`]))
    return (itemId: string) => labels.get(itemId) ?? itemId
  }, [items])
  const summary = summaryQuery.data
  const open = openQuery.data ?? []
  const lotsQuery = useLotsQuery(projectId)
  const recordMutation = useRecordInspectionMutation(projectId)
  const logMutation = useLogDefectMutation(projectId)
  const [form, setForm] = useState<null | 'inspection' | 'defect'>(null)
  const targets = useMemo(() => qualityTargets(items, lotsQuery.data ?? []), [items, lotsQuery.data])
  const lotNo = useMemo(() => new Map((lotsQuery.data ?? []).map((lot) => [lot.lotId, lot.lotNo])), [lotsQuery.data])
  const targetLabel = (target: InspectionTarget) =>
    target.lotId ? `LOT ${lotNo.get(target.lotId) ?? target.lotId} · ${itemLabel(target.itemId)}` : itemLabel(target.itemId)
  const saveError = recordMutation.isError
    ? errorMessage(recordMutation.error, 'The inspection could not be saved.')
    : logMutation.isError
      ? errorMessage(logMutation.error, 'The defect could not be saved.')
      : null

  function openForm(next: typeof form) {
    recordMutation.reset()
    logMutation.reset()
    setForm(next)
  }
  const [exportError, setExportError] = useState<string | null>(null)

  /** Fetches the full list only when asked, and saves the period's part of it. */
  async function exportCsv(kind: 'inspections' | 'defects') {
    setExportError(null)
    try {
      const query = `projectId=${encodeURIComponent(projectId)}`
      const text =
        kind === 'inspections'
          ? inspectionsCsv(unwrapApiResponse(await httpClient.get<ApiEnvelope<QualityInspectionDto[]>>(`/quality-inspections?${query}`)), from)
          : defectsCsv(unwrapApiResponse(await httpClient.get<ApiEnvelope<DefectDto[]>>(`/defects?${query}&openOnly=false`)), from)
      const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }))
      const link = document.createElement('a')
      link.href = url
      link.download = `${kind}-${(from ?? 'all').slice(0, 10)}-to-${new Date().toISOString().slice(0, 10)}.csv`
      link.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      setExportError(errorMessage(error, 'The export failed.'))
    }
  }

  return (
    <div style={{ display: 'grid', gap: 16 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'end', flexWrap: 'wrap' }}>
        <label style={{ display: 'grid', gap: 4, fontSize: 12 }}>
          <span>Period</span>
          <select value={days ?? ''} onChange={(e) => setDays(e.target.value === '' ? null : Number(e.target.value))}>
            {QUALITY_PERIODS.map((period) => (
              <option key={period.label} value={period.days ?? ''}>
                {period.label}
              </option>
            ))}
          </select>
        </label>
        <button type="button" onClick={() => void exportCsv('inspections')}>Inspections CSV</button>
        <button type="button" onClick={() => void exportCsv('defects')}>Defects CSV</button>
        <span className="inspector-hint">Inspections and defects are recorded on a run or a LOT.</span>
      </div>
      {exportError && <p role="alert" style={{ color: '#dc2626', fontSize: 12, margin: 0 }}>{exportError}</p>}

      <div style={{ display: 'grid', gap: 8 }}>
        {form === null && (
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', fontSize: 12 }}>
            <button type="button" disabled={targets.length === 0} onClick={() => openForm('inspection')}>
              Record inspection
            </button>
            <button type="button" disabled={targets.length === 0} onClick={() => openForm('defect')}>
              Log defect
            </button>
            <span className="inspector-hint">
              For a LOT or an item without LOT tracking, such as goods received. Runs have their own on the run page.
            </span>
          </div>
        )}
        {form === 'inspection' && (
          <InspectionForm
            targets={targets}
            targetLabel={targetLabel}
            productionRunId={null}
            standards={standardsQuery.data ?? []}
            pending={recordMutation.isPending}
            onSubmit={(body) => recordMutation.mutateAsync(body)}
            onDone={() => setForm(null)}
            onCancel={() => openForm(null)}
          />
        )}
        {form === 'defect' && (
          <DefectForm
            targets={targets}
            targetLabel={targetLabel}
            productionRunId={null}
            fromInspection={null}
            pending={logMutation.isPending}
            onSubmit={async (body) => {
              await logMutation.mutateAsync(body)
              setForm(null)
            }}
            onCancel={() => openForm(null)}
          />
        )}
        {saveError && (
          <p role="alert" style={{ color: '#dc2626', fontSize: 12, margin: 0 }}>
            {saveError}
          </p>
        )}
      </div>

      {summaryQuery.isError && (
        <p style={{ color: '#dc2626', fontSize: 12 }}>{errorMessage(summaryQuery.error, 'Failed to load the quality summary.')}</p>
      )}
      {summary && (
        <>
          <dl aria-label="Quality summary" style={{ display: 'flex', gap: 24, flexWrap: 'wrap', margin: 0, fontSize: 13 }}>
            <Figure label="Inspections" value={String(summary.inspections)} />
            <Figure label="Pass rate" value={formatRate(summary.passRate)} />
            <Figure label="Failed" value={String(summary.failed)} tone={summary.failed > 0 ? '#b91c1c' : undefined} />
            <Figure label="Defects logged" value={String(summary.openDefects + summary.resolvedDefects)} />
            <Figure label="Still open" value={String(summary.openDefects)} tone={summary.openDefects > 0 ? '#b45309' : undefined} />
          </dl>

          <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap', alignItems: 'start' }}>
            <section aria-label="Failures by check" style={{ flex: '1 1 280px' }}>
              <h4 style={{ margin: '0 0 4px' }}>Failures by check</h4>
              {summary.failuresByCheck.length === 0 ? (
                <p className="inspector-hint">No failed inspections in this period.</p>
              ) : (
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                  <thead>
                    <tr>
                      <th style={head}>Check</th>
                      <th style={{ ...head, textAlign: 'right' }}>Failed</th>
                      <th style={{ ...head, textAlign: 'right' }}>Of</th>
                    </tr>
                  </thead>
                  <tbody>
                    {summary.failuresByCheck.map((check) => (
                      <tr key={check.inspectionType}>
                        <td style={cell}>{check.inspectionType}</td>
                        <td style={num}>{check.failed}</td>
                        <td style={num}>{check.inspections}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>
            <section aria-label="Defects by type" style={{ flex: '1 1 280px' }}>
              <h4 style={{ margin: '0 0 4px' }}>Defects by type</h4>
              {summary.defectsByType.length === 0 ? (
                <p className="inspector-hint">No defects logged in this period.</p>
              ) : (
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                  <thead>
                    <tr>
                      <th style={head}>Type</th>
                      <th style={{ ...head, textAlign: 'right' }}>Logged</th>
                      <th style={{ ...head, textAlign: 'right' }}>Open</th>
                    </tr>
                  </thead>
                  <tbody>
                    {summary.defectsByType.map((type) => (
                      <tr key={type.defectType}>
                        <td style={cell}>{type.defectType}</td>
                        <td style={num}>{type.count}</td>
                        <td style={num}>{type.open}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>
          </div>
          {(summary.byItem ?? []).length > 0 && (
            <section aria-label="Quality by item">
              <h4 style={{ margin: '0 0 4px' }}>By item</h4>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                <thead>
                  <tr>
                    <th style={head}>Item</th>
                    <th style={{ ...head, textAlign: 'right' }}>Failed checks</th>
                    <th style={{ ...head, textAlign: 'right' }}>Defects</th>
                    <th style={{ ...head, textAlign: 'right' }}>Open</th>
                    <th style={{ ...head, textAlign: 'right' }}>Defective qty</th>
                  </tr>
                </thead>
                <tbody>
                  {(summary.byItem ?? []).map((line) => (
                    <tr key={line.itemId}>
                      <td style={cell}>{line.itemCode ? `${line.itemCode} · ${line.itemName ?? ''}` : line.itemId}</td>
                      <td style={num}>
                        {line.failed} <span style={{ opacity: 0.6 }}>of {line.inspections}</span>
                      </td>
                      <td style={num}>{line.defects}</td>
                      <td style={{ ...num, color: line.openDefects > 0 ? '#b45309' : undefined }}>{line.openDefects}</td>
                      <td style={num}>
                        {line.defects > 0 ? `${formatQty(line.defectQuantity)} ${line.unit ?? ''}` : '-'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          )}
        </>
      )}

      <section aria-label="Open defects">
        <h4 style={{ margin: '0 0 4px' }}>Open defects{open.length > 0 ? ` (${open.length})` : ''}</h4>
        {openQuery.isError && <p style={{ color: '#dc2626', fontSize: 12 }}>{errorMessage(openQuery.error, 'Failed to load defects.')}</p>}
        {open.length === 0 && !openQuery.isLoading && <p className="inspector-hint">No open defects.</p>}
        <DefectList
          defects={open}
          subjectOf={(record) => {
            const item = record.itemId ? itemLabel(record.itemId) : (record.itemCode ?? '')
            return record.lotNo ? `${item} · LOT ${record.lotNo}` : item
          }}
          stock={inventoriesQuery.data ?? []}
          resolving={resolveMutation.isPending}
          onResolve={(defect, resolution) => resolveMutation.mutateAsync({ defectLogId: defect.defectLogId, ...resolution })}
        />
        {resolveMutation.isError && (
          <p role="alert" style={{ color: '#dc2626', fontSize: 12 }}>
            {errorMessage(resolveMutation.error, 'The defect could not be resolved.')}
          </p>
        )}
      </section>

      <NonconformityPanel projectId={projectId} />
      <InspectionStandardsPanel projectId={projectId} items={items} />
    </div>
  )
}

function Figure({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div>
      <dt style={{ opacity: 0.65, fontSize: 12 }}>{label}</dt>
      <dd style={{ margin: 0, fontSize: 20, fontWeight: 600, color: tone }}>{value}</dd>
    </div>
  )
}

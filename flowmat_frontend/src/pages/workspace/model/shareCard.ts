import type { ReadingView } from './canvasReadingModel'
import { escape, type WorkflowSvgParts } from './workflowSvg'

/**
 * archify's share card (adapted from archify viewer/export.js, Apache-2.0): one fixed-size picture of what is being read,
 * a route or a reach, to drop into a chat or a document. A badge and the workflow's name over one line that says what
 * the reading is, then the workflow itself with the reading drawn strongly and the rest receded.
 */
export const SHARE_CARD = { width: 1200, height: 630, padding: 40, header: 124, inset: 18 } as const

export type ShareCardVariant = 'route' | 'reach'

/** What a card says about a reading, and what its file is called after the workflow's name. */
export interface ShareCardText {
  variant: ShareCardVariant
  badge: string
  summary: string
  /** Appended to the workflow's file name, e.g. "route-share-card". */
  suffix: string
  accent: string
}

/** The reading tones' colours on a card: a route in sky, what comes before in violet, what comes after in green. */
const ACCENT = { route: '#0ea5e9', upstream: '#8b5cf6', downstream: '#10b981' } as const

const PALETTES = {
  light: { background: '#f4f3f8', text: '#0f172a', muted: '#64748b', border: '#cbd5e1' },
  dark: { background: '#0b1120', text: '#f8fafc', muted: '#94a3b8', border: '#334155' },
} as const

const count = (value: number, one: string, many = `${one}s`) => `${value} ${value === 1 ? one : many}`

/**
 * The card for a reading, or null when the reading is not a route that exists or a reach that reaches something: a card
 * of one process alone, of a lens or of a pinned connection says nothing a plain picture does not.
 */
export function shareCardText(view: ReadingView | null, nameOf: (processId: string) => string): ShareCardText | null {
  const highlight = view?.highlight
  if (!view || !highlight || !highlight.origin) return null
  if (highlight.tone === 'route' && view.steps.length >= 2) {
    const steps = view.steps.length - 1
    return {
      variant: 'route',
      badge: `FLOWMAT · ROUTE · ${count(steps, 'step').toUpperCase()}`,
      summary: `Path: ${nameOf(view.steps[0])} → ${nameOf(view.steps[view.steps.length - 1])} · ${count(steps, 'step')}`,
      suffix: 'route-share-card',
      accent: ACCENT.route,
    }
  }
  if ((highlight.tone === 'upstream' || highlight.tone === 'downstream') && highlight.nodeIds.size > 1) {
    const direction = highlight.tone === 'upstream' ? 'Upstream' : 'Downstream'
    // The reading's own receipt, "Downstream · 8 processes · 8 connections · max 5 hops", with where it starts.
    const counts = view.text.startsWith(`${direction} · `) ? view.text.slice(direction.length + 3) : view.text
    return {
      variant: 'reach',
      badge: `FLOWMAT · ${direction.toUpperCase()} REACH`,
      summary: `${direction} from ${nameOf(highlight.origin)} · ${counts}`,
      suffix: `${highlight.tone}-reach-share-card`,
      accent: ACCENT[highlight.tone],
    }
  }
  return null
}

/**
 * The card as one SVG picture. {@code diagram} is the workflow drawn with the reading emphasised, placed whole inside the
 * card's panel. Text too long for its line ends in an ellipsis: a card has no room to wrap.
 */
export function shareCardSvg(input: {
  title: string
  text: ShareCardText
  diagram: WorkflowSvgParts
  theme: 'light' | 'dark'
}): string {
  const { width, height, padding, header, inset } = SHARE_CARD
  const colour = PALETTES[input.theme]
  const panel = { x: padding, y: header, width: width - padding * 2, height: height - header - padding }
  const { diagram } = input
  return [
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}" width="${width}" height="${height}"`
      + ` font-family="system-ui, 'Segoe UI', Roboto, sans-serif" role="img">`,
    `<title>${escape(`${input.title}: ${input.text.summary}`)}</title>`,
    `<rect width="${width}" height="${height}" fill="${colour.background}"/>`,
    // Header: a short accent rule and the badge over the title and the summary.
    `<rect x="${padding}" y="34" width="28" height="3" fill="${escape(input.text.accent)}"/>`,
    `<text x="${width - padding}" y="40" font-size="12" font-weight="600" text-anchor="end" letter-spacing="0.6"`
      + ` fill="${colour.muted}">${escape(input.text.badge)}</text>`,
    `<text x="${padding}" y="74" font-size="30" font-weight="650" fill="${colour.text}">${escape(fit(input.title, 48))}</text>`,
    `<text x="${padding}" y="99" font-size="15" fill="${colour.muted}">${escape(fit(input.text.summary, 140))}</text>`,
    // The workflow sits whole on one rounded panel with an even inset.
    `<rect x="${panel.x}" y="${panel.y}" width="${panel.width}" height="${panel.height}" rx="14" fill="${colour.background}"`
      + ` stroke="${colour.border}"/>`,
    `<svg x="${panel.x + inset}" y="${panel.y + inset}" width="${panel.width - inset * 2}" height="${panel.height - inset * 2}"`
      + ` viewBox="${diagram.left} ${diagram.top} ${diagram.width} ${diagram.height}" preserveAspectRatio="xMidYMid meet">`,
    diagram.body,
    '</svg>',
    '</svg>',
  ].join('\n') + '\n'
}

/**
 * The text cut to what fits a line of about {@code units} Latin letters, ending in an ellipsis when cut. A CJK character
 * takes about two letters' room.
 */
export function fit(text: string, units: number): string {
  const value = text.trim()
  let used = 0
  let end = 0
  for (const character of value) {
    const wide = /[ᄀ-ᇿ⺀-鿿가-힯豈-﫿＀-￯]/.test(character) ? 2 : 1
    if (used + wide > units) return `${value.slice(0, end).trimEnd()}…`
    used += wide
    end += character.length
  }
  return value
}

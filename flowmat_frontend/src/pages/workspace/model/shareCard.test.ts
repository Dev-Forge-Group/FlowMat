import { describe, expect, it } from 'vitest'
import type { ReadingView } from './canvasReadingModel'
import { SHARE_CARD, fit, shareCardSvg, shareCardText } from './shareCard'
import { workflowSvgParts, type SvgConnection, type SvgProcess } from './workflowSvg'

const names: Record<string, string> = { mix: 'Mix', bake: 'Bake', cool: 'Cool' }
const nameOf = (id: string) => names[id] ?? id

const processes: SvgProcess[] = [
  { id: 'mix', x: 0, y: 0, width: 160, height: 60, name: 'Mix', kind: 'process' },
  { id: 'bake', x: 240, y: 0, width: 160, height: 60, name: 'Bake <hot>', kind: 'equipment' },
  { id: 'cool', x: 480, y: 0, width: 160, height: 60, name: 'Cool', kind: 'storage' },
]
const connections: SvgConnection[] = [
  { id: 'c1', d: 'M160,30 L240,30', stroke: '#64748b', dash: null, label: 'dough', labelAt: { x: 200, y: 30 }, ends: null },
  { id: 'c2', d: 'M400,30 L480,30', stroke: '#64748b', dash: null, label: null, labelAt: null, ends: null },
]

function view(overrides: Partial<ReadingView> & { tone: 'route' | 'upstream' | 'downstream' | 'focus' | 'lens'; nodes: string[] }): ReadingView {
  return {
    highlight: { nodeIds: new Set(overrides.nodes), edgeIds: new Set(['c1']), tone: overrides.tone, origin: overrides.nodes[0] ?? null },
    text: overrides.text ?? '',
    steps: overrides.steps ?? [],
  }
}

describe('shareCardText', () => {
  it('says a route by its two ends and its steps', () => {
    const card = shareCardText(view({ tone: 'route', nodes: ['mix', 'bake', 'cool'], steps: ['mix', 'bake', 'cool'] }), nameOf)
    expect(card).toMatchObject({ variant: 'route', badge: 'FLOWMAT · ROUTE · 2 STEPS', summary: 'Path: Mix → Cool · 2 steps',
      suffix: 'route-share-card' })
    expect(shareCardText(view({ tone: 'route', nodes: ['mix', 'bake'], steps: ['mix', 'bake'] }), nameOf)?.summary)
      .toBe('Path: Mix → Bake · 1 step')
  })

  it('says a reach by its direction, where it starts and the reading\'s own counts', () => {
    const card = shareCardText(view({ tone: 'downstream', nodes: ['mix', 'bake', 'cool'],
      text: 'Downstream · 2 processes · 2 connections · max 2 hops' }), nameOf)
    expect(card).toMatchObject({ variant: 'reach', badge: 'FLOWMAT · DOWNSTREAM REACH',
      summary: 'Downstream from Mix · 2 processes · 2 connections · max 2 hops', suffix: 'downstream-reach-share-card' })
    expect(shareCardText(view({ tone: 'upstream', nodes: ['cool', 'bake'], text: 'Upstream · 1 process · 1 connection · max 1 hop' }), nameOf))
      .toMatchObject({ badge: 'FLOWMAT · UPSTREAM REACH', summary: 'Upstream from Cool · 1 process · 1 connection · max 1 hop',
        suffix: 'upstream-reach-share-card' })
  })

  it('has no card for a reading that is neither a route that exists nor a reach that reaches something', () => {
    expect(shareCardText(null, nameOf)).toBeNull()
    expect(shareCardText({ highlight: null, text: 'Cool cannot be reached from Mix along the arrows.', steps: [] }, nameOf)).toBeNull()
    expect(shareCardText(view({ tone: 'focus', nodes: ['mix', 'bake'] }), nameOf)).toBeNull()
    expect(shareCardText(view({ tone: 'lens', nodes: ['mix'] }), nameOf)).toBeNull()
    expect(shareCardText(view({ tone: 'downstream', nodes: ['cool'], text: 'No downstream processes from Cool' }), nameOf)).toBeNull()
  })
})

describe('workflowSvgParts with a reading emphasised', () => {
  it('draws the reading strongly in the accent and lets the rest recede', () => {
    const parts = workflowSvgParts(processes, connections, 'light',
      { nodeIds: new Set(['mix', 'bake']), edgeIds: new Set(['c1']), accent: '#0ea5e9' })
    // The connection on the route: accent colour, thicker, its own arrow, not receded.
    expect(parts.body).toContain('<path d="M160,30 L240,30" fill="none" stroke="#0ea5e9" stroke-width="2.5" marker-end="url(#arrow-0)"/>')
    // The one off the route keeps its colour and recedes, with its own arrow.
    expect(parts.body).toContain('<path d="M400,30 L480,30" fill="none" stroke="#64748b" stroke-width="1.5" marker-end="url(#arrow-1)" opacity="0.28"/>')
    // A strong line's arrowhead is drawn at 1.5/2.5 of the plain one, so both come out the same size.
    expect(parts.body).toContain('<marker id="arrow-0" markerWidth="9.6" markerHeight="9.6" refX="4.8" refY="2.4" orient="auto">'
      + '<path d="M0,0 L0,4.8 L4.8,2.4 z" fill="#0ea5e9"/></marker>')
    expect(parts.body).toContain('<marker id="arrow-1" markerWidth="16" markerHeight="16" refX="8" refY="4" orient="auto">'
      + '<path d="M0,0 L0,8 L8,4 z" fill="#64748b"/></marker>')
    expect(parts.body.match(/<g opacity="0.28">/g)).toHaveLength(1)
    // The two processes on the route are outlined in the accent.
    expect(parts.body.match(/rx="12" fill="#ffffff" stroke="#0ea5e9" stroke-width="2.5"\/>/g)).toHaveLength(2)
    expect(parts).toMatchObject({ left: -48, top: -48, width: 736, height: 156 })
  })

  it('leaves the plain picture as it was without an emphasis', () => {
    const parts = workflowSvgParts(processes, connections, 'light')
    expect(parts.body).not.toContain('opacity=')
    expect(parts.body).not.toContain('stroke-width="2.5"')
  })
})

describe('shareCardSvg', () => {
  const text = shareCardText(view({ tone: 'route', nodes: ['mix', 'bake'], steps: ['mix', 'bake'] }), nameOf)!
  const diagram = workflowSvgParts(processes, connections, 'dark', { nodeIds: new Set(['mix', 'bake']), edgeIds: new Set(['c1']), accent: text.accent })

  it('is a fixed-size card with the badge, the title, the summary and the workflow inside its panel', () => {
    const svg = shareCardSvg({ title: 'Bakery & <line>', text, diagram, theme: 'dark' })
    expect(svg).toContain(`viewBox="0 0 ${SHARE_CARD.width} ${SHARE_CARD.height}" width="1200" height="630"`)
    expect(svg).toContain('>FLOWMAT · ROUTE · 1 STEP</text>')
    expect(svg).toContain('>Bakery &amp; &lt;line&gt;</text>')
    expect(svg).toContain('>Path: Mix → Bake · 1 step</text>')
    expect(svg).toContain('<rect width="1200" height="630" fill="#0b1120"/>')
    // The workflow keeps its own coordinates and is fitted whole into the panel.
    expect(svg).toContain('<svg x="58" y="142" width="1084" height="430" viewBox="-48 -48 736 156" preserveAspectRatio="xMidYMid meet">')
    expect(svg).toContain('>Bake &lt;hot&gt;</text>')
    expect(svg.match(/<svg /g)).toHaveLength(2)
    expect(svg.trimEnd().endsWith('</svg>\n</svg>')).toBe(true)
  })

  it('cuts a title and a summary that are too long for their line', () => {
    const long = 'A workflow with a name that goes on for far longer than a card has room to show'
    const svg = shareCardSvg({ title: long, text: { ...text, summary: `${'Path '.repeat(40)}end` }, diagram, theme: 'light' })
    expect(svg).toContain(`>${fit(long, 48)}</text>`)
    expect(fit(long, 48).endsWith('…')).toBe(true)
    expect(fit(long, 48).length).toBeLessThanOrEqual(49)
    // The line on the card is cut; the picture's title for screen readers keeps the whole summary.
    expect(svg).toMatch(/<text x="40" y="99"[^>]*>(Path )+Path…<\/text>/)
    expect(svg).toContain('Path end</title>')
  })
})

describe('fit', () => {
  it('keeps what fits and counts a CJK character as two letters', () => {
    expect(fit('  Bakery line ', 48)).toBe('Bakery line')
    expect(fit('abcdef', 6)).toBe('abcdef')
    expect(fit('abcdefg', 6)).toBe('abcdef…')
    expect(fit('제빵 라인 공정', 8)).toBe('제빵 라…')
    expect(fit('제빵 라인', 9)).toBe('제빵 라인')
  })
})

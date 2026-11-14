import { describe, expect, it } from 'vitest'
import { workflowSvg } from './workflowSvg'

const mix = { id: 'a', x: 0, y: 0, width: 150, height: 72, name: 'Mix', kind: 'process' }
const pack = { id: 'b', x: 300, y: 100, width: 150, height: 72, name: 'Pack & <seal>', kind: 'output' }

describe('workflow svg', () => {
  it('frames every process with room around it and draws each line with its colour, dash, arrow and label', () => {
    const svg = workflowSvg('Line "A"', [mix, pack], [
      { id: 'ab', d: 'M150,36 L300,136', stroke: '#94a3b8', dash: null, label: 'Feed', labelAt: { x: 225, y: 86 },
        ends: [{ x: 150, y: 36 }, { x: 300, y: 136 }] },
      { id: 'ba', d: 'M300,120 L150,20', stroke: '#f87171', dash: '8 4', label: null, labelAt: null },
    ])
    expect(svg).toContain('viewBox="-48 -48 546 268"')
    expect(svg).toContain('<title>Line &quot;A&quot;</title>')
    expect(svg).toContain('<path d="M150,36 L300,136" fill="none" stroke="#94a3b8" stroke-width="1.5" marker-end="url(#arrow-0)"/>')
    expect(svg).toContain('stroke="#f87171" stroke-width="1.5" stroke-dasharray="8 4" marker-end="url(#arrow-1)"')
    expect(svg.match(/<marker /g)).toHaveLength(2)
    expect(svg).toContain('>Mix</text>')
    expect(svg).toContain('>Pack &amp; &lt;seal&gt;</text>')
    expect(svg).toContain('>OUTPUT</text>')
    expect(svg).toContain('>Feed</text>')
    // The handles the canvas draws where a line leaves and arrives.
    expect(svg).toContain('<circle cx="150" cy="36" r="4"')
    expect(svg).toContain('<circle cx="300" cy="136" r="4"')
    expect(svg.match(/<circle /g)).toHaveLength(2)
  })

  it('draws the dark picture in dark colours and keeps each line in its own', () => {
    const lines = [{ id: 'ab', d: 'M150,36 L300,136', stroke: '#94a3b8', dash: null, label: 'Feed', labelAt: { x: 225, y: 86 } }]
    const light = workflowSvg('Line', [mix, pack], lines)
    const dark = workflowSvg('Line', [mix, pack], lines, 'dark')

    expect(light).toContain('class="wf-bg" x="-48" y="-48" width="546" height="268" fill="#f4f3f8"')
    expect(dark).toContain('class="wf-bg" x="-48" y="-48" width="546" height="268" fill="#0b1120"')
    expect(dark).toContain('fill="#111827" stroke="#334155"')
    expect(dark).not.toContain('#ffffff')
    expect(dark).toContain('stroke="#94a3b8" stroke-width="1.5" marker-end')
    expect(light).not.toContain('<style>')
    expect(dark).not.toContain('<style>')
  })

  it('draws auto as the light picture that turns dark where the viewer prefers dark', () => {
    const auto = workflowSvg('Line', [mix], [], 'auto')

    expect(auto).toContain('fill="#f4f3f8"')
    expect(auto).toContain('<style>@media (prefers-color-scheme: dark) { .wf-bg { fill: #0b1120 }')
    expect(auto).toContain('.wf-box { fill: #111827; stroke: #334155 }')
  })

  it('names a process saved without a kind a process', () => {
    expect(workflowSvg('Line', [{ ...mix, kind: null }], [])).toContain('>PROCESS</text>')
  })

  it('writes nothing of the editor: no selection, reading or run classes', () => {
    const svg = workflowSvg('Line', [mix], [])
    expect(svg).not.toMatch(/selected|reading-|run-|change-/)
    expect(svg.startsWith('<svg xmlns="http://www.w3.org/2000/svg"')).toBe(true)
  })
})

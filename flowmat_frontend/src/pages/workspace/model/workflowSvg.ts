/**
 * The workflow as a standalone vector picture (archify exports clean SVG): each process a rounded box with its name and
 * kind, each connection the path the canvas drew, in its kind's colour and dash, with an arrow and its label. Only the
 * workflow itself: no selection, reading, run or change marks, and no editor controls. Canvas coordinates throughout.
 *
 * Like archify's SVG exports it comes light, dark or auto. Auto is the light picture with a `prefers-color-scheme: dark`
 * style, so it matches whatever page or viewer shows it. A connection keeps its kind's colour in every theme.
 */
/** `kind` is the stored node type; rows saved before node types existed have none and are processes. */
export interface SvgProcess { id: string; x: number; y: number; width: number; height: number; name: string; kind: string | null }
export interface SvgConnection {
  id: string
  /** The SVG path data the canvas drew for it, in canvas coordinates. */
  d: string
  stroke: string
  dash: string | null
  label: string | null
  labelAt: { x: number; y: number } | null
  /** Where the line leaves and arrives: the canvas draws a handle there, so the picture does too. */
  ends?: readonly [{ x: number; y: number }, { x: number; y: number }] | null
}

const PADDING = 48

export type SvgTheme = 'light' | 'dark' | 'auto'

const PALETTES = {
  light: { background: '#f4f3f8', box: '#ffffff', border: '#cbd5e1', name: '#0f172a', kind: '#64748b', handle: '#94a3b8', label: '#334155' },
  dark: { background: '#0b1120', box: '#111827', border: '#334155', name: '#f8fafc', kind: '#94a3b8', handle: '#64748b', label: '#e2e8f0' },
} as const

/** Auto's dark half: the same colours as the dark picture, as CSS that overrides the light presentation attributes. */
function darkStyle() {
  const dark = PALETTES.dark
  return '<style>@media (prefers-color-scheme: dark) {'
    + ` .wf-bg { fill: ${dark.background} } .wf-box { fill: ${dark.box}; stroke: ${dark.border} }`
    + ` .wf-name { fill: ${dark.name} } .wf-kind { fill: ${dark.kind} } .wf-handle { fill: ${dark.box}; stroke: ${dark.handle} }`
    + ` .wf-label { fill: ${dark.label}; stroke: ${dark.background} } }</style>`
}

export function workflowSvg(
  title: string,
  processes: readonly SvgProcess[],
  connections: readonly SvgConnection[],
  theme: SvgTheme = 'light',
): string {
  const colour = PALETTES[theme === 'dark' ? 'dark' : 'light']
  const left = Math.min(...processes.map((process) => process.x)) - PADDING
  const top = Math.min(...processes.map((process) => process.y)) - PADDING
  const right = Math.max(...processes.map((process) => process.x + process.width)) + PADDING
  const bottom = Math.max(...processes.map((process) => process.y + process.height)) + PADDING
  const width = round(right - left)
  const height = round(bottom - top)
  const colours = [...new Set(connections.map((connection) => connection.stroke))]
  const markerOf = (colour: string) => `arrow-${colours.indexOf(colour)}`

  const lines = [
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${round(left)} ${round(top)} ${width} ${height}" width="${width}" height="${height}"`
      + ` font-family="system-ui, 'Segoe UI', Roboto, sans-serif" role="img">`,
    `<title>${escape(title)}</title>`,
    ...(theme === 'auto' ? [darkStyle()] : []),
    '<defs>',
    ...colours.map((colour) => `<marker id="${markerOf(colour)}" markerWidth="16" markerHeight="16" refX="8" refY="4" orient="auto">`
      + `<path d="M0,0 L0,8 L8,4 z" fill="${escape(colour)}"/></marker>`),
    '</defs>',
    `<rect class="wf-bg" x="${round(left)}" y="${round(top)}" width="${width}" height="${height}" fill="${colour.background}"/>`,
    ...connections.map((connection) => `<path d="${escape(connection.d)}" fill="none" stroke="${escape(connection.stroke)}"`
      + ` stroke-width="1.5"${connection.dash ? ` stroke-dasharray="${escape(connection.dash)}"` : ''}`
      + ` marker-end="url(#${markerOf(connection.stroke)})"/>`),
    ...processes.map((process) => [
      `<g>`,
      `<rect class="wf-box" x="${round(process.x)}" y="${round(process.y)}" width="${round(process.width)}"`
        + ` height="${round(process.height)}" rx="12" fill="${colour.box}" stroke="${colour.border}" stroke-width="1.5"/>`,
      `<text class="wf-name" x="${round(process.x + 14)}" y="${round(process.y + 26)}" font-size="14" font-weight="600"`
        + ` fill="${colour.name}">${escape(process.name)}</text>`,
      `<text class="wf-kind" x="${round(process.x + 14)}" y="${round(process.y + 46)}" font-size="11" fill="${colour.kind}">`
        + `${escape((process.kind || 'process').toUpperCase())}</text>`,
      `</g>`,
    ].join('')),
    ...connections.flatMap((connection) => (connection.ends ?? []).map((end) =>
      `<circle cx="${round(end.x)}" cy="${round(end.y)}" r="4" class="wf-handle" fill="${colour.box}" stroke="${colour.handle}"`
        + ` stroke-width="1.5"/>`)),
    ...connections.filter((connection) => connection.label && connection.labelAt).map((connection) =>
      `<text class="wf-label" x="${round(connection.labelAt!.x)}" y="${round(connection.labelAt!.y)}" font-size="11"`
        + ` fill="${colour.label}" text-anchor="middle" dominant-baseline="middle" stroke="${colour.background}" stroke-width="4"`
        + ` paint-order="stroke">`
        + `${escape(connection.label!)}</text>`),
    '</svg>',
  ]
  return `${lines.join('\n')}\n`
}

function round(value: number): number {
  return Math.round(value * 10) / 10
}

function escape(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

/**
 * The workflow as a standalone vector picture (archify exports clean SVG): each process a rounded box with its name and
 * kind, each connection the path the canvas drew, in its kind's colour and dash, with an arrow and its label. Only the
 * workflow itself: no selection, reading, run or change marks, and no editor controls. Canvas coordinates throughout.
 */
export interface SvgProcess { id: string; x: number; y: number; width: number; height: number; name: string; kind: string }
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

export function workflowSvg(title: string, processes: readonly SvgProcess[], connections: readonly SvgConnection[]): string {
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
    '<defs>',
    ...colours.map((colour) => `<marker id="${markerOf(colour)}" markerWidth="16" markerHeight="16" refX="8" refY="4" orient="auto">`
      + `<path d="M0,0 L0,8 L8,4 z" fill="${escape(colour)}"/></marker>`),
    '</defs>',
    `<rect x="${round(left)}" y="${round(top)}" width="${width}" height="${height}" fill="#f4f3f8"/>`,
    ...connections.map((connection) => `<path d="${escape(connection.d)}" fill="none" stroke="${escape(connection.stroke)}"`
      + ` stroke-width="1.5"${connection.dash ? ` stroke-dasharray="${escape(connection.dash)}"` : ''}`
      + ` marker-end="url(#${markerOf(connection.stroke)})"/>`),
    ...processes.map((process) => [
      `<g>`,
      `<rect x="${round(process.x)}" y="${round(process.y)}" width="${round(process.width)}" height="${round(process.height)}"`
        + ` rx="12" fill="#ffffff" stroke="#cbd5e1" stroke-width="1.5"/>`,
      `<text x="${round(process.x + 14)}" y="${round(process.y + 26)}" font-size="14" font-weight="600" fill="#0f172a">`
        + `${escape(process.name)}</text>`,
      `<text x="${round(process.x + 14)}" y="${round(process.y + 46)}" font-size="11" fill="#64748b">`
        + `${escape(process.kind.toUpperCase())}</text>`,
      `</g>`,
    ].join('')),
    ...connections.flatMap((connection) => (connection.ends ?? []).map((end) =>
      `<circle cx="${round(end.x)}" cy="${round(end.y)}" r="4" fill="#ffffff" stroke="#94a3b8" stroke-width="1.5"/>`)),
    ...connections.filter((connection) => connection.label && connection.labelAt).map((connection) =>
      `<text x="${round(connection.labelAt!.x)}" y="${round(connection.labelAt!.y)}" font-size="11" fill="#334155"`
        + ` text-anchor="middle" dominant-baseline="middle" stroke="#f4f3f8" stroke-width="4" paint-order="stroke">`
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

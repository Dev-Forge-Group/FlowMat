import { useEffect, useRef } from 'react'
import type { ShareCardVariant } from '../model/shareCard'
import type { SvgTheme } from '../model/workflowSvg'

/** What the menu can make: a PNG to save or to copy, an SVG in one of archify's three themes, or a card of a reading. */
export type ExportChoice = { kind: 'png' } | { kind: 'png-clipboard' } | { kind: 'svg'; theme: SvgTheme }
  | { kind: 'share-card'; variant: ShareCardVariant } | { kind: 'webm' }

/** Whether the motion clip can be made: there must be a line for a signal to run along, and a browser that records. */
export type MotionAvailability = 'ready' | 'no-connections' | 'unsupported'

const MOTION_UNLESS: Record<Exclude<MotionAvailability, 'ready'>, string> = {
  'no-connections': 'Connect two processes first',
  unsupported: 'This browser cannot record video',
}

interface Item { choice: ExportChoice; label: string; hint: string; unless?: string }

const SECTIONS: { title: string; items: Item[] }[] = [
  { title: 'Copy', items: [{ choice: { kind: 'png-clipboard' }, label: 'PNG to clipboard', hint: 'Paste it into a chat or a document' }] },
  { title: 'Raster image', items: [{ choice: { kind: 'png' }, label: 'PNG', hint: 'Download the picture' }] },
  {
    title: 'Vector',
    items: [
      { choice: { kind: 'svg', theme: 'light' }, label: 'SVG · Light', hint: 'Always light' },
      { choice: { kind: 'svg', theme: 'dark' }, label: 'SVG · Dark', hint: 'Always dark' },
      { choice: { kind: 'svg', theme: 'auto' }, label: 'SVG · Auto', hint: "Follows the viewer's light or dark setting" },
    ],
  },
  {
    title: 'Share card',
    items: [
      { choice: { kind: 'share-card', variant: 'route' }, label: 'Route share card', hint: 'The path being read, on one card',
        unless: 'Read a route first (R)' },
      { choice: { kind: 'share-card', variant: 'reach' }, label: 'Reach share card', hint: 'What comes before or after a process, on one card',
        unless: 'Trace upstream or downstream of a process first' },
    ],
  },
  {
    title: 'Motion',
    items: [{ choice: { kind: 'webm' }, label: 'WebM · 6 seconds', hint: 'A signal runs along each connection in flow order' }],
  },
]

/**
 * archify's export menu (adapted from archify viewer/export.js, Apache-2.0): portable, clean pictures of the workflow,
 * without selection, reading, run or change marks. It opens from the view controls or with E. The arrow keys move between
 * the outputs; Esc or a click elsewhere closes it and gives the export button the focus back. A share card is offered
 * only while its reading is on the canvas: a route for the route card, an upstream or downstream trace for the reach card.
 * The motion clip is offered when the workflow has a connection and the browser can record a canvas.
 */
export function ExportMenu({ shareCard, motion = 'ready', onChoose, onClose }: {
  /** Which share card the current reading can make, if any. */
  shareCard: ShareCardVariant | null
  motion?: MotionAvailability
  onChoose(choice: ExportChoice): void
  onClose(): void
}) {
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    menuRef.current?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus()
  }, [])

  // A press outside the menu closes it; the export button toggles it itself.
  useEffect(() => {
    function away(event: PointerEvent) {
      const target = event.target as Element
      if (menuRef.current?.contains(target) || target.closest?.('.diagram-nav-export')) return
      onClose()
    }
    document.addEventListener('pointerdown', away, true)
    return () => document.removeEventListener('pointerdown', away, true)
  }, [onClose])

  function backToTrigger() {
    document.querySelector<HTMLButtonElement>('.diagram-nav .diagram-nav-export')?.focus()
  }

  return (
    <div ref={menuRef} className="preset-menu export-menu" role="menu" aria-label="Export diagram"
      onKeyDown={(event) => {
        // Outputs that cannot be made now are passed over.
        const items = [...(menuRef.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]:not(:disabled)') ?? [])]
        const at = items.indexOf(document.activeElement as HTMLButtonElement)
        if (event.key === 'Escape') {
          event.preventDefault()
          event.stopPropagation()
          onClose()
          backToTrigger()
        } else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
          event.preventDefault()
          items[(at + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length]?.focus()
        } else if (event.key === 'Home' || event.key === 'End') {
          event.preventDefault()
          items[event.key === 'Home' ? 0 : items.length - 1]?.focus()
        }
      }}>
      <span className="preset-menu-title" aria-hidden="true">Export · portable, clean outputs <kbd>E</kbd></span>
      {SECTIONS.map((section) => (
        <div key={section.title} className="export-menu-section" role="group" aria-label={section.title}>
          <span className="export-menu-section-title" aria-hidden="true">{section.title}</span>
          {section.items.map((item) => {
            const unless = item.choice.kind === 'share-card'
              ? (item.choice.variant !== shareCard ? item.unless : undefined)
              : item.choice.kind === 'webm' && motion !== 'ready' ? MOTION_UNLESS[motion] : undefined
            const unavailable = unless !== undefined
            return (
              <button key={item.label} type="button" role="menuitem" className="preset-option export-option"
                disabled={unavailable} title={unless}
                onClick={() => {
                  onClose()
                  backToTrigger()
                  onChoose(item.choice)
                }}>
                <span className="preset-option-copy">
                  <strong>{item.label}</strong><small>{unless ?? item.hint}</small>
                </span>
              </button>
            )
          })}
        </div>
      ))}
    </div>
  )
}

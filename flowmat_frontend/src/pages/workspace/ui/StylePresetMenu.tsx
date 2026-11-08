import { useEffect, useRef } from 'react'

/**
 * archify's visual style try-on (adapted from archify template.source.html and viewer.css, Apache-2.0): one workflow, four
 * looks for the canvas. The choice lasts while the page is open; it is never saved, shared or put in the address, and the
 * exported SVG keeps the plain look.
 */
export type CanvasPreset = 'classic' | 'signal-flow' | 'blueprint' | 'editorial'

export const CANVAS_PRESETS: { id: CanvasPreset; label: string; hint: string }[] = [
  { id: 'classic', label: 'Classic', hint: 'Stable technical default' },
  { id: 'signal-flow', label: 'Signal Flow', hint: 'Motion-forward presentation' },
  { id: 'blueprint', label: 'Blueprint', hint: 'Engineering review' },
  { id: 'editorial', label: 'Editorial', hint: 'Publication and launch notes' },
]

export function presetLabel(preset: CanvasPreset) {
  return CANVAS_PRESETS.find((entry) => entry.id === preset)?.label ?? 'Classic'
}

/** The next look, as archify's S key cycles them. */
export function nextPreset(preset: CanvasPreset): CanvasPreset {
  const at = CANVAS_PRESETS.findIndex((entry) => entry.id === preset)
  return CANVAS_PRESETS[(at + 1) % CANVAS_PRESETS.length].id
}

/**
 * The style menu above the view controls: each look with its hint, the current one checked. The arrow keys move between
 * them, Esc or a click elsewhere closes it and gives the style button the focus back.
 */
export function StylePresetMenu({ preset, onChoose, onClose }: {
  preset: CanvasPreset
  onChoose(preset: CanvasPreset): void
  onClose(): void
}) {
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    menuRef.current?.querySelector<HTMLButtonElement>('[aria-checked="true"]')?.focus()
  }, [])

  // A press outside the menu closes it; the style button toggles it itself.
  useEffect(() => {
    function away(event: PointerEvent) {
      const target = event.target as Element
      if (menuRef.current?.contains(target) || target.closest?.('.diagram-nav-style')) return
      onClose()
    }
    document.addEventListener('pointerdown', away, true)
    return () => document.removeEventListener('pointerdown', away, true)
  }, [onClose])

  function backToTrigger() {
    document.querySelector<HTMLButtonElement>('.diagram-nav .diagram-nav-style')?.focus()
  }

  return (
    <div ref={menuRef} className="preset-menu" role="menu" aria-label="Visual style"
      onKeyDown={(event) => {
        const items = [...(menuRef.current?.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]') ?? [])]
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
      <span className="preset-menu-title" aria-hidden="true">Visual style <kbd>S</kbd> to cycle</span>
      {CANVAS_PRESETS.map((entry) => (
        <button key={entry.id} type="button" role="menuitemradio" className="preset-option" data-preset-value={entry.id}
          aria-checked={entry.id === preset}
          onClick={() => {
            onChoose(entry.id)
            onClose()
            backToTrigger()
          }}>
          <span className="preset-option-swatch" data-preset={entry.id} aria-hidden="true" />
          <span className="preset-option-copy"><strong>{entry.label}</strong><small>{entry.hint}</small></span>
          <span className="preset-option-check" aria-hidden="true" />
        </button>
      ))}
    </div>
  )
}

import { presetLabel, type CanvasPreset } from './StylePresetMenu'

/**
 * archify's diagram view controls (adapted from archify template.source.html and viewer.css, Apache-2.0): one dock at
 * the canvas's bottom right with the reading modes (ROUTE, MAP, LENS, and FlowMat's RUNS and CHANGES), the finder, the
 * guide, a zoom stepper whose middle shows the reading depth (MAP below 100%, READ, FULL from 175%) and the zoom, and
 * archify's toolbar controls: the visual style, motion (LIVE or STILL) and the presentation stage.
 */
function readingDepth(zoom: number): 'MAP' | 'READ' | 'FULL' {
  if (zoom < 1) return 'MAP'
  return zoom >= 1.75 ? 'FULL' : 'READ'
}

export function DiagramNav({
  routeActive, onRoute, mapOpen, onMap, lensActive, onLens, runsActive, onRuns, changesActive, onChanges, onFind, onGuide,
  zoom, onZoomOut, onReset, onZoomIn, preset, styleOpen, onStyle, motion, presenting, onPresent,
}: {
  routeActive: boolean
  onRoute(): void
  mapOpen: boolean
  onMap(): void
  lensActive: boolean
  onLens(): void
  runsActive: boolean
  onRuns(): void
  changesActive: boolean
  onChanges(): void
  onFind(): void
  onGuide(): void
  zoom: number
  onZoomOut(): void
  onReset(): void
  onZoomIn(): void
  preset: CanvasPreset
  styleOpen: boolean
  onStyle(): void
  motion: { still: boolean; reduced: boolean; onToggle(): void }
  presenting: boolean
  onPresent(): void
}) {
  const motionLabel = motion.reduced ? 'Motion paused by reduced-motion preference'
    : motion.still ? 'Resume motion' : 'Pause motion'
  const percent = `${Math.round(zoom * 100)}%`
  return (
    <div className="diagram-nav" role="toolbar" aria-label="Diagram view controls">
      <button type="button" className="diagram-nav-mode" data-mode="route" aria-pressed={routeActive}
        aria-label="Trace a directed route" title="Trace route" onClick={onRoute}>ROUTE</button>
      <button type="button" className="diagram-nav-mode" data-mode="map" aria-expanded={mapOpen}
        aria-label="Open semantic radar" title="Semantic radar (M)" onClick={onMap}>MAP</button>
      <button type="button" className="diagram-nav-mode" data-mode="lens" aria-pressed={lensActive}
        aria-label={lensActive ? 'Open active semantic lens' : 'Open semantic lens'} title="Semantic lens" onClick={onLens}>LENS</button>
      <button type="button" className="diagram-nav-mode" data-mode="runs" aria-pressed={runsActive}
        aria-label="Show a production run" title="Show a run over the canvas" onClick={onRuns}>RUNS</button>
      <button type="button" className="diagram-nav-mode" data-mode="changes" aria-pressed={changesActive}
        aria-label="Compare with a revision" title="Compare the draft with a published revision" onClick={onChanges}>CHANGES</button>
      <button type="button" className="diagram-nav-find" aria-label="Find a node" title="Find a node (/)" onClick={onFind}>
        <span className="diagram-nav-icon find" aria-hidden="true" />
      </button>
      <button type="button" aria-label="Open diagram guide" title="Diagram guide (?)" onClick={onGuide}>
        <span className="diagram-nav-icon guide" aria-hidden="true" />
      </button>
      <button type="button" data-view="out" aria-label="Zoom out" title="Zoom out (-)" disabled={zoom <= 0.25} onClick={onZoomOut}>
        <span className="diagram-nav-icon minus" aria-hidden="true" />
      </button>
      <button type="button" data-view="reset" aria-label={`${readingDepth(zoom)} ${percent}. Reset diagram view`}
        title="Reset view (0)" onClick={onReset}>
        <span className="diagram-nav-detail">{readingDepth(zoom)}</span>
        <span className="diagram-nav-percent">{percent}</span>
      </button>
      <button type="button" data-view="in" aria-label="Zoom in" title="Zoom in (+)" disabled={zoom >= 3} onClick={onZoomIn}>
        <span className="diagram-nav-icon plus" aria-hidden="true" />
      </button>
      <button type="button" className="diagram-nav-style" aria-haspopup="menu" aria-expanded={styleOpen}
        aria-label={`Visual style: ${presetLabel(preset)}. Choose visual style`} title="Choose visual style (S cycles)"
        onClick={onStyle}>
        <span className="preset-control-mark" data-preset={preset} aria-hidden="true" />
      </button>
      <button type="button" className="diagram-nav-motion" aria-pressed={!motion.still} aria-label={motionLabel}
        title={motionLabel} disabled={motion.reduced} onClick={motion.onToggle}>
        <span className="motion-control-dot" aria-hidden="true" />
        <span>{motion.still ? 'STILL' : 'LIVE'}</span>
      </button>
      <button type="button" className="diagram-nav-present" aria-pressed={presenting}
        aria-label={presenting ? 'Exit presentation stage' : 'Enter presentation stage'}
        title={presenting ? 'Exit presentation stage (F or Escape)' : 'Presentation stage (F)'} onClick={onPresent}>
        <span className={`diagram-nav-icon ${presenting ? 'present-exit' : 'present'}`} aria-hidden="true" />
      </button>
    </div>
  )
}

import { MOTION, drawMotionFrame, type MotionScene } from '../model/workflowMotion'

/**
 * Records the scene over the still picture as a WebM (archify's recordWebm, adapted from archify viewer/export.js,
 * Apache-2.0): the picture is drawn into a canvas, the canvas is recorded while the signals are drawn on every frame, and
 * the recording stops after the clip's length. No server and no encoder of our own: the browser's MediaRecorder does it.
 * A tab in the background draws no frames, so the clip is recorded while the page is in view.
 */
export function recordMotion(
  svg: string,
  view: { left: number; top: number; width: number; height: number },
  scene: MotionScene,
  mimeType: string,
  seconds: number = MOTION.seconds,
): Promise<Blob> {
  return new Promise<Blob>((resolve, reject) => {
    const source = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml;charset=utf-8' }))
    const picture = new Image()
    let stream: MediaStream | null = null
    let recorder: MediaRecorder | null = null
    let frame = 0
    let stopTimer = 0
    let flushTimer = 0
    let settled = false

    function finish(error: Error | null, blob?: Blob) {
      if (settled) return
      // Settle first: stopping the recorder and the stream can still send late events.
      settled = true
      cancelAnimationFrame(frame)
      window.clearTimeout(stopTimer)
      window.clearTimeout(flushTimer)
      picture.onload = null
      picture.onerror = null
      if (recorder) {
        recorder.ondataavailable = null
        recorder.onerror = null
        recorder.onstop = null
        try {
          if (recorder.state !== 'inactive') recorder.stop()
        } catch {
          // Already stopped.
        }
      }
      stream?.getTracks().forEach((track) => track.stop())
      URL.revokeObjectURL(source)
      if (blob) resolve(blob)
      else reject(error ?? new Error('The recording stopped.'))
    }

    picture.onerror = () => finish(new Error('The picture to record could not be drawn.'))
    picture.onload = () => {
      if (settled) return
      try {
        const scale = Math.min(1, MOTION.maxWidth / view.width)
        const canvas = document.createElement('canvas')
        // Video encoders want even sizes.
        canvas.width = Math.max(2, Math.round((view.width * scale) / 2) * 2)
        canvas.height = Math.max(2, Math.round((view.height * scale) / 2) * 2)
        const context = canvas.getContext('2d')
        if (!context) throw new Error('This browser gave no canvas to record.')
        stream = canvas.captureStream(MOTION.fps)
        const active = new MediaRecorder(stream, { mimeType, videoBitsPerSecond: MOTION.bitsPerSecond })
        recorder = active
        const chunks: Blob[] = []
        let startedAt = performance.now()
        const draw = (now: number) => {
          if (settled) return
          try {
            drawMotionFrame(context, picture, view, scene, Math.max(0, (now - startedAt) / 1000))
            frame = requestAnimationFrame(draw)
          } catch (error) {
            finish(error instanceof Error ? error : new Error(String(error)))
          }
        }
        active.ondataavailable = (event) => {
          if (!settled && event.data.size > 0) chunks.push(event.data)
        }
        active.onerror = () => finish(new Error('The browser stopped the recording.'))
        active.onstop = () => {
          if (settled) return
          const blob = new Blob(chunks, { type: active.mimeType || mimeType })
          if (blob.size === 0) finish(new Error('The recording came out empty.'))
          else finish(null, blob)
        }
        drawMotionFrame(context, picture, view, scene, 0)
        active.start(250)
        startedAt = performance.now()
        frame = requestAnimationFrame(draw)
        stopTimer = window.setTimeout(() => {
          if (settled || active.state === 'inactive') return
          // Ask for what the encoder holds, then stop, which hands over the last piece.
          try {
            active.requestData()
          } catch {
            // Stopping still collects it.
          }
          flushTimer = window.setTimeout(() => {
            if (settled) return
            try {
              if (active.state !== 'inactive') active.stop()
            } catch (error) {
              finish(error instanceof Error ? error : new Error(String(error)))
            }
          }, 120)
        }, seconds * 1000)
      } catch (error) {
        finish(error instanceof Error ? error : new Error(String(error)))
      }
    }
    picture.src = source
  })
}

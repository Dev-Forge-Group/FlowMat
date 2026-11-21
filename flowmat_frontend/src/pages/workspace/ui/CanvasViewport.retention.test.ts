import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('CanvasViewport React Flow retention', () => {
  it('keeps workflow nodes and edges on React Flow while mounting editor elements as a viewport overlay', () => {
    const source = readFileSync(join(process.cwd(), 'src', 'pages', 'workspace', 'ui', 'CanvasViewport.tsx'), 'utf8')

    expect(source).toContain("from '@xyflow/react'")
    expect(source).toContain('const nodeTypes')
    expect(source).toContain('const edgeTypes')
    expect(source).toContain('<ReactFlow')
    expect(source).toContain('<ViewportPortal>')
    expect(source).toContain('<WorkspaceEditorLayer')
  })

  it('keeps legacy annotations out of React Flow node types', () => {
    const source = readFileSync(join(process.cwd(), 'src', 'pages', 'workspace', 'ui', 'CanvasViewport.tsx'), 'utf8')

    expect(source).not.toContain('CanvasAnnotationNode')
    expect(source).not.toContain('annotationNode')
    expect(source).toContain('onUpdateAnnotation={onUpdateAnnotation}')
    expect(source).toContain('onDeleteAnnotations={onDeleteAnnotations}')
  })
})

describe('Workspace editor keyboard integration', () => {
  it('keeps editor tool shortcuts and editor select-all wired in the workspace page', () => {
    const page = readFileSync(join(process.cwd(), 'src', 'pages', 'workspace', 'ui', 'WorkflowCanvasPage.tsx'), 'utf8')
    // The page hands its state to the workspace keyboard hook, which reads the shortcut table in the workspace tools
    // model (workspaceTools.test.ts checks what that table answers).
    const source = readFileSync(join(process.cwd(), 'src', 'pages', 'workspace', 'model', 'useWorkspaceKeyboard.ts'), 'utf8')
    const tools = readFileSync(join(process.cwd(), 'src', 'pages', 'workspace', 'model', 'workspaceTools.ts'), 'utf8')

    expect(page).toContain('useWorkspaceKeyboard({')
    expect(page).toContain('editorCommandApiRef, editorSelection')
    expect(source).toContain('getWorkspaceToolShortcut')
    expect(tools).toContain("return 'editor-rectangle'")
    expect(tools).toContain("return 'editor-ellipse'")
    expect(tools).toContain("return 'editor-line'")
    expect(tools).toContain("return 'editor-text'")
    expect(source).toContain('editorCommandApiRef.current?.selectAll()')
    expect(source).toContain('editorCommandApiRef.current?.clearSelection()')
    expect(source).toContain('editorCommandApiRef.current?.undo()')
    expect(source).toContain('editorCommandApiRef.current?.redo()')
    expect(source).toContain('getUndoRedoShortcut')
  })

  it('keeps editor document undo/redo routed through the workspace editor command api', () => {
    const pageSource = readFileSync(join(process.cwd(), 'src', 'pages', 'workspace', 'ui', 'WorkflowCanvasPage.tsx'), 'utf8')
    // A new editor element is saved by the page's editor document hook, which records it for undo.
    const documentSource = readFileSync(join(process.cwd(), 'src', 'pages', 'workspace', 'model', 'useEditorDocumentActions.ts'), 'utf8')
    const layerSource = readFileSync(join(process.cwd(), 'src', 'pages', 'workspace', 'ui', 'WorkspaceEditorLayer.tsx'), 'utf8')

    expect(pageSource).toContain('isEditorCommandContext')
    expect(pageSource).toContain('useEditorDocumentActions(')
    expect(documentSource).toContain('editorCommandApiRef.current?.recordBackendSnapshot')
    expect(layerSource).toContain('recordBackendSnapshot(document: EditorDocument')
    expect(layerSource).toContain('split.annotationIds.length === 0')
    expect(layerSource).toContain('persistDocument(selectElements(target.document, target.selectedIds))')
  })

  it('keeps legacy annotation toolbar commands backed by svg editor selection', () => {
    // The ribbon's commands are built in workspaceRibbon.tsx from the page's state.
    const ribbonSource = readFileSync(join(process.cwd(), 'src', 'pages', 'workspace', 'ui', 'workspaceRibbon.tsx'), 'utf8')
    const viewportSource = readFileSync(join(process.cwd(), 'src', 'pages', 'workspace', 'ui', 'CanvasViewport.tsx'), 'utf8')

    expect(viewportSource).toContain('selectedEditorAnnotationIdsRef.current')
    expect(viewportSource).toContain('onAnnotationSelectionReady?.(() => selectedEditorAnnotationIdsRef.current)')
    expect(viewportSource).toContain('filter((id) => annotationIds.has(id))')
    expect(ribbonSource).toContain('batchAnnotations: (input) => k.batchAnnotationMutation.mutateAsync(input)')
  })
})

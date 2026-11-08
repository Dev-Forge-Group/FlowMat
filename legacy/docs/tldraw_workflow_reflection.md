# tldraw Workflow Reflection for FlowMat

## Purpose

This note records what the `tldraw` workflow template is doing structurally, why it works, and how those ideas should be reflected in `FlowMat`.

The goal is not to copy `tldraw` directly. `FlowMat` currently uses React Flow and a Spring Boot backend. The useful part is the architecture:

1. Separate domain node definitions from canvas rendering.
2. Separate connection rules from UI event handlers.
3. Keep the canvas entrypoint as a composition layer rather than a dumping ground for behavior.

## What tldraw's workflow template is really doing

### 1. `App.tsx` is an assembly root

In `templates/workflow/src/App.tsx`, the app does not hardcode everything into one component. It assembles:

- custom shape utils
- custom binding utils
- custom overlay utils
- custom UI components
- custom interaction state

This means the canvas runtime is composed from domain parts.

### 2. `NodeShapeUtil` is the domain node model

`NodeShapeUtil` is not just a visual rectangle. It defines:

- what a node is
- what props it owns
- how big it is
- how ports are exposed
- how it renders

This is why the workflow template can support multiple node kinds without scattering shape decisions across unrelated files.

### 3. `ConnectionBindingUtil` is the relationship model

The template treats a connection as a first-class relationship with lifecycle:

- connect
- reconnect
- disconnect
- delete

This prevents canvas behavior from being spread across click handlers.

### 4. `PointingPort` extends interaction, not just rendering

The workflow template changes the canvas by extending interaction state. That is the important idea:

- clicking a port means something domain-specific
- dragging from a port means something domain-specific

The runtime is tool/state driven, not only component driven.

## What this means for FlowMat

`FlowMat` should treat its workflow canvas as a domain editor, not as a generic graph UI.

That means:

1. Node palette must reflect backend-supported node types.
2. Node rendering rules must come from a single catalog.
3. Connection creation/deletion rules must come from a single policy layer.
4. The canvas page should orchestrate behavior, not define domain constants inline.

## Changes reflected now

### Node catalog

`FlowMat` now has a dedicated node catalog:

- `flowmat_frontend/src/entities/workflow/model/nodeCatalog.ts`

This file centralizes:

- palette-visible node types
- labels
- descriptions
- default size
- color scheme
- visual shape kind

It also aligns the frontend palette with backend-recognized node types:

- `process`
- `equipment`
- `storage`
- `input`
- `output`

### Connection policy

`FlowMat` now has a dedicated connection policy:

- `flowmat_frontend/src/entities/workflow/model/connectionPolicy.ts`

This file centralizes:

- default connection payload creation
- handle normalization
- related edge lookup for safe node deletion

### Backend node type validation

`FlowMat` backend now validates workflow node types against the workflow domain enum.

- `flowmat_backend/modules/workflow/src/main/java/org/myweb/flowmat/domain/workflow/domain/enums/NodeType.java`
- `flowmat_backend/modules/workflow/src/main/java/org/myweb/flowmat/domain/workflow/application/ProcessServiceImpl.java`
- `flowmat_backend/modules/workflow/src/main/java/org/myweb/flowmat/domain/workflow/application/ProcessTemplateServiceImpl.java`

This means frontend palette definitions and backend accepted values are no longer drifting silently.

## Why this matters

Without this separation, every new node type or connection rule forces edits in:

- the palette
- the canvas page
- the node renderer
- deletion logic
- connection logic

With this separation, new workflow concepts can be added with less coupling.

## Changes reflected (second pass — interaction features)

### Drag-to-create (from tldraw `useDragToCreate`)

Palette items in the left panel are now draggable onto the canvas. Dropping
creates the node centered on the drop position.

- `flowmat_frontend/src/pages/workspace/ui/WorkflowCanvasPage.tsx` — palette `draggable` + dataTransfer
- `flowmat_frontend/src/pages/workspace/ui/CanvasViewport.tsx` — `onDragOver`/`onDrop`, `PALETTE_DRAG_MIME`
- `flowmat_frontend/src/pages/workspace/model/useWorkflowCanvasActions.ts` — `createNodeFromTool`

### On-canvas node picker (from tldraw `OnCanvasComponentPicker`)

Dragging a connection from a handle and dropping it on empty canvas opens a
node type picker at the drop point. Picking a type creates the node and wires
the connection automatically (works from both output and input handles).

- `flowmat_frontend/src/pages/workspace/model/canvasInteractionStore.ts` — picker + edge-hover state
- `flowmat_frontend/src/pages/workspace/ui/NodePickerPopup.tsx` — popup UI
- `flowmat_frontend/src/pages/workspace/ui/CanvasViewport.tsx` — `onConnectEnd` → picker
- `flowmat_frontend/src/pages/workspace/model/useWorkflowCanvasActions.ts` — `createNodeFromConnectionDrop`

### Insert node within connection (from tldraw `insertNodeWithinConnection` + `ConnectionCenterHandleOverlayUtil`)

Hovering or selecting an edge shows a "+" handle at its midpoint. Clicking it
opens the same picker; picking a type splits the connection: the original edge
is removed and replaced by source→new and new→target. The whole operation is
undoable as a single command.

- `flowmat_frontend/src/pages/workspace/ui/CanvasEdge.tsx` — center "+" handle
- `flowmat_frontend/src/pages/workspace/model/useWorkflowCanvasActions.ts` — `insertNodeOnEdge`

Not ported (yet) from the tldraw insert flow: automatic nudging/animation of
downstream nodes to make room for the inserted node.

## Next recommended steps

1. Move node edit forms into a dedicated workflow-node command layer.
2. Move port CRUD into a dedicated port policy layer.
3. ~~Add backend validation so node types are constrained to the same catalog used by the frontend.~~ (done)
4. Add template-driven node insertion so process templates become first-class canvas tools.
5. Port workflow region detection + execution graph (`WorkflowRegions`, `ExecutionGraph`) once the Run domain contract is settled with the backend.
6. Nudge downstream nodes aside when inserting a node into a connection (tldraw `moveNodesIfNeeded`).

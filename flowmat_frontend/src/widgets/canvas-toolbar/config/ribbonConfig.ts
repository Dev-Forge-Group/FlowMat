import {
  AlignCenterHorizontal,
  AlignCenterVertical,
  AlignEndHorizontal,
  AlignEndVertical,
  AlignHorizontalDistributeCenter,
  AlignHorizontalJustifyStart,
  AlignStartHorizontal,
  AlignStartVertical,
  AlignVerticalDistributeCenter,
  AlignVerticalJustifyStart,
  BoxSelect,
  BringToFront,
  Copy,
  FileCode,
  FileJson,
  Group,
  Image,
  Maximize2,
  MousePointer2,
  Plus,
  Redo2,
  RefreshCw,
  Save,
  SendToBack,
  Trash2,
  Undo2,
  Ungroup,
} from 'lucide-react'
import type { ReactNode } from 'react'
import type { RibbonButtonDefinition, RibbonGroupDefinition, RibbonTabDefinition } from '../ui/types'

// Structure only — no onClick/active/disabled here. See migration plan §5-1:
// adding a button should mean adding one entry to a group's `buttons` array below,
// nothing else. Handlers are injected per-render via buildRibbonTabs(handlers).
export type RibbonButtonSkeleton = Omit<RibbonButtonDefinition, 'onClick' | 'active' | 'disabled' | 'title'>

export interface RibbonGroupSkeleton {
  id: string
  label: string
  buttons: RibbonButtonSkeleton[]
}

export interface RibbonTabSkeleton {
  id: string
  label: string
  groups: RibbonGroupSkeleton[]
}

// Step 2: Home tab groups/buttons filled in per migration plan §2.
// Step 3: Annotate tab groups/buttons filled in per migration plan §3-2 (Draw/Editor
// Document/Align/Group/Arrange groups; Grid is a skeleton — see §9 Step 3 log).
// Step 4: Collaborate tab groups per migration plan §4. Presence/Status/Workflow all
// show state rather than trigger actions, so their `buttons` stay empty here — actual
// content (avatars, save label, workflow <select>) is injected per-render via
// buildRibbonTabs(handlers, dynamicButtons, groupContent), same pattern as
// dynamicButtons but for non-button content. See §9 Step 4 log for why the
// workflow-switcher ended up here instead of staying in the title bar.
export const ribbonConfig: RibbonTabSkeleton[] = [
  {
    id: 'home',
    label: 'Home',
    groups: [
      {
        id: 'tools',
        label: 'Tools',
        buttons: [
          { id: 'select-pointer', icon: MousePointer2, label: 'Pointer' },
          { id: 'add-node', icon: Plus, label: 'Add Node' },
        ],
      },
      {
        id: 'modify',
        label: 'Modify',
        buttons: [
          { id: 'undo', icon: Undo2, label: 'Undo' },
          { id: 'redo', icon: Redo2, label: 'Redo' },
        ],
      },
      {
        id: 'layout',
        label: 'Layout',
        buttons: [
          { id: 'layout-tb', icon: AlignVerticalJustifyStart, label: 'Layout TB' },
          { id: 'layout-lr', icon: AlignHorizontalJustifyStart, label: 'Layout LR' },
        ],
      },
      {
        id: 'export',
        label: 'Export',
        buttons: [
          { id: 'export-json', icon: FileJson, label: 'Export JSON' },
          { id: 'export-png', icon: Image, label: 'Export PNG' },
          { id: 'export-svg', icon: FileCode, label: 'Export SVG' },
        ],
      },
    ],
  },
  {
    id: 'annotate',
    label: 'Annotate',
    groups: [
      {
        // Buttons are all dynamic (ANNOTATION_TOOL_DEFINITIONS + EDITOR_TOOL_DEFINITIONS),
        // injected via ribbonDynamicButtons.draw — see migration plan §3-2.
        id: 'draw',
        label: 'Draw',
        buttons: [],
      },
      {
        id: 'editor-document',
        label: 'Editor Document',
        buttons: [
          { id: 'save-editor-document', icon: Save, label: 'Save Editor' },
          { id: 'reload-editor-document', icon: RefreshCw, label: 'Reload' },
        ],
      },
      {
        id: 'align',
        label: 'Align',
        buttons: [
          { id: 'align-left', icon: AlignStartVertical, label: 'Left' },
          { id: 'align-center-x', icon: AlignCenterVertical, label: 'Center' },
          { id: 'align-right', icon: AlignEndVertical, label: 'Right' },
          { id: 'align-top', icon: AlignStartHorizontal, label: 'Top' },
          { id: 'align-center-y', icon: AlignCenterHorizontal, label: 'Middle' },
          { id: 'align-bottom', icon: AlignEndHorizontal, label: 'Bottom' },
          { id: 'distribute-horizontal', icon: AlignHorizontalDistributeCenter, label: 'Dist. H' },
          { id: 'distribute-vertical', icon: AlignVerticalDistributeCenter, label: 'Dist. V' },
        ],
      },
      {
        id: 'group',
        label: 'Group',
        buttons: [
          { id: 'group', icon: Group, label: 'Group' },
          { id: 'ungroup', icon: Ungroup, label: 'Ungroup' },
        ],
      },
      {
        id: 'arrange',
        label: 'Arrange',
        buttons: [
          { id: 'duplicate-selected', icon: Copy, label: 'Duplicate' },
          { id: 'delete-selected', icon: Trash2, label: 'Delete' },
          { id: 'bring-to-front', icon: BringToFront, label: 'Front' },
          { id: 'send-to-back', icon: SendToBack, label: 'Back' },
        ],
      },
      {
        // Skeleton only — no grid snap toggle UI yet. See migration plan §⚠️ item 7 /
        // §9 Step 3 log: WORKSPACE_EDITOR_GRID_SIZE is still a fixed constant, no
        // on/off state exists to wire a button to. Left for a follow-up step.
        id: 'grid',
        label: 'Grid',
        buttons: [],
      },
    ],
  },
  {
    id: 'view',
    label: 'View',
    groups: [
      {
        id: 'navigation',
        label: 'Navigation',
        buttons: [
          { id: 'fit-view', icon: Maximize2, label: 'Fit View' },
          { id: 'select-all', icon: BoxSelect, label: 'Select All' },
        ],
      },
    ],
  },
  {
    id: 'collaborate',
    label: 'Collaborate',
    groups: [
      { id: 'presence', label: 'Presence', buttons: [] },
      { id: 'status', label: 'Status', buttons: [] },
      { id: 'workflow', label: 'Workflow', buttons: [] },
    ],
  },
]

export type RibbonButtonHandlers = Record<
  string,
  Pick<RibbonButtonDefinition, 'onClick'> & Partial<Pick<RibbonButtonDefinition, 'active' | 'disabled' | 'title'>>
>

// groupId -> fully-built buttons appended after that group's static buttons.
// Used for buttons whose set can't be known ahead of time in ribbonConfig.ts
// (e.g. one per palette node type) — data/logic stays injected from the page,
// per plan §5-1.
export type RibbonDynamicButtons = Record<string, RibbonButtonDefinition[]>

// groupId -> non-button content (presence avatars, status text, a <select>, ...) for
// groups that show state rather than trigger actions. Same injection pattern as
// dynamicButtons, but for RibbonGroupDefinition.content instead of extra buttons.
export type RibbonGroupContent = Record<string, ReactNode>

export function buildRibbonTabs(
  handlers: RibbonButtonHandlers = {},
  dynamicButtons: RibbonDynamicButtons = {},
  groupContent: RibbonGroupContent = {}
): RibbonTabDefinition[] {
  return ribbonConfig.map((tab) => ({
    id: tab.id,
    label: tab.label,
    groups: tab.groups.map((group) =>
      buildRibbonGroup(group, handlers, dynamicButtons[group.id] ?? [], groupContent[group.id])
    ),
  }))
}

function buildRibbonGroup(
  group: RibbonGroupSkeleton,
  handlers: RibbonButtonHandlers,
  extraButtons: RibbonButtonDefinition[],
  content: ReactNode
): RibbonGroupDefinition {
  const staticButtons = group.buttons.map((button) => {
    const handler = handlers[button.id]
    return {
      ...button,
      onClick: handler?.onClick ?? (() => {}),
      active: handler?.active,
      disabled: handler?.disabled,
      title: handler?.title,
    }
  })
  return {
    id: group.id,
    label: group.label,
    buttons: [...staticButtons, ...extraButtons],
    content,
  }
}

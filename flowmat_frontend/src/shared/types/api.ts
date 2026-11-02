export interface ApiEnvelope<T> {
  success: boolean
  data: T | null
  message: string | null
}

export type UiError = {
  httpStatus: number
  message: string
  kind: 'validation' | 'not_found' | 'forbidden' | 'unknown'
}

// Raw DTOs from the backend.

export interface UserDto {
  id: string
  userId: string
  userName: string
  userEmail: string
  userStatus: string
}

export interface UserPermissionDto {
  canManageUsers: boolean
  canManageTemplates: boolean
  canManageMasterData: boolean
}

export interface UnitDto {
  unitId: string
  unitCode: string
  unitName: string
  unitType: string
  baseUnitCode: string | null
  conversionRate: number
  activeYn: string
}

export interface ProductionRunDto {
  productionRunId: string
  projectId: string
  workflowId: string
  workflowRevisionId: string | null
  runNumber: string
  runType: string | null
  runStatus: string
  targetItemId: string | null
  plannedOutputQty: number
  actualOutputQty: number | null
  workOrderId: string | null
  /** Original server completion instant; legacy finished records may lack it. */
  actualEndAt?: string | null
  /** BOM revision frozen onto the run at start, if any. */
  bomId: string | null
  bomVersion: number | null
}

export interface WorkflowRevisionDto {
  workflowRevisionId: string
  workflowId: string
  revisionNo: number
  status: 'published' | 'retired'
  schemaVersion: number
  publishedBy: string
  publishedAt: string
  retiredBy: string | null
  retiredAt: string | null
}

export interface WorkflowRevisionDetailDto extends WorkflowRevisionDto {
  snapshot: unknown
}

export type WorkOrderStatus = 'draft' | 'approved' | 'in_progress' | 'completed' | 'cancelled'

export interface WorkOrderDto {
  workOrderId: string
  projectId: string
  workflowId: string | null
  workOrderNumber: string
  workOrderTitle: string
  workOrderStatus: WorkOrderStatus
  priority: string
  targetItemId: string | null
  targetQuantity: number | null
  plannedStartAt: string | null
  plannedEndAt: string | null
  actualStartAt: string | null
  actualEndAt: string | null
  instruction: string | null
  assignedTo: string | null
  approvedBy: string | null
  approvedAt: string | null
  producedQuantity: number
  runCount: number
  bomId: string | null
  /** Link to the written work instruction; http or https only. */
  instructionUrl?: string | null
  /** The equipment the order runs on; readiness checks its time in the planned window (docs/domain/equipment-schedule.md). */
  equipmentId?: string | null
  /** Optional BOM materials the order takes, by item; its BOM calculations plan them (ADR-033). */
  optionalItemIds?: string[]
}

export interface ProductionRunItemDto {
  productionRunItemId: string
  productionRunId: string
  processId: string | null
  processIoId: string | null
  inventoryId: string | null
  itemId: string
  direction: string
  plannedQty: number
  actualQty: number | null
  unit: string
  /**
   * "manual" when recorded by hand, "bom" when planned from the BOM snapshot at run start, "correction" when added by a
   * finished-run correction.
   */
  quantitySource: string | null
  conversionRate: number | null
  lotId: string | null
  /** A cancelled recording: its stock movement was reversed and it no longer counts. */
  cancelled: boolean
  cancelledBy: string | null
  cancelledAt: string | null
  cancelReason: string | null
  /** The correction that added this recording. */
  productionRunCorrectionId?: string | null
  /** The correction that voided this recording (null when it was cancelled on the open run). */
  cancelledByCorrectionId?: string | null
  /** A planned BOM line expanded through this phantom sub-assembly (docs/domain/multi-level-bom.md P2). */
  viaItemId?: string | null
}

/** A run's material cost at today's unit costs (docs/domain/material-cost.md). Nothing is stored. */
export type RunCostBasis = 'CURRENT' | 'HISTORICAL' | 'ESTIMATED'

export interface RunCostDto {
  productionRunId: string
  materialCost: number
  /** False when some input has no unit cost (or a unit that cannot be converted). */
  costComplete: boolean
  outputQuantity: number | null
  /** materialCost / outputQuantity; null until there is an output quantity or while the cost is incomplete. */
  costPerUnit: number | null
  /** Price instant for completed runs; null when the original end time is unavailable. */
  costBasisAt?: string | null
  costBasis?: RunCostBasis
  estimated?: boolean
  lines: {
    itemId: string
    itemCode: string
    itemName: string | null
    quantity: number | null
    unit: string | null
    unitCost: number | null
    cost: number | null
    costBasis?: RunCostBasis
  }[]
}

/** A run's material use against the BOM it started with, scaled to the output (GET /production-runs/{id}/material-usage). */
export interface RunMaterialUsageDto {
  productionRunId: string
  bomId: string | null
  bomVersion: number | null
  plannedOutputQty: number | null
  /** The output the standard is worked out for: the actual output once there is one, else the planned output. */
  basisQuantity: number | null
  basisIsActual: boolean
  /** Over zero: more was spent than the BOM allows. */
  varianceCost: number
  varianceCostComplete: boolean
  lines: RunMaterialUsageLineDto[]
}

/** One input item in its own unit; an item not in the BOM has no plan and a standard of 0. */
export interface RunMaterialUsageLineDto {
  itemId: string
  itemCode: string
  itemName: string | null
  unit: string | null
  inBom: boolean
  planned: number | null
  standard: number | null
  /** Null when a recording's unit cannot be converted. */
  actual: number | null
  variance: number | null
  variancePercent: number | null
  unitCost: number | null
  varianceCost: number | null
  /** The main material this item stood in for: it takes a share of that material's standard (ADR-032). */
  substituteFor?: string | null
  /** An optional material of the BOM that the run's work order did not pick, so not planned (ADR-033). */
  optional?: boolean
}

export type ReadinessCheckStatus = 'ok' | 'warn' | 'fail'

/** Whether a work order can run now (docs/domain/work-order-readiness.md). Nothing is reserved by checking. */
export interface WorkOrderReadinessDto {
  workOrderId: string
  /** No check failed; warnings do not block. */
  ready: boolean
  /** Target minus what finished runs produced; null without a target quantity. */
  remainingQuantity: number | null
  checks: { code: string; status: ReadinessCheckStatus; message: string }[]
  materials: {
    itemId: string
    itemCode: string
    itemName: string | null
    requiredQuantity: number
    unit: string
    availableQuantity: number
    shortageQuantity: number
    lotTracked: boolean
    usableLots: number
  }[]
}

export type RunCorrectionStatus = 'pending_approval' | 'applied' | 'rejected'
export type RunCorrectionKind =
  | 'void_item' | 'add_item' | 'set_output_qty' | 'cancel_setup' | 'add_setup' | 'cancel_running_time' | 'add_running_time'
  | 'cancel_labor' | 'add_labor'

export interface RunCorrectionLineDto {
  lineNo: number
  kind: RunCorrectionKind
  targetRunItemId: string | null
  direction: string | null
  itemId: string | null
  inventoryId: string | null
  qty: number | null
  unit: string | null
  beforeQty: number | null
  afterQty: number | null
  createdRunItemId: string | null
  /** Setup corrections (docs/domain/equipment-setup-cost.md AS7). */
  targetRunSetupId?: string | null
  equipmentId?: string | null
  setupMinutes?: number | null
  createdRunSetupId?: string | null
  /** Running-time corrections (docs/domain/equipment-setup-cost.md ER10); add_running_time uses equipmentId. */
  targetRunEquipmentTimeId?: string | null
  runningMinutes?: number | null
  createdRunEquipmentTimeId?: string | null
  /** Worked-time corrections (docs/domain/worker-calendar.md LB9). */
  targetRunLaborId?: string | null
  userId?: string | null
  workedMinutes?: number | null
  createdRunLaborId?: string | null
  /** add_labor: the process step it names (LB17). */
  stepId?: string | null
}

/** A correction of a finished run (docs/domain/production-run-correction.md). */
export interface RunCorrectionDto {
  productionRunCorrectionId: string
  productionRunId: string
  correctionNo: number
  status: RunCorrectionStatus
  reason: string
  requestedBy: string
  requestedAt: string
  decidedBy: string | null
  decidedAt: string | null
  decisionNote: string | null
  appliedAt: string | null
  lines: RunCorrectionLineDto[]
}

/** One change in a correction request. */
export type RunCorrectionLineRequest =
  | { kind: 'void_item'; targetRunItemId: string }
  | { kind: 'add_item'; direction: 'input' | 'output'; itemId: string; inventoryId: string | null; qty: number; unit: string }
  | { kind: 'set_output_qty'; afterQty: number }
  | { kind: 'cancel_setup'; targetRunSetupId: string }
  | { kind: 'add_setup'; equipmentId: string; setupMinutes: number }
  | { kind: 'cancel_running_time'; targetRunEquipmentTimeId: string }
  | { kind: 'add_running_time'; equipmentId: string; runningMinutes: number }
  | { kind: 'cancel_labor'; targetRunLaborId: string }
  | { kind: 'add_labor'; userId: string; workedMinutes: number; stepId?: string }

export type InspectionResult = 'pass' | 'fail'
export type DefectSeverity = 'minor' | 'major' | 'critical'

/** A recorded inspection (docs/domain/quality-inspection.md). Never edited; a new one follows a wrong one. */
export interface QualityInspectionDto {
  inspectionId: string
  projectId: string
  productionRunId: string | null
  runNumber: string | null
  itemId: string | null
  itemCode: string | null
  itemName: string | null
  lotId: string | null
  lotNo: string | null
  /** The LOT's status now, not at inspection time. */
  lotStatus: string | null
  inspectionType: string
  resultStatus: InspectionResult
  measuredValue: number | null
  standardMin: number | null
  standardMax: number | null
  unit: string | null
  note: string | null
  inspectedBy: string
  inspectedAt: string
  /** The inspection standard followed, if any. */
  standardId?: string | null
}

export interface QualityInspectionCreateRequest {
  projectId: string
  productionRunId?: string | null
  lotId?: string | null
  itemId?: string | null
  inspectionType: string
  /** Worked out by the server when a measured value and a limit are given. */
  result?: InspectionResult | null
  measuredValue?: number | null
  standardMin?: number | null
  standardMax?: number | null
  unit?: string | null
  note?: string | null
  /** Failed inspections of a LOT only: quarantine the whole LOT in the same request. */
  quarantineLot?: boolean
  /** The inspection standard followed; it sets the check, limits and unit (docs/domain/inspection-standard.md). */
  standardId?: string | null
}

/** A logged defect; logging one moves no stock. */
export interface DefectDto {
  defectLogId: string
  projectId: string
  inspectionId: string | null
  productionRunId: string | null
  runNumber: string | null
  itemId: string
  itemCode: string | null
  itemName: string | null
  lotId: string | null
  lotNo: string | null
  defectType: string
  quantity: number
  /** The item's unit. */
  unit: string | null
  severity: DefectSeverity
  reason: string | null
  resolved: boolean
  actionTaken: string | null
  loggedBy: string
  loggedAt: string
  resolvedBy: string | null
  resolvedAt: string | null
}

export interface DefectCreateRequest {
  projectId: string
  inspectionId?: string | null
  productionRunId?: string | null
  lotId?: string | null
  itemId?: string | null
  quantity: number
  defectType: string
  severity?: DefectSeverity
  reason?: string | null
}

/** The project's quality at a glance (GET /quality/summary), over an optional window. */
export interface QualitySummaryDto {
  inspections: number
  passed: number
  failed: number
  /** passed / inspections, 0–1; null without inspections. */
  passRate: number | null
  openDefects: number
  resolvedDefects: number
  /** Most frequent first; counts only, as quantities of different items do not add up. */
  defectsByType: { defectType: string; count: number; open: number }[]
  /** Checks that failed at least once, most failures first. */
  failuresByCheck: { inspectionType: string; inspections: number; failed: number }[]
  /** Items with a failed inspection or a defect, most defects first; defectQuantity is in the item's unit. */
  byItem?: {
    itemId: string
    itemCode: string | null
    itemName: string | null
    unit: string | null
    inspections: number
    failed: number
    defects: number
    openDefects: number
    defectQuantity: number
  }[]
}

/** Consumption, days of cover and idle time per item (GET /stock-analysis). Quantities are in the item's unit. */
export interface StockAnalysisDto {
  days: number
  /** Start of the consumption window; it ends now. */
  from: string
  /** The listed place the analysis is limited to, with the places inside it; null for the whole project. */
  location?: string | null
  lines: StockAnalysisLineDto[]
}

export interface StockAnalysisLineDto {
  itemId: string
  itemCode: string
  itemName: string | null
  unit: string | null
  onHandQuantity: number
  /** Available outside quarantine and closed or expired LOTs. */
  usableQuantity: number
  stockValue: number | null
  /** Issued and put into production in the window, less reversals. */
  consumedQuantity: number
  averageDailyConsumption: number
  /** How long the usable stock lasts at the window's rate; null without consumption. */
  daysOfCover: number | null
  leadTimeDays: number | null
  coverBelowLeadTime: boolean
  lastConsumedAt: string | null
  lastReceivedAt: string | null
  /** Days since the last consumption, or since the first receipt when never consumed. */
  idleDays: number | null
  /** consumedQuantity × unit cost; null without a unit cost. */
  consumedValue: number | null
  /** A: the items making up the first 80% of the value used, B: the next 15%, C: the rest and unused. Null without a unit cost. */
  abcClass: 'A' | 'B' | 'C' | null
}

/** Stock as it stood at a moment (GET /inventory-snapshots), rebuilt from the ledger; values at today's unit costs. */
export interface StockSnapshotDto {
  at: string
  totalValue: number
  /** False when some record's item has no unit cost; the total leaves it out. */
  valueComplete: boolean
  rows: StockSnapshotRowDto[]
}

export interface StockSnapshotRowDto {
  inventoryId: string
  itemId: string
  itemCode: string | null
  itemName: string | null
  unit: string | null
  location: string | null
  lotId: string | null
  lotNo: string | null
  quantity: number
  reservedQuantity: number
  value: number | null
  /** False for a record that has never moved: its quantity is what it was created with. */
  fromLedger: boolean
}

/** One item line of a spreadsheet import (POST /items/import); every value is text as it was in the file. */
export interface ItemImportRowDto {
  itemCode: string
  itemName?: string
  itemType?: string
  resourceCategory?: string
  /** A unit's code such as "kg". */
  unitCode?: string
  itemStatus?: string
  lotTracked?: string
  safetyStockQty?: string
  leadTimeDays?: string
  unitCost?: string
  /** Details; a blank cell keeps the stored value. */
  itemGroup?: string
  spec?: string
  barcode?: string
  sku?: string
  storageCondition?: string
  description?: string
  /** What the item is bought in, e.g. "bag"; a blank cell keeps the stored value. */
  purchaseUnit?: string
  /** Stock units in one purchase unit. */
  purchaseUnitQty?: string
}

/** What an item import did or would do. Nothing is saved when any row has an error or it is a dry run. */
export interface ItemImportResultDto {
  dryRun: boolean
  applied: boolean
  created: number
  updated: number
  unchanged: number
  errors: number
  rows: { row: number; itemCode: string | null; action: 'create' | 'update' | 'unchanged' | 'error'; message: string | null }[]
}

/** One material line of a draft BOM import (POST /boms/{id}/lines/import); values are text as in the file. */
export interface BomLineImportRowDto {
  itemCode: string
  quantity?: string
  unit?: string
  note?: string
  /** material (blank), by_product or waste (docs/domain/bom-by-products.md). */
  lineType?: string
  /** Y for a phantom sub-assembly, blank or N for none (docs/domain/multi-level-bom.md P1). */
  phantom?: string
  /** Planned scrap as a percent, as on screen: 5 or 5% (ADR-031); blank for none. */
  scrapPercent?: string
}

/** What a BOM line import did or would do; nothing is saved when any row has an error or it is a dry run. */
export interface BomLineImportResultDto {
  dryRun: boolean
  applied: boolean
  added: number
  /** Lines of the draft removed (or to be removed) because the import replaces them. */
  removed: number
  errors: number
  rows: { row: number; itemCode: string | null; action: 'add' | 'error'; message: string | null }[]
}

/** One row of a stock import (POST /inventories/import); values are text as in the file. */
export interface StockImportRowDto {
  itemCode: string
  location?: string
  lotNo?: string
  quantity?: string
  /** yyyy-MM-dd, for a LOT the file registers. */
  expiryDate?: string
  /** Instead of a quantity: how many of the item's purchase units came in. */
  packs?: string
}

/** What a stock import did or would do; nothing is received when any row has an error or it is a dry run. */
export interface StockImportResultDto {
  dryRun: boolean
  applied: boolean
  created: number
  received: number
  newLots: number
  errors: number
  rows: { row: number; itemCode: string | null; action: 'create' | 'receive' | 'error'; message: string | null }[]
}

/** Each item's stock over a period (GET /stock-movement-summary); quantities in the item's unit. */
export interface StockMovementSummaryDto {
  from: string
  to: string
  lines: StockMovementSummaryLineDto[]
}

export interface StockMovementSummaryLineDto {
  itemId: string
  itemCode: string | null
  itemName: string | null
  unit: string | null
  opening: number
  received: number
  produced: number
  /** Issues, as a positive number. */
  issued: number
  /** Production input, as a positive number. */
  consumed: number
  /** Transfers in less transfers out; zero, as a transfer stays with the item. */
  transferred: number
  /** Adjustments and reversals, signed. */
  corrected: number
  closing: number
  /** Not zero only for records that changed outside the ledger. */
  unexplained: number
}

/** A suspect LOT and every LOT made from it (GET /lots/{id}/recall), start first. */
export interface LotRecallDto {
  lotId: string
  lotNo: string
  lots: LotRecallLineDto[]
}

export interface LotRecallLineDto {
  lotId: string
  lotNo: string
  itemId: string
  itemCode: string | null
  itemName: string | null
  unit: string | null
  /** 0 for the suspect LOT, then steps along the genealogy. */
  depth: number
  /** The LOT one step closer to the start that this one was made from. */
  viaLotNo: string | null
  lotStatus: LotStatus
  onHand: number
  /** "WH-A 4" for each record holding stock. */
  places: string[]
  /** What issues took out, less those reversed: what already left the site. */
  issued: number
}

export interface LotRecallQuarantineResultDto {
  quarantined: string[]
  skipped: { lotNo: string; reason: string }[]
}

/** What open work orders still need against usable stock (GET /material-requirements). */
export interface MaterialRequirementDto {
  /** Open work orders counted: approved or in progress, with a BOM and quantity still to make. */
  orders: number
  /** Biggest shortfall first; quantities in the material's own unit. */
  lines: {
    itemId: string
    itemCode: string | null
    itemName: string | null
    unit: string | null
    required: number
    usable: number
    /** required − usable − plannedSupply − onOrder, never below zero. */
    shortage: number
    /** A work order's share, or (with viaItemCode, no work order) the share from making a short sub-assembly. */
    orders: { workOrderId: string | null; workOrderTitle: string | null; required: number; viaItemId?: string | null; viaItemCode?: string | null }[]
    /** What open orders making this item still have to make (docs/domain/multi-level-bom.md). */
    plannedSupply?: number
    /** The item has its own approved BOM: what stock and planned supply leave short is made from its materials. */
    madeHere?: boolean
    /** Ordered from suppliers and not received yet (docs/domain/purchasing.md PO8). */
    onOrder?: number
    /** plannedSupply work order by work order (docs/domain/purchasing.md PO25). */
    supplies?: { workOrderId: string; workOrderTitle: string | null; quantity: number }[]
  }[]
  /** Work orders whose BOM could not be worked out, with the reason. */
  problems: string[]
}

/** A BOM revision that uses an item as a line (where-used). */
export interface BomWhereUsedDto {
  bomId: string
  bomName: string
  bomVersion: number
  bomStatus: string
  targetItemId: string
  targetItemCode: string | null
  targetItemName: string | null
  baseQuantity: number
  baseUnit: string
  bomLineId: string
  lineQuantity: number
  lineUnit: string
  scrapRate: number | null
  /** Whether the BOM uses the item or gives it off. */
  lineType?: BomLineType
}

/**
 * Where an item is used at every level through approved BOMs (GET /boms/where-used/all-levels,
 * docs/domain/multi-level-bom.md). Quantities of the searched item are in `unit`.
 */
export interface BomWhereUsedTreeDto {
  itemId: string
  itemCode: string
  unit: string | null
  /** Depth first: a direct use, then the uses of that product. */
  uses: {
    level: number
    materialItemId: string
    materialItemCode: string
    productItemId: string
    productItemCode: string
    productItemName: string | null
    productUnit: string | null
    bomId: string
    bomVersion: number
    lineQuantity: number
    lineUnit: string
    baseQuantity: number
    baseUnit: string
    /** The searched item per one unit of the product along this route; null when a quantity could not be converted. */
    perProductUnit: number | null
    topLevel: boolean
    path: string[]
  }[]
  /** Products no approved BOM uses, with the searched item per unit over all routes. */
  topProducts: {
    itemId: string
    itemCode: string
    itemName: string | null
    unit: string | null
    perUnit: number | null
    levels: number
    routes: number
  }[]
  problems: string[]
  /** The day whose revisions were walked (docs/domain/multi-level-bom.md M7). */
  asOf?: string
}

/** A stock row outside its thresholds (docs/domain/stock-alert.md); it closes by itself when the row is back inside. */
export interface StockAlertDto {
  stockAlertId: string
  projectId: string
  inventoryId: string
  itemId: string
  itemCode: string | null
  itemName: string | null
  location: string | null
  lotNo: string | null
  /**
   * low: available below the minimum. over: on hand above the maximum. expiry: stock of a LOT expiring within the warning
   * window (threshold = window in days, actual = days left, negative once expired).
   */
  alertType: 'low' | 'over' | 'expiry'
  severity: 'critical' | 'warning' | 'info'
  thresholdValue: number
  /** The latest value while open. */
  actualValue: number
  unit: string | null
  message: string | null
  resolved: boolean
  triggeredAt: string
  resolvedAt: string | null
}

export interface InventoryDto {
  inventoryId: string
  projectId: string
  itemId: string
  quantity: number
  reservedQuantity: number
  availableQuantity: number
  inventoryStatus: string
  location: string | null
  minThreshold: number | null
  maxThreshold: number | null
  stockLevel: 'low' | 'ok' | 'over'
  /** Optimistic-lock version; send it back as expectedVersion when adjusting. */
  version: number | null
  lotId: string | null
  lotNo: string | null
  /** When a stock count last covered this record (ISO timestamp); null when never counted. */
  lastCheckedAt?: string | null
  lastCheckedBy?: string | null
}

export type BomStatus = 'draft' | 'pending_approval' | 'approved' | 'retired'

/** A line the BOM consumes, or one a batch gives off (docs/domain/bom-by-products.md). */
export type BomLineType = 'material' | 'by_product' | 'waste'

/** A by-product or waste line scaled to a batch: in the line's unit and in the item's own unit. */
export interface BomOutputDto {
  bomLineId: string
  itemId: string
  lineType: BomLineType
  lineQuantity: number
  lineUnit: string
  quantity: number
  itemUnit: string
  itemQuantity: number
}

export interface BomLineDto {
  bomLineId: string
  childItemId: string
  /** Missing on lines from before line types: a material. */
  lineType?: BomLineType
  quantity: number
  unit: string
  scrapRate: number | null
  optionalYn: string | null
  substituteGroup: string | null
  sortOrder: number | null
  note: string | null
  /** A phantom sub-assembly, used through its own BOM (docs/domain/multi-level-bom.md P1). */
  phantom?: boolean
  /** Order of preference in the substitute group, lowest first (ADR-032). */
  priority?: number | null
  /** The main line this one stands in for; null for a main line and a line outside a group (ADR-032). */
  substituteFor?: string | null
}

/** One BOM revision. Only drafts can change; a change to an approved BOM is a new revision. */
export interface BomDto {
  bomId: string
  projectId: string
  targetItemId: string
  bomName: string
  bomVersion: number
  baseQuantity: number
  baseUnit: string
  bomStatus: BomStatus
  approvedBy: string | null
  approvedAt: string | null
  note: string | null
  lines: BomLineDto[]
  /** First day the revision is effective (project calendar); null or missing is open (docs/domain/multi-level-bom.md). */
  effectiveFrom?: string | null
  /** Last day it is effective; null or missing is open. */
  effectiveTo?: string | null
}

export interface BomRequirementLineDto {
  bomLineId: string
  childItemId: string
  lineQuantity: number
  lineUnit: string
  requiredQuantity: number
  itemUnit: string
  requiredItemQuantity: number
  conversionRate: number
  /** Cost per itemUnit; null when the material has no unit cost (docs/domain/material-cost.md). */
  unitCost?: number | null
  /** requiredItemQuantity × unitCost; null when the unit cost is not known. */
  lineCost?: number | null
  /** The phantom sub-assembly this material comes through (docs/domain/multi-level-bom.md P2). */
  viaItemId?: string | null
  /** The line's planned scrap, 0 to 1; the required quantities already include it (ADR-031). */
  scrapRate?: number | null
  /** Materials the BOM approves in place of this one, in order of preference; not in the requirement (ADR-032). */
  substitutes?: BomRequirementSubstituteDto[]
}

/** What the same production would take of a substitute instead of its main material, with its own scrap (ADR-032). */
export interface BomRequirementSubstituteDto {
  bomLineId: string
  childItemId: string
  requiredItemQuantity: number
  itemUnit: string
  unitCost: number | null
  lineCost: number | null
  priority: number | null
  /** How much of the main material one item unit of this substitute stands for. */
  mainPerUnit: number | null
}

/** Planned scrap against what a BOM revision's finished runs lost, material by material (ADR-034). */
export interface BomScrapPerformanceDto {
  bomId: string
  bomVersion: number
  /** Finished runs counted. */
  runs: number
  lines: BomScrapPerformanceLineDto[]
}

/** One material over the runs that planned it, each scaled to its actual output, in the item's unit. */
export interface BomScrapPerformanceLineDto {
  itemId: string
  itemCode: string
  unit: string | null
  plannedScrapRate: number
  runs: number
  /** What the output takes without scrap. */
  net: number
  planned: number
  /** What went in, a substitute as its main material. */
  actual: number
  /** actual ÷ net − 1; null when net is zero. */
  actualLossRate: number | null
  /** False when some input could not be converted and is left out. */
  complete: boolean
}

export interface BomRequirementDto {
  bomId: string
  bomVersion: number
  targetItemId: string
  productionQuantity: number
  baseQuantity: number
  lines: BomRequirementLineDto[]
  /** Sum of the known line costs. */
  materialCost?: number
  /** False when some material has no unit cost, so materialCost leaves it out. */
  costComplete?: boolean
  /** By-products and waste the batch gives off; not in lines. */
  outputs?: BomOutputDto[]
  /** Optional materials no work order picked here: worked out like lines, but not in them or in materialCost (ADR-033). */
  optionals?: BomRequirementLineDto[]
}

/** GET /boms/{id}/buildable: how much of the product usable stock could make now (docs/domain/material-requirements.md). */
export interface BuildableQuantityDto {
  bomId: string
  targetItemId: string
  /** The product's unit, which baseQuantity and every buildable quantity are in. */
  targetUnit: string
  baseQuantity: number
  /** Rounded down, whole units for a counted product; null when the BOM has no materials. */
  buildable: number | null
  limitingItemId: string | null
  lines: BuildableQuantityLineDto[]
  /** Why the BOM could not be worked out; only in the project-wide list (GET /boms/buildable). */
  problem?: string | null
}

export interface BuildableQuantityLineDto {
  childItemId: string
  itemUnit: string
  /** Needed for one batch, in the material's unit. */
  perBatch: number
  /** Available outside quarantine and closed or expired LOTs, in the material's unit. */
  usable: number
  /** What this material alone allows, in the product's unit; null when its need rounds to nothing. */
  buildable: number | null
}

export type LotStatus = 'available' | 'reserved' | 'quarantined' | 'inspection_pending' | 'consumed' | 'closed'

export interface LotDto {
  lotId: string
  projectId: string
  itemId: string
  lotNo: string
  serialNo: string | null
  lotStatus: LotStatus
  receivedAt: string | null
  producedAt: string | null
  expiryDate: string | null
  productionRunId: string | null
  quantityOnHand: number
  quantityReserved: number
  /** Past its expiry date today (docs/domain/lot-expiry.md): cannot go into production or be reserved. */
  expired?: boolean
}

export interface LotTraceNodeDto {
  lot: LotDto
  depth: number
  viaLotId: string
  productionRunId: string | null
  consumedQty: number | null
  producedQty: number | null
  unit: string | null
}

export interface LotTraceDto {
  lot: LotDto
  direction: 'backward' | 'forward'
  nodes: LotTraceNodeDto[]
}

export interface ProjectInvitePreviewDto {
  projectName: string
  projectRole: string
  inviterName: string | null
  invitedEmailMasked: string | null
  inviteStatus: string
  expiredAt: string | null
  expired: boolean
  addressedToCurrentUser: boolean
}

/** One page of GET /inventory-transactions/search, newest first; pass nextCursor back for the next page. */
export interface InventoryTransactionPageDto {
  items: InventoryTransactionDto[]
  nextCursor: string | null
}

export interface InventoryTransactionDto {
  inventoryTransactionId: string
  inventoryId: string
  projectId: string
  itemId: string
  transactionType: string
  quantityDelta: number
  reservedDelta: number
  availableDelta: number
  quantityAfter: number
  reservedAfter: number
  availableAfter: number
  referenceType: string | null
  referenceId: string | null
  note: string | null
  createdBy: string | null
  createdAt: string | null
  lotId: string | null
  /** Idempotency key sent with the movement; null for rows written before V17. */
  requestId: string | null
}

export interface ProjectSummaryDto {
  projectId: string
  projectName: string
  projectStatus: string
}

export interface ProjectDto {
  projectId: string
  projectName: string
  projectDesc: string | null
  projectStatus: string
  visibility: string
  currentWorkflowId: string | null
}

export interface WorkflowDto {
  workflowId: string
  projectId: string
  workflowName: string
  workflowDesc: string | null
  workflowType: string
  workflowStatus: string
}

export interface ItemDto {
  itemId: string
  projectId: string
  itemCode: string
  itemName: string
  itemType: string
  resourceCategory: string | null
  resourceType: string | null
  unitId: string | null
  itemStatus: string
  /** "Y" when stock of this item is tracked per LOT. */
  lotManageYn: string | null
  /** "Y" when a new LOT waits for its receipt checks before its stock can be used (docs/domain/lot-release.md). */
  lotReleaseRequiredYn?: string | null
  /** Stock to keep across all records; below it the item is on the reorder list. */
  safetyStockQty?: number | null
  leadTimeDays?: number | null
  /** Cost of one unit in the item's own unit; 0 or null means not known. */
  unitCost?: number | null
  /** Descriptive fields; the server always sends them (docs/domain/item-details.md). */
  details?: ItemDetailsDto
  /** What the item is bought in, e.g. "bag"; null when bought in its stock unit. */
  purchaseUnit?: string | null
  /** Stock units in one purchase unit; null without a purchase unit. */
  purchaseUnitQty?: number | null
}

/** What an item is, beyond its code and name; null where not recorded. */
export interface ItemDetailsDto {
  /** Free grouping, e.g. "flour" or "packaging". */
  itemGroup: string | null
  spec: string | null
  /** Unique among the project's active items. */
  barcode: string | null
  sku: string | null
  storageCondition: string | null
  description: string | null
}

/** Stock lost in the last days, by why (GET /stock-waste). Quantities in each item's unit; values at today's unit costs. */
export interface StockWasteDto {
  days: number
  from: string
  to: string
  value: number
  /** False when a lost item has no unit cost; the values leave it out. */
  valueComplete: boolean
  expiredValue: number
  defectValue: number
  countLossValue: number
  lines: {
    itemId: string
    itemCode: string | null
    itemName: string | null
    unit: string | null
    expired: number
    defect: number
    countLoss: number
    total: number
    value: number | null
  }[]
}

/** What a write-off of expired LOT stock did, one line per LOT (POST /lots/expired/write-off). */
export interface ExpiredWriteOffDto {
  lots: number
  /** At today's unit costs; items without one are left out (valueComplete false). */
  value: number
  valueComplete: boolean
  lines: {
    lotId: string
    lotNo: string
    itemId: string
    itemCode: string | null
    unit: string | null
    writtenOff: number
    value: number | null
    closed: boolean
    /** Why some stock stayed (quarantined, reserved); null when all of it went. */
    note: string | null
  }[]
}

/** What a first-expiring-first issue took, one line per stock record (POST /inventories/issue-fefo). */
export interface FefoIssueDto {
  itemId: string
  action: 'issue' | 'reserve'
  quantity: number
  /** The item's unit; every quantity is in it. */
  unit: string | null
  lines: {
    inventoryTransactionId: string
    inventoryId: string
    lotId: string | null
    lotNo: string | null
    location: string | null
    /** Issued or reserved from the record. */
    quantity: number
    quantityAfter: number
    reservedAfter: number
  }[]
}

/** What a piece of equipment is and where it stands; null where not recorded (docs/domain/equipment.md). */
export interface EquipmentDetailsDto {
  manufacturer: string | null
  modelName: string | null
  serialNo: string | null
  /** Output per hour, in the unit the project counts this equipment's work in. */
  capacityPerHour: number | null
  /** Electricity per hour of running. */
  powerKwh: number | null
  /** Water per hour of running. */
  waterLiter: number | null
  location: string | null
  /** The share of capacityPerHour it really reaches, above 0 to 100; null for 100 (docs/domain/equipment.md E8). */
  efficiencyPercent?: number | null
  /** Several work orders may run on it at once (docs/domain/equipment.md E12); older answers leave it out. */
  concurrentWorkOrders?: boolean
  /** With concurrentWorkOrders, at most this many at once, 2 to 99; null for no limit (E16). */
  concurrentLimit?: number | null
}

export interface EquipmentDto {
  equipmentId: string
  projectId: string
  equipmentCode: string | null
  equipmentName: string
  equipmentType: string
  equipmentStatus: string
  details: EquipmentDetailsDto
}

/** An item below its safety stock over all its records (GET /stock-alerts/reorder). */
export interface ReorderLineDto {
  itemId: string
  itemCode: string
  itemName: string
  unit: string | null
  safetyStockQty: number
  /** Usable stock: not quarantined, not of a closed or expired LOT. */
  availableQuantity: number
  shortageQuantity: number
  leadTimeDays: number | null
  /** The place the line is for (docs/domain/stock-alert.md PS4); null on the item-level list. */
  location?: string | null
}

export interface ProcessDto {
  processId: string
  projectId: string
  workflowId: string
  processName: string
  processType: string
  nodeType: string
  processStatus: string
  colorScheme: string
  posX: number
  posY: number
  width: number
  height: number
  processDesc: string | null
  version: number
  versionNonce: number
}

export interface ProcessIoDto {
  processIoId: string
  processId: string
  itemId: string | null
  ioName: string | null
  direction: 'input' | 'output'
  ioType: string | null
  quantity: number | null
  unit: string | null
  formula: string | null
  colorScheme: string | null
  requiredYn: 'Y' | 'N'
  allowShortageYn: 'Y' | 'N'
  role: string | null
  resourceType: string
  schemaJson: Record<string, unknown> | null
  validationRule: string | null
}

export interface ProcessConnectionDto {
  connectionId: string
  projectId: string
  workflowId: string
  fromProcessId: string
  toProcessId: string
  fromIoId: string | null
  toIoId: string | null
  itemId?: string | null
  sourceHandle: string
  targetHandle: string
  connectionType: string
  connectionLabel: string | null
  flowRate: number | null
  unit: string | null
  delayTimeSec: number | null
  /** What the delay counts in production runs: all time or the work order's working time (ADR-022); older answers leave it out. */
  delayBasis?: 'elapsed' | 'working'
  lossRate: number | null
  priority: number | null
  conditionExpr: string | null
  capacity: number | null
  failurePolicy: 'stop' | 'skip' | 'retry'
  /** An Exclusive gateway's default connection (ADR-006); older answers leave it out. */
  defaultRoute?: boolean
  /** A Split gateway's share (ADR-008): share (a percent), fixed (a quantity) or rest; null for other connections. */
  splitKind?: 'share' | 'fixed' | 'rest' | null
  splitValue?: number | null
  version: number
  versionNonce: number
}

export interface CanvasAnnotationDto {
  annotationId: string
  workflowId: string
  projectId: string
  annotationType: 'shape' | 'freehand' | 'text'
  shapeKind: 'rectangle' | 'ellipse' | 'diamond' | null
  posX: number
  posY: number
  width: number | null
  height: number | null
  rotation: number | null
  points: number[][] | null
  textContent: string | null
  style: Record<string, unknown> | null
  zIndex: string
  groupId: string | null
  lockedYn: 'Y' | 'N'
  version: number
  versionNonce: number
}

export interface GraphEntityPayloadDto {
  workflow: WorkflowDto | null
  process: ProcessDto | null
  processIos: ProcessIoDto[]
  connection: ProcessConnectionDto | null
  annotation: CanvasAnnotationDto | null
}

export interface WorkflowGraphChangeDto {
  seq: number
  changeType: string
  workflowId: string
  entityId: string
  userId: string | null
  timestamp: number
  payload: GraphEntityPayloadDto | null
}

export interface WorkflowGraphChangesDto {
  currentSeq: number
  resetRequired: boolean
  changes: WorkflowGraphChangeDto[]
}

export interface WorkflowCanvasDto {
  workflow: WorkflowDto
  graphSeq: number
  processes: ProcessDto[]
  processIos: ProcessIoDto[]
  connections: ProcessConnectionDto[]
  annotations: CanvasAnnotationDto[]
  currentUserRole: 'viewer' | 'editor' | 'owner'
}

export interface FlowRuleDto {
  ruleId: string
  projectId: string
  targetType: string
  targetId: string
  ruleName: string
  ruleDesc: string | null
  conditionType: string
  conditionExpression: string
  actionType: string
  actionConfig: string
  priority: number
  enabledYn: string
}

export interface ProjectMemberDto {
  projectMemberId: string
  projectId: string
  userId: string
  projectRole: string
  memberStatus: string
  joinedAt: string | null
}

export interface ProjectInviteDto {
  inviteId: string
  projectId: string
  invitedEmail: string
  invitedUserId: string | null
  projectRole: string
  inviteStatus: string
  inviteToken: string
  acceptedAt: string | null
  expiredAt: string | null
}

export interface RoleDto {
  roleId: string
  roleName: string
  roleDescription: string | null
}

export interface UserRoleDto {
  userRolesId: string
  userId: string
  roleId: string
  roleName: string
  scopeType: string
  grantedAt: string | null
}

export interface ProcessTemplateDto {
  templateId: string
  templateName: string
  templateCategory: string
  templateType: string
  iconKey: string | null
  defaultColorScheme: string
  defaultWidth: number | null
  defaultHeight: number | null
  defaultDesc: string | null
  defaultConfig: string | null
  publicYn: string
  sortOrder: number | null
}

/** Stock moved between places in the last days (GET /stock-analysis/transfers, docs/domain/stock-analysis.md "위치 간 이동"). */
export interface StockTransferAnalysisDto {
  days: number
  from: string
  to: string
  /** Most moves first; a null place is stock kept without one. */
  routes: {
    fromLocation: string | null
    toLocation: string | null
    moves: number
    /** Quantities in the item's unit, the most moved item first. */
    items: { itemId: string; itemCode: string | null; itemName: string | null; unit: string | null; quantity: number; moves: number }[]
  }[]
}

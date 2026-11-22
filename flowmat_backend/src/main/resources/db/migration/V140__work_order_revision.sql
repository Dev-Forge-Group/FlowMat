-- The published revision a work order is planned on (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md
-- decision 7): a confirmed plan's work order keeps the revision the plan read, and its production runs start on it.
-- Null for work orders made before or by hand: those runs take the latest published revision as before. Added only.
ALTER TABLE work_order ADD COLUMN workflow_revision_id varchar(50);

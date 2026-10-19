-- V99: A connection's delay can count only the work order's working time in production runs
-- (docs/architecture/adr/ADR-022-working-time-delay.md, docs/domain/process-port-connection-contract.md GR-53-GR-58).
-- Every existing connection keeps counting all time.
ALTER TABLE process_connection ADD COLUMN IF NOT EXISTS delay_basis varchar(10) NOT NULL DEFAULT 'elapsed';
ALTER TABLE process_connection ADD CONSTRAINT ck_process_connection_delay_basis CHECK (delay_basis IN ('elapsed', 'working'));

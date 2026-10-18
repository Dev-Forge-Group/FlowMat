-- V91: The process a work instruction step belongs to (docs/architecture/adr/ADR-011-production-steps.md decision 6). A step
-- with a process shows with that process's step in a run; one without belongs to the whole run, as before.
ALTER TABLE work_instruction_step
    ADD COLUMN process_id varchar(50);

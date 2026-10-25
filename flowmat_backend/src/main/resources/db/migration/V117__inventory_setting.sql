-- V117: A project's inventory settings (docs/domain/storage-location.md SL5-SL7): whether stock coming into a listed place
-- whose storage condition differs from the item's is refused. Without a row, or with 'warn', it is only pointed out (SL3).
CREATE TABLE inventory_setting (
    project_id varchar(50) PRIMARY KEY,
    storage_condition_policy varchar(10) NOT NULL DEFAULT 'warn' CHECK (storage_condition_policy IN ('warn', 'refuse')),
    version bigint NOT NULL DEFAULT 0,
    updated_by varchar(50) NOT NULL,
    updated_at timestamptz NOT NULL
);

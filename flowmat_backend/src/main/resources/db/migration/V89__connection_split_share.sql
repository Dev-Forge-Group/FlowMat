-- V89: A Split gateway's share on each of its connections (docs/architecture/adr/ADR-008-quantity-split.md decisions 2, 10):
-- share (a percent of the quantity that arrives), fixed (a quantity) or rest (what is left). Other connections have none.
ALTER TABLE process_connection
    ADD COLUMN split_kind varchar(10),
    ADD COLUMN split_value numeric(19,4),
    -- CASE, not OR: a CHECK that comes out NULL passes, so a value without a kind must come out false.
    ADD CONSTRAINT ck_process_connection_split CHECK (
        CASE split_kind
            WHEN 'share' THEN split_value IS NOT NULL AND split_value > 0 AND split_value <= 100
            WHEN 'fixed' THEN split_value IS NOT NULL AND split_value > 0
            WHEN 'rest' THEN split_value IS NULL
            ELSE split_kind IS NULL AND split_value IS NULL
        END);

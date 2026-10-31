-- Keep active item identities unique after competing service checks finish.
-- Block writes before the preflight so duplicate values never appear in an index error.
LOCK TABLE item IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM item
        WHERE deleted_yn = 'N'
        GROUP BY project_id, item_code
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Active item codes are duplicated. Resolve duplicates before applying V125.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM item
        WHERE deleted_yn = 'N' AND barcode IS NOT NULL
        GROUP BY project_id, barcode
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'Active item barcodes are duplicated. Resolve duplicates before applying V125.';
    END IF;
END
$$;

CREATE UNIQUE INDEX uq_item_project_code_active
    ON item (project_id, item_code)
    WHERE deleted_yn = 'N';

CREATE UNIQUE INDEX uq_item_project_barcode_active
    ON item (project_id, barcode)
    WHERE deleted_yn = 'N' AND barcode IS NOT NULL;

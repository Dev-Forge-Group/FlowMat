-- V109: The suppliers a project buys from (docs/architecture/adr/ADR-027-purchasing.md decision 10, docs/domain/purchasing.md
-- PO18-PO21). Once a project lists any, its purchase orders name an active one. Suppliers are switched off, never deleted, and
-- purchase orders keep the supplier name they were given.
CREATE TABLE supplier (
    supplier_id varchar(50) PRIMARY KEY,
    project_id varchar(50) NOT NULL,
    supplier_name varchar(100) NOT NULL,
    contact varchar(200),
    lead_time_days integer CHECK (lead_time_days IS NULL OR lead_time_days BETWEEN 0 AND 365),
    note varchar(500),
    active_yn char(1) NOT NULL DEFAULT 'Y' CHECK (active_yn IN ('Y', 'N')),
    version bigint NOT NULL DEFAULT 0,
    created_by varchar(50) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_by varchar(50),
    updated_at timestamptz
);
CREATE UNIQUE INDEX uq_supplier_name ON supplier(project_id, lower(supplier_name));

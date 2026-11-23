-- A factory's resource limits (docs/architecture/adr/ADR-046-resources-first-class.md decision 5): how much energy or water
-- the project can draw per hour or per day, in the unit its plans count it in. A plan whose average draw is above a limit
-- says so. Added only.
CREATE TABLE resource_capacity (
    project_id varchar(50) NOT NULL REFERENCES project(project_id),
    resource_type varchar(20) NOT NULL CHECK (resource_type IN ('energy', 'water')),
    unit varchar(30) NOT NULL,
    amount numeric(18, 4) NOT NULL CHECK (amount > 0),
    per varchar(10) NOT NULL CHECK (per IN ('hour', 'day')),
    updated_by varchar(50),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_resource_capacity PRIMARY KEY (project_id, resource_type, unit)
);

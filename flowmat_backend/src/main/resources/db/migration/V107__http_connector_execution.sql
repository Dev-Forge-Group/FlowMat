CREATE TABLE connector_profile (
    profile_id varchar(50) PRIMARY KEY,
    project_id varchar(50) NOT NULL REFERENCES project(project_id),
    name varchar(120) NOT NULL,
    enabled boolean NOT NULL,
    deleted_yn char(1) NOT NULL DEFAULT 'N' CHECK (deleted_yn IN ('N', 'Y')),
    current_revision integer NOT NULL CHECK (current_revision >= 1),
    version bigint NOT NULL CHECK (version >= 0),
    created_by varchar(50) NOT NULL,
    updated_by varchar(50) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX idx_connector_profile_project ON connector_profile(project_id, deleted_yn, created_at);

CREATE TABLE connector_profile_version (
    profile_version_id varchar(50) PRIMARY KEY,
    profile_id varchar(50) NOT NULL REFERENCES connector_profile(profile_id),
    revision integer NOT NULL CHECK (revision >= 1),
    base_origin varchar(512) NOT NULL,
    credential_alias varchar(100),
    operations_json jsonb NOT NULL CHECK (jsonb_typeof(operations_json) = 'array'),
    created_by varchar(50) NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (profile_id, revision)
);

CREATE TABLE process_execution_binding (
    process_id varchar(50) PRIMARY KEY REFERENCES process(process_id),
    profile_version_id varchar(50) REFERENCES connector_profile_version(profile_version_id),
    operation_id varchar(100),
    output_port_id varchar(50) REFERENCES process_io(process_io_id),
    request_mapping jsonb,
    response_mapping jsonb,
    binding_version bigint NOT NULL DEFAULT 0 CHECK (binding_version >= 0),
    CONSTRAINT ck_process_execution_binding_state CHECK (
        (profile_version_id IS NULL AND operation_id IS NULL AND output_port_id IS NULL
            AND request_mapping IS NULL AND response_mapping IS NULL)
        OR (profile_version_id IS NOT NULL AND operation_id IS NOT NULL AND output_port_id IS NOT NULL
            AND request_mapping IS NOT NULL AND response_mapping IS NOT NULL
            AND jsonb_typeof(request_mapping) = 'object' AND jsonb_typeof(response_mapping) = 'object')
    )
);
CREATE INDEX idx_process_execution_binding_profile ON process_execution_binding(profile_version_id);

CREATE TABLE flow_run_http_execution (
    execution_id varchar(50) PRIMARY KEY,
    flow_run_id varchar(50) NOT NULL REFERENCES flow_run(flow_run_id),
    step_id varchar(50) NOT NULL REFERENCES flow_run_step(step_id),
    attempt_id varchar(50) NOT NULL UNIQUE REFERENCES flow_run_step_attempt(attempt_id),
    request_id varchar(36) NOT NULL,
    claim_token varchar(50) NOT NULL,
    profile_version_id varchar(50) NOT NULL REFERENCES connector_profile_version(profile_version_id),
    operation_id varchar(100) NOT NULL,
    request_hash varchar(64) NOT NULL,
    transport_status varchar(30) NOT NULL CHECK (transport_status IN
        ('PENDING', 'SUCCEEDED', 'HTTP_REJECTED', 'RESPONSE_REJECTED', 'TRANSPORT_FAILED', 'OUTCOME_UNKNOWN')),
    apply_status varchar(30) NOT NULL CHECK (apply_status IN ('PENDING', 'APPLIED', 'APPLY_FAILED', 'LATE')),
    http_status integer CHECK (http_status BETWEEN 100 AND 599),
    error_code varchar(80),
    output_json jsonb,
    requested_by varchar(50) NOT NULL,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    UNIQUE (step_id, request_id)
);
CREATE INDEX idx_flow_run_http_execution_step ON flow_run_http_execution(step_id, started_at DESC);

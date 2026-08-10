CREATE TABLE yumpoo.m011_probe (
    id uuid PRIMARY KEY,
    actor_user_id uuid NOT NULL,
    name varchar(120) NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL
);

CREATE TABLE yumpoo.m011_projection (
    consumer_name varchar(120) NOT NULL,
    event_id uuid NOT NULL,
    probe_id uuid NOT NULL,
    request_id varchar(64) NOT NULL,
    correlation_id varchar(64) NOT NULL,
    consumer_causation_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
);

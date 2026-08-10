CREATE TABLE yumpoo.m010_probe (
    id uuid PRIMARY KEY,
    actor_user_id uuid NOT NULL,
    name varchar(120) NOT NULL,
    status varchar(20) NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL
);


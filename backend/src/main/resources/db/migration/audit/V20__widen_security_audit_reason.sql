ALTER TABLE yumpoo.security_audit_event
    DROP CONSTRAINT ck_security_audit_event_reason;

ALTER TABLE yumpoo.security_audit_event
    ALTER COLUMN reason_reference TYPE varchar(500),
    ADD CONSTRAINT ck_security_audit_event_reason CHECK (
        reason_reference IS NULL
        OR (char_length(reason_reference) BETWEEN 1 AND 500
            AND reason_reference = btrim(reason_reference))
    );

COMMENT ON COLUMN yumpoo.security_audit_event.reason_reference IS
    'Trimmed governance reason or stable reason reference; sensitive business content is forbidden.';

ALTER TABLE yumpoo.idempotency_record
    ADD COLUMN response_text text;

UPDATE yumpoo.idempotency_record
   SET response_text = response_json::text
 WHERE state = 'COMPLETED';

CREATE OR REPLACE FUNCTION yumpoo.default_idempotent_response_text()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.state = 'COMPLETED'
        AND NEW.response_text IS NULL
        AND NEW.response_json IS NOT NULL THEN
        NEW.response_text := NEW.response_json::text;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_idempotent_response_text
    BEFORE INSERT OR UPDATE ON yumpoo.idempotency_record
    FOR EACH ROW EXECUTE FUNCTION yumpoo.default_idempotent_response_text();

ALTER TABLE yumpoo.idempotency_record
    DROP CONSTRAINT ck_idempotency_record_lifecycle;

ALTER TABLE yumpoo.idempotency_record
    ADD CONSTRAINT ck_idempotency_record_lifecycle CHECK (
        (
            state = 'PROCESSING'
            AND lease_until IS NOT NULL
            AND http_status IS NULL
            AND response_json IS NULL
            AND response_text IS NULL
            AND resource_id IS NULL
            AND response_etag IS NULL
            AND completed_at IS NULL
        )
        OR (
            state = 'COMPLETED'
            AND lease_until IS NULL
            AND http_status IS NOT NULL
            AND response_json IS NOT NULL
            AND response_text IS NOT NULL
            AND completed_at IS NOT NULL
        )
    );

COMMENT ON COLUMN yumpoo.idempotency_record.response_text IS
    'Original serialized HTTP response preserved byte-for-byte for stable replay';

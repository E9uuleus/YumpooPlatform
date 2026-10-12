CREATE INDEX idx_activity_event_company_cursor
    ON yumpoo.activity_event (company_id, occurred_at DESC, id DESC);

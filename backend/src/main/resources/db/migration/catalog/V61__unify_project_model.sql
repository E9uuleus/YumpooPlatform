ALTER TABLE yumpoo.project
    DROP CONSTRAINT ck_project_lifecycle,
    DROP CONSTRAINT ck_project_lifecycle_times,
    DROP CONSTRAINT ck_project_timestamps,
    DROP CONSTRAINT fk_project_template_reference,
    DROP CONSTRAINT uq_project_template_scope;

UPDATE yumpoo.project SET lifecycle = 'ACTIVE' WHERE lifecycle = 'DRAFT';

-- Finish deferred owner-membership checks before changing the table shape.
SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE yumpoo.project
    DROP COLUMN project_type,
    DROP COLUMN template_key,
    DROP COLUMN template_version,
    DROP COLUMN customer_name,
    DROP COLUMN customer_reference,
    DROP COLUMN delivery_site,
    DROP COLUMN contact_note,
    DROP COLUMN activated_at,
    ADD CONSTRAINT ck_project_lifecycle CHECK (lifecycle IN ('ACTIVE', 'ARCHIVED')),
    ADD CONSTRAINT ck_project_lifecycle_times CHECK ((lifecycle = 'ARCHIVED') = (archived_at IS NOT NULL)),
    ADD CONSTRAINT ck_project_timestamps CHECK (
        updated_at >= created_at
        AND (archived_at IS NULL OR archived_at BETWEEN created_at AND updated_at)
    );

DELETE FROM yumpoo.outbox_consumer_receipt receipt
USING yumpoo.outbox_event event
WHERE receipt.event_id = event.event_id AND (
    (event.event_type = 'catalog.project_created' AND event.event_version = 1)
    OR event.event_type IN ('catalog.project_template_applied', 'catalog.project_activated')
    OR event.event_type LIKE 'templateworkflow.%'
);

DELETE FROM yumpoo.outbox_event WHERE
    (event_type = 'catalog.project_created' AND event_version = 1)
    OR event_type IN ('catalog.project_template_applied', 'catalog.project_activated')
    OR event_type LIKE 'templateworkflow.%';

DROP TABLE yumpoo.project_template_content_blueprint;
DROP TABLE yumpoo.workflow_transition_definition;
DROP TABLE yumpoo.workflow_status_definition;
DROP TABLE yumpoo.project_template_definition;
DROP FUNCTION yumpoo.guard_project_template_structure_mutation();
DROP FUNCTION yumpoo.guard_project_template_definition_mutation();

COMMENT ON TABLE yumpoo.project IS 'Company-scoped Project aggregate owned by catalog; stable server-generated codes for new projects.';

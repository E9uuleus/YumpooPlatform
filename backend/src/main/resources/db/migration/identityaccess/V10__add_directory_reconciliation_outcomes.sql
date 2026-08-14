ALTER TABLE yumpoo.directory_sync_run
    DROP CONSTRAINT ck_directory_sync_run_counts;

ALTER TABLE yumpoo.directory_sync_run
    ADD CONSTRAINT ck_directory_sync_run_counts CHECK (
        page_count >= 0
        AND discovered_count >= 0
        AND staged_count >= 0
        AND created_count >= 0
        AND updated_count >= 0
        AND unchanged_count >= 0
        AND left_count >= 0
        AND returned_count >= 0
        AND failed_count >= 0
        AND not_applied_count >= 0
        AND created_count + updated_count + unchanged_count + returned_count
            + failed_count + not_applied_count <= discovered_count
    ),
    ADD CONSTRAINT ck_directory_sync_run_terminal_outcome CHECK (
        status NOT IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED')
        OR (
            scan_complete
            AND not_applied_count = 0
            AND (
                (
                    status = 'SUCCEEDED'
                    AND failed_count = 0
                    AND error_code IS NULL
                    AND created_count + updated_count + unchanged_count + returned_count
                        = discovered_count
                )
                OR (
                    status = 'PARTIALLY_SUCCEEDED'
                    AND failed_count > 0
                    AND error_code IS NOT NULL
                    AND created_count + updated_count + unchanged_count + returned_count
                        + failed_count = discovered_count
                )
            )
        )
    );

ALTER TABLE yumpoo.directory_sync_item
    DROP CONSTRAINT ck_directory_sync_item_action,
    DROP CONSTRAINT ck_directory_sync_item_result,
    DROP CONSTRAINT ck_directory_sync_item_outcome;

ALTER TABLE yumpoo.directory_sync_item
    ADD CONSTRAINT ck_directory_sync_item_action CHECK (
        action IN ('PROVISION', 'MARK_LEFT')
    ),
    ADD CONSTRAINT ck_directory_sync_item_result CHECK (
        result IN (
            'PENDING',
            'CREATED',
            'UPDATED',
            'UNCHANGED',
            'RETURNED',
            'LEFT',
            'FAILED',
            'NOT_APPLIED'
        )
    ),
    ADD CONSTRAINT ck_directory_sync_item_outcome CHECK (
        (
            action = 'PROVISION'
            AND result = 'PENDING'
            AND user_id IS NULL
            AND error_code IS NULL
        )
        OR (
            action = 'PROVISION'
            AND result IN ('CREATED', 'UPDATED', 'UNCHANGED', 'RETURNED')
            AND user_id IS NOT NULL
            AND error_code IS NULL
        )
        OR (
            action = 'PROVISION'
            AND result IN ('FAILED', 'NOT_APPLIED')
            AND user_id IS NULL
            AND error_code IS NOT NULL
        )
        OR (
            action = 'MARK_LEFT'
            AND result = 'LEFT'
            AND user_id IS NOT NULL
            AND error_code IS NULL
        )
    );

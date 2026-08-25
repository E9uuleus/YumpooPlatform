ALTER TABLE yumpoo.work_item_update
    DROP CONSTRAINT ck_work_item_update_edit_facts,
    DROP CONSTRAINT ck_work_item_update_delete_facts;

ALTER TABLE yumpoo.work_item_update
    ADD CONSTRAINT ck_work_item_update_edit_facts CHECK (
        (status = 'PUBLISHED' AND edited_at IS NULL AND edited_by_user_id IS NULL)
        OR (status = 'EDITED' AND edited_at IS NOT NULL
            AND edited_by_user_id = author_user_id
            AND edited_at >= created_at AND edited_at < edit_deadline_at)
        OR (status = 'DELETED' AND (
            (edited_at IS NULL AND edited_by_user_id IS NULL)
            OR (edited_at IS NOT NULL AND edited_by_user_id = author_user_id
                AND edited_at >= created_at AND edited_at < edit_deadline_at)
        ))
    ),
    ADD CONSTRAINT ck_work_item_update_delete_facts CHECK (
        (status <> 'DELETED' AND deleted_at IS NULL
            AND deleted_by_user_id IS NULL AND delete_reason IS NULL)
        OR (status = 'DELETED' AND deleted_at IS NOT NULL
            AND deleted_by_user_id IS NOT NULL AND deleted_at >= created_at
            AND (
                (deleted_by_user_id = author_user_id
                    AND deleted_at < edit_deadline_at AND delete_reason IS NULL)
                OR (delete_reason IS NOT NULL
                    AND delete_reason = btrim(delete_reason)
                    AND char_length(delete_reason) BETWEEN 1 AND 500)
            ))
    );

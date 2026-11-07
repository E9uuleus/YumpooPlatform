-- 子项表固定列数量：可空，null 视为 0；旧版本忽略该列。
ALTER TABLE yumpoo.work_item_table_settings
    ADD COLUMN subitem_pinned_column_count integer;

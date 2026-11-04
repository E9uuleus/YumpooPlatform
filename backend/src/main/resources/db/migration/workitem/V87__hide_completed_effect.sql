-- 隐藏已完成的表格显示效果：FADE 置灰、FADE_STRIKETHROUGH 置灰加删除线（原有效果）、HIDE 主表不显示。旧版本忽略该列。
ALTER TABLE yumpoo.work_item_timer_state
    ADD COLUMN hide_completed_effect text NOT NULL DEFAULT 'FADE_STRIKETHROUGH';

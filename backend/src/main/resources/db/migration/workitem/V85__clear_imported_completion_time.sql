-- 导入直接设为完成的工作项曾以导入时刻作为完成时间（completed_at 与 created_at 相同）；
-- 正常创建总从「未开始」开始，不会产生这种组合。改为「完成时间未记录」。
UPDATE yumpoo.work_item
   SET completed_at = NULL
 WHERE status_category = 'DONE'
   AND completed_at = created_at;

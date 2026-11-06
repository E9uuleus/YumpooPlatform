-- 已删除工作项上的会话不再参与并行均分：删除其分摊行，并按剩余行重算同一区段的并行数。
-- 同一用户的分摊区段由重建按全部切点对齐，同一区段的行起止相同，剩余行数即新的并行数。
DELETE FROM yumpoo.work_item_time_share sh
 USING yumpoo.work_item w
 WHERE w.company_id = sh.company_id
   AND w.id = sh.work_item_id
   AND w.deleted_at IS NOT NULL;

UPDATE yumpoo.work_item_time_share sh
   SET concurrency = c.n
  FROM (SELECT company_id, user_id, started_at, stopped_at, count(*)::integer AS n
          FROM yumpoo.work_item_time_share
         GROUP BY company_id, user_id, started_at, stopped_at) c
 WHERE c.company_id = sh.company_id
   AND c.user_id = sh.user_id
   AND c.started_at = sh.started_at
   AND c.stopped_at IS NOT DISTINCT FROM sh.stopped_at
   AND sh.concurrency <> c.n;

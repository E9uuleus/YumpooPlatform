-- 迁入工时写到工作项上的记录：只读，重新迁入同一成员同一天时据此覆盖。旧版本忽略该列，把这些记录当作普通补录。
ALTER TABLE yumpoo.work_item_time_session ADD COLUMN migrated boolean NOT NULL DEFAULT false;

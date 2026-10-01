export const ruleNames: Record<string, string> = {
  HOST_CPU_HIGH: '系统 CPU 过高',
  JVM_HEAP_HIGH: 'JVM 堆使用率过高',
  DISK_LOW: '磁盘剩余不足',
  DB_UNAVAILABLE: '数据库不可达',
  DB_SLOW: '数据库响应缓慢',
  DB_POOL_SATURATED: '连接池等待过多',
  HTTP_ERROR_RATE: '接口错误率过高',
  HTTP_LATENCY_P95: '业务请求 P95 延迟过高',
  OUTBOX_BACKLOG_AGE: '事件积压超时',
  OUTBOX_DEAD: '事件投递失败',
  ERROR_LOG_BURST: '错误日志突增',
  CONFIG_POSTURE: '生产配置异常',
}
export const RULE_DESCRIPTIONS: Record<string, string> = {
  HOST_CPU_HIGH: '系统整体 CPU 使用率',
  JVM_HEAP_HIGH: 'JVM 堆已用 / 堆上限',
  DISK_LOW: '按存储卷评估剩余空间比例',
  DB_UNAVAILABLE: '数据库探测连续失败次数，恢复后补记',
  DB_SLOW: '数据库探测往返耗时',
  DB_POOL_SATURATED: '等待数据库连接的线程数',
  HTTP_ERROR_RATE: '5 分钟窗口的业务请求 5xx 比例',
  HTTP_LATENCY_P95: '业务请求全局 P95 耗时',
  OUTBOX_BACKLOG_AGE: '最老待处理事件的等待时间',
  OUTBOX_DEAD: '最终投递失败的事件数',
  ERROR_LOG_BURST: '5 分钟内 ERROR 日志条数',
  CONFIG_POSTURE: '存在严重级别的生产配置问题',
}
export const metricNames: Record<string, string> = {
  'cpu.system': '系统 CPU',
  'cpu.process': '进程 CPU',
  'memory.used': '物理内存',
  'heap.used': 'JVM 堆',
  'heap.max': '堆上限',
  'http.requests': '请求数',
  'http.errors': '5xx',
  'http.p95': '业务请求 P95',
  'http.errorRate': '5xx 比例',
  'db.pool.active': '活跃连接',
  'db.pool.pending': '等待连接',
  'db.pool.max': '连接上限',
  'db.ping': '数据库 Ping',
  'outbox.backlog': '待投递',
  'outbox.dead': '投递失败',
  'outbox.oldestAge': '最老事件等待',
  'threads.live': '线程',
  'gc.pause': 'GC 暂停',
  'sessions.online': '在线成员',
  'logs.error': '错误日志',
}
export const componentNames: Record<string, string> = {
  application: '应用服务',
  database: '数据库',
  disk: '磁盘容量',
  deploymentDirectories: '部署目录',
  outbox: '事件队列',
}
export const postureNames: Record<string, string> = {
  LOCAL_AUTH_ENABLED: '本地登录入口',
  CONTROLLED_AUTH_ENABLED: '受控验收登录',
  M113_FIXTURE_ENABLED: '验收数据开关',
  WECOM_PROBE_ENABLED: '企微验收探针',
  SERVER_NOT_LOOPBACK: '服务监听地址',
  DEFAULT_CHARSET_NOT_UTF8: '默认字符集',
  LOG_FILE_NOT_CONFIGURED: '文件日志',
  DEFENDER_NOT_CONFIGURED: '附件扫描程序',
  ATTACHMENT_CLEANUP_DELETE_ENABLED: '附件清理删除',
  SESSION_PREVIOUS_KEY_EXPIRED: '旧会话密钥期限',
}
export const eventNames: Record<string, string> = {
  FIRED: '触发',
  ACKNOWLEDGED: '已确认',
  ESCALATED: '升级为严重',
  DEESCALATED: '降为警告',
  RESOLVED: '恢复',
}
export const MODULE_LABELS: Record<string, string> = {
  foundation: '基础服务',
  identityaccess: '身份与权限',
  administration: '公司管理',
  project: '项目',
  workitem: '工作项',
  filestorage: '附件存储',
  audit: '审计',
  notification: '通知',
  operations: '运维',
  reporting: '报表',
  catalog: '项目与目录',
  organization: '组织架构',
  templateworkflow: '模板与流程',
  worklog: '工时',
  spring: 'Spring 框架',
  hikari: '数据库连接池',
  flyway: '数据库迁移',
  postgres: 'PostgreSQL',
  tomcat: 'HTTP 服务',
  other: '其他',
}
export const EVENT_LABELS: Record<string, string> = {
  'http.request.completed': '请求完成',
  'http.request.failed': '请求失败',
  'http.request.rejected': '请求被拒绝',
  'http.request.slow': '请求响应缓慢',
  'http.request.unhandled': '请求发生异常',
  'app.started': '应用已启动',
  'app.stopping': '应用正在停止',
  'outbox.dispatch.failed': '事件派发失败',
  'outbox.cycle.failed': '事件派发失败',
  'outbox.delivery.failed': '事件投递失败',
  'outbox.delivery.succeeded': '事件投递成功',
  'operations.sample.failed': '运维采样失败',
  'operations.persistence.failed': '运维数据写入失败',
  'outbox.consumer.completed': '事件消费完成',
  'outbox.event.completed': '事件投递完成',
  'outbox.event.stale_lease': '事件租约已失效',
  'outbox.event.dead': '事件投递终止',
  'outbox.event.retry_scheduled': '事件等待重试',
  'api.request.failed': '接口处理失败',
  'session.cleanup.completed': '过期会话清理完成',
  'oauth.cleanup.completed': '过期登录请求清理完成',
  'identity.bootstrap.completed': '身份初始化完成',
  'identity.bootstrap.failed': '身份初始化失败',
  'identity.bootstrap.audit.failed': '身份初始化审计失败',
  'identity.maintenance.completed': '管理员维护完成',
  'identity.outbox.requeued': '身份事件重新入队',
  'identity.audit.failed': '身份安全审计失败',
  'identity.login.audit.failed': '登录审计失败',
  'identity.local.ready': '本地认证已就绪',
  'identity.local.initialized': '本地认证已初始化',
  'identity.fixture.initialized': '验收数据已初始化',
  'attachment.maintenance.completed': '附件维护完成',
  'notification.source.ignored': '通知源事件已忽略',
}
export function moduleLabel(code: string): string {
  return MODULE_LABELS[code] ?? code
}
export function volumePurpose(value: string): string {
  return (
    (
      {
        APPLICATION: '程序',
        ATTACHMENTS: '附件存储',
        UPLOAD_TEMP: '上传临时',
        LOGS: '日志',
      } as Record<string, string>
    )[value] ?? value
  )
}
export function time(value?: Date | null): string {
  return value ? value.toLocaleString('zh-CN', { hour12: false }) : '—'
}
export function formatOperationsTimeRange(from: Date, to: Date, utc = false): string {
  if (!Number.isFinite(from.getTime()) || !Number.isFinite(to.getTime())) return '—'
  const timeZone = utc ? 'UTC' : undefined
  const date = new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', timeZone,
  })
  const clock = new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit', minute: '2-digit',
    ...(to.getTime() - from.getTime() < 60000 ? { second: '2-digit' } : {}),
    hourCycle: 'h23', timeZone,
  })
  const sameDay = date.format(from) === date.format(to)
  const label = (at: Date) => (sameDay ? '' : date.format(at) + ' ') + clock.format(at)
  return label(from) + '–' + label(to)
}
export function bytes(value?: number | null): string {
  if (value == null || value < 0) return '—'
  const unit = value >= 1073741824 ? 'GB' : value >= 1048576 ? 'MB' : 'KB'
  return (
    (value / (unit === 'GB' ? 1073741824 : unit === 'MB' ? 1048576 : 1024)).toFixed(1) + ' ' + unit
  )
}
export function metric(value?: number | null, unit = ''): string {
  if (value == null || !Number.isFinite(value)) return '—'
  if (unit === 'RATIO' || unit === 'ratio') return (value * 100).toFixed(1) + '%'
  if (unit === 'bytes' || unit === 'BYTES') return bytes(value)
  if ((unit === 'SECONDS' || unit === 'seconds') && value >= 120) return duration(value * 1000)
  return (
    Number(value.toFixed(1)).toLocaleString('zh-CN') +
    ({ MS: ' ms', ms: ' ms', SECONDS: ' 秒', seconds: ' 秒', PER_MINUTE: ' 次/分钟' }[unit] ?? '')
  )
}
export function duration(value: number): string {
  const minutes = Math.floor(Math.max(0, value) / 60000)
  return minutes >= 1440
    ? Math.floor(minutes / 1440) + ' 天 ' + Math.floor((minutes % 1440) / 60) + ' 小时'
    : minutes >= 60
      ? Math.floor(minutes / 60) + ' 小时 ' + (minutes % 60) + ' 分钟'
      : minutes + ' 分钟'
}

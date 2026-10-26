import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import YAML from 'yaml'
import { expr, thresholdsFor, WINDOWS_VOLUME } from './expressions.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const directory = path.join(root, 'deployment/monitoring')
const compose = YAML.parse(fs.readFileSync(path.join(directory, 'compose.yml'), 'utf8'), { merge: true })
const datasource = { type: 'prometheus', uid: 'prometheus' }
const severityColors = { warning: 'orange', critical: 'red' }
const severityNames = { warning: '警告', critical: '严重' }
const CHECK_NAMES = { home: '首页', health: '健康检查', version: '版本信息', auth: '认证入口', assets: '静态资源', download: '下载页', manifest: '更新清单', installer: '安装包' }
const PHASES = { resolve: 'DNS 解析', connect: 'TCP 连接', tls: 'TLS 握手', processing: '服务端处理', transfer: '内容传输' }
const valueMapping = values => [{ type: 'value', options: Object.fromEntries(Object.entries(values).map(([key, [text, color]]) => [key, { text, color }])) }]
const SERVICE_STATUS = valueMapping({ 0: ['运行正常', 'green'], 1: ['维护中', 'blue'], 2: ['状态未知', 'yellow'], 3: ['访问异常', 'red'] })
const UP = valueMapping({ 0: ['失败', 'red'], 1: ['正常', 'green'] })
const RUNNING = valueMapping({ 0: ['未运行', 'red'], 1: ['运行中', 'green'] })
const thresholds = (base, ...steps) => ({ mode: 'absolute', steps: [{ color: base, value: null }, ...steps.map(([value, color]) => ({ value, color }))] })
const override = (name, properties) => ({ matcher: { id: 'byName', options: name }, properties: Object.entries(properties).map(([id, value]) => ({ id, value })) })
const displayNames = names => Object.entries(names).map(([name, displayName]) => override(name, { displayName }))
const fixedColor = color => ({ mode: 'fixed', fixedColor: color })
const rightAxis = (unit = 'short') => ({ unit, 'custom.axisPlacement': 'right', 'custom.axisLabel': '', 'custom.stacking': { mode: 'none', group: 'A' } })
const hostThresholds = (key, used = false) => thresholds('green', ...thresholdsFor(key)
  .map(alert => [Number((used ? 1 - Number(alert.value) : Number(alert.value)).toFixed(6)), severityColors[alert.severity]])
  .sort(([a], [b]) => a - b))
const hostDescription = (key, metric) => thresholdsFor(key).map(alert => `${alert.alert}：${metric} ${alert.op} ${Number(alert.value) * 100}%（${severityNames[alert.severity]}）`).join('；')

function q(...queries) {
  return queries.map((query, index) => {
    const [expression, legendFormat = '', options = {}] = Array.isArray(query) ? query : [query]
    return { datasource, refId: String.fromCharCode(65 + index), expr: expression, legendFormat, editorMode: 'code', format: 'time_series', range: !options.instant, instant: false, ...options }
  })
}

function panel(type, title, size, targets, description, { defaults = {}, overrides = [], options = {}, transformations = [] } = {}) {
  return { type, title, description, datasource, gridPos: { w: size[0], h: size[1] }, targets,
    fieldConfig: { defaults: { unit: 'short', noValue: '无数据', ...defaults }, overrides }, options, transformations }
}

function stat(title, size, targets, description, config = {}) {
  return panel('stat', title, size, targets, description, { ...config,
    defaults: { color: { mode: 'thresholds' }, thresholds: thresholds('green'), ...config.defaults },
    options: { reduceOptions: { calcs: ['lastNotNull'], fields: '', values: false }, colorMode: 'background', graphMode: 'none', textMode: 'auto', wideLayout: true, ...config.options } })
}

function timeseries(title, size, targets, description, config = {}) {
  const hasThresholds = config.defaults?.thresholds || config.overrides?.some(item => item.properties.some(property => property.id === 'thresholds'))
  return panel('timeseries', title, size, targets, description, { ...config,
    defaults: { min: 0, color: { mode: 'palette-classic' }, thresholds: thresholds('green'), ...config.defaults, custom: {
      spanNulls: false, lineWidth: 2, fillOpacity: 10, showPoints: 'never', drawStyle: 'line',
      thresholdsStyle: { mode: hasThresholds ? 'dashed' : 'off' }, ...config.custom,
    } },
    options: { legend: { displayMode: 'list', placement: 'bottom', calcs: [] }, tooltip: { mode: 'multi', sort: 'desc' }, ...config.options } })
}

function stateTimeline(title, size, targets, description, config = {}) {
  return panel('state-timeline', title, size, targets, description, { ...config,
    defaults: { color: { mode: 'palette-classic' }, custom: { lineWidth: 0, fillOpacity: 80, spanNulls: false }, ...config.defaults },
    options: { mergeValues: true, showValue: 'auto', alignValue: 'center', rowHeight: 0.8, legend: { displayMode: 'list', placement: 'bottom' }, tooltip: { mode: 'single', sort: 'none' }, ...config.options } })
}

function barGauge(title, size, targets, description, config = {}) {
  return panel('bargauge', title, size, targets.map(target => ({ ...target, instant: true, range: false })), description, { ...config,
    defaults: { min: 0, max: 1, color: { mode: 'thresholds' }, ...config.defaults },
    options: { orientation: 'horizontal', displayMode: 'gradient', showUnfilled: true, reduceOptions: { calcs: ['lastNotNull'], fields: '', values: false }, ...config.options } })
}

function table(title, size, targets, description, config = {}) {
  return panel('table', title, size, targets.map(target => ({ ...target, instant: true, range: false, format: 'table' })), description, { ...config,
    options: { showHeader: true, cellHeight: 'sm', ...config.options } })
}

let nextId = 1
let y = 0
const panels = []
function row(title, children, collapsed = false) {
  const header = { id: nextId++, type: 'row', title, collapsed, gridPos: { x: 0, y: y++, w: 24, h: 1 }, panels: [] }
  let x = 0; let height = 0
  for (const child of children) {
    const { w, h } = child.gridPos
    if (x + w > 24) { y += height; x = 0; height = 0 }
    child.id = nextId++
    child.gridPos = { x, y, w, h }
    x += w; height = Math.max(height, h)
  }
  y += height
  if (collapsed) { header.panels = children; panels.push(header) }
  else panels.push(header, ...children)
}

const severityMapping = valueMapping({ critical: ['严重', 'red'], warning: ['警告', 'orange'] })
const backgroundCell = { type: 'color-background', mode: 'basic' }
const organize = (excluded, renamed) => ({ id: 'organize', options: { excludeByName: Object.fromEntries(excluded.map(name => [name, true])), renameByName: renamed } })
const sortBy = field => ({ id: 'sortBy', options: { fields: {}, sort: [{ field, desc: false }] } })
const backlogDescription = '来源为 Hub 持久投递队列。NotificationBacklog 在待发送 >20 或投递失败 >0 持续 90 秒后告警；过期失败消息不再计入。'

row('总览', [
  stat('协作平台', [3, 4], q(['monitor_service_status{service="server"}', '协作平台']), '来自 Hub，与独立状态页同源；维护覆盖展示状态，实际观测见下方时间线。', { defaults: { mappings: SERVICE_STATUS } }),
  stat('桌面下载与更新', [3, 4], q(['monitor_service_status{service="desktop"}', '桌面下载与更新']), '来自 Hub，与独立状态页同源；无新鲜观测时显示状态未知。', { defaults: { mappings: SERVICE_STATUS } }),
  stat('外网可用率', [3, 4], q(['avg_over_time(service:probe_success:min{service="server"}[$__range])', '所选时段', { instant: true }]), '按所选时间范围统计服务最差探测结果的平均值；记录规则 service:probe_success:min 每 30 秒评估。缺失采样不计入分母；99% 与 99.9% 为看板参考阈值。', { defaults: { unit: 'percentunit', decimals: 2, min: 0, max: 1, thresholds: thresholds('red', [0.99, 'orange'], [0.999, 'green']) } }),
  stat('首页外网耗时', [3, 4], q(['max(probe_duration_seconds{check="home"})', '首页']), '取首页探测的最大耗时；1 秒、3 秒仅为看板参考阈值，没有对应告警。', { defaults: { unit: 's', thresholds: thresholds('green', [1, 'orange'], [3, 'red']) }, options: { graphMode: 'area' } }),
  stat('活动告警', [3, 4], q(['count(ALERTS{alertstate="firing",severity="critical"}) or vector(0)', '严重'], ['count(ALERTS{alertstate="firing",severity="warning"}) or vector(0)', '警告']), '统计 Prometheus 正在触发的严重与警告级别告警；任一严重告警为红，任一警告为橙。', { options: { textMode: 'value_and_name' }, overrides: [override('严重', { thresholds: thresholds('green', [1, 'red']) }), override('警告', { thresholds: thresholds('green', [1, 'orange']) })] }),
  stat('证书剩余', [3, 4], q(['(min(probe_ssl_earliest_cert_expiry) - time()) / 86400', '最早到期证书']), '取全部 HTTPS 探测中最早到期的证书；CertificateExpiring 在不足 30 天时警告、不足 7 天时严重告警，持续 90 秒。', { defaults: { unit: 'd', decimals: 0, thresholds: thresholds('red', [7, 'orange'], [30, 'green']) } }),
  stat('采集中断', [3, 4], q(['count(up == 0) or vector(0)', '中断目标']), '来自 Prometheus up；统计当前抓取失败的目标。持续 90 秒触发 MonitoringTargetUnavailable，缺失目标另由 MonitoringTargetMissing 检测。', { defaults: { thresholds: thresholds('green', [1, 'red']) } }),
  stat('通知队列', [3, 4], q(['monitor_delivery_pending', '待发送'], ['monitor_delivery_dead', '失败']), backlogDescription, { options: { textMode: 'value_and_name' }, overrides: [override('待发送', { thresholds: thresholds('green', [20, 'orange']) }), override('失败', { thresholds: thresholds('green', [1, 'red']) })] }),
  stateTimeline('服务状态', [12, 7], q(['monitor_service_status', '{{service}}'], ['monitor_service_observed_status', '{{service}} 实际观测']), '来源为 Hub 的状态页状态与忽略维护的实际观测；维护中的异常仍显示在实际观测时间线上，采样过期为未知。', { defaults: { mappings: SERVICE_STATUS }, overrides: displayNames({ server: '协作平台', desktop: '桌面下载与更新', 'server 实际观测': '协作平台（实际观测）', 'desktop 实际观测': '桌面下载与更新（实际观测）' }) }),
  table('当前告警', [12, 7], q('ALERTS{alertstate="firing"}'), '来自 Prometheus 当前 firing 告警；按级别排序，严重优先。维护期可能存在预期告警，请结合维护注解判断。', {
    defaults: { noValue: '当前无触发告警' },
    transformations: [organize(['Time', 'Value', '__name__', 'alertstate', 'expected_maintenance'], { alertname: '告警', severity: '级别', service: '服务', source_job: '来源', instance: '实例', rule: '规则', windows_service: 'Windows 服务', volume: '磁盘卷', job: '采集任务', state: '服务状态', check: '检查项', module: '探测模块' }), sortBy('级别')],
    overrides: [override('级别', { mappings: severityMapping, 'custom.cellOptions': backgroundCell })],
  }),
])

row('外网访问（黑盒探测）', [
  stateTimeline('检查项结果', [24, 6], q(['min by (check) (probe_success)', '{{check}}']), '来源为 blackbox 探测；同一检查项任一目标失败即为失败，空白表示无采样，不能视为恢复。', { defaults: { mappings: UP }, overrides: displayNames(CHECK_NAMES) }),
  timeseries('探测耗时（按检查项）', [12, 8], q(['max by (check) (probe_duration_seconds)', '{{check}}']), '按检查项展示最大探测耗时，避免逐条绘制带哈希的资源 URL；右侧列出平均、最大和最新值。', { defaults: { unit: 's' }, overrides: displayNames(CHECK_NAMES), options: { legend: { displayMode: 'table', placement: 'right', calcs: ['mean', 'max', 'lastNotNull'] } } }),
  timeseries('首页请求阶段', [12, 8], q(['max by (phase) (probe_http_duration_seconds{check="home"})', '{{phase}}']), '来自首页 HTTP 探测各阶段耗时，用于区分 DNS、连接、TLS、服务端处理与内容传输。', { defaults: { unit: 's' }, custom: { stacking: { mode: 'normal', group: 'A' }, fillOpacity: 30 }, overrides: displayNames(PHASES) }),
  table('探测明细', [24, 8], q('probe_success', 'probe_http_status_code', 'probe_duration_seconds'), '来自 blackbox 的即时结果、HTTP 状态码和耗时；按失败优先排序，保留地址用于定位具体资源。', {
    transformations: [{ id: 'filterFieldsByName', options: { exclude: { names: ['__name__'] } } }, { id: 'merge', options: {} }, organize(['Time', 'job', 'module'], { check: '检查项', service: '服务', instance: '地址', 'Value #A': '结果', 'Value #B': '状态码', 'Value #C': '耗时' }), sortBy('结果')],
    overrides: [override('结果', { mappings: UP, 'custom.cellOptions': backgroundCell }), override('耗时', { unit: 's', decimals: 3 }), override('检查项', { mappings: valueMapping(Object.fromEntries(Object.entries(CHECK_NAMES).map(([key, name]) => [key, [name]]))) })],
  }),
])

const appDescription = text => `${text} 应用每 15 秒内存采样、Prometheus 每 30 秒抓取；阈值为运维中心默认值，应用内调整后以应用为准。`
row('应用 YumpooServer', [
  timeseries('请求速率（近似）', [8, 7], q(['yumpoo_ops_http_requests / 15', '请求/秒']), appDescription('以 15 秒请求增量除以 15 得到近似请求速率；增量 gauge 不能作为累计计数器计算 rate。'), { defaults: { unit: 'reqps' } }),
  timeseries('HTTP 错误率', [8, 7], q(['yumpoo_ops_http_errorrate', '错误率']), appDescription('HTTP_ERROR_RATE：1% 警告、5% 严重；近 5 分钟不足 20 次请求时为空。'), { defaults: { unit: 'percentunit', max: 1, thresholds: thresholds('green', [0.01, 'orange'], [0.05, 'red']) } }),
  timeseries('HTTP P95', [8, 7], q(['yumpoo_ops_http_p95', 'P95']), appDescription('HTTP_LATENCY_P95：1500 ms 警告、3000 ms 严重。'), { defaults: { unit: 'ms', thresholds: thresholds('green', [1500, 'orange'], [3000, 'red']) } }),
  timeseries('JVM 堆占用', [8, 7], q(['yumpoo_ops_heap_ratio', '堆占用率']), appDescription('JVM_HEAP_HIGH：85% 警告、95% 严重。'), { defaults: { unit: 'percentunit', max: 1, thresholds: thresholds('green', [0.85, 'orange'], [0.95, 'red']) } }),
  timeseries('GC 与线程', [8, 7], q(['yumpoo_ops_gc_pause', 'GC 暂停（ms/15s）'], ['yumpoo_ops_threads_live', '活动线程']), appDescription('左轴为每 15 秒采样窗口的 GC 暂停时间，右轴为活动线程数；用于诊断，没有对应默认告警阈值。'), { defaults: { unit: 'ms' }, overrides: [override('活动线程', rightAxis('none'))] }),
  timeseries('错误日志', [8, 7], q(['yumpoo_ops_logs_error5m', 'ERROR（5 分钟）']), appDescription('ERROR_LOG_BURST：近 5 分钟 ERROR 日志达到 10 条警告、50 条严重。'), { defaults: { thresholds: thresholds('green', [10, 'orange'], [50, 'red']) }, custom: { drawStyle: 'bars' } }),
  timeseries('数据库连接池', [8, 7], q(['yumpoo_ops_db_pool_active', '活动连接'], ['yumpoo_ops_db_pool_pending', '等待线程'], ['yumpoo_ops_db_pool_max', '连接上限']), appDescription('DB_POOL_SATURATED：等待线程 ≥1 警告、≥5 严重，持续 120 秒；连接上限为配置容量参考线。'), { overrides: [override('连接上限', { color: fixedColor('gray'), 'custom.lineStyle': { fill: 'dash', dash: [10, 10] }, 'custom.fillOpacity': 0 })] }),
  timeseries('数据库响应', [8, 7], q(['yumpoo_ops_db_ping', 'Ping'], ['yumpoo_ops_db_failures', '连续失败次数']), appDescription('DB_SLOW：Ping 200 ms 警告、1000 ms 严重；Prometheus DatabaseUnavailable 在连续失败次数 ≥2 持续 90 秒后触发。'), { defaults: { unit: 'ms' }, overrides: [override('Ping', { thresholds: thresholds('green', [200, 'orange'], [1000, 'red']) }), override('连续失败次数', { ...rightAxis(), 'custom.drawStyle': 'bars' })] }),
  timeseries('业务事件队列', [8, 7], q(['yumpoo_ops_outbox_oldestage', '最老积压'], ['yumpoo_ops_outbox_dead', '死信']), appDescription('业务 outbox 队列，与监控通知队列分别计数。OUTBOX_BACKLOG_AGE：300 秒警告、900 秒严重；死信计数见右轴。'), { defaults: { unit: 's' }, overrides: [override('最老积压', { thresholds: thresholds('green', [300, 'orange'], [900, 'red']) }), override('死信', { ...rightAxis(), 'custom.drawStyle': 'bars' })] }),
  timeseries('应用规则告警', [12, 7], q(['sum by (severity) (yumpoo_ops_alert_active)', '{{severity}}']), appDescription('按级别汇总应用运维中心活动规则；对应 Prometheus ApplicationRuleFiring。'), { custom: { drawStyle: 'bars', stacking: { mode: 'normal', group: 'A' } }, overrides: [override('critical', { displayName: '严重', color: fixedColor('red') }), override('warning', { displayName: '警告', color: fixedColor('orange') })] }),
  timeseries('采样新鲜度', [12, 7], q(['clamp_max(time() - yumpoo_ops_sample_timestamp_seconds, 600)', '采样'], ['clamp_max(time() - yumpoo_ops_io_timestamp_seconds, 600)', 'I/O'], ['clamp_max(time() - yumpoo_ops_alert_evaluation_timestamp_seconds, 600)', '规则评估']), appDescription('显示上限截断为 600 秒；60 秒橙、90 秒红。ApplicationSamplesStale：采样年龄 >60 秒，或 I/O、规则评估年龄 >90 秒，持续 90 秒。'), { defaults: { unit: 's', max: 600, thresholds: thresholds('green', [60, 'orange'], [90, 'red']) } }),
])

row('Windows 业务主机', [
  timeseries('CPU 使用率', [6, 7], q([expr.windowsCpu, 'CPU']), `来自 windows_exporter idle 累计计数器的 5 分钟速率。${hostDescription('windowsCpu', 'CPU 使用率')}；持续 5 分钟。阈值来自共享 hostAlerts。`, { defaults: { unit: 'percentunit', max: 1, thresholds: hostThresholds('windowsCpu') } }),
  timeseries('内存', [6, 7], q([`1 - (${expr.windowsMemoryAvailable})`, '物理内存使用率'], ['windows_memory_committed_bytes / windows_memory_commit_limit_bytes', '提交内存占比']), `来自 windows_exporter；${hostDescription('windowsMemoryAvailable', '可用物理内存')}。使用率阈值按 1 - hostAlerts 的可用比例推导；提交内存仅供诊断。`, { defaults: { unit: 'percentunit', max: 1 }, overrides: [override('物理内存使用率', { thresholds: hostThresholds('windowsMemoryAvailable', true) })] }),
  barGauge('磁盘使用率', [6, 7], q([`1 - (${expr.windowsDiskFree})`, '{{volume}}']), `只统计盘符卷，排除恢复分区。${hostDescription('windowsDiskFree', '磁盘可用比例')}；使用率阈值由共享 hostAlerts 反向推导。`, { defaults: { unit: 'percentunit', thresholds: hostThresholds('windowsDiskFree', true) } }),
  stat('运行时长', [3, 7], q(['time() - windows_system_boot_time_timestamp', 'Windows']), '来自 windows_exporter 的系统启动时间；启动不足 1 小时显示橙色，随后绿色，仅为运维参考。', { defaults: { unit: 'dtdurations', thresholds: thresholds('orange', [3600, 'green']) } }),
  stat('预计写满', [3, 7], q([`windows_logical_disk_free_bytes{${WINDOWS_VOLUME}} / clamp_min(-deriv(windows_logical_disk_free_bytes{${WINDOWS_VOLUME}}[24h]), 1) / 86400`, '{{volume}}']), '按盘符卷过去 24 小时剩余空间的线性变化估算；14、30 天为参考阈值，没有对应告警。空间不下降时用最小 1 byte/s 计算，超过 365 天显示 > 1 年。', { defaults: { unit: 'd', thresholds: thresholds('red', [14, 'orange'], [30, 'green']), mappings: [{ type: 'range', options: { from: 365, to: null, result: { text: '> 1 年', color: 'green' } } }] }, options: { textMode: 'value_and_name' } }),
  stateTimeline('关键服务', [12, 6], q(['windows_service_state{state="running"}', '{{service}}']), 'windows_exporter 仅筛选 YumpooServer、postgresql-x64-17、sshd。未运行由 WindowsServiceStopped 告警；exporter 在线但关键服务序列缺失由 WindowsServiceMissing 告警，空白不能视为运行正常。', { defaults: { mappings: RUNNING } }),
  timeseries('磁盘剩余空间', [12, 6], q([`windows_logical_disk_free_bytes{${WINDOWS_VOLUME}}`, '{{volume}}']), '来自 windows_exporter 的盘符卷剩余字节数；与 WindowsDiskLow 相同卷过滤范围，比例告警阈值见磁盘使用率。', { defaults: { unit: 'bytes' } }),
])

const memoryLimits = Object.entries(compose.services).map(([name, service]) => `${name} ${parseInt(service.mem_limit)} MiB`).join('；')
const retention = compose.services.prometheus.command.find(value => value.startsWith('--storage.tsdb.retention.size=')).split('=')[1]
const retentionParts = /^(\d+)(KB|MB|GB|TB)$/u.exec(retention)
if (!retentionParts) throw new Error('Unsupported Prometheus retention size')
const retentionBytes = Number(retentionParts[1]) * 1024 ** ({ KB: 1, MB: 2, GB: 3, TB: 4 }[retentionParts[2]])
row('监控链路与 Ubuntu', [
  stateTimeline('采集目标', [12, 7], q(['min by (job) (up)', '{{job}}']), 'Prometheus 按 job 汇总抓取状态；external 为全部外网探测目标的最差抓取状态，不等于目标站点探测成功。对应 MonitoringTargetUnavailable/Missing。', { defaults: { mappings: valueMapping({ 0: ['中断', 'red'], 1: ['正常', 'green'] }) } }),
  timeseries('抓取耗时', [12, 7], q(['max by (job) (scrape_duration_seconds)', '{{job}}']), '来自 Prometheus 抓取时长，按 job 取最大值；红线 12 秒对应配置的 scrape_timeout。', { defaults: { unit: 's', thresholds: thresholds('green', [12, 'red']) } }),
  timeseries('通知投递', [8, 7], q(['monitor_delivery_pending', '待发送'], ['monitor_delivery_dead', '投递失败']), backlogDescription),
  timeseries('Windows 事件中继', [8, 7], q(['yumpoo_monitor_relay_pending', '待投递'], ['yumpoo_monitor_relay_dead', '被拒绝'], ['clamp_max(time() - yumpoo_monitor_relay_timestamp_seconds, 600)', '心跳年龄']), '来自 Windows 发布事件中继；WindowsEventRelayBacklog 在待投递或被拒绝 >0，或心跳年龄 >90 秒持续 3 分钟后告警。心跳年龄显示上限 600 秒。', { overrides: [override('心跳年龄', { ...rightAxis('s'), max: 600, thresholds: thresholds('green', [90, 'red']) })] }),
  timeseries('Ubuntu 资源', [8, 7], q([expr.ubuntuCpu, 'CPU'], [`1 - (${expr.ubuntuMemoryAvailable})`, '内存'], [`1 - (${expr.ubuntuRootFree})`, '根分区']), `来自 node-exporter。${hostDescription('ubuntuCpu', 'CPU 使用率')}；${hostDescription('ubuntuMemoryAvailable', '可用内存')}；${hostDescription('ubuntuRootFree', '根分区可用比例')}。告警阈值文字由共享 hostAlerts 生成。`, { defaults: { unit: 'percentunit', max: 1 } }),
  timeseries('组件常驻内存', [12, 7], q(['max by (job) (process_resident_memory_bytes{job=~"prometheus|alertmanager|grafana|blackbox|ubuntu"})', '{{job}}']), `来自各组件进程常驻内存指标；Compose 容器上限：${memoryLimits}。进程 RSS 与容器总内存口径不同。Hub 不导出进程指标。`, { defaults: { unit: 'bytes' }, overrides: displayNames({ ubuntu: 'node-exporter' }) }),
  timeseries('Prometheus 存储', [12, 7], q(['prometheus_tsdb_storage_blocks_bytes', '数据块'], ['prometheus_tsdb_wal_storage_size_bytes', 'WAL'], ['prometheus_tsdb_head_chunks_storage_size_bytes', '头部块'], ['prometheus_tsdb_head_series', '活跃序列']), `来自 Prometheus TSDB 指标；数据块、WAL、头部块按字节堆叠，活跃序列使用右轴且不堆叠。红线来自 Compose 保留容量 ${retention}，按 GiB 换算为 ${retentionBytes} 字节。`, { defaults: { unit: 'bytes', thresholds: thresholds('green', [retentionBytes, 'red']) }, custom: { stacking: { mode: 'normal', group: 'A' }, fillOpacity: 30 }, overrides: [override('活跃序列', { ...rightAxis(), 'custom.fillOpacity': 0, 'custom.thresholdsStyle': { mode: 'off' }, thresholds: thresholds('green') })] }),
], true)

function annotation(name, color, expression, titleFormat, textFormat, tagKeys = '') {
  return { name, datasource, enable: true, hide: false, iconColor: color, expr: expression, step: '30s', titleFormat, textFormat, tagKeys, useValueForTime: false,
    target: { refId: 'Anno', expr: expression, interval: '30s' } }
}

const dashboard = {
  uid: 'yumpoo-offsite', title: 'Yumpoo 异机监控', schemaVersion: 39, version: 1, editable: false,
  graphTooltip: 1, refresh: '30s', timezone: 'browser', time: { from: 'now-6h', to: 'now' },
  timepicker: { refresh_intervals: ['30s', '1m', '5m', '15m', '1h'] }, tags: ['yumpoo', 'offsite'],
  links: [{ title: '状态与通知管理页', type: 'link', url: 'http://127.0.0.1:8787', targetBlank: true, icon: 'external link' }],
  annotations: { list: [
    { builtIn: 1, datasource: { type: 'grafana', uid: '-- Grafana --' }, enable: true, hide: true, iconColor: 'rgba(0, 211, 255, 1)', name: 'Annotations & Alerts', type: 'dashboard' },
    annotation('发布维护', 'blue', 'monitor_maintenance_active{kind="automatic"} == 1', '发布维护', '{{service}}'),
    annotation('人工维护', 'purple', 'monitor_maintenance_active{kind="manual"} == 1', '人工维护', '{{service}}'),
    annotation('严重告警', 'red', 'ALERTS{alertstate="firing",severity="critical"}', '{{alertname}}', '{{service}} {{instance}}', 'alertname,service'),
  ] }, panels,
}
const file = path.join(directory, 'grafana/dashboards/yumpoo.json')
const text = `${JSON.stringify(dashboard, null, 2)}\n`
if (process.argv.includes('--check')) {
  if (fs.readFileSync(file, 'utf8') !== text) throw new Error('Regenerate the monitoring dashboard')
} else {
  fs.mkdirSync(path.dirname(file), { recursive: true })
  fs.writeFileSync(file, text)
}

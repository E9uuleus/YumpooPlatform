// Classic script for the packaged diagnostics page: type-only imports keep it free of module boilerplate.
type DiagnosticsBridgeType = import('@yumpoo/preload-contract').DesktopDiagnosticsBridge
type DiagnosticsReportType = import('@yumpoo/preload-contract').DiagnosticsReport
type DiagnosticsActionType = import('@yumpoo/preload-contract').DiagnosticsAction
type DiagnosticsProbeType = import('@yumpoo/preload-contract').DiagnosticsProbe

interface DiagnosticsActionButton {
  readonly action: DiagnosticsActionType
  readonly label: string
  readonly tone?: 'primary' | 'danger'
  readonly visible?: (report: DiagnosticsReportType) => boolean
}

{
  const bridge = (window as unknown as { yumpooDiagnostics?: DiagnosticsBridgeType }).yumpooDiagnostics
  const root = document.documentElement
  const byId = (id: string) => document.getElementById(id) as HTMLElement

  const FAILURES: Record<string, string> = {
    DISTRIBUTION_INVALID: '安装文件缺少分发配置或已损坏，请重新安装 Yumpoo Desktop。',
    LOAD_FAILED: '无法加载 Yumpoo。请检查下方的网络连接项。',
    CERTIFICATE_ERROR: '服务器证书校验失败，连接已被阻止。',
    STARTUP_ERROR: '启动时发生错误，详情已写入日志。',
    RENDERER_GONE: '页面进程意外退出。',
  }

  const ACTIONS: readonly DiagnosticsActionButton[] = [
    { action: 'retry', label: '重试', tone: 'primary' },
    { action: 'restart-normal', label: '以正常模式重启', visible: report => report.mode.safeMode },
    { action: 'open-web', label: '用浏览器打开 Web 版', visible: report => report.origin !== null },
    { action: 'export', label: '导出诊断包…' },
    { action: 'open-logs', label: '打开日志文件夹' },
    { action: 'register-protocol', label: '重新注册登录回调' },
    { action: 'sign-out', label: '退出登录并清除本机凭据', tone: 'danger' },
    { action: 'reset-preferences', label: '重置本机偏好', tone: 'danger' },
  ]

  const row = (list: HTMLElement, term: string, value: string, state?: 'ok' | 'fail' | 'skipped') => {
    const dt = document.createElement('dt')
    dt.textContent = term
    const dd = document.createElement('dd')
    dd.textContent = value
    if (state) dd.className = state
    list.append(dt, dd)
  }

  const probeState = (probe: DiagnosticsProbeType, skipped: boolean) => (skipped ? 'skipped' : probe.ok ? 'ok' : 'fail')
  const probeText = (probe: DiagnosticsProbeType, success: string, skipped: boolean) => {
    if (skipped) return '未检测'
    const duration = typeof probe.durationMs === 'number' ? `（${probe.durationMs} ms）` : ''
    return probe.ok ? `${success}${duration}` : `失败：${probe.error ?? '未知错误'}${duration}`
  }

  const render = (report: DiagnosticsReportType) => {
    const network = report.network
    const failure = report.mode.startupFailure
    const networkOk = !network.skipped && network.health.ok && network.tls.ok
    const summary = byId('summary')
    summary.textContent = failure ? (FAILURES[failure] ?? '启动失败。') : networkOk ? '连接正常。' : network.skipped ? '已完成本机检测。' : '连接存在问题，请查看下方检测项。'
    summary.classList.toggle('fail', !!failure || (!network.skipped && !networkOk))

    const mode = byId('mode')
    mode.hidden = !report.mode.safeMode
    mode.textContent = report.mode.crashLoop
      ? `连续 ${report.mode.consecutiveFailures} 次启动未成功，已自动进入安全模式：硬件加速、计时窗和收件箱提醒已关闭。`
      : '当前为安全模式：硬件加速、计时窗和收件箱提醒已关闭。'

    const networkList = byId('network')
    networkList.replaceChildren()
    const skipped = network.skipped
    row(networkList, '服务地址', report.origin ?? '缺少分发配置', report.origin ? undefined : 'fail')
    row(networkList, '系统代理', network.proxy ?? (skipped ? '未检测' : '无法读取'))
    row(networkList, '域名解析', probeText(network.dns, `${network.dns.addresses ?? 0} 个地址`, skipped), probeState(network.dns, skipped))
    const certificate = network.tls.subject ? `${network.tls.subject}，${network.tls.issuer ?? '未知签发者'}，剩余 ${network.tls.daysRemaining ?? '?'} 天` : '证书有效'
    row(networkList, 'TLS 证书', probeText(network.tls, certificate, skipped), probeState(network.tls, skipped))
    row(networkList, '服务健康', probeText(network.health, '正常', skipped), probeState(network.health, skipped))
    const skew = network.version.clockSkewMs
    const version = `${network.version.serverVersion ?? '可访问'}${typeof skew === 'number' ? `，时钟偏差 ${Math.round(skew / 1000)} 秒` : ''}`
    row(networkList, '服务版本', probeText(network.version, version, skipped),
      skipped ? 'skipped' : network.version.ok && Math.abs(skew ?? 0) < 120_000 ? 'ok' : 'fail')

    const local = byId('local')
    local.replaceChildren()
    row(local, '安全存储', report.local.safeStorageAvailable ? '可用' : '不可用，无法保存登录状态', report.local.safeStorageAvailable ? 'ok' : 'fail')
    row(local, '用户数据目录', report.local.userDataWritable ? '可写' : '不可写', report.local.userDataWritable ? 'ok' : 'fail')
    row(local, '图形加速', report.local.gpu.gpu_compositing ?? '未知')
    const memory = report.local.processes.reduce((total, process) => total + process.workingSetKb, 0)
    row(local, '内存占用', `${Math.round(memory / 1024)} MB，${report.local.processes.length} 个进程`)

    const versions = byId('versions')
    versions.replaceChildren()
    row(versions, 'Yumpoo Desktop', report.versions.shell)
    row(versions, 'Electron / Chromium', `${report.versions.electron} / ${report.versions.chrome}`)
    row(versions, '操作系统', `${report.versions.os} ${report.versions.arch}`)
    const update = report.update
    const updateText: Record<string, string> = {
      unavailable: '未启用', disabled: '未启用', idle: '已是最新版本', checking: '正在检查…', downloading: `正在下载 ${update.version ?? ''}`,
      verifying: `正在校验 ${update.version ?? ''}`, ready: `${update.version ?? '新版本'} 已就绪，重启后安装`, error: `失败：${update.error ?? '未知错误'}`,
    }
    row(versions, '应用内更新', updateText[update.state] ?? update.state, update.state === 'error' ? 'fail' : update.state === 'ready' ? 'ok' : undefined)

    const actions = byId('actions')
    actions.replaceChildren(...ACTIONS.filter(item => item.visible?.(report) ?? true).map(item => {
      const button = document.createElement('button')
      button.type = 'button'
      button.textContent = item.label
      if (item.tone) button.className = item.tone
      button.addEventListener('click', () => { void run(item.action, button) })
      return button
    }))
  }

  const setBusy = (busy: boolean) => {
    for (const button of document.querySelectorAll('button')) button.disabled = busy
  }

  const run = async (action: DiagnosticsActionType, button: HTMLButtonElement) => {
    if (!bridge) return
    const status = byId('status')
    status.textContent = `${button.textContent ?? ''}…`
    setBusy(true)
    try {
      const result = await bridge.runAction(action)
      status.textContent = result.message ?? (result.completed ? '已完成。' : '已取消。')
    } catch {
      status.textContent = '操作失败，详情已写入日志。'
    } finally {
      setBusy(false)
    }
  }

  const load = async () => {
    if (!bridge) {
      root.dataset.state = 'error'
      byId('summary').textContent = '诊断组件不可用，请重新安装 Yumpoo Desktop。'
      return
    }
    root.dataset.state = 'loading'
    byId('summary').textContent = '正在检测…'
    setBusy(true)
    try {
      render(await bridge.getReport())
      root.dataset.state = 'ready'
    } catch {
      root.dataset.state = 'error'
      byId('summary').textContent = '检测失败，详情已写入日志。'
    } finally {
      setBusy(false)
    }
  }

  byId('refresh').addEventListener('click', () => { void load() })
  void load()
}

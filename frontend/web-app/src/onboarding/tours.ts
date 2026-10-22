export type TourId = 'welcome' | 'project-work-items' | 'workspace' | 'dashboards' | 'inbox' | 'company'
export type TourPlacement = 'top' | 'top-start' | 'top-end' | 'bottom' | 'bottom-start' | 'bottom-end' | 'left' | 'left-start' | 'right' | 'right-start'

export interface TourStep {
  /** 缺省时居中展示。 */
  target?: string
  title: string
  description: string
  placement?: TourPlacement
  /** 引入或内容实质变化时的引导版本，缺省为 1；只看过更早版本的用户自动只看这些步骤。 */
  since?: number
  /** 主目标不可见时改用的目标与说明，例如二级导航收起时指向展开按钮。 */
  fallback?: { target: string; description: string; placement?: TourPlacement }
}

export interface TourDefinition {
  /** 内容大改时加一，已处理旧版本的用户会再看到一次。 */
  version: number
  steps: TourStep[]
}

const at = (name: string) => `[data-tour="${name}"]`

export const TOURS: Record<TourId, TourDefinition> = {
  welcome: {
    version: 2,
    steps: [
      { title: '欢迎来到工作中台', description: '接下来用 1 分钟带你认识首页和主要入口，随时可以按 Esc 退出。' },
      { target: at('shell-modules'), placement: 'right-start', title: '功能模块', description: '在首页和工作台之间切换；如果你是管理员，这里还会出现团队、公司管理与运维。' },
      { target: at('home-jumps'), placement: 'bottom', title: '快捷入口', description: '直达管理项目、收件箱，或开始计时、记录工时；这里还会按你的角色显示仪表板、团队视图和管理入口。' },
      { target: at('home-tasks'), placement: 'right-start', title: '我的工作项', description: '分配给你、进行中和待开始的工作项都在这里，逾期会标红；点击即可打开所在项目。' },
      { target: at('home-calendar'), placement: 'left-start', title: '工时日历', description: '本月每天记录的工时，达到当天标准工时的日期颜色最深；“查看明细”进入我的工时。', since: 2 },
      { target: at('timer-launcher'), placement: 'top-end', title: '开始工作', description: '随时从这里选择工作项开始计时，可以同时计时多项；计时中这里显示已用时长。', since: 2 },
      { target: at('topbar-inbox'), placement: 'bottom-end', title: '收件箱', description: '有人 @你、评论或给你指派工作项时，这里会提醒。' },
      { target: at('topbar-help'), placement: 'bottom-end', title: '帮助', description: '打开当前页面的帮助、快速开始和常见问题；帮助中心不登录也能访问。', since: 2 },
      { target: at('topbar-tour'), placement: 'bottom-end', title: '查看本页引导', description: '随时点这里重看当前页面的引导；没有引导的页面按钮会变灰。', since: 2 },
      { target: at('topbar-preferences'), placement: 'bottom-end', title: '偏好设置', description: '主题、界面密度等个人偏好都在这里；桌面端还能设置悬浮计时、小窗固定和全局快捷键。', since: 2 },
      { target: at('home-projects'), placement: 'left-start', title: '从项目开始', description: '结束导览后，点击任一项目进入工作项表格，我们会继续介绍工作项的用法。' },
    ],
  },
  'project-work-items': {
    version: 2,
    steps: [
      { target: at('project-title'), placement: 'bottom-start', title: '项目信息', description: '点击项目名查看负责人、创建时间，并设置本项目的通知提醒。' },
      { target: at('project-sections'), placement: 'bottom-end', title: '项目分区', description: '在工作项、统计、成员、动态和设置之间切换。', since: 2 },
      { target: at('work-item-views'), placement: 'bottom-start', title: '表格与看板', description: '表格适合批量编辑；在看板中拖动卡片即可推进状态。' },
      { target: at('work-item-toolbar'), placement: 'bottom-start', title: '工具栏', description: '搜索、按处理人或条件筛选、排序、隐藏列和分组；打开“隐没已完成”可淡化已完成的工作项。这些设置只影响你自己的视图。', since: 2 },
      { target: at('work-item-table'), placement: 'top', title: '直接在表格里编辑', description: '点击状态、处理人、截止日期等单元格即可修改；勾选多行后，底部会出现批量操作栏。' },
      { target: at('work-item-quick-add'), placement: 'top-start', title: '快速添加', description: '输入名称按 Enter 创建，Shift+Enter 创建后继续添加下一条；分组时每组底部都可以添加。', since: 2 },
      { target: at('work-item-discussion'), placement: 'right', title: '讨论与详情', description: '打开工作项详情：编写描述、讨论并 @同事、上传附件、查看活动记录。' },
      { target: at('work-item-timer'), placement: 'left', title: '时长追踪', description: '这一列汇总每个工作项的计时；开启计时器时点击即可开始或停止，可同时计时多项，时长按项数平均计入。', since: 2 },
      { target: at('work-item-column-header'), placement: 'bottom', title: '列头菜单', description: '列头菜单可排序、隐藏列；表头最右侧的“+”可添加跨项目连接列。' },
    ],
  },
  workspace: {
    version: 1,
    steps: [
      { target: at('context-nav'), placement: 'right-start', title: '工作台导航', description: '仪表板、我的工时、管理项目、归档项目，以及你参与的项目列表；顶部的搜索按钮可以快速查找项目。',
        fallback: { target: at('context-nav-expand'), placement: 'right-start', description: '点这里展开工作台菜单：仪表板、我的工时、管理项目、归档项目和你参与的项目都在里面。' } },
      { target: at('project-catalog-tabs'), placement: 'bottom-start', title: '最近与内容', description: '“最近”是你最近打开的项目，可置顶常用项目；“内容”是全部项目目录。' },
      { target: at('project-filter'), placement: 'bottom', title: '搜索与筛选', description: '按名称或编码搜索，按最后修改时间、负责人和你的角色筛选。' },
      { target: at('project-create'), placement: 'bottom-end', title: '创建项目', description: '新建一个项目，开始组织工作项与成员。' },
    ],
  },
  dashboards: {
    version: 1,
    steps: [
      { target: at('dashboard-tabs'), placement: 'bottom-start', title: '仪表板标签', description: '在标签之间切换你的仪表板；点“+”新建自己的仪表板。' },
      { target: at('dashboard-add-widget'), placement: 'bottom-start', title: '添加组件', description: '添加图表、表格等组件，拖动即可调整布局，修改会自动保存。' },
      { target: at('dashboard-projects'), placement: 'bottom', title: '连接项目', description: '选择这个仪表板要统计的项目范围。' },
      { target: at('dashboard-filters'), placement: 'bottom', title: '筛选', description: '按处理人、状态等条件过滤所有组件的数据。' },
      { target: at('dashboard-actions'), placement: 'bottom-end', title: '刷新与导出', description: '刷新数据、导出 PDF；更多菜单可重命名、复制或恢复默认布局。' },
    ],
  },
  inbox: {
    version: 1,
    steps: [
      { target: at('context-nav'), placement: 'right-start', title: '通知分类', description: '按未读、全部、@我、评论与回复、指派给我、项目动态和已归档查看通知。',
        fallback: { target: at('context-nav-expand'), placement: 'right-start', description: '点这里展开收件箱分类：未读、全部、@我、评论与回复、指派给我、项目动态和已归档。' } },
      { target: at('inbox-list'), placement: 'top', title: '通知列表', description: '点击通知直接打开相关工作项。' },
      { target: at('inbox-read-all'), placement: 'bottom-end', title: '全部标为已读', description: '一键清空当前分类的未读通知。' },
    ],
  },
  company: {
    version: 3,
    steps: [
      { target: at('context-nav'), placement: 'right-start', title: '公司管理', description: '概览、项目、成员与角色、活动日志、同步运行、定时任务和工作日历都在这里切换。', since: 3,
        fallback: { target: at('context-nav-expand'), placement: 'right-start', description: '点这里展开公司管理菜单，在概览、项目、成员与角色、活动日志、同步运行、定时任务和工作日历之间切换。' } },
      { target: at('company-profile'), placement: 'bottom', title: '公司信息', description: '公司名称与企微 Corp ID，可在此修改公司名称。' },
      { target: at('company-entries'), placement: 'bottom', title: '管理入口', description: '从这里直达各管理页面，卡片上显示关键状态。' },
      { target: at('company-integrations'), placement: 'top', title: '企业微信集成', description: '查看企微登录与通讯录同步的配置和最近运行状态。' },
    ],
  },
}

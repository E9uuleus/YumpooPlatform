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
    version: 1,
    steps: [
      { title: '欢迎来到工作中台', description: '接下来用 1 分钟带你认识首页和主要入口，随时可以按 Esc 退出。' },
      { target: at('shell-modules'), placement: 'right-start', title: '功能模块', description: '在首页和工作台之间切换；如果你是管理员，这里还会出现团队、公司管理与运维。' },
      { target: at('home-jumps'), placement: 'bottom', title: '快捷入口', description: '直达管理项目、收件箱，或直接开始计时；这里还会按你的角色显示仪表板、团队视图和管理入口。' },
      { target: at('home-tasks'), placement: 'right-start', title: '我的工作项', description: '分配给你、进行中和待开始的工作项都在这里，逾期会标红；点击即可打开所在项目。' },
      { target: at('home-calendar'), placement: 'left-start', title: '工时日历', description: '本月每天记录的工时，满 8 小时的日期以深色显示；“查看明细”进入我的工时。' },
      { target: at('topbar-inbox'), placement: 'bottom-end', title: '收件箱', description: '有人 @你、评论或给你指派工作项时，这里会提醒。' },
      { target: at('topbar-appearance'), placement: 'bottom-end', title: '外观', description: '切换浅色、深色、夜间主题和界面密度。' },
      { target: at('topbar-user'), placement: 'bottom-end', title: '随时回看', description: '在这里点“查看本页引导”，可以重新查看当前页面的引导。' },
      { target: at('home-projects'), placement: 'left-start', title: '从项目开始', description: '结束导览后，点击任一项目进入工作项表格，我们会继续介绍工作项的用法。' },
    ],
  },
  'project-work-items': {
    version: 1,
    steps: [
      { target: at('project-title'), placement: 'bottom-start', title: '项目信息', description: '点击项目名查看负责人、创建时间，并设置本项目的通知提醒。' },
      { target: at('project-sections'), placement: 'bottom-end', title: '项目分区', description: '在工作项、成员、动态和设置之间切换。' },
      { target: at('work-item-views'), placement: 'bottom-start', title: '表格与看板', description: '表格适合批量编辑；在看板中拖动卡片即可推进状态。' },
      { target: at('work-item-toolbar'), placement: 'bottom-start', title: '工具栏', description: '搜索、按处理人筛选、排序、隐藏列和分组；表格设置只影响你自己的视图。“小计时器”也在这里。' },
      { target: at('work-item-table'), placement: 'top', title: '直接在表格里编辑', description: '点击状态、处理人、截止日期等单元格即可修改；勾选多行后，底部会出现批量操作栏。' },
      { target: at('work-item-quick-add'), placement: 'top-start', title: '快速添加', description: '输入名称按 Enter 创建，Shift+Enter 创建后继续添加下一条。' },
      { target: at('work-item-discussion'), placement: 'right', title: '讨论与详情', description: '打开工作项详情：编写描述、讨论并 @同事、上传附件、查看活动记录。' },
      { target: at('work-item-timer'), placement: 'left', title: '计时', description: '点击开始或停止计时，工时会自动汇总到首页日历和我的工时；桌面端可收起为悬浮球。' },
      { target: at('work-item-column-header'), placement: 'bottom', title: '列头菜单', description: '列头菜单可排序、隐藏列；表头最右侧的“+”可添加跨项目连接列。' },
    ],
  },
  workspace: {
    version: 1,
    steps: [
      { target: at('context-nav'), placement: 'right-start', title: '工作台导航', description: '仪表板、我的工时、管理项目、归档项目，以及你参与的项目列表；顶部的搜索按钮可以快速查找项目。' },
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
      { target: at('context-nav'), placement: 'right-start', title: '通知分类', description: '按未读、全部、@我、评论与回复、指派给我、项目动态和已归档查看通知。' },
      { target: at('inbox-list'), placement: 'top', title: '通知列表', description: '点击通知直接打开相关工作项。' },
      { target: at('inbox-read-all'), placement: 'bottom-end', title: '全部标为已读', description: '一键清空当前分类的未读通知。' },
    ],
  },
  company: {
    version: 2,
    steps: [
      { target: at('context-nav'), placement: 'right-start', title: '公司管理', description: '概览、项目、成员与角色、活动日志、同步运行和定时任务都在这里切换。' },
      { target: at('company-profile'), placement: 'bottom', title: '公司信息', description: '公司名称与企微 Corp ID，可在此修改公司名称。' },
      { target: at('company-entries'), placement: 'bottom', title: '管理入口', description: '从这里直达各管理页面，卡片上显示关键状态。' },
      { target: at('company-integrations'), placement: 'top', title: '企业微信集成', description: '查看企微登录与通讯录同步的配置和最近运行状态。' },
    ],
  },
}

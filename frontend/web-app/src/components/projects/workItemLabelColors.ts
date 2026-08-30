import { WorkItemLabelColorToken } from '@yumpoo/api-client'

interface WorkItemLabelColorOption {
  token: WorkItemLabelColorToken
  label: string
  color: string
}

export const mondayWorkItemLabelColors = [
  { token: WorkItemLabelColorToken.BrightGreen, label: '明亮绿', color: 'var(--yp-label-bright-green)' },
  { token: WorkItemLabelColorToken.Saladish, label: '沙拉绿', color: 'var(--yp-label-saladish)' },
  { token: WorkItemLabelColorToken.EggYolk, label: '蛋黄', color: 'var(--yp-label-egg-yolk)' },
  { token: WorkItemLabelColorToken.DarkOrange, label: '深橙', color: 'var(--yp-label-dark-orange)' },
  { token: WorkItemLabelColorToken.Peach, label: '桃色', color: 'var(--yp-label-peach)' },
  { token: WorkItemLabelColorToken.Sunset, label: '日落红', color: 'var(--yp-label-sunset)' },
  { token: WorkItemLabelColorToken.DarkRed, label: '深红', color: 'var(--yp-label-dark-red)' },
  { token: WorkItemLabelColorToken.SofiaPink, label: '索菲亚粉', color: 'var(--yp-label-sofia-pink)' },
  { token: WorkItemLabelColorToken.Lipstick, label: '唇膏粉', color: 'var(--yp-label-lipstick)' },
  { token: WorkItemLabelColorToken.Bubble, label: '泡泡粉', color: 'var(--yp-label-bubble)' },
  { token: WorkItemLabelColorToken.DarkPurple, label: '深紫', color: 'var(--yp-label-dark-purple)' },
  { token: WorkItemLabelColorToken.Berry, label: '莓紫', color: 'var(--yp-label-berry)' },
  { token: WorkItemLabelColorToken.DarkIndigo, label: '深靛蓝', color: 'var(--yp-label-dark-indigo)' },
  { token: WorkItemLabelColorToken.Indigo, label: '靛蓝', color: 'var(--yp-label-indigo)' },
  { token: WorkItemLabelColorToken.Navy, label: '藏蓝', color: 'var(--yp-label-navy)' },
  { token: WorkItemLabelColorToken.BrightBlue, label: '亮蓝', color: 'var(--yp-label-bright-blue)' },
  { token: WorkItemLabelColorToken.Aquamarine, label: '水绿色', color: 'var(--yp-label-aquamarine)' },
  { token: WorkItemLabelColorToken.ChiliBlue, label: '晴空蓝', color: 'var(--yp-label-chili-blue)' },
  { token: WorkItemLabelColorToken.River, label: '河流蓝', color: 'var(--yp-label-river)' },
  { token: WorkItemLabelColorToken.Winter, label: '冬日灰蓝', color: 'var(--yp-label-winter)' },
  { token: WorkItemLabelColorToken.AmericanGray, label: '美式灰', color: 'var(--yp-label-american-gray)' },
  { token: WorkItemLabelColorToken.Blackish, label: '墨黑', color: 'var(--yp-label-blackish)' },
  { token: WorkItemLabelColorToken.Brown, label: '棕色', color: 'var(--yp-label-brown)' },
  { token: WorkItemLabelColorToken.Orchid, label: '兰花粉', color: 'var(--yp-label-orchid)' },
  { token: WorkItemLabelColorToken.Tan, label: '棕褐', color: 'var(--yp-label-tan)' },
  { token: WorkItemLabelColorToken.Sky, label: '天空蓝', color: 'var(--yp-label-sky)' },
  { token: WorkItemLabelColorToken.Coffee, label: '咖啡色', color: 'var(--yp-label-coffee)' },
  { token: WorkItemLabelColorToken.Royal, label: '皇家蓝', color: 'var(--yp-label-royal)' },
  { token: WorkItemLabelColorToken.Teal, label: '蓝绿色', color: 'var(--yp-label-teal)' },
  { token: WorkItemLabelColorToken.Lavender, label: '薰衣草紫', color: 'var(--yp-label-lavender)' },
  { token: WorkItemLabelColorToken.Steel, label: '钢蓝', color: 'var(--yp-label-steel)' },
  { token: WorkItemLabelColorToken.Lilac, label: '丁香紫', color: 'var(--yp-label-lilac)' },
  { token: WorkItemLabelColorToken.Pecan, label: '碧根果棕', color: 'var(--yp-label-pecan)' },
] as const satisfies readonly WorkItemLabelColorOption[]

const labelColorValues: Record<string, string> = {
  ...Object.fromEntries(mondayWorkItemLabelColors.map(item => [item.token, item.color])),
  GREEN: 'var(--yp-label-green)',
  BLUE: 'var(--yp-label-blue)',
  PURPLE: 'var(--yp-label-purple)',
  MAGENTA: 'var(--yp-label-magenta)',
  RED: 'var(--yp-label-red)',
  ORANGE: 'var(--yp-label-orange)',
  AMBER: 'var(--yp-label-amber)',
  LIME: 'var(--yp-label-lime)',
  CYAN: 'var(--yp-label-cyan)',
  GRAY: 'var(--yp-label-gray)',
}

export function workItemLabelColorValue(token?: WorkItemLabelColorToken | string): string {
  return token ? labelColorValues[token] ?? 'var(--yp-label-gray)' : 'var(--yp-label-gray)'
}

export function workItemLabelColorStyle(token?: WorkItemLabelColorToken | string): Record<string, string> {
  return { backgroundColor: workItemLabelColorValue(token) }
}

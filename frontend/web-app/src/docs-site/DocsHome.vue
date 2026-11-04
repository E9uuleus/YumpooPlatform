<script setup lang="ts">
import {
  ArrowRight, Bell, ChatDotRound, Cpu, DataAnalysis, FolderOpened, Monitor, OfficeBuilding, Timer, User, UserFilled, Setting,
} from '@element-plus/icons-vue'
import { ElButton, ElIcon } from 'element-plus'
import { onMounted, type Component } from 'vue'
import AuroraShapes from '../components/motion/AuroraShapes.vue'
import { daypart } from '../views/home/homeModel'
import { docPath } from './docsNav'
import { inDesktopShell } from './openDocs'

const desktop = inDesktopShell()
const heroDaypart = daypart(new Date(), Intl.DateTimeFormat().resolvedOptions().timeZone)

const roles: { label: string; text: string; slug: string; icon: Component }[] = [
  { label: '成员', text: '登录、找到项目、记录第一笔工时。', slug: 'start/first-day', icon: User },
  { label: '公司管理员', text: '成员与角色、工作日历和定时任务。', slug: 'start/admin', icon: OfficeBuilding },
  { label: '平台管理员', text: '运维中心的组件、告警与日志。', slug: 'guide/operations', icon: Cpu },
  { label: '开发者', text: '本地开发环境、常用命令与仓库约定。', slug: 'start/developer', icon: Setting },
]

const capabilities: { label: string; text: string; slug: string; icon: Component; tone: string }[] = [
  { label: '项目与工作项', text: '表格与看板、分组筛选、子项和跨项目连接。', slug: 'guide/work-items', icon: FolderOpened, tone: 'blue' },
  { label: '讨论与附件', text: '两级讨论、@提及、置顶和附件，动态集中可查。', slug: 'guide/collaboration', icon: ChatDotRound, tone: 'teal' },
  { label: '计时与工时', text: '一键计时、并行计时，在我的一天里补录与调整。', slug: 'guide/time-tracking', icon: Timer, tone: 'green' },
  { label: '仪表板与统计', text: '自定义图表与项目统计，导出 PDF 和 Excel。', slug: 'guide/dashboards', icon: DataAnalysis, tone: 'purple' },
  { label: '收件箱', text: '@提及、回复、指派和工时提醒按分类处理。', slug: 'guide/inbox', icon: Bell, tone: 'red' },
  { label: '桌面客户端', text: '悬浮计时、托盘快捷工时和自动更新。', slug: 'guide/desktop', icon: Monitor, tone: 'orange' },
  { label: '团队与公司管理', text: '团队视图、成员与角色、工作日历和活动日志。', slug: 'guide/company', icon: UserFilled, tone: 'pink' },
  { label: '运维中心', text: '组件状态、主机图表、在线会话与告警规则。', slug: 'guide/operations', icon: Cpu, tone: 'gray' },
]

const faqs: { label: string; anchor: string }[] = [
  { label: '无法登录怎么办', anchor: '无法登录' },
  { label: '看不到某个项目', anchor: '看不到某个项目' },
  { label: '工时和预期不一致', anchor: '工时和预期不一致' },
  { label: '引导不见了怎么重看', anchor: '引导不见了怎么重看' },
  { label: '桌面端如何更新', anchor: '桌面端如何更新' },
]

onMounted(() => { document.title = 'Yumpoo 文档' })
</script>

<template>
  <div class="docs-home">
    <section class="docs-hero">
      <AuroraShapes :daypart="heroDaypart" />
      <div class="docs-hero__text">
        <p class="docs-hero__eyebrow">
          Yumpoo 文档
        </p>
        <h1>Yumpoo 工作中台</h1>
        <p class="docs-hero__lead">
          面向团队的项目协作与工时平台：项目、工作项、计时、统计与企业微信登录，在一个工作空间完成。
        </p>
        <div class="docs-hero__actions">
          <router-link
            v-slot="{ href, navigate }"
            :to="docPath('start/first-day')"
            custom
          >
            <el-button
              type="primary"
              size="large"
              tag="a"
              :href="href"
              @click="navigate"
            >
              快速开始<el-icon class="el-icon--right">
                <ArrowRight />
              </el-icon>
            </el-button>
          </router-link>
          <router-link
            v-slot="{ href, navigate }"
            :to="docPath('intro/capabilities')"
            custom
          >
            <el-button
              size="large"
              tag="a"
              :href="href"
              @click="navigate"
            >
              浏览核心能力
            </el-button>
          </router-link>
          <router-link
            v-if="!desktop"
            class="docs-hero__download"
            to="/download"
          >
            下载桌面客户端
          </router-link>
        </div>
      </div>
    </section>

    <section
      class="docs-home__section"
      aria-labelledby="docs-roles"
    >
      <h2 id="docs-roles">
        按角色开始
      </h2>
      <div class="docs-home__grid docs-home__grid--roles">
        <router-link
          v-for="role in roles"
          :key="role.slug"
          class="docs-card"
          :to="docPath(role.slug)"
        >
          <el-icon class="docs-card__icon">
            <component :is="role.icon" />
          </el-icon>
          <strong>{{ role.label }}</strong>
          <span>{{ role.text }}</span>
        </router-link>
      </div>
    </section>

    <section
      class="docs-home__section"
      aria-labelledby="docs-capabilities"
    >
      <h2 id="docs-capabilities">
        核心能力
      </h2>
      <div class="docs-home__grid">
        <router-link
          v-for="capability in capabilities"
          :key="capability.slug"
          class="docs-card"
          :to="docPath(capability.slug)"
        >
          <el-icon
            class="docs-card__icon"
            :data-tone="capability.tone"
          >
            <component :is="capability.icon" />
          </el-icon>
          <strong>{{ capability.label }}</strong>
          <span>{{ capability.text }}</span>
        </router-link>
      </div>
    </section>

    <section
      class="docs-home__section"
      aria-labelledby="docs-faq"
    >
      <h2 id="docs-faq">
        常见问题速览
      </h2>
      <ul class="docs-home__faq">
        <li
          v-for="faq in faqs"
          :key="faq.anchor"
        >
          <router-link :to="docPath('guide/faq', faq.anchor)">
            {{ faq.label }}<el-icon><ArrowRight /></el-icon>
          </router-link>
        </li>
      </ul>
    </section>

    <footer class="docs-home__footer">
      文档随应用版本一同发布，内容与当前部署版本一致。
    </footer>
  </div>
</template>

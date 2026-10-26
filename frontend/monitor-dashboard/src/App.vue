<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
type Service = { id:string; name:string; state:string; observed:string; sampledAt:number|null }
type Maintenance = { id:string; title:string; service:string; starts:number; ends:number; state:string; automatic:number }
type Delivery = { id:number; channel:string; content:string; state:string; attempts:number; created:number; expires:number|null; sent:number|null; error:string|null }
type Overview = { services:Service[]; maintenance:Maintenance[]; history:{service:string;state:string;at:number;maintained:number}[]; channels?:{channel:string;configured:boolean;counts:{state:string;count:number}[]}[]; deliveries?:Delivery[]; audit?:{at:number;action:string;subject:string}[] }
const data = ref<Overview>({ services:[],maintenance:[],history:[] })
const admin = ref(false); const panel = ref('status'); const username = ref(''); const password = ref(''); const csrf = ref(''); const error = ref(''); const busy = ref(false); const stale = ref(false)
const title = ref('计划维护'); const service = ref('server'); const starts = ref(''); const ends = ref(''); const preview = ref('')
const previewError = ref('')
const labels:Record<string,string> = { healthy:'运行正常',down:'访问异常',unknown:'状态未知',maintenance:'维护中',active:'进行中',ended:'已结束',cancelled:'已取消',expired:'已超时',failed:'异常',pending:'待发送',sent:'已发送',dead:'投递失败',superseded:'已被后续消息取代',operations:'运维群',announcements:'公告群' }
const deliveryLabels:Record<string,string> = { ...labels, expired:'已过期', abandoned:'已放弃' }
const time = (value:number|null) => value ? new Date(value).toLocaleString('zh-CN') : '暂无采样'
const overall = computed(() => stale.value || data.value.services.some(s=>s.state==='unknown') ? '状态待确认' : data.value.services.some(s=>s.state==='down') ? '部分服务访问异常' : data.value.services.some(s=>s.state==='maintenance') ? '正在维护' : '所有服务运行正常')
async function api(path:string, body?:unknown) {
  const response = await fetch(`/api/v1/${path}`, { method:body ? 'POST':'GET', headers:body ? {'content-type':'application/json','x-csrf-token':csrf.value} : {}, ...(body ? {body:JSON.stringify(body)} : {}), signal:AbortSignal.timeout(10000) })
  const result = await response.json()
  if (!response.ok) { if(response.status===401) admin.value=false; throw new Error(result.error ?? '请求失败') }
  return result
}
async function refresh() { try { data.value=await api(admin.value ? 'admin/overview':'status'); stale.value=false } catch { stale.value=true } }
async function action(work:()=>Promise<void>) { busy.value=true; error.value=''; try { await work(); await refresh() } catch(e) { error.value=e instanceof Error ? e.message:'操作失败' } finally {busy.value=false} }
async function login() { await action(async()=>{ const result=await api('login',{username:username.value,password:password.value}); csrf.value=result.csrf; password.value=''; admin.value=true; panel.value='manage' }) }
const maintenancePayload = () => ({title:title.value,service:service.value,starts:new Date(starts.value).getTime(),ends:new Date(ends.value).getTime()})
async function createMaintenance() { await action(async()=>{ await api('admin/maintenance',maintenancePayload()) }) }
async function changeMaintenance(item:Maintenance, change:string) { await action(async()=>{ await api(`admin/maintenance/${item.id}`,{action:change,...(change==='postpone'?{ends:item.ends+30*60000}:{})}) }) }
async function discardDelivery(item:Delivery) {
  if (!window.confirm('放弃后该消息不再投递，确认？')) return
  await action(async()=>{ await api(`admin/deliveries/${item.id}/discard`,{}) })
}
watch([title,service,starts,ends,admin], (_values,_old,onCleanup) => {
  preview.value=''; previewError.value=''
  if (!admin.value || !title.value.trim() || !service.value || !starts.value || !ends.value) return
  let cancelled=false
  const pending=setTimeout(async()=>{
    try {
      const result=await api('admin/preview',maintenancePayload())
      if (!cancelled) preview.value=result.text
    } catch(e) {
      if (!cancelled) previewError.value=e instanceof Error ? e.message:'公告预览暂不可用'
    }
  },400)
  onCleanup(()=>{cancelled=true;clearTimeout(pending)})
})
let timer:ReturnType<typeof setInterval>|undefined
onMounted(()=>{void refresh();timer=setInterval(()=>void refresh(),30000)})
onUnmounted(()=>clearInterval(timer))
</script>

<template>
  <div class="shell">
    <header>
      <a
        class="brand"
        href="/"
      >Y<span>Yumpoo</span></a><span class="eyebrow">服务状态与通知</span><button @click="panel = panel === 'login' ? 'status' : admin ? 'manage' : 'login'">
        {{ admin ? '管理控制台' : '管理员登录' }}
      </button>
    </header>
    <nav aria-label="页面">
      <button
        :class="{selected:panel==='status'}"
        @click="panel='status'"
      >
        服务状态
      </button><button
        v-if="admin"
        :class="{selected:panel==='manage'}"
        @click="panel='manage'"
      >
        维护与通知
      </button><a
        v-if="admin"
        href="http://127.0.0.1:3000"
        target="_blank"
        rel="noreferrer"
      >指标看板 ↗</a>
    </nav>
    <p
      v-if="error"
      role="alert"
      class="error"
    >
      {{ error }}
    </p>
    <p
      v-if="stale"
      role="alert"
      class="error"
    >
      监测连接暂不可用，以下为历史记录，当前状态未知。
    </p>
    <main>
      <template v-if="panel==='status'">
        <section class="hero">
          <p class="eyebrow">
            独立异机监测
          </p><h1>{{ overall }}</h1><p>持续监测服务访问、维护安排与恢复情况。每 30 秒更新。</p>
        </section>
        <div class="cards">
          <article
            v-for="item in data.services"
            :key="item.id"
            class="card"
          >
            <div class="row">
              <h2>{{ item.name }}</h2><span :class="['badge',stale ? 'unknown' : item.state]">{{ labels[stale ? 'unknown':item.state] }}</span>
            </div><p>最近采样 {{ time(item.sampledAt) }}</p><small v-if="item.state==='maintenance'">实际观测：{{ labels[item.observed] }}</small>
          </article>
        </div>
        <section>
          <h2>维护安排</h2><p
            v-if="!data.maintenance.length"
            class="muted"
          >
            当前没有维护安排
          </p><article
            v-for="item in data.maintenance"
            :key="item.id"
            class="list-row"
          >
            <div><strong>{{ item.title }}</strong><p>{{ time(item.starts) }} — {{ time(item.ends) }}</p></div><span>{{ labels[item.state] ?? item.state }}</span>
          </article>
        </section>
        <section>
          <h2>近期状态记录</h2><p
            v-if="!data.history.length"
            class="muted"
          >
            等待首次采样
          </p><div
            v-for="(item,index) in data.history.slice(0,20)"
            :key="index"
            class="list-row"
          >
            <span>{{ data.services.find(s=>s.id===item.service)?.name }} · {{ labels[item.state] }}{{ item.maintained ? '（维护期间）' : '' }}</span><time>{{ time(item.at) }}</time>
          </div>
        </section>
      </template>
      <section
        v-else-if="panel==='login'"
        class="login card"
      >
        <h1>管理员登录</h1><p class="muted">
          使用监控系统独立账号
        </p><form @submit.prevent="login">
          <label>账号<input
            v-model="username"
            autocomplete="username"
            required
          ></label><label>密码<input
            v-model="password"
            type="password"
            autocomplete="current-password"
            required
          ></label><button
            class="primary"
            :disabled="busy"
          >
            登录
          </button>
        </form>
      </section>
      <template v-else-if="admin">
        <h1>维护与通知</h1><div class="cards">
          <article
            v-for="channel in data.channels"
            :key="channel.channel"
            class="card"
          >
            <h2>{{ labels[channel.channel] }}</h2><p>{{ channel.configured ? '已配置' : '待配置' }}</p><span
              v-for="count in channel.counts"
              :key="count.state"
              class="metric"
            >{{ deliveryLabels[count.state] }} {{ count.count }}</span>
          </article>
        </div>
        <section class="card">
          <h2>创建维护安排</h2><p class="muted">
            仅管理维护公告和预期告警，不控制服务器启停。
          </p><form
            class="maintenance-form"
            @submit.prevent="createMaintenance"
          >
            <label>标题<input
              v-model="title"
              maxlength="120"
              required
            ></label><label>服务<select v-model="service"><option
              v-for="s in data.services"
              :key="s.id"
              :value="s.id"
            >{{ s.name }}</option></select></label><label>开始时间<input
              v-model="starts"
              type="datetime-local"
              required
            ></label><label>结束时间<input
              v-model="ends"
              type="datetime-local"
              required
            ></label><button
              class="primary"
              :disabled="busy"
            >
              创建并通知
            </button>
          </form><pre
            class="preview"
            :class="{muted:!preview}"
            aria-live="polite"
          >{{ preview || previewError || '填写开始和结束时间后显示公告预览' }}</pre>
        </section>
        <section>
          <h2>维护窗口</h2><p
            v-if="!data.maintenance.length"
            class="muted"
          >
            暂无维护窗口
          </p><article
            v-for="item in data.maintenance"
            :key="item.id"
            class="list-row"
          >
            <div><strong>{{ item.title }}</strong><p>{{ time(item.starts) }} — {{ time(item.ends) }} · {{ labels[item.state] }}</p></div><span
              v-if="item.automatic"
              class="muted"
            >自动发布维护</span><div
              v-else-if="item.state==='active'"
              class="actions"
            >
              <button
                :disabled="busy"
                @click="changeMaintenance(item,'postpone')"
              >
                延长 30 分钟
              </button><button
                :disabled="busy"
                @click="changeMaintenance(item,'cancel')"
              >
                取消
              </button><button
                :disabled="busy"
                @click="changeMaintenance(item,'end')"
              >
                确认恢复
              </button>
            </div>
          </article>
        </section>
        <section>
          <h2>投递记录</h2><div class="table-wrap">
            <table>
              <thead><tr><th>时间 / 通道</th><th>内容</th><th>状态</th><th>操作</th></tr></thead><tbody>
                <tr
                  v-for="item in data.deliveries"
                  :key="item.id"
                >
                  <td>{{ time(item.created) }}<br>{{ labels[item.channel] }}</td><td class="message">
                    {{ item.content }}
                  </td><td>{{ deliveryLabels[item.state] }}<small v-if="item.state==='sent' && item.sent">发送于 {{ time(item.sent) }}</small><small v-if="item.error">{{ item.error }}</small></td><td>
                    <button
                      v-if="item.state==='dead' && (!item.expires || item.expires > Date.now())"
                      :disabled="busy"
                      @click="action(async()=>{await api(`admin/deliveries/${item.id}/retry`,{})})"
                    >
                      重试
                    </button><button
                      v-if="item.state==='dead'"
                      :disabled="busy"
                      @click="discardDelivery(item)"
                    >
                      放弃
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>
    </main><footer>Yumpoo · 异机监测独立运行</footer>
  </div>
</template>

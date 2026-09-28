<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElTable, ElTableColumn, ElSelect, ElOption, ElInput, ElButton,
  ElDatePicker, ElPagination, ElDialog, ElMessage, ElTag
} from 'element-plus'
import { Search as SearchIcon, Refresh as RefreshIcon } from '@element-plus/icons-vue'
import { api } from '../api'

interface OperationLog {
  id: number
  operationType: string
  targetType: string
  targetId: number | null
  operator: string
  details: string
  ipAddress: string | null
  result: string
  operationTime: string
}

interface LogPageData {
  content: OperationLog[]
  totalElements: number
  totalPages: number
  currentPage: number
  pageSize: number
  viewToken: string
  viewCreatedAt: string
  newSinceView: number
}

const route = useRoute()
const router = useRouter()

// 表单中正在编辑、尚未点击“搜索”生效的筛选值
const filters = ref({
  operationType: '',
  targetType: '',
  operator: '',
  keyword: '',
  startDate: '',
  endDate: ''
})

const page = ref(1)
const size = ref(10)
const logs = ref<OperationLog[]>([])
const total = ref(0)
const loading = ref(false)

// 查询视图令牌：一次搜索会话内翻页/改每页条数复用，搜索或刷新后由后端换新
const viewToken = ref('')
const viewCreatedAt = ref('')

// 详情弹窗：始终按行 ID 单独拉取，确保打开的就是点击的那条日志
const detailVisible = ref(false)
const detailLoading = ref(false)
const currentDetail = ref<OperationLog | null>(null)

const operationTypes = [
  'CREATE', 'UPDATE', 'DELETE', 'UPDATE_STATUS', 'UPDATE_AVAILABILITY',
  'MATCH', 'BATCH_MATCH',
  'PLAN_CREATE', 'PLAN_SUBMIT', 'PLAN_REVIEW_APPROVE', 'PLAN_REVIEW_REJECT',
  'PLAN_ADMIN_REJECT', 'PLAN_PUBLISH', 'PLAN_DISCARD', 'PLAN_INVALIDATE',
  'PLAN_ASSIGN', 'PLAN_ASSIGN_FAIL'
]

const targetTypes = [
  'COURSE', 'INSTRUCTOR', 'ASSIGNMENT', 'MATCH_RECORD', 'INDUSTRY',
  'MATCH_PLAN', 'WEIGHT_CONFIG'
]

const getOperationTypeLabel = (type?: string) => {
  const map: Record<string, string> = {
    CREATE: '创建',
    UPDATE: '更新',
    DELETE: '删除',
    UPDATE_STATUS: '状态变更',
    UPDATE_AVAILABILITY: '可用状态变更',
    MATCH: '匹配',
    BATCH_MATCH: '批量匹配',
    PLAN_CREATE: '方案创建',
    PLAN_SUBMIT: '方案提交',
    PLAN_REVIEW_APPROVE: '评审通过',
    PLAN_REVIEW_REJECT: '评审驳回',
    PLAN_ADMIN_REJECT: '管理员驳回',
    PLAN_PUBLISH: '方案发布',
    PLAN_DISCARD: '方案丢弃',
    PLAN_INVALIDATE: '方案失效',
    PLAN_ASSIGN: '方案指派',
    PLAN_ASSIGN_FAIL: '指派失败'
  }
  return type ? (map[type] || type) : '-'
}

const getTargetTypeLabel = (type?: string) => {
  const map: Record<string, string> = {
    COURSE: '课程',
    INSTRUCTOR: '讲师',
    ASSIGNMENT: '指派',
    MATCH_RECORD: '匹配记录',
    INDUSTRY: '行业',
    MATCH_PLAN: '推演方案',
    WEIGHT_CONFIG: '权重配置'
  }
  return type ? (map[type] || type) : '-'
}

const getResultTag = (result?: string) => {
  if (result === 'SUCCESS') return { text: '成功', type: 'success' as const }
  if (result === 'FAIL' || result === 'FAILED') return { text: '失败', type: 'danger' as const }
  return { text: result || '-', type: 'info' as const }
}

const formatTime = (iso?: string) => {
  if (!iso) return '-'
  return iso.replace('T', ' ').replace(/\.\d+$/, '')
}

const viewCreatedText = computed(() => formatTime(viewCreatedAt.value))

// ---------- 筛选值在 URL 与本地（按登录用户隔离）间持久化 ----------

const currentUsername = () => {
  try {
    const user = JSON.parse(localStorage.getItem('user') || 'null')
    return user?.username || 'anonymous'
  } catch {
    return 'anonymous'
  }
}
const storageKey = () => `logsView:${currentUsername()}`

interface PersistedState {
  operationType?: string
  targetType?: string
  operator?: string
  keyword?: string
  startDate?: string
  endDate?: string
  page?: string
  size?: string
}

const toQuery = (): PersistedState => {
  const q: PersistedState = { page: String(page.value), size: String(size.value) }
  for (const [k, v] of Object.entries(filters.value)) {
    if (v) q[k as keyof PersistedState] = v
  }
  return q
}

const applyQuery = (q: PersistedState) => {
  filters.value = {
    operationType: q.operationType || '',
    targetType: q.targetType || '',
    operator: q.operator || '',
    keyword: q.keyword || '',
    startDate: q.startDate || '',
    endDate: q.endDate || ''
  }
  page.value = Math.max(1, parseInt(q.page || '1', 10) || 1)
  size.value = [10, 20, 50].includes(parseInt(q.size || '10', 10))
    ? parseInt(q.size || '10', 10)
    : 10
}

// URL 查询参数 / localStorage 的值统一归一化，避免数组型 query 值污染筛选
const normalizeState = (raw: Record<string, unknown>): PersistedState => {
  const str = (v: unknown): string | undefined =>
    typeof v === 'string' && v !== '' ? v : undefined
  return {
    operationType: str(raw.operationType),
    targetType: str(raw.targetType),
    operator: str(raw.operator),
    keyword: str(raw.keyword),
    startDate: str(raw.startDate),
    endDate: str(raw.endDate),
    page: str(raw.page),
    size: str(raw.size)
  }
}

let urlSignature = ''
const sign = (q: PersistedState) => JSON.stringify(q)

// 将当前筛选与分页写入 URL（不触发重复查询）并按用户持久化
const syncUrl = () => {
  const q = toQuery()
  urlSignature = sign(q)
  router.replace({ path: '/logs', query: q as Record<string, string> })
  localStorage.setItem(storageKey(), JSON.stringify(q))
}

const loadStored = (): PersistedState => {
  try {
    return JSON.parse(localStorage.getItem(storageKey()) || '{}') as PersistedState
  } catch {
    return {}
  }
}

// ---------- 数据查询 ----------

let requestSeq = 0

const buildParams = (useView: boolean) => {
  const params: Record<string, string | number> = {
    page: page.value - 1,
    size: size.value
  }
  const f = filters.value
  if (f.operationType) params.operationType = f.operationType
  if (f.targetType) params.targetType = f.targetType
  if (f.operator.trim()) params.operator = f.operator.trim()
  if (f.keyword.trim()) params.keyword = f.keyword.trim()
  if (f.startDate) params.startDate = f.startDate
  if (f.endDate) params.endDate = f.endDate
  if (useView && viewToken.value) params.viewToken = viewToken.value
  return params
}

/**
 * @param mode fresh  建立新查询视图（搜索/重置/刷新/浏览器前进后退/首次进入）
 *             sticky 复用当前视图（翻页/改变每页条数）
 */
const fetchLogs = async (mode: 'fresh' | 'sticky', hint?: 'refresh' | 'search' | 'reset') => {
  // 开始晚于结束在前端先给一次明确提示，后端也会兜底返回 400
  if (filters.value.startDate && filters.value.endDate
      && filters.value.startDate > filters.value.endDate) {
    ElMessage.error('开始日期不能晚于结束日期，请调整日期范围后重新查询')
    return
  }

  const seq = ++requestSeq
  loading.value = true
  const params = buildParams(mode === 'sticky')
  const previousToken = mode === 'fresh' ? viewToken.value : ''
  if (mode === 'fresh') {
    params.previousToken = previousToken
  }
  try {
    const response = await api.get('/logs', { params })
    if (seq !== requestSeq) return // 只采用最后一次请求的结果，防止乱序覆盖
    const data = (response.data as { data: LogPageData }).data
    logs.value = data.content || []
    total.value = data.totalElements || 0
    viewToken.value = data.viewToken || ''
    viewCreatedAt.value = data.viewCreatedAt || ''

    if (mode === 'fresh') {
      if (hint === 'refresh') {
        if (data.newSinceView > 0) {
          ElMessage.success(`查询视图已更新，新增 ${data.newSinceView} 条匹配记录，共 ${data.totalElements} 条`)
        } else {
          ElMessage.info('查询视图已刷新，暂无新的匹配记录')
        }
      } else if (hint === 'reset') {
        ElMessage.success('筛选已清空，已回到第一页')
      }
    }
  } catch (error: any) {
    if (seq !== requestSeq) return
    const message = error.response?.data?.message || '获取操作日志失败'
    ElMessage.error(message)
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

// 点击“搜索”：新视图、第一页
const handleSearch = () => {
  page.value = 1
  syncUrl()
  fetchLogs('fresh', 'search')
}

// 清空筛选：回到第一页并建立新视图
const handleReset = () => {
  filters.value = {
    operationType: '', targetType: '', operator: '', keyword: '',
    startDate: '', endDate: ''
  }
  page.value = 1
  syncUrl()
  fetchLogs('fresh', 'reset')
}

// 主动刷新：建立新视图，回到第一页查看新增记录与更新后的总数
const handleRefresh = () => {
  page.value = 1
  syncUrl()
  fetchLogs('fresh', 'refresh')
}

// 翻页：同一查询视图内移动，新写入的日志不会顶动结果
const handlePageChange = () => {
  syncUrl()
  fetchLogs('sticky')
}

// 改变每页条数：从当前查询视图的第一页重新计算
const handleSizeChange = () => {
  page.value = 1
  syncUrl()
  fetchLogs('sticky')
}

// 点击行查看详情：用该行日志 ID 单独查询，避免被新插入的日志带偏
const openDetail = async (row: OperationLog) => {
  detailVisible.value = true
  detailLoading.value = true
  currentDetail.value = row
  try {
    const response = await api.get(`/logs/${row.id}`)
    const data = (response.data as { data: OperationLog }).data
    if (data && data.id === row.id) {
      currentDetail.value = data
    }
  } catch {
    ElMessage.error('获取日志详情失败')
  } finally {
    detailLoading.value = false
  }
}

// ---------- 浏览器前进/后退与首次进入 ----------
//
// URL 是筛选含义的唯一外部来源：浏览器返回时按 URL 中的筛选与页码重建查询视图；
// URL 没有参数时（如重新登录后首次进入）恢复该用户上次使用的筛选。

const persist = () => {
  localStorage.setItem(storageKey(), JSON.stringify(toQuery()))
}

const initFromLocation = () => {
  const hasQuery = Object.keys(route.query).length > 0
  const state = hasQuery
    ? normalizeState(route.query as Record<string, unknown>)
    : normalizeState(loadStored() as Record<string, unknown>)
  applyQuery(state)
  syncUrl()
  fetchLogs('fresh')
}

let popListening = false
onMounted(() => {
  initFromLocation()
  window.addEventListener('popstate', onPopState)
  popListening = true
})

onBeforeUnmount(() => {
  if (popListening) window.removeEventListener('popstate', onPopState)
})

function onPopState() {
  // 浏览器前进/后退：以 URL 为准建立新视图，筛选值与结果含义保持一致
  applyQuery(normalizeState(route.query as Record<string, unknown>))
  urlSignature = sign(toQuery())
  persist()
  fetchLogs('fresh')
}

// 兜底：监听路由 query 的外部变化（popstate 之外的路径，如历史 API 操作），与已处理的请求去重
watch(() => route.fullPath, () => {
  if (sign(toQuery()) === urlSignature) return
  initFromLocation()
})
</script>

<template>
  <div class="space-y-6">
    <div class="bg-white rounded-xl shadow-sm p-6">
      <div class="flex items-center justify-between mb-6">
        <div>
          <h3 class="font-semibold text-gray-800">操作日志</h3>
          <p class="text-xs text-gray-400 mt-1">
            关键词可匹配操作人、操作详情和目标编号；一次搜索内翻页结果保持稳定，点击刷新查看新写入日志
          </p>
        </div>
        <ElButton :loading="loading" @click="handleRefresh">
          <RefreshIcon class="w-4 h-4" />
          刷新
        </ElButton>
      </div>

      <div class="grid grid-cols-1 md:grid-cols-3 lg:grid-cols-6 gap-4 mb-4">
        <ElSelect v-model="filters.operationType" placeholder="操作类型" clearable style="width: 100%">
          <ElOption v-for="type in operationTypes" :key="type" :value="type" :label="getOperationTypeLabel(type)" />
        </ElSelect>

        <ElSelect v-model="filters.targetType" placeholder="目标类型" clearable style="width: 100%">
          <ElOption v-for="type in targetTypes" :key="type" :value="type" :label="getTargetTypeLabel(type)" />
        </ElSelect>

        <ElInput v-model="filters.operator" placeholder="操作人" clearable />

        <ElInput v-model="filters.keyword" placeholder="关键词：操作人/详情/编号" clearable :prefix-icon="SearchIcon" />

        <ElDatePicker v-model="filters.startDate" type="date" placeholder="开始日期" value-format="YYYY-MM-DD" style="width: 100%" />

        <ElDatePicker v-model="filters.endDate" type="date" placeholder="结束日期" value-format="YYYY-MM-DD" style="width: 100%" />
      </div>

      <div class="flex items-center gap-2">
        <ElButton type="primary" @click="handleSearch">
          <SearchIcon class="w-4 h-4" />
          搜索
        </ElButton>
        <ElButton @click="handleReset">重置</ElButton>
      </div>
    </div>

    <div class="bg-white rounded-xl shadow-sm p-6">
      <div v-if="viewCreatedAt" class="mb-3 text-xs text-gray-400">
        当前结果为 {{ viewCreatedText }} 建立的查询视图（共 {{ total }} 条）：翻页期间新写入的日志不会改变本页顺序与总数，点击“刷新”后才纳入新记录
      </div>

      <ElTable
        :data="logs"
        v-loading="loading"
        border
        stripe
        row-key="id"
        empty-text="没有符合条件的操作日志"
        @row-click="(row: unknown) => openDetail(row as OperationLog)"
        class="cursor-pointer"
      >
        <ElTableColumn prop="id" label="ID" width="90" />
        <ElTableColumn prop="operationType" label="操作类型" width="130">
          <template #default="{ row }">{{ getOperationTypeLabel(row.operationType) }}</template>
        </ElTableColumn>
        <ElTableColumn prop="targetType" label="目标类型" width="110">
          <template #default="{ row }">{{ getTargetTypeLabel(row.targetType) }}</template>
        </ElTableColumn>
        <ElTableColumn label="目标编号" width="90">
          <template #default="{ row }">{{ row.targetId ?? '-' }}</template>
        </ElTableColumn>
        <ElTableColumn prop="operator" label="操作人" width="110">
          <template #default="{ row }">{{ row.operator || '-' }}</template>
        </ElTableColumn>
        <ElTableColumn prop="details" label="操作详情" show-overflow-tooltip min-width="220">
          <template #default="{ row }">{{ row.details || '-' }}</template>
        </ElTableColumn>
        <ElTableColumn label="结果" width="90">
          <template #default="{ row }">
            <ElTag :type="getResultTag(row.result).type" size="small">
              {{ getResultTag(row.result).text }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作时间" width="170">
          <template #default="{ row }">{{ formatTime(row.operationTime) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <ElButton type="primary" link size="small" @click.stop="openDetail(row as OperationLog)">详情</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>

      <div class="mt-4 flex items-center justify-end">
        <ElPagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </div>

    <ElDialog v-model="detailVisible" title="操作日志详情" width="600px">
      <div v-loading="detailLoading" class="space-y-4">
        <div class="grid grid-cols-2 gap-4 text-sm">
          <div>
            <p class="text-xs text-gray-400">日志 ID</p>
            <p class="font-medium">{{ currentDetail?.id ?? '-' }}</p>
          </div>
          <div>
            <p class="text-xs text-gray-400">操作类型</p>
            <p class="font-medium">{{ getOperationTypeLabel(currentDetail?.operationType) }}</p>
          </div>
          <div>
            <p class="text-xs text-gray-400">目标类型</p>
            <p class="font-medium">{{ getTargetTypeLabel(currentDetail?.targetType) }}</p>
          </div>
          <div>
            <p class="text-xs text-gray-400">目标编号</p>
            <p class="font-medium">{{ currentDetail?.targetId ?? '-' }}</p>
          </div>
          <div>
            <p class="text-xs text-gray-400">操作人</p>
            <p class="font-medium">{{ currentDetail?.operator || '-' }}</p>
          </div>
          <div>
            <p class="text-xs text-gray-400">结果</p>
            <ElTag :type="getResultTag(currentDetail?.result).type" size="small">
              {{ getResultTag(currentDetail?.result).text }}
            </ElTag>
          </div>
          <div>
            <p class="text-xs text-gray-400">操作时间</p>
            <p class="font-medium">{{ formatTime(currentDetail?.operationTime) }}</p>
          </div>
          <div>
            <p class="text-xs text-gray-400">IP 地址</p>
            <p class="font-medium">{{ currentDetail?.ipAddress || '-' }}</p>
          </div>
        </div>
        <div>
          <p class="text-xs text-gray-400 mb-1">操作详情</p>
          <div class="bg-gray-50 rounded-lg p-3 text-sm text-gray-700 whitespace-pre-wrap break-all">
            {{ currentDetail?.details || '-' }}
          </div>
        </div>
      </div>
      <template #footer>
        <ElButton @click="detailVisible = false">关闭</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElTable, ElTableColumn, ElSelect, ElOption, ElInput, ElButton,
  ElDatePicker, ElPagination, ElDialog, ElDescriptions, ElDescriptionsItem, ElTag, ElMessage
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
  ipAddress: string
  result: string
  operationTime: string
}

interface LogPageData {
  content: OperationLog[]
  totalElements: number
  totalPages: number
  currentPage: number
  pageSize: number
  viewId: number
  viewCreated: boolean
  newCount: number | null
}

interface Filters {
  operationType: string
  targetType: string
  operator: string
  keyword: string
  startDate: string
  endDate: string
}

const EMPTY_FILTERS: Filters = {
  operationType: '',
  targetType: '',
  operator: '',
  keyword: '',
  startDate: '',
  endDate: ''
}

const route = useRoute()
const router = useRouter()

const logs = ref<OperationLog[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const filters = ref<Filters>({ ...EMPTY_FILTERS })
const loading = ref(false)
/** 当前查询视图 ID：一次搜索会话内翻页/改每页条数都复用，结果不被新日志顶动 */
const viewId = ref<number | null>(null)

const operationTypes = [
  'CREATE', 'UPDATE', 'DELETE', 'UPDATE_STATUS', 'UPDATE_AVAILABILITY',
  'MATCH', 'BATCH_MATCH', 'EXPORT',
  'PLAN_CREATE', 'PLAN_SUBMIT', 'PLAN_REVIEW_APPROVE', 'PLAN_REVIEW_REJECT',
  'PLAN_ADMIN_REJECT', 'PLAN_PUBLISH', 'PLAN_DISCARD', 'PLAN_INVALIDATE',
  'PLAN_ASSIGN', 'PLAN_ASSIGN_FAIL'
]
const targetTypes = ['COURSE', 'INSTRUCTOR', 'ASSIGNMENT', 'WEIGHT_CONFIG', 'MATCH_RECORD', 'INDUSTRY', 'MATCH_PLAN']

const OPERATION_TYPE_LABELS: Record<string, string> = {
  CREATE: '创建',
  UPDATE: '更新',
  DELETE: '删除',
  UPDATE_STATUS: '状态变更',
  UPDATE_AVAILABILITY: '可用状态变更',
  MATCH: '匹配',
  BATCH_MATCH: '批量匹配',
  EXPORT: '导出',
  PLAN_CREATE: '方案创建',
  PLAN_SUBMIT: '方案提交评审',
  PLAN_REVIEW_APPROVE: '专业评审通过',
  PLAN_REVIEW_REJECT: '专业评审驳回',
  PLAN_ADMIN_REJECT: '管理员驳回',
  PLAN_PUBLISH: '方案发布',
  PLAN_DISCARD: '方案废弃',
  PLAN_INVALIDATE: '方案失效',
  PLAN_ASSIGN: '正式指派',
  PLAN_ASSIGN_FAIL: '指派失败'
}

const TARGET_TYPE_LABELS: Record<string, string> = {
  COURSE: '课程',
  INSTRUCTOR: '讲师',
  ASSIGNMENT: '指派记录',
  WEIGHT_CONFIG: '权重配置',
  MATCH_RECORD: '匹配记录',
  INDUSTRY: '行业',
  MATCH_PLAN: '推演方案'
}

const RESULT_LABELS: Record<string, string> = {
  SUCCESS: '成功',
  FAIL: '失败',
  FAILED: '失败'
}

const getOperationTypeLabel = (type?: string) => (type && OPERATION_TYPE_LABELS[type]) || type || '-'
const getTargetTypeLabel = (type?: string) => (type && TARGET_TYPE_LABELS[type]) || type || '-'
const getResultLabel = (result?: string) => (result && RESULT_LABELS[result]) || result || '-'

const formatTime = (value?: string) => {
  if (!value) return '-'
  // 后端返回 ISO-8601，统一展示为 yyyy-MM-dd HH:mm:ss
  return value.replace('T', ' ').replace(/\.\d+.*$/, '')
}

// ---------------- 详情弹窗：始终按所点击行的日志 ID 拉取，新日志插入不影响 ----------------

const detailVisible = ref(false)
const detailLoading = ref(false)
const currentDetail = ref<OperationLog | null>(null)

const openDetail = async (row: OperationLog) => {
  detailVisible.value = true
  detailLoading.value = true
  currentDetail.value = null
  try {
    const res = await api.get(`/logs/${row.id}`)
    currentDetail.value = res.data.data
  } catch {
    ElMessage.error('获取日志详情失败')
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

// ---------------- 查询视图（快照） ----------------

const buildParams = (useView: boolean, includeFromView = false) => {
  const params: Record<string, string | number> = {
    page: page.value - 1,
    size: size.value
  }
  if (useView && viewId.value != null) {
    params.viewId = viewId.value
  } else {
    for (const [key, value] of Object.entries(filters.value)) {
      if (value !== '' && value !== null && value !== undefined) {
        params[key] = value
      }
    }
    if (includeFromView && viewId.value != null) {
      params.fromViewId = viewId.value
    }
  }
  return params
}

const syncUrl = () => {
  const query: Record<string, string | number> = {}
  if (viewId.value != null) query.vid = viewId.value
  if (page.value > 1) query.page = page.value
  if (size.value !== 10) query.size = size.value
  for (const [key, value] of Object.entries(filters.value)) {
    if (value) query[key] = value
  }
  // 翻页、浏览器前进后退、重新登录后，URL 都能还原出同一查询视图与筛选含义
  router.replace({ path: route.path, query })
}

const applyResponse = (data: LogPageData) => {
  viewId.value = data.viewId
  total.value = data.totalElements
  size.value = data.pageSize
  // 服务端可能因页码越界做过收敛，回写为一页制页码
  page.value = data.currentPage + 1
  logs.value = data.content || []
  if (data.viewCreated && data.newCount != null) {
    if (data.newCount > 0) {
      ElMessage.success(`已刷新：本次查询共 ${data.totalElements} 条，新增 ${data.newCount} 条匹配日志`)
    } else {
      ElMessage.info(`已刷新：暂无新增匹配日志，共 ${data.totalElements} 条`)
    }
  }
}

/**
 * 加载日志。
 * - reuse=true：翻页/改变每页条数，复用当前查询视图，结果集保持稳定；
 * - reuse=false：搜索或刷新，按当前筛选建立新的查询视图。
 */
const loadLogs = async (reuse: boolean, refresh = false) => {
  loading.value = true
  try {
    const res = await api.get('/logs', { params: buildParams(reuse, refresh) })
    applyResponse(res.data.data as LogPageData)
    syncUrl()
  } catch (error: any) {
    if (!reuse || error?.response?.status !== 404) {
      ElMessage.error(error?.response?.data?.message || '获取操作日志失败')
      return
    }
    // 视图过期（长时间未访问被清理）：按 URL 中的筛选条件重建，不会静默切换成别的结果
    viewId.value = null
    try {
      const res = await api.get('/logs', { params: buildParams(false) })
      applyResponse(res.data.data as LogPageData)
      syncUrl()
      ElMessage.info('查询视图已过期，已按当前筛选条件重新建立')
    } catch (retryError: any) {
      ElMessage.error(retryError?.response?.data?.message || '获取操作日志失败')
    }
  } finally {
    loading.value = false
  }
}

/** 执行一次新搜索：建立新的查询视图并回到第一页 */
const handleSearch = () => {
  if (filters.value.startDate && filters.value.endDate
      && filters.value.startDate > filters.value.endDate) {
    ElMessage.warning('开始日期不能晚于结束日期，请调整查询时间范围')
    return
  }
  page.value = 1
  viewId.value = null
  loadLogs(false)
}

/** 清空筛选：建立无筛选的新视图，回到第一页 */
const handleReset = () => {
  filters.value = { ...EMPTY_FILTERS }
  page.value = 1
  viewId.value = null
  loadLogs(false)
}

/** 用户主动刷新：基于最新数据建立新视图，并明确提示新增记录与更新后的总数 */
const handleRefresh = () => {
  page.value = 1
  loadLogs(false, true)
}

const handlePageChange = () => {
  loadLogs(true)
}

/** 改变每页条数：在当前查询视图内从第一页重新计算，视图不变 */
const handleSizeChange = () => {
  page.value = 1
  loadLogs(true)
}

// ---------------- 与 URL / 浏览器前进后退保持一致 ----------------

const readFiltersFromRoute = (): Filters => ({
  operationType: (route.query.operationType as string) || '',
  targetType: (route.query.targetType as string) || '',
  operator: (route.query.operator as string) || '',
  keyword: (route.query.keyword as string) || '',
  startDate: (route.query.startDate as string) || '',
  endDate: (route.query.endDate as string) || ''
})

/** 当前 URL 表达的查询状态签名；与界面状态一致时不重复请求（含自身 syncUrl 触发） */
const routeSignature = () => JSON.stringify([
  route.query.vid || '',
  route.query.page || '',
  route.query.size || '',
  route.query.operationType || '',
  route.query.targetType || '',
  route.query.operator || '',
  route.query.keyword || '',
  route.query.startDate || '',
  route.query.endDate || ''
])

const stateSignature = () => JSON.stringify([
  viewId.value ?? '',
  page.value > 1 ? String(page.value) : '',
  size.value !== 10 ? String(size.value) : '',
  filters.value.operationType,
  filters.value.targetType,
  filters.value.operator,
  filters.value.keyword,
  filters.value.startDate,
  filters.value.endDate
])

let initialized = false
onMounted(() => {
  filters.value = readFiltersFromRoute()
  size.value = Number(route.query.size) || 10
  page.value = Number(route.query.page) || 1
  const urlViewId = Number(route.query.vid)
  viewId.value = Number.isFinite(urlViewId) && urlViewId > 0 ? urlViewId : null
  initialized = true
  // 带 vid 的 URL（浏览器返回/重新登录）直接恢复原查询视图；否则建立新视图
  loadLogs(viewId.value != null)
})

// 浏览器前进/后退：URL 变化时按 URL 中的视图与筛选恢复，不套用当前页面状态
watch(() => route.fullPath, () => {
  if (!initialized) return
  // syncUrl 自身或页码收敛导致的同值变化不需要再次加载
  if (routeSignature() === stateSignature()) return

  filters.value = readFiltersFromRoute()
  size.value = Number(route.query.size) || 10
  page.value = Number(route.query.page) || 1
  const rawVid = Number(route.query.vid)
  viewId.value = Number.isFinite(rawVid) && rawVid > 0 ? rawVid : null
  loadLogs(viewId.value != null)
})
</script>

<template>
  <div class="space-y-6">
    <div class="bg-white rounded-xl shadow-sm p-6">
      <div class="flex items-center justify-between mb-6">
        <h3 class="font-semibold text-gray-800">操作日志</h3>
        <ElButton :loading="loading" @click="handleRefresh">
          <RefreshIcon class="w-4 h-4" />
          刷新
        </ElButton>
      </div>

      <div class="grid grid-cols-1 md:grid-cols-3 lg:grid-cols-6 gap-4 mb-4">
        <ElSelect v-model="filters.operationType" placeholder="操作类型" clearable filterable style="width: 100%">
          <ElOption v-for="type in operationTypes" :key="type" :value="type" :label="getOperationTypeLabel(type)" />
        </ElSelect>

        <ElSelect v-model="filters.targetType" placeholder="目标类型" clearable filterable style="width: 100%">
          <ElOption v-for="type in targetTypes" :key="type" :value="type" :label="getTargetTypeLabel(type)" />
        </ElSelect>

        <ElInput v-model="filters.operator" placeholder="操作人" clearable />

        <ElInput v-model="filters.keyword" placeholder="关键词（操作人/详情/目标编号）" clearable :prefix-icon="SearchIcon" />

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
      <ElTable
        :data="logs"
        v-loading="loading"
        border
        stripe
        class="cursor-pointer"
        @row-click="openDetail"
      >
        <ElTableColumn prop="id" label="ID" width="90" />
        <ElTableColumn prop="operationType" label="操作类型" width="140">
          <template #default="{ row }">{{ getOperationTypeLabel(row.operationType) }}</template>
        </ElTableColumn>
        <ElTableColumn prop="targetType" label="目标类型" width="110">
          <template #default="{ row }">{{ getTargetTypeLabel(row.targetType) }}</template>
        </ElTableColumn>
        <ElTableColumn label="目标编号" width="100">
          <template #default="{ row }">{{ row.targetId ?? '-' }}</template>
        </ElTableColumn>
        <ElTableColumn prop="operator" label="操作人" width="110" />
        <ElTableColumn prop="details" label="操作详情" show-overflow-tooltip>
          <template #default="{ row }">{{ row.details || '-' }}</template>
        </ElTableColumn>
        <ElTableColumn prop="result" label="结果" width="90">
          <template #default="{ row }">
            <ElTag v-if="row.result" :type="row.result === 'SUCCESS' ? 'success' : 'danger'" size="small">
              {{ getResultLabel(row.result) }}
            </ElTag>
            <span v-else>-</span>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="operationTime" label="操作时间" width="180">
          <template #default="{ row }">{{ formatTime(row.operationTime) }}</template>
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

    <ElDialog v-model="detailVisible" title="操作日志详情" width="640px">
      <div v-loading="detailLoading">
        <ElDescriptions v-if="currentDetail" :column="1" border>
          <ElDescriptionsItem label="日志 ID">{{ currentDetail.id }}</ElDescriptionsItem>
          <ElDescriptionsItem label="操作类型">{{ getOperationTypeLabel(currentDetail.operationType) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="目标类型">{{ getTargetTypeLabel(currentDetail.targetType) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="目标编号">{{ currentDetail.targetId ?? '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="操作人">{{ currentDetail.operator || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="操作详情">{{ currentDetail.details || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="结果">
            <ElTag :type="currentDetail.result === 'SUCCESS' ? 'success' : 'danger'" size="small">
              {{ getResultLabel(currentDetail.result) }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="IP 地址">{{ currentDetail.ipAddress || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="操作时间">{{ formatTime(currentDetail.operationTime) }}</ElDescriptionsItem>
        </ElDescriptions>
      </div>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ProductApiError,
  confirmProductImport,
  createProductExport,
  createProductImport,
  downloadProductExport,
  downloadProductImportErrors,
  downloadProductImportTemplate,
  getProductExport,
  getProductImport,
  type ProductExportJob,
  type ProductFilter,
  type ProductImportJob,
} from '../../api/products'

type CenterMode = 'import' | 'export'

const route = useRoute()
const router = useRouter()
const mode = ref<CenterMode>(route.query.mode === 'export' || route.query.exportJobId ? 'export' : 'import')
const selectedFile = ref<File | null>(null)
const importJob = ref<ProductImportJob | null>(null)
const exportJob = ref<ProductExportJob | null>(null)
const uploading = ref(false)
const confirming = ref(false)
const exporting = ref(false)
const downloading = ref(false)
const errorMessage = ref('')
const importPollingExpired = ref(false)
const exportPollingExpired = ref(false)
const importRefreshNeeded = ref(false)
const exportRefreshNeeded = ref(false)
const confirmKey = ref('')
const exportCreateKey = ref('')
const exportFilterSnapshot = ref<ProductFilter | null>(null)
const exportFilter = reactive<ProductFilter>({
  keyword: '',
  barcode: '',
  brandId: '',
  categoryId: '',
  status: undefined,
})
let importTimer: ReturnType<typeof setTimeout> | undefined
let exportTimer: ReturnType<typeof setTimeout> | undefined
let importPollCount = 0
let exportPollCount = 0
let importRequestSequence = 0
let exportRequestSequence = 0
let disposed = false

const importStatus = computed(() => importJob.value?.status ?? '')
const importCanConfirm = computed(() => importStatus.value === 'PREFLIGHT_READY')
const importIsPolling = computed(() => ['UPLOADED', 'CONFIRMED', 'RUNNING'].includes(importStatus.value))
const exportIsPolling = computed(() => ['QUEUED', 'RUNNING'].includes(exportJob.value?.status ?? ''))
const exportFilterLocked = computed(() => exporting.value || Boolean(exportCreateKey.value))
const importStage = computed(() => {
  if (!importJob.value) return selectedFile.value ? 1 : 0
  if (['UPLOADED'].includes(importStatus.value)) return 1
  if (importStatus.value === 'PREFLIGHT_READY') return 2
  if (['CONFIRMED', 'RUNNING'].includes(importStatus.value)) return 3
  return 4
})
const importStatusLabel = computed(() => ({
  UPLOADED: '正在预检',
  PREFLIGHT_READY: '预检完成，等待确认',
  CONFIRMED: '已确认，等待执行',
  RUNNING: '正在导入',
  SUCCEEDED: '导入成功',
  PARTIALLY_SUCCEEDED: '部分成功',
  FAILED: '导入失败',
}[importStatus.value] ?? '等待上传'))
const exportStatusLabel = computed(() => ({
  QUEUED: '等待生成',
  RUNNING: '正在生成导出文件',
  SUCCEEDED: '导出文件已生成',
  FAILED: '导出失败',
}[exportJob.value?.status ?? ''] ?? '尚未创建导出任务'))

function idempotencyKey() {
  return globalThis.crypto?.randomUUID?.()
    ?? `product-job-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function apiMessage(error: unknown, fallback: string) {
  return error instanceof ProductApiError ? error.message : fallback
}

function replaceQuery(values: Record<string, string | undefined>) {
  const query = { ...route.query }
  for (const [key, value] of Object.entries(values)) {
    if (value) query[key] = value
    else delete query[key]
  }
  return router.replace({ query })
}

function setMode(next: CenterMode) {
  mode.value = next
  errorMessage.value = ''
  void replaceQuery({ mode: next === 'export' ? 'export' : undefined })
}

function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files?.[0] ?? null
  errorMessage.value = ''
}

function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.append(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

async function downloadTemplate() {
  downloading.value = true
  errorMessage.value = ''
  try {
    saveBlob(await downloadProductImportTemplate(), 'product-import-template.xlsx')
  } catch (error) {
    errorMessage.value = apiMessage(error, '模板下载失败，请稍后重试')
  } finally {
    downloading.value = false
  }
}

function clearImportTimer() {
  if (importTimer) clearTimeout(importTimer)
  importTimer = undefined
}

function clearExportTimer() {
  if (exportTimer) clearTimeout(exportTimer)
  exportTimer = undefined
}

function scheduleImportPoll() {
  clearImportTimer()
  if (disposed || !importIsPolling.value) return
  if (importPollCount >= 15) {
    importPollingExpired.value = true
    return
  }
  importTimer = setTimeout(() => void refreshImport(true), 2000)
}

function scheduleExportPoll() {
  clearExportTimer()
  if (disposed || !exportIsPolling.value) return
  if (exportPollCount >= 15) {
    exportPollingExpired.value = true
    return
  }
  exportTimer = setTimeout(() => void refreshExport(true), 2000)
}

async function refreshImport(fromPoll = false) {
  if (!importJob.value?.id) return
  const jobId = importJob.value.id
  const requestSequence = ++importRequestSequence
  if (fromPoll) importPollCount += 1
  errorMessage.value = ''
  try {
    const result = await getProductImport(jobId)
    if (disposed || requestSequence !== importRequestSequence || importJob.value?.id !== jobId) return
    importJob.value = result
    importRefreshNeeded.value = false
    scheduleImportPoll()
  } catch (error) {
    if (disposed || requestSequence !== importRequestSequence || importJob.value?.id !== jobId) return
    clearImportTimer()
    importRefreshNeeded.value = true
    errorMessage.value = apiMessage(error, '导入任务加载失败，请手动刷新')
  }
}

async function refreshExport(fromPoll = false) {
  if (!exportJob.value?.id) return
  const jobId = exportJob.value.id
  const requestSequence = ++exportRequestSequence
  if (fromPoll) exportPollCount += 1
  errorMessage.value = ''
  try {
    const result = await getProductExport(jobId)
    if (disposed || requestSequence !== exportRequestSequence || exportJob.value?.id !== jobId) return
    exportJob.value = result
    exportRefreshNeeded.value = false
    scheduleExportPoll()
  } catch (error) {
    if (disposed || requestSequence !== exportRequestSequence || exportJob.value?.id !== jobId) return
    clearExportTimer()
    exportRefreshNeeded.value = true
    errorMessage.value = apiMessage(error, '导出任务加载失败，请手动刷新')
  }
}

function manualRefreshImport() {
  importPollCount = 0
  importPollingExpired.value = false
  importRefreshNeeded.value = false
  void refreshImport()
}

function manualRefreshExport() {
  exportPollCount = 0
  exportPollingExpired.value = false
  exportRefreshNeeded.value = false
  void refreshExport()
}

async function uploadForPreflight() {
  if (!selectedFile.value || uploading.value || confirming.value || importIsPolling.value) return
  const file = selectedFile.value
  uploading.value = true
  errorMessage.value = ''
  importPollingExpired.value = false
  importPollCount = 0
  try {
    const accepted = await createProductImport(file)
    clearImportTimer()
    importRequestSequence += 1
    confirmKey.value = ''
    importJob.value = {
      id: accepted.id,
      filename: file.name,
      status: 'UPLOADED',
      createCount: 0,
      updateCount: 0,
      skipCount: 0,
      conflictCount: 0,
      failureCount: 0,
      errorObjectKey: null,
      createdAt: new Date().toISOString(),
      startedAt: null,
      finishedAt: null,
    }
    await replaceQuery({ importJobId: accepted.id })
    await refreshImport()
  } catch (error) {
    errorMessage.value = apiMessage(error, '上传预检失败，请检查文件后重试')
  } finally {
    uploading.value = false
  }
}

async function confirmImport() {
  if (!importJob.value || !importCanConfirm.value || confirming.value || uploading.value) return
  const jobId = importJob.value.id
  confirming.value = true
  errorMessage.value = ''
  importPollingExpired.value = false
  importPollCount = 0
  confirmKey.value ||= idempotencyKey()
  try {
    await confirmProductImport(jobId, confirmKey.value)
    if (disposed || importJob.value?.id !== jobId) return
    importJob.value = { ...importJob.value, status: 'CONFIRMED' }
    await refreshImport()
  } catch (error) {
    errorMessage.value = apiMessage(error, '确认导入失败，请稍后重试')
  } finally {
    confirming.value = false
  }
}

async function downloadErrors() {
  if (!importJob.value || downloading.value) return
  downloading.value = true
  errorMessage.value = ''
  try {
    saveBlob(
      await downloadProductImportErrors(importJob.value.id),
      `product-import-errors-${importJob.value.id}.xlsx`,
    )
  } catch (error) {
    errorMessage.value = apiMessage(error, '错误明细下载失败，请稍后重试')
  } finally {
    downloading.value = false
  }
}

async function createExport() {
  if (exporting.value || exportIsPolling.value) return
  exporting.value = true
  errorMessage.value = ''
  exportPollingExpired.value = false
  exportPollCount = 0
  if (!exportCreateKey.value) {
    exportCreateKey.value = idempotencyKey()
    exportFilterSnapshot.value = { ...exportFilter }
  }
  try {
    const accepted = await createProductExport(exportFilterSnapshot.value ?? {}, exportCreateKey.value)
    clearExportTimer()
    exportRequestSequence += 1
    exportCreateKey.value = ''
    exportFilterSnapshot.value = null
    exportJob.value = {
      id: accepted.id,
      status: 'QUEUED',
      objectKey: null,
      createdAt: new Date().toISOString(),
      finishedAt: null,
    }
    await replaceQuery({ exportJobId: accepted.id, mode: 'export' })
    await refreshExport()
  } catch (error) {
    errorMessage.value = apiMessage(error, '导出任务创建失败，请稍后重试')
  } finally {
    exporting.value = false
  }
}

function resetExportRetry() {
  exportCreateKey.value = ''
  exportFilterSnapshot.value = null
  errorMessage.value = ''
}

async function downloadExport() {
  if (!exportJob.value || exportJob.value.status !== 'SUCCEEDED' || downloading.value) return
  downloading.value = true
  errorMessage.value = ''
  try {
    saveBlob(
      await downloadProductExport(exportJob.value.id),
      `product-export-${exportJob.value.id}.xlsx`,
    )
  } catch (error) {
    errorMessage.value = apiMessage(error, '导出文件下载失败，请稍后重试')
  } finally {
    downloading.value = false
  }
}

async function resumeJobs() {
  const importJobId = typeof route.query.importJobId === 'string' ? route.query.importJobId : ''
  const exportJobId = typeof route.query.exportJobId === 'string' ? route.query.exportJobId : ''
  if (importJobId) {
    importJob.value = {
      id: importJobId,
      filename: '',
      status: 'UPLOADED',
      createCount: 0,
      updateCount: 0,
      skipCount: 0,
      conflictCount: 0,
      failureCount: 0,
      errorObjectKey: null,
      createdAt: '',
      startedAt: null,
      finishedAt: null,
    }
    await refreshImport()
  }
  if (exportJobId) {
    exportJob.value = {
      id: exportJobId,
      status: 'QUEUED',
      objectKey: null,
      createdAt: '',
      finishedAt: null,
    }
    await refreshExport()
  }
}

onMounted(() => void resumeJobs())
onBeforeUnmount(() => {
  disposed = true
  importRequestSequence += 1
  exportRequestSequence += 1
  clearImportTimer()
  clearExportTimer()
})
</script>

<template>
  <section class="page-panel product-job-center" aria-labelledby="product-import-title">
    <header class="page-heading page-heading-with-action">
      <div>
        <h1 id="product-import-title">商品数据中心</h1>
        <p>批量预检并导入商品，或按当前条件导出商品资料。</p>
      </div>
      <RouterLink class="secondary-button" to="/products">返回商品列表</RouterLink>
    </header>

    <div class="status-tabs product-job-tabs" role="tablist" aria-label="商品数据任务">
      <button
        type="button"
        role="tab"
        :aria-selected="mode === 'import'"
        @click="setMode('import')"
      >
        商品导入
      </button>
      <button
        type="button"
        role="tab"
        :aria-selected="mode === 'export'"
        @click="setMode('export')"
      >
        商品导出
      </button>
    </div>

    <div v-if="errorMessage" class="inline-notice warning-notice" role="alert">
      <span>{{ errorMessage }}</span>
    </div>

    <template v-if="mode === 'import'">
      <ol class="import-steps" aria-label="商品导入进度">
        <li
          v-for="(label, index) in ['选择文件', '上传预检', '查看预检', '确认导入', '下载结果']"
          :key="label"
          :class="{ active: index === importStage, done: index < importStage }"
          :aria-current="index === importStage ? 'step' : undefined"
        >
          <span>{{ index + 1 }}</span>
          <strong>{{ label }}</strong>
        </li>
      </ol>

      <div class="product-job-layout">
        <section class="editor-section import-workbench">
          <div class="section-heading">
            <div>
              <h2>1. 上传 Excel 并执行预检</h2>
              <p>只接受 .xlsx 文件。预检不会写入商品主数据，确认后才会正式导入。</p>
            </div>
            <button
              class="secondary-button"
              type="button"
              :disabled="downloading"
              @click="downloadTemplate"
            >
              下载导入模板
            </button>
          </div>

          <label class="product-upload-zone">
            <span class="product-upload-icon" aria-hidden="true">↑</span>
            <strong>{{ selectedFile?.name || '选择商品文件' }}</strong>
            <small>{{ selectedFile ? '已选择，可开始上传预检' : '点击选择 .xlsx 文件' }}</small>
            <input
              aria-label="选择商品文件"
              type="file"
              :disabled="uploading || confirming || importIsPolling"
              accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
              @change="selectFile"
            >
          </label>
          <div class="product-job-actions">
            <button
              class="primary-button"
              type="button"
              :disabled="!selectedFile || uploading || confirming || importIsPolling"
              @click="uploadForPreflight"
            >
              {{ uploading ? '正在上传…' : '上传并预检' }}
            </button>
            <span v-if="selectedFile">文件：{{ selectedFile.name }}</span>
          </div>
        </section>

        <aside class="editor-section job-guidance">
          <div class="section-heading">
            <div>
              <h2>导入前检查</h2>
              <p>降低批量导入后的返工风险。</p>
            </div>
          </div>
          <ul>
            <li>SPU 编码、SKU 编码不可重复</li>
            <li>品牌和类目需使用系统中的有效 ID</li>
            <li>先核对预检冲突，再点击确认导入</li>
          </ul>
        </aside>
      </div>

      <section v-if="importJob" class="editor-section product-job-result">
        <div class="job-result-heading">
          <div>
            <span
              class="status-tag"
              :class="{
                success: importStatus === 'SUCCEEDED',
                warning: importStatus === 'PARTIALLY_SUCCEEDED' || importStatus === 'PREFLIGHT_READY',
                danger: importStatus === 'FAILED',
                info: importIsPolling,
              }"
            >
              {{ importStatusLabel }}
            </span>
            <h2>{{ importJob.filename || '商品导入任务' }}</h2>
            <p>任务号：{{ importJob.id }}</p>
          </div>
          <button
            v-if="importPollingExpired || importRefreshNeeded"
            class="secondary-button"
            type="button"
            @click="manualRefreshImport"
          >
            手动刷新
          </button>
        </div>

        <div class="job-metrics" aria-label="导入统计">
          <div><span>预计新建</span><strong>{{ importJob.createCount }}</strong></div>
          <div><span>预计更新</span><strong>{{ importJob.updateCount }}</strong></div>
          <div><span>预检跳过</span><strong>{{ importJob.skipCount }}</strong></div>
          <div><span>预检冲突</span><strong>{{ importJob.conflictCount }}</strong></div>
          <div><span>执行失败</span><strong>{{ importJob.failureCount }}</strong></div>
        </div>

        <div v-if="importStatus === 'PREFLIGHT_READY'" class="preflight-callout">
          <div>
            <strong>预检已完成，尚未写入商品数据</strong>
            <p>请核对预计新增、更新和冲突数量，确认后才会开始正式导入。</p>
          </div>
          <button
            class="primary-button"
            type="button"
            :disabled="confirming || uploading"
            @click="confirmImport"
          >
            {{ confirming ? '正在确认…' : '确认执行导入' }}
          </button>
        </div>

        <div v-else-if="importIsPolling" class="job-progress" role="status">
          <span class="job-progress-bar" />
          <p>{{ importStatusLabel }}，页面会自动刷新任务状态。</p>
        </div>

        <div
          v-else-if="['SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED'].includes(importStatus)"
          class="job-completion-actions"
        >
          <p v-if="importStatus === 'SUCCEEDED'">全部数据已处理完成，可返回商品列表核对结果。</p>
          <p v-else-if="importStatus === 'PARTIALLY_SUCCEEDED'">部分行导入失败，请下载错误明细修正后重新导入。</p>
          <p v-else>任务执行失败，请下载错误明细或稍后重新提交。</p>
          <div>
            <button
              v-if="importJob.errorObjectKey"
              class="primary-button"
              type="button"
              :disabled="downloading"
              @click="downloadErrors"
            >
              下载错误明细
            </button>
            <RouterLink class="secondary-button" to="/products">查看商品列表</RouterLink>
          </div>
        </div>
      </section>
    </template>

    <template v-else>
      <section class="editor-section export-workbench">
        <div class="section-heading">
          <div>
            <h2>按条件导出商品</h2>
            <p>留空表示导出全部商品；任务完成后再下载 Excel 文件。</p>
          </div>
        </div>
        <div class="export-filter-grid">
          <label class="stacked-field">
            <span>商品/SKU 关键词</span>
            <input
              v-model="exportFilter.keyword"
              type="search"
              placeholder="商品名、SPU 或 SKU"
              :disabled="exportFilterLocked"
            >
          </label>
          <label class="stacked-field">
            <span>条码</span>
            <input
              v-model="exportFilter.barcode"
              type="search"
              placeholder="输入完整或部分条码"
              :disabled="exportFilterLocked"
            >
          </label>
          <label class="stacked-field">
            <span>品牌 ID</span>
            <input v-model="exportFilter.brandId" type="text" placeholder="可选" :disabled="exportFilterLocked">
          </label>
          <label class="stacked-field">
            <span>类目 ID</span>
            <input v-model="exportFilter.categoryId" type="text" placeholder="可选" :disabled="exportFilterLocked">
          </label>
          <label class="stacked-field">
            <span>商品状态</span>
            <select v-model="exportFilter.status" :disabled="exportFilterLocked">
              <option :value="undefined">全部状态</option>
              <option value="DRAFT">草稿</option>
              <option value="ACTIVE">启用</option>
              <option value="DISABLED">停用</option>
              <option value="ARCHIVED">已归档</option>
            </select>
          </label>
        </div>
        <div class="product-job-actions">
          <button
            class="primary-button"
            type="button"
            :disabled="exporting || exportIsPolling"
            @click="createExport"
          >
            {{ exporting ? '正在创建…' : '创建导出任务' }}
          </button>
          <button
            v-if="exportCreateKey && !exporting"
            class="secondary-button"
            type="button"
            @click="resetExportRetry"
          >
            放弃本次重试并修改条件
          </button>
          <span>系统将在后台生成文件，不影响继续处理其他业务。</span>
        </div>
      </section>

      <section v-if="exportJob" class="editor-section product-job-result export-result">
        <div class="job-result-heading">
          <div>
            <span
              class="status-tag"
              :class="{
                success: exportJob.status === 'SUCCEEDED',
                danger: exportJob.status === 'FAILED',
                info: exportIsPolling,
              }"
            >
              {{ exportStatusLabel }}
            </span>
            <h2>商品导出任务</h2>
            <p>任务号：{{ exportJob.id }}</p>
          </div>
          <button
            v-if="exportPollingExpired || exportRefreshNeeded"
            class="secondary-button"
            type="button"
            @click="manualRefreshExport"
          >
            手动刷新
          </button>
        </div>
        <div v-if="exportIsPolling" class="job-progress" role="status">
          <span class="job-progress-bar" />
          <p>{{ exportStatusLabel }}，页面会自动刷新任务状态。</p>
        </div>
        <div v-else class="job-completion-actions">
          <p v-if="exportJob.status === 'SUCCEEDED'">导出文件已生成，可立即下载。</p>
          <p v-else>导出任务未能完成，请重新创建任务。</p>
          <button
            v-if="exportJob.status === 'SUCCEEDED'"
            class="primary-button"
            type="button"
            :disabled="downloading"
            @click="downloadExport"
          >
            下载导出文件
          </button>
        </div>
      </section>
    </template>
  </section>
</template>

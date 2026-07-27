<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import {
  ProductApiError,
  getProduct,
  removeProductImage,
  reorderProductImages,
  uploadProductImage,
  type ProductDetail,
  type ProductImage,
  type ProductStatus,
} from '../../api/products'

const route = useRoute()
const detail = ref<ProductDetail | null>(null)
const loading = ref(false)
const loadError = ref('')
const imageError = ref('')
const uploading = ref(false)
const imageActionPending = ref(false)
const removingImage = ref<ProductImage | null>(null)
const removalReason = ref('')
const productId = computed(() => String(route.params.id ?? ''))

const statusLabels: Record<ProductStatus, string> = {
  DRAFT: '草稿',
  ACTIVE: '销售中',
  DISABLED: '已停用',
  ARCHIVED: '已归档',
}

async function loadDetail() {
  loading.value = true
  loadError.value = ''
  try {
    detail.value = await getProduct(productId.value)
  } catch {
    detail.value = null
    loadError.value = '商品加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

watch(() => route.fullPath, () => void loadDetail(), { immediate: true })

function imageFailure(error: unknown, fallback: string) {
  if (error instanceof ProductApiError && error.status === 409) {
    imageError.value = `${error.message}，请刷新商品后重试`
  } else {
    imageError.value = fallback
  }
}

async function uploadImage(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  imageError.value = ''
  if (!file || !detail.value) {
    return
  }
  const accepted = new Set(['image/jpeg', 'image/png', 'image/webp'])
  if (!accepted.has(file.type)) {
    imageError.value = '仅支持 JPG、PNG 或 WebP 图片'
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    imageError.value = '单张图片不能超过 10 MiB'
    return
  }
  uploading.value = true
  try {
    await uploadProductImage(detail.value.id, file, detail.value.version)
    await loadDetail()
  } catch (error) {
    imageFailure(error, '图片上传失败，请稍后重试')
  } finally {
    uploading.value = false
  }
}

async function moveImage(index: number, offset: number) {
  if (!detail.value) {
    return
  }
  const target = index + offset
  if (target < 0 || target >= detail.value.images.length) {
    return
  }
  const reordered = [...detail.value.images]
  const [image] = reordered.splice(index, 1)
  reordered.splice(target, 0, image)
  imageActionPending.value = true
  imageError.value = ''
  try {
    await reorderProductImages(
      detail.value.id,
      reordered.map((item) => item.id),
      detail.value.version,
    )
    await loadDetail()
  } catch (error) {
    imageFailure(error, '图片排序失败，请稍后重试')
  } finally {
    imageActionPending.value = false
  }
}

function openRemoveImage(image: ProductImage) {
  removingImage.value = image
  removalReason.value = ''
  imageError.value = ''
}

function closeRemoveImage() {
  removingImage.value = null
  removalReason.value = ''
}

async function confirmRemoveImage() {
  if (!detail.value || !removingImage.value || !removalReason.value.trim()) {
    return
  }
  imageActionPending.value = true
  try {
    await removeProductImage(
      detail.value.id,
      removingImage.value.id,
      detail.value.version,
      removalReason.value.trim(),
    )
    closeRemoveImage()
    await loadDetail()
  } catch (error) {
    closeRemoveImage()
    imageFailure(error, '图片删除失败，请稍后重试')
  } finally {
    imageActionPending.value = false
  }
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value)).replace(/\//g, '-')
}
</script>

<template>
  <section class="page-panel product-detail-page" aria-labelledby="product-detail-title">
    <div v-if="loading" class="table-state">正在加载商品…</div>
    <div v-else-if="loadError" class="table-state error-state" role="alert">
      <span>{{ loadError }}</span>
      <button type="button" class="text-button" @click="loadDetail">重新加载商品</button>
    </div>

    <template v-else-if="detail">
      <header class="page-heading page-heading-with-action">
        <div>
          <div class="title-with-status">
            <h1 id="product-detail-title">{{ detail.name }}</h1>
            <span class="status-tag" :class="detail.status.toLowerCase()">
              {{ statusLabels[detail.status] }}
            </span>
          </div>
          <p>{{ detail.spuCode }} · 更新于 {{ formatTime(detail.updatedAt) }}</p>
        </div>
        <div class="heading-actions">
          <RouterLink class="secondary-button" to="/products">返回列表</RouterLink>
          <RouterLink class="primary-button" :to="`/products/${detail.id}/edit`">
            编辑商品
          </RouterLink>
        </div>
      </header>

      <div class="product-detail-grid">
        <div class="detail-main-column">
          <section class="editor-section" aria-labelledby="detail-basic-title">
            <div class="section-heading">
              <h2 id="detail-basic-title">基本资料</h2>
            </div>
            <dl class="product-definition-grid">
              <div><dt>SPU 编码</dt><dd>{{ detail.spuCode }}</dd></div>
              <div><dt>品牌</dt><dd>{{ detail.brandName || detail.brandId || '未设置' }}</dd></div>
              <div><dt>类目</dt><dd>{{ detail.categoryName || detail.categoryId || '未设置' }}</dd></div>
              <div><dt>创建时间</dt><dd>{{ formatTime(detail.createdAt) }}</dd></div>
              <div><dt>数据版本</dt><dd>{{ detail.version }}</dd></div>
            </dl>
            <div class="json-summary">
              <h3>商品属性</h3>
              <p v-if="!Object.keys(detail.attributes).length">未设置商品属性</p>
              <dl v-else>
                <div v-for="(value, key) in detail.attributes" :key="key">
                  <dt>{{ key }}</dt><dd>{{ value }}</dd>
                </div>
              </dl>
            </div>
          </section>

          <section class="editor-section" aria-labelledby="detail-skus-title">
            <div class="section-heading">
              <div>
                <h2 id="detail-skus-title">SKU 明细</h2>
                <p>共 {{ detail.skus.length }} 个 SKU</p>
              </div>
            </div>
            <div class="table-wrap">
              <table class="detail-sku-table">
                <thead>
                  <tr>
                    <th>SKU 编码</th>
                    <th>名称</th>
                    <th>条码</th>
                    <th>规格</th>
                    <th>单位</th>
                    <th>状态</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="sku in detail.skus" :key="sku.id">
                    <td class="sku-code">{{ sku.skuCode }}</td>
                    <td>{{ sku.name }}</td>
                    <td>{{ sku.barcode || '—' }}</td>
                    <td>
                      <span v-if="!Object.keys(sku.specifications).length">—</span>
                      <span v-else>
                        {{ Object.entries(sku.specifications).map(([key, value]) => `${key}: ${value}`).join('；') }}
                      </span>
                    </td>
                    <td>{{ sku.unit }}</td>
                    <td>{{ statusLabels[sku.status] }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <section class="editor-section" aria-labelledby="audit-title">
            <div class="section-heading">
              <div>
                <h2 id="audit-title">操作记录</h2>
                <p>记录商品的重要资料和状态变更。</p>
              </div>
            </div>
            <ol class="product-audit-list">
              <li v-for="(audit, index) in detail.auditHistory" :key="`${audit.createdAt}-${index}`">
                <time>{{ formatTime(audit.createdAt) }}</time>
                <div>
                  <strong>{{ audit.action }}</strong>
                  <p>{{ audit.reason || '未填写原因' }}</p>
                  <small>操作人：{{ audit.actor }}</small>
                </div>
              </li>
              <li v-if="!detail.auditHistory.length" class="empty-audit">暂无操作记录</li>
            </ol>
          </section>
        </div>

        <aside class="editor-section product-image-section" aria-labelledby="image-title">
          <div class="section-heading">
            <div>
              <h2 id="image-title">商品图片</h2>
              <p>首张图片作为主图，最多 10 MiB。</p>
            </div>
          </div>
          <label class="image-upload-button" :class="{ disabled: uploading }">
            <span>{{ uploading ? '正在上传图片…' : '上传商品图片' }}</span>
            <input
              type="file"
              accept="image/jpeg,image/png,image/webp"
              :disabled="uploading"
              @change="uploadImage"
            />
          </label>
          <div v-if="imageError" class="inline-notice error-state" role="alert">
            {{ imageError }}
          </div>
          <div class="product-image-list">
            <article v-for="(image, index) in detail.images" :key="image.id" class="product-image-card">
              <img
                v-if="image.sourceUrl"
                :src="image.sourceUrl"
                :alt="`商品图片 ${index + 1}`"
              />
              <div v-else class="image-placeholder">图片 {{ index + 1 }}</div>
              <div class="image-card-meta">
                <strong>{{ index === 0 ? '主图' : `图片 ${index + 1}` }}</strong>
                <small>{{ image.mediaType }}</small>
              </div>
              <div class="image-card-actions">
                <button
                  type="button"
                  class="text-button"
                  :disabled="index === 0 || imageActionPending"
                  :aria-label="`图片 ${index + 1} 上移`"
                  @click="moveImage(index, -1)"
                >
                  上移
                </button>
                <button
                  type="button"
                  class="text-button"
                  :disabled="index === detail.images.length - 1 || imageActionPending"
                  :aria-label="`图片 ${index + 1} 下移`"
                  @click="moveImage(index, 1)"
                >
                  下移
                </button>
                <button
                  type="button"
                  class="text-button danger"
                  :disabled="imageActionPending"
                  :aria-label="`删除图片 ${index + 1}`"
                  @click="openRemoveImage(image)"
                >
                  删除
                </button>
              </div>
            </article>
            <p v-if="!detail.images.length" class="empty-image-state">暂未上传商品图片</p>
          </div>
        </aside>
      </div>
    </template>

    <div
      v-if="removingImage"
      class="modal-backdrop"
      role="presentation"
      @click.self="closeRemoveImage"
    >
      <form
        class="confirm-dialog"
        role="dialog"
        aria-modal="true"
        aria-label="删除商品图片"
        @submit.prevent="confirmRemoveImage"
      >
        <h2>删除商品图片</h2>
        <p>图片删除后无法恢复，请填写操作原因。</p>
        <label class="stacked-field">
          <span>操作原因</span>
          <textarea v-model="removalReason" rows="3"></textarea>
        </label>
        <div class="dialog-actions">
          <button type="button" class="secondary-button" @click="closeRemoveImage">取消</button>
          <button
            type="submit"
            class="primary-button"
            :disabled="!removalReason.trim() || imageActionPending"
          >
            确认删除
          </button>
        </div>
      </form>
    </div>
  </section>
</template>

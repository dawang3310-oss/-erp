<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  ProductApiError,
  createProductBrand,
  createProductCategory,
  createProduct,
  getProduct,
  listProductBrands,
  listProductCategories,
  updateProduct,
  type ProductCategoryItem,
  type ProductReferenceItem,
} from '../../api/products'
import {
  createEmptyProductForm,
  createEmptySku,
  hasProductFormErrors,
  productDetailToForm,
  toProductInput,
  toProductUpdate,
  validateProductForm,
  type ProductFormErrors,
} from './productForm'

const route = useRoute()
const router = useRouter()
const form = reactive(createEmptyProductForm())
const errors = ref<ProductFormErrors>({ skus: {} })
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const submitError = ref('')
const version = ref(0)
const brands = ref<ProductReferenceItem[]>([])
const categories = ref<ProductCategoryItem[]>([])
const brandQuery = ref('')
const categoryQuery = ref('')
const referenceError = ref('')
const referenceLoading = ref(false)
const createReference = ref<'brand' | 'category' | null>(null)
const referenceName = ref('')
const referenceParentId = ref('')
const referenceCreateError = ref('')
const creatingReference = ref(false)
const isEdit = computed(() => route.name === 'product-edit')
const productId = computed(() => String(route.params.id ?? ''))
const title = computed(() => isEdit.value ? '编辑商品' : '新建商品')
let brandRequestSequence = 0
let categoryRequestSequence = 0

function replaceForm(next: ReturnType<typeof createEmptyProductForm>) {
  form.spuCode = next.spuCode
  form.name = next.name
  form.brandId = next.brandId
  form.categoryId = next.categoryId
  form.attributesText = next.attributesText
  form.skus.splice(0, form.skus.length, ...next.skus)
}

async function searchBrands() {
  const requestSequence = ++brandRequestSequence
  referenceError.value = ''
  try {
    const result = await listProductBrands(brandQuery.value)
    if (requestSequence === brandRequestSequence) {
      const selected = brands.value.find((brand) => brand.id === form.brandId)
      brands.value = selected && !result.some((brand) => brand.id === selected.id)
        ? [...result, selected]
        : result
    }
  } catch {
    if (requestSequence === brandRequestSequence) {
      referenceError.value = '品牌加载失败，请稍后重试'
    }
  }
}

async function searchCategories() {
  const requestSequence = ++categoryRequestSequence
  referenceError.value = ''
  try {
    const result = await listProductCategories(categoryQuery.value)
    if (requestSequence === categoryRequestSequence) {
      const selected = categories.value.find((category) => category.id === form.categoryId)
      categories.value = selected && !result.some((category) => category.id === selected.id)
        ? [...result, selected]
        : result
    }
  } catch {
    if (requestSequence === categoryRequestSequence) {
      referenceError.value = '类目加载失败，请稍后重试'
    }
  }
}

async function loadReferenceData() {
  referenceLoading.value = true
  await Promise.all([searchBrands(), searchCategories()])
  referenceLoading.value = false
}

function includeCurrentReferences(
  brandId: string | null,
  brandName: string | null,
  categoryId: string | null,
  categoryName: string | null,
) {
  if (brandId && !brands.value.some((brand) => brand.id === brandId)) {
    brands.value.push({ id: brandId, name: brandName || brandId })
  }
  if (categoryId && !categories.value.some((category) => category.id === categoryId)) {
    categories.value.push({
      id: categoryId,
      name: categoryName || categoryId,
      parentId: null,
      path: `/${categoryId}`,
    })
  }
}

async function loadEditor() {
  errors.value = { skus: {} }
  loadError.value = ''
  submitError.value = ''
  const referencesReady = loadReferenceData()
  if (!isEdit.value) {
    void referencesReady
    version.value = 0
    replaceForm(createEmptyProductForm())
    return
  }
  loading.value = true
  try {
    const detail = await getProduct(productId.value)
    version.value = detail.version
    replaceForm(productDetailToForm(detail))
    await referencesReady
    includeCurrentReferences(
      detail.brandId,
      detail.brandName,
      detail.categoryId,
      detail.categoryName,
    )
  } catch {
    loadError.value = '商品加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

watch(() => route.fullPath, () => void loadEditor(), { immediate: true })

function addSku() {
  form.skus.push(createEmptySku())
}

function removeSku(index: number) {
  if (form.skus.length <= 1) {
    return
  }
  form.skus.splice(index, 1)
}

function applyServerConflict(error: ProductApiError) {
  const match = error.message.match(/(DUPLICATE_SPU|DUPLICATE_SKU|DUPLICATE_BARCODE):\s*(.+)$/)
  if (!match) {
    return
  }
  const [, kind, conflictingValue] = match
  if (kind === 'DUPLICATE_SPU') {
    errors.value.spuCode = 'SPU 编码已存在'
    return
  }
  for (const sku of form.skus) {
    errors.value.skus[sku.rowId] ??= {}
    if (kind === 'DUPLICATE_SKU' && sku.skuCode.trim() === conflictingValue) {
      errors.value.skus[sku.rowId].skuCode = 'SKU 编码已存在'
    }
    if (kind === 'DUPLICATE_BARCODE' && sku.barcode.trim() === conflictingValue) {
      errors.value.skus[sku.rowId].barcode = '商品条码已存在'
    }
  }
}

function openCreateReference(kind: 'brand' | 'category') {
  createReference.value = kind
  referenceName.value = ''
  referenceParentId.value = ''
  referenceCreateError.value = ''
}

function closeCreateReference() {
  createReference.value = null
  referenceName.value = ''
  referenceParentId.value = ''
}

async function confirmCreateReference() {
  if (!createReference.value || !referenceName.value.trim()) {
    return
  }
  creatingReference.value = true
  referenceCreateError.value = ''
  try {
    if (createReference.value === 'brand') {
      const created = await createProductBrand(referenceName.value)
      brands.value = [
        created,
        ...brands.value.filter((brand) => brand.id !== created.id),
      ]
      form.brandId = created.id
    } else {
      const created = await createProductCategory(
        referenceName.value,
        referenceParentId.value || null,
      )
      categories.value = [
        created,
        ...categories.value.filter((category) => category.id !== created.id),
      ]
      form.categoryId = created.id
    }
    closeCreateReference()
  } catch (error) {
    referenceCreateError.value = error instanceof ProductApiError
      ? error.message
      : '主数据创建失败，请稍后重试'
  } finally {
    creatingReference.value = false
  }
}

async function saveProduct() {
  errors.value = validateProductForm(form)
  submitError.value = ''
  if (hasProductFormErrors(errors.value)) {
    submitError.value = '请先修正表单中的错误'
    return
  }
  saving.value = true
  try {
    if (isEdit.value) {
      await updateProduct(productId.value, toProductUpdate(form, version.value))
      await router.push(`/products/${productId.value}`)
    } else {
      const created = await createProduct(toProductInput(form))
      await router.push(`/products/${created.id}`)
    }
  } catch (error) {
    if (error instanceof ProductApiError && error.status === 409) {
      applyServerConflict(error)
      submitError.value = error.message
    } else {
      submitError.value = '商品保存失败，请稍后重试'
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="page-panel product-editor-page" aria-labelledby="product-editor-title">
    <header class="page-heading page-heading-with-action">
      <div>
        <h1 id="product-editor-title">{{ title }}</h1>
        <p>维护 SPU 基本信息、商品属性及 SKU 规格。</p>
      </div>
      <RouterLink class="secondary-button" :to="isEdit ? `/products/${productId}` : '/products'">
        取消
      </RouterLink>
    </header>

    <div v-if="loading" class="table-state">正在加载商品…</div>
    <div v-else-if="loadError" class="table-state error-state" role="alert">
      <span>{{ loadError }}</span>
      <button type="button" class="text-button" @click="loadEditor">重新加载商品</button>
    </div>

    <form v-else class="product-editor-form" @submit.prevent="saveProduct">
      <div v-if="submitError" class="inline-notice error-state" role="alert">
        <span>{{ submitError }}</span>
        <button
          v-if="isEdit && submitError.includes('修改')"
          type="button"
          class="text-button"
          @click="loadEditor"
        >
          重新加载商品
        </button>
      </div>

      <section class="editor-section" aria-labelledby="basic-info-title">
        <div class="section-heading">
          <div>
            <h2 id="basic-info-title">基本信息</h2>
            <p>SPU 编码保存后不可修改；品牌和类目可搜索或直接新建。</p>
          </div>
        </div>
        <div class="editor-grid">
          <label class="stacked-field">
            <span>SPU 编码</span>
            <input v-model="form.spuCode" :disabled="isEdit" />
            <small v-if="errors.spuCode" class="field-error">{{ errors.spuCode }}</small>
          </label>
          <label class="stacked-field">
            <span>商品名称</span>
            <input v-model="form.name" />
            <small v-if="errors.name" class="field-error">{{ errors.name }}</small>
          </label>
          <div class="reference-field">
            <label class="stacked-field">
              <span>搜索品牌</span>
              <input
                v-model="brandQuery"
                aria-label="搜索品牌"
                placeholder="输入品牌名称"
                @input="searchBrands"
              />
            </label>
            <label class="stacked-field">
              <span>品牌</span>
              <select v-model="form.brandId" aria-label="品牌" :disabled="referenceLoading">
                <option value="">未设置品牌</option>
                <option v-for="brand in brands" :key="brand.id" :value="brand.id">
                  {{ brand.name }}
                </option>
              </select>
            </label>
            <button type="button" class="text-button" @click="openCreateReference('brand')">
              新建品牌
            </button>
          </div>
          <div class="reference-field">
            <label class="stacked-field">
              <span>搜索类目</span>
              <input
                v-model="categoryQuery"
                aria-label="搜索类目"
                placeholder="输入类目名称"
                @input="searchCategories"
              />
            </label>
            <label class="stacked-field">
              <span>类目</span>
              <select v-model="form.categoryId" aria-label="类目" :disabled="referenceLoading">
                <option value="">未设置类目</option>
                <option v-for="category in categories" :key="category.id" :value="category.id">
                  {{ category.name }}
                </option>
              </select>
            </label>
            <button type="button" class="text-button" @click="openCreateReference('category')">
              新建类目
            </button>
          </div>
          <div v-if="referenceError" class="inline-notice error-state full-field" role="alert">
            {{ referenceError }}
          </div>
          <label class="stacked-field full-field">
            <span>商品属性 JSON</span>
            <textarea v-model="form.attributesText" rows="4"></textarea>
            <small v-if="errors.attributesText" class="field-error">
              {{ errors.attributesText }}
            </small>
          </label>
        </div>
      </section>

      <section class="editor-section" aria-labelledby="sku-editor-title">
        <div class="section-heading">
          <div>
            <h2 id="sku-editor-title">SKU 明细</h2>
            <p>至少保留一个 SKU；已保存的 SKU 编码不可修改。</p>
          </div>
          <button type="button" class="secondary-button" @click="addSku">添加 SKU</button>
        </div>

        <article
          v-for="(sku, index) in form.skus"
          :key="sku.rowId"
          class="sku-editor-card"
          :aria-labelledby="`sku-title-${sku.rowId}`"
        >
          <div class="sku-card-heading">
            <h3 :id="`sku-title-${sku.rowId}`">SKU {{ index + 1 }}</h3>
            <button
              v-if="form.skus.length > 1"
              type="button"
              class="text-button danger"
              :aria-label="`删除 SKU ${index + 1}`"
              @click="removeSku(index)"
            >
              删除
            </button>
          </div>
          <div class="sku-editor-grid">
            <label class="stacked-field">
              <span>SKU 编码</span>
              <input
                v-model="sku.skuCode"
                :aria-label="`SKU 编码 ${index + 1}`"
                :disabled="Boolean(sku.id)"
              />
              <small v-if="errors.skus[sku.rowId]?.skuCode" class="field-error">
                {{ errors.skus[sku.rowId].skuCode }}
              </small>
            </label>
            <label class="stacked-field">
              <span>SKU 名称</span>
              <input v-model="sku.name" :aria-label="`SKU 名称 ${index + 1}`" />
              <small v-if="errors.skus[sku.rowId]?.name" class="field-error">
                {{ errors.skus[sku.rowId].name }}
              </small>
            </label>
            <label class="stacked-field">
              <span>条码</span>
              <input v-model="sku.barcode" :aria-label="`条码 ${index + 1}`" />
              <small v-if="errors.skus[sku.rowId]?.barcode" class="field-error">
                {{ errors.skus[sku.rowId].barcode }}
              </small>
            </label>
            <label class="stacked-field">
              <span>单位</span>
              <input v-model="sku.unit" :aria-label="`单位 ${index + 1}`" />
              <small v-if="errors.skus[sku.rowId]?.unit" class="field-error">
                {{ errors.skus[sku.rowId].unit }}
              </small>
            </label>
            <label class="stacked-field full-field">
              <span>规格 JSON</span>
              <textarea
                v-model="sku.specificationsText"
                rows="3"
                :aria-label="`规格 JSON ${index + 1}`"
              ></textarea>
              <small v-if="errors.skus[sku.rowId]?.specificationsText" class="field-error">
                {{ errors.skus[sku.rowId].specificationsText }}
              </small>
            </label>
          </div>
        </article>
      </section>

      <footer class="editor-actions">
        <RouterLink class="secondary-button" :to="isEdit ? `/products/${productId}` : '/products'">
          取消
        </RouterLink>
        <button type="submit" class="primary-button" :disabled="saving">
          {{ saving ? '正在保存…' : '保存商品' }}
        </button>
      </footer>
    </form>

    <div
      v-if="createReference"
      class="modal-backdrop"
      role="presentation"
      @click.self="closeCreateReference"
    >
      <form
        class="confirm-dialog"
        role="dialog"
        aria-modal="true"
        :aria-label="createReference === 'brand' ? '新建品牌' : '新建类目'"
        @submit.prevent="confirmCreateReference"
      >
        <h2>{{ createReference === 'brand' ? '新建品牌' : '新建类目' }}</h2>
        <div v-if="referenceCreateError" class="inline-notice error-state" role="alert">
          {{ referenceCreateError }}
        </div>
        <label class="stacked-field">
          <span>{{ createReference === 'brand' ? '品牌名称' : '类目名称' }}</span>
          <input v-model="referenceName" />
        </label>
        <label v-if="createReference === 'category'" class="stacked-field">
          <span>上级类目</span>
          <select v-model="referenceParentId">
            <option value="">作为一级类目</option>
            <option v-for="category in categories" :key="category.id" :value="category.id">
              {{ category.name }}
            </option>
          </select>
        </label>
        <div class="dialog-actions">
          <button type="button" class="secondary-button" @click="closeCreateReference">
            取消
          </button>
          <button
            type="submit"
            class="primary-button"
            :disabled="!referenceName.trim() || creatingReference"
          >
            {{
              creatingReference
                ? '正在创建…'
                : createReference === 'brand'
                  ? '确认新建品牌'
                  : '确认新建类目'
            }}
          </button>
        </div>
      </form>
    </div>
  </section>
</template>

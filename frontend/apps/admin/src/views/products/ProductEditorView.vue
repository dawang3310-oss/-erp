<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const title = computed(() => route.name === 'product-new' ? '新建商品' : '编辑商品')
</script>

<template>
  <section class="page-panel" aria-labelledby="product-editor-title">
    <header class="page-heading">
      <div>
        <h1 id="product-editor-title">{{ title }}</h1>
        <p>维护 SPU 基本信息及 SKU 规格。</p>
      </div>
    </header>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = defineProps<{ src: string | null; name: string }>()
const failed = ref(false)
const image = computed(() => {
  if (!props.src) return ''
  try {
    const url = new URL(props.src, window.location.origin)
    return ['http:', 'https:'].includes(url.protocol) ? url.href : ''
  } catch {
    return ''
  }
})
watch(image, () => { failed.value = false })
</script>

<template>
  <div class="product-image">
    <img v-if="image && !failed" :src="image" :alt="name" loading="lazy" referrerpolicy="no-referrer" @error="failed = true">
    <div v-else class="image-placeholder" aria-hidden="true"><span>Ke</span></div>
  </div>
</template>

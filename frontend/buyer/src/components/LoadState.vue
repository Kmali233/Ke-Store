<script setup lang="ts">
defineProps<{ loading: boolean; error: string }>()
defineEmits<{ retry: [] }>()
</script>

<template>
  <p v-if="loading" class="state" role="status">正在加载…</p>
  <div v-else-if="error" class="state">
    <p class="error" role="alert">{{ error }}</p>
    <button class="outline" type="button" @click="$emit('retry')">重新加载</button>
  </div>
  <slot v-else />
</template>

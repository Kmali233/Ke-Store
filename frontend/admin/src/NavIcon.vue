<script setup lang="ts">
defineProps<{ name: string }>()
const paths: Record<string, string> = {
  home: 'M3 11l9-7 9 7M5 10v10h14V10M10 20v-6h4v6',
  box: 'M4 7l8-4 8 4v10l-8 4-8-4V7zm0 0l8 4 8-4m-8 4v10',
  grid: 'M4 4h6v6H4zm10 0h6v6h-6zM4 14h6v6H4zm10 0h6v6h-6z',
  layers: 'M3 8l9-4 9 4-9 4-9-4zm0 4 9 4 9-4m-18 4 9 4 9-4',
  truck: 'M3 6h12v11H3V6zm12 4h3l3 3v4h-6m-7 0a2 2 0 1 1-4 0m16 0a2 2 0 1 1-4 0',
  list: 'M6 4h12v16H6zM9 8h6m-6 4h6m-6 4h4',
  users: 'M15 20H3v-2a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v2zm-3-13a3 3 0 1 1-6 0 3 3 0 0 1 6 0zm5-3a3 3 0 0 1 0 6m1 4a4 4 0 0 1 3 4v2',
}
</script>
<template>
  <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor"
       stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path :d="paths[name] || paths.home" />
  </svg>
</template>

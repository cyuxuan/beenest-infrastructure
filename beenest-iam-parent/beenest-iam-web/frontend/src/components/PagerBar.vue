<script setup lang="ts">
import { computed, ref } from 'vue';

const props = withDefaults(
  defineProps<{
    pageNum: number;
    pages: number;
    total: number;
    pageSize?: number;
    sizes?: number[];
  }>(),
  { pageSize: 20, sizes: () => [10, 20, 50, 100] },
);

const emit = defineEmits<{
  (e: 'change', page: number): void;
  (e: 'update:pageSize', size: number): void;
}>();

const jump = ref('');

const items = computed<(number | string)[]>(() => {
  const pages = props.pages;
  if (pages <= 0) return [];
  const windowSize = 5;
  let start = Math.max(1, props.pageNum - Math.floor(windowSize / 2));
  let end = Math.min(pages, start + windowSize - 1);
  start = Math.max(1, end - windowSize + 1);
  const list: (number | string)[] = [];
  if (start > 1) {
    list.push(1);
    if (start > 2) list.push('...left');
  }
  for (let page = start; page <= end; page += 1) list.push(page);
  if (end < pages) {
    if (end < pages - 1) list.push('...right');
    list.push(pages);
  }
  return list;
});

function go(page: number) {
  const target = Math.min(Math.max(1, page), Math.max(props.pages, 1));
  if (target !== props.pageNum) emit('change', target);
}

function onSizeChange(event: Event) {
  emit('update:pageSize', Number((event.target as HTMLSelectElement).value));
}

function onJump() {
  const value = Number(jump.value);
  if (Number.isFinite(value) && value > 0) go(Math.floor(value));
  jump.value = '';
}
</script>

<template>
  <div class="pager">
    <div class="pager-info">
      共 <strong>{{ total }}</strong> 条
      <span class="pager-sep">·</span>
      第 {{ pageNum }} / {{ Math.max(pages, 1) }} 页
    </div>
    <div class="pager-controls">
      <label v-if="sizes.length" class="pager-size">
        <span>每页</span>
        <select class="input select is-compact" :value="pageSize" @change="onSizeChange">
          <option v-for="size in sizes" :key="size" :value="size">{{ size }}</option>
        </select>
      </label>
      <div class="pager-pages">
        <button class="page-btn is-arrow" type="button" :disabled="pageNum <= 1" @click="go(pageNum - 1)">
          上一页
        </button>
        <template v-for="item in items" :key="String(item)">
          <span v-if="typeof item === 'string'" class="pager-ellipsis">…</span>
          <button
            v-else
            class="page-btn"
            :class="{ 'is-active': item === pageNum }"
            type="button"
            @click="go(item)"
          >
            {{ item }}
          </button>
        </template>
        <button class="page-btn is-arrow" type="button" :disabled="pageNum >= pages" @click="go(pageNum + 1)">
          下一页
        </button>
      </div>
      <label class="pager-jump">
        <span>跳至</span>
        <input v-model="jump" class="input is-compact" type="number" min="1" :max="Math.max(pages, 1)" @keydown.enter="onJump" @blur="onJump" />
        <span>页</span>
      </label>
    </div>
  </div>
</template>

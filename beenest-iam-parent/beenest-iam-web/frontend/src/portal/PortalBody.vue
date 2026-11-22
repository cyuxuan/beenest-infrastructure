<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue';
import AppCard from './AppCard.vue';
import type { PortalApp } from './types';

defineProps<{
  groups: Array<[string, PortalApp[]]>;
  recent: PortalApp[];
  keyword: string;
  total: number;
}>();
const emit = defineEmits<{ launch: [PortalApp]; 'clear-keyword': [] }>();
</script>

<template>
  <div class="portal-body">
    <section v-if="recent.length" class="group is-recent">
      <div class="group-head">
        <h2 class="group-title">
          <span class="group-mark"><AppIcon name="clock" :size="14" /></span>
          <span>最近使用</span>
          <span class="count-pill">{{ recent.length }}</span>
        </h2>
        <span class="group-hint">本机记录 · 仅自己可见</span>
      </div>
      <div class="grid">
        <AppCard v-for="item in recent" :key="item.appCode" :app="item" @launch="emit('launch', $event)" />
      </div>
    </section>

    <section v-for="[name, items] in groups" :key="name" class="group">
      <div class="group-head">
        <h2 class="group-title">
          <span class="group-mark"><AppIcon name="layers" :size="14" /></span>
          <span>{{ name }}</span>
          <span class="count-pill">{{ items.length }}</span>
        </h2>
      </div>
      <div class="grid">
        <AppCard v-for="item in items" :key="item.appCode" :app="item" @launch="emit('launch', $event)" />
      </div>
    </section>

    <div v-if="!groups.length && !recent.length" class="empty">
      <span class="empty-icon"><AppIcon name="hexagon" :size="24" /></span>
      <strong>{{ total ? '没有匹配的应用' : '暂无可访问的应用' }}</strong>
      <p>{{ total ? '换个关键词试试,或清空搜索查看全部应用。' : '请联系管理员为你开通应用权限。' }}</p>
      <button v-if="total && keyword.trim()" class="btn" type="button" @click="emit('clear-keyword')">
        清空搜索
      </button>
    </div>
  </div>
</template>

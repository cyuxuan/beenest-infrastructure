import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { NAV_ITEMS, canAccessNav, firstAccessiblePath, navItemByPath } from '@/navigation';

const routes: RouteRecordRaw[] = NAV_ITEMS.map((item) => ({
  path: item.path,
  name: item.label,
  component: item.load,
  meta: {
    perm: item.perm,
    requiresManagedApp: item.requiresManagedApp,
    title: item.label,
    group: item.group,
  },
}));

routes.push(
  {
    path: '/forbidden',
    name: 'forbidden',
    component: () => import('@/views/ForbiddenView.vue'),
    meta: { title: '无访问权限' },
  },
  {
    path: '/',
    redirect: () => firstAccessiblePath() || '/forbidden',
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: () => firstAccessiblePath() || '/forbidden',
  },
);

const router = createRouter({
  history: createWebHistory('/console'),
  routes,
  scrollBehavior: () => ({ top: 0 }),
});

router.beforeEach((to) => {
  const item = navItemByPath(to.path);
  const fallback = firstAccessiblePath();
  if (item && !canAccessNav(item)) {
    if (fallback && fallback !== to.path) return fallback;
    return { path: '/forbidden' };
  }
  if (to.path === '/forbidden' && fallback) return fallback;
  return true;
});

router.afterEach((to) => {
  const item = navItemByPath(to.path);
  document.title = item ? `${item.label} · 蜂巢身份管理控制台` : '管理控制台 · 蜂巢身份';
});

export default router;

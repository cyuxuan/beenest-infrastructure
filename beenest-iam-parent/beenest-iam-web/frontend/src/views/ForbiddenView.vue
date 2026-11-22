<script setup lang="ts">
import { useRouter } from 'vue-router';
import { auth } from '@/stores/auth';
import AppIcon from '@/components/AppIcon.vue';
import BrandMark from '@/components/BrandMark.vue';

const router = useRouter();

function goBack() {
  const first = document.querySelector<HTMLElement>('.nav-item');
  if (first) (first as HTMLAnchorElement).click();
  else router.push('/');
}

async function logout() {
  try {
    await fetch('/api/v1/auth/logout', { method: 'POST', credentials: 'same-origin' });
  } finally {
    location.replace('/login');
  }
}
</script>

<template>
  <div class="forbidden-view">
    <div class="forbidden-card">
      <div class="forbidden-mark"><BrandMark :size="46" glow /></div>
      <div class="page-kicker"><span class="kicker-hex" />ACCESS REVIEW</div>
      <h1>当前账号没有管理权限</h1>
      <p>
        你已通过身份认证,但账号 <strong>{{ auth.me?.username }}</strong>
        所属身份域 <strong>{{ auth.realmKey }}</strong> 未授予任何管理页面的权限。
      </p>
      <div class="forbidden-actions">
        <button class="btn btn-primary" type="button" @click="goBack">返回可访问页面</button>
        <a class="btn" href="/portal">前往应用门户</a>
        <button class="btn btn-ghost" type="button" @click="logout">
          <AppIcon name="logout" :size="15" />
          <span>退出登录</span>
        </button>
      </div>
      <div class="forbidden-note">
        请联系所属身份域的管理员,为你的账号授予管理角色;权限变更将在下一次认证时生效。
      </div>
    </div>
  </div>
</template>

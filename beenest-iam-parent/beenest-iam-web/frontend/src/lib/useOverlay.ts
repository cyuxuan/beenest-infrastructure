import { nextTick, onBeforeUnmount, watch, type Ref } from 'vue';

const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'textarea:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

const stack: symbol[] = [];
let lockCount = 0;
let previousOverflow = '';
let previousPadding = '';

function focusables(panel: HTMLElement | null): HTMLElement[] {
  if (!panel) return [];
  return Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
    (node) => node.offsetParent !== null || node === document.activeElement,
  );
}

function lockScroll() {
  if (lockCount === 0) {
    previousOverflow = document.documentElement.style.overflow;
    previousPadding = document.documentElement.style.paddingRight;
    const gap = window.innerWidth - document.documentElement.clientWidth;
    document.documentElement.style.overflow = 'hidden';
    if (gap > 0) document.documentElement.style.paddingRight = `${gap}px`;
  }
  lockCount += 1;
}

function unlockScroll() {
  lockCount = Math.max(0, lockCount - 1);
  if (lockCount === 0) {
    document.documentElement.style.overflow = previousOverflow;
    document.documentElement.style.paddingRight = previousPadding;
  }
}

/**
 * 浮层的焦点管理:打开时锁定背景滚动、焦点进入浮层,Tab 循环留在浮层内,
 * 关闭后把焦点还给触发元素。多层浮层以栈顶为准,确认框永远优先接管键盘。
 * 带 data-focus-exempt 的元素不在回填范围内,复制用的临时输入框依赖该标记保留焦点。
 */
export function useOverlay(isOpen: () => boolean, panel: Ref<HTMLElement | null>, close: () => void) {
  const id = Symbol('overlay');
  let restoreTarget: HTMLElement | null = null;
  let active = false;

  function isTop(): boolean {
    return stack[stack.length - 1] === id;
  }

  function onKeydown(event: KeyboardEvent) {
    if (!active || !isTop()) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      close();
      return;
    }
    if (event.key !== 'Tab') return;
    const list = focusables(panel.value);
    if (!list.length) {
      event.preventDefault();
      panel.value?.focus();
      return;
    }
    const first = list[0];
    const last = list[list.length - 1];
    const current = document.activeElement;
    if (event.shiftKey && (current === first || current === panel.value)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && current === last) {
      event.preventDefault();
      first.focus();
    }
  }

  function onFocusIn(event: FocusEvent) {
    if (!active || !isTop() || !panel.value) return;
    const target = event.target as Node | null;
    if (target instanceof Element && target.closest('[data-focus-exempt]')) return;
    if (!panel.value.contains(target)) {
      const list = focusables(panel.value);
      (list[0] || panel.value).focus();
    }
  }

  function activate() {
    if (active) return;
    active = true;
    restoreTarget = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    stack.push(id);
    lockScroll();
    document.addEventListener('keydown', onKeydown, true);
    document.addEventListener('focusin', onFocusIn, true);
    nextTick(() => {
      const preferred = panel.value?.querySelector<HTMLElement>('[data-autofocus]');
      const target = preferred || focusables(panel.value)[0] || panel.value;
      target?.focus({ preventScroll: true });
    });
  }

  function deactivate() {
    if (!active) return;
    active = false;
    const index = stack.lastIndexOf(id);
    if (index >= 0) stack.splice(index, 1);
    document.removeEventListener('keydown', onKeydown, true);
    document.removeEventListener('focusin', onFocusIn, true);
    unlockScroll();
    const target = restoreTarget;
    restoreTarget = null;
    if (target?.isConnected) {
      window.setTimeout(() => target.focus({ preventScroll: true }), 0);
    }
  }

  watch(isOpen, (open) => (open ? activate() : deactivate()), { immediate: true });
  onBeforeUnmount(deactivate);
}

import { reactive } from 'vue';
import type { ConfirmOptions } from './types';

interface ConfirmState {
  options: ConfirmOptions | null;
  resolve: ((value: boolean) => void) | null;
}

export const confirmState: ConfirmState = reactive({ options: null, resolve: null });

/** 危险操作统一二次确认;返回用户是否确认。 */
export function confirmDialog(options: ConfirmOptions): Promise<boolean> {
  // 同一时刻只保留一个确认框,新请求以取消结束旧请求,避免 Promise 悬挂
  confirmState.resolve?.(false);
  return new Promise((resolve) => {
    confirmState.options = {
      title: options.title || '请确认',
      confirmText: options.confirmText || '确认执行',
      cancelText: options.cancelText || '取消',
      danger: options.danger !== false,
      ...options,
    };
    confirmState.resolve = resolve;
  });
}

export function settleConfirm(value: boolean) {
  const resolve = confirmState.resolve;
  confirmState.options = null;
  confirmState.resolve = null;
  resolve?.(value);
}

import { reactive } from 'vue';

let toastId = 0;

export interface ToastEntry {
  id: number;
  message: string;
  type: 'info' | 'success' | 'error' | 'warn';
}

export const toastState = reactive<{ items: ToastEntry[] }>({ items: [] });

export function dismissToast(id: number) {
  const index = toastState.items.findIndex((item) => item.id === id);
  if (index >= 0) toastState.items.splice(index, 1);
}

export function toast(message: string, type: ToastEntry['type'] = 'info', timeout = 3600) {
  const id = ++toastId;
  toastState.items.push({ id, message, type });
  if (toastState.items.length > 5) toastState.items.shift();
  window.setTimeout(() => dismissToast(id), timeout);
  return id;
}

export function notifyError(message: string) {
  toast(message, 'error', 4600);
}

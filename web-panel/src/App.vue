<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { MonitorX, X, AlertCircle, CheckCircle2, Info } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'

type ToastKind = 'success' | 'error' | 'info'
interface ToastPayload { type: ToastKind; message: string; durationMs?: number }

const MIN_WIDTH = 1280
const viewportWidth = ref<number>(typeof window === 'undefined' ? MIN_WIDTH : window.innerWidth)
const isSmallViewport = computed<boolean>(() => viewportWidth.value < MIN_WIDTH)

const router = useRouter()
const authStore = useAuthStore()

// —— 全局 toast：配合 app:toast 事件使用；request.ts、详情页按钮等都可触发。
type ToastItem = { id: number; type: ToastKind; message: string }
const toasts = ref<ToastItem[]>([])
let toastSeq = 0
function pushToast(p: ToastPayload): void {
  const id = ++toastSeq
  toasts.value.push({ id, type: p.type, message: p.message })
  window.setTimeout(() => {
    toasts.value = toasts.value.filter(t => t.id !== id)
  }, Math.max(1500, Math.min(8000, p.durationMs ?? 3000)))
}
function removeToast(id: number): void {
  toasts.value = toasts.value.filter(t => t.id !== id)
}
function onAppToast(e: Event): void {
  const detail = (e as CustomEvent).detail as ToastPayload | undefined
  if (!detail) return
  pushToast({ type: detail.type || 'info', message: detail.message || '', durationMs: detail.durationMs })
}
// 暴露到 window（方便组件直接调用），不用全局引入
declare global { interface Window { __anticheatToast?: (p: ToastPayload) => void } }
if (typeof window !== 'undefined') window.__anticheatToast = pushToast

function onResize(): void {
  viewportWidth.value = window.innerWidth
}

function onAuthExpired(): void {
  try { authStore.logout() } catch { /* noop */ }
  router.replace('/login').catch(() => { /* noop */ })
}

onMounted(() => {
  window.addEventListener('resize', onResize)
  window.addEventListener('app:auth-expired', onAuthExpired)
  window.addEventListener('app:toast', onAppToast as EventListener)
  useNotificationStore().fetchList().catch(() => void 0)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  window.removeEventListener('app:auth-expired', onAuthExpired)
  window.removeEventListener('app:toast', onAppToast as EventListener)
})

function toastIconClass(k: ToastKind): { color: string } {
  switch (k) {
    case 'success': return { color: 'var(--success-green)' }
    case 'error':   return { color: 'var(--danger-red)' }
    default:        return { color: 'var(--accent-blue)' }
  }
}
</script>

<template>
  <!-- 全局 Toast 容器（右上角，高于 Sidebar z-40） -->
  <div class="toast-container" role="status" aria-live="polite">
    <TransitionGroup name="toast">
      <div v-for="t in toasts" :key="t.id" :class="['toast-item', 'toast-'+t.type]">
        <div class="toast-icon">
          <CheckCircle2 v-if="t.type==='success'" :size="18" :style="toastIconClass(t.type)"/>
          <AlertCircle   v-else-if="t.type==='error'" :size="18" :style="toastIconClass(t.type)"/>
          <Info          v-else                              :size="18" :style="toastIconClass(t.type)"/>
        </div>
        <div class="toast-msg">{{ t.message }}</div>
        <button class="toast-close" @click="removeToast(t.id)" aria-label="关闭">
          <X :size="14"/>
        </button>
      </div>
    </TransitionGroup>
  </div>

  <!-- 小屏警告层：<1280px 时显示，提示切换更大屏幕 -->
  <div v-if="isSmallViewport" class="small-screen-warning" role="alert">
    <MonitorX :size="64" :stroke-width="1.4" />
    <h2>请使用更大的屏幕</h2>
    <p>
      反作弊指挥中心当前仅支持宽度不低于 <strong>1280px</strong> 的桌面端浏览器。
      当前宽度为 <strong>{{ viewportWidth }}px</strong>，请放大窗口或使用分辨率更高的显示器访问，以获得完整的监控与操作体验。
    </p>
  </div>

  <!-- 正常宽度渲染主路由 -->
  <router-view v-else />
</template>

<style scoped>
.toast-container {
  position: fixed;
  top: 72px;
  right: 24px;
  z-index: 9998;
  display: flex;
  flex-direction: column;
  gap: 10px;
  pointer-events: none;
  max-width: min(420px, calc(100vw - 48px));
}
.toast-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid var(--border-line);
  background: rgba(22, 26, 38, 0.92);
  backdrop-filter: blur(8px);
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.35);
  pointer-events: auto;
}
.toast-item.toast-success { border-color: rgba(64, 192, 128, 0.35); }
.toast-item.toast-error   { border-color: rgba(240, 82, 82, 0.4); }
.toast-item.toast-info    { border-color: rgba(80, 150, 255, 0.35); }
.toast-icon  { padding-top: 1px; flex-shrink: 0; }
.toast-msg   { flex: 1; font-size: 13px; line-height: 1.5; color: var(--text-primary); word-break: break-word; }
.toast-close {
  flex-shrink: 0;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  padding: 2px;
  border-radius: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.toast-close:hover { background: var(--bg-hover); color: var(--text-primary); }

.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateX(20px); }
.toast-enter-active, .toast-leave-active { transition: all 260ms cubic-bezier(.2,.7,.2,1); }
.toast-move { transition: transform 240ms ease; }
</style>

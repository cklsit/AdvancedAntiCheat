import { createRouter, createWebHistory, type RouteRecordRaw, type Router } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * 路由表: 所有业务路由懒加载
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', requiresAuth: false, layout: 'blank' }
  },
  {
    path: '/',
    component: () => import('@/components/layout/Shell.vue'),
    meta: { requiresAuth: true },
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/DashboardView.vue'),
        alias: '/',
        meta: { title: '总览', icon: 'LayoutDashboard' }
      },
      {
        path: 'players',
        name: 'Players',
        component: () => import('@/views/PlayersView.vue'),
        meta: { title: '玩家管理', icon: 'Users' }
      },
      {
        path: 'players/:id',
        name: 'PlayerDetail',
        component: () => import('@/views/PlayerDetailView.vue'),
        meta: { title: '玩家详情', icon: 'User', hidden: true }
      },
      {
        path: 'cases',
        name: 'Cases',
        component: () => import('@/views/CasesView.vue'),
        meta: { title: '案件审理', icon: 'FolderKanban' }
      },
      {
        path: 'cases/:id',
        name: 'CaseReview',
        component: () => import('@/views/CaseReviewView.vue'),
        meta: { title: '案件审理详情', icon: 'FolderSearch', hidden: true }
      },
      {
        path: 'live-map',
        name: 'LiveMap',
        component: () => import('@/views/LiveMapView.vue'),
        meta: { title: '实时地图', icon: 'Map' }
      },
      {
        path: 'replay',
        name: 'Replay',
        component: () => import('@/views/ReplayView.vue'),
        meta: { title: '违规回放', icon: 'PlaySquare' },
        children: [
          {
            path: 'watch',
            name: 'ReplayWatch',
            component: () => import('@/views/ReplayWatchView.vue'),
            meta: { title: '回放播放', hidden: true }
          }
        ]
      },
      {
        path: 'ai-lab',
        name: 'AILab',
        component: () => import('@/views/AILabView.vue'),
        meta: { title: 'AI 实验室', icon: 'Sparkles' }
      },
      {
        path: 'config',
        name: 'Config',
        component: () => import('@/views/ConfigView.vue'),
        meta: { title: '系统配置', icon: 'Settings' }
      },
      {
        path: 'audit',
        name: 'Audit',
        component: () => import('@/views/AuditLogView.vue'),
        meta: { title: '审计日志', icon: 'ClipboardList' }
      },
      {
        path: 'alliance',
        name: 'Alliance',
        component: () => import('@/views/AllianceView.vue'),
        meta: { title: '联盟图谱', icon: 'Network' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '404', requiresAuth: false, layout: 'blank' }
  }
]

const router: Router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

/**
 * 全局前置守卫: 登录状态检查
 */
router.beforeEach(async (to, _from, next) => {
  const auth = useAuthStore()

  // 确保登录态被恢复
  if (!auth.isLoggedIn && auth.token) {
    await auth.restoreSession()
  }

  const requiresAuth = to.meta?.requiresAuth !== false
  const isLoginRoute = to.path === '/login' || to.name === 'Login'

  // 已登录用户访问 /login -> 跳转总览
  if (isLoginRoute && auth.isLoggedIn) {
    return next({ path: '/dashboard' })
  }

  // 需要登录但未登录 -> 跳 /login
  if (requiresAuth && !auth.isLoggedIn) {
    return next({ path: '/login', query: { redirect: to.fullPath } })
  }

  next()
})

router.afterEach((to) => {
  const base = '反作弊指挥中心 · AntiCheat Command Center'
  const title = to.meta?.title as string | undefined
  document.title = title ? `${title} · ${base}` : base
})

export default router

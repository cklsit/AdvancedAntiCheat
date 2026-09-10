import axios, { AxiosInstance, AxiosRequestConfig, InternalAxiosRequestConfig, AxiosResponse } from 'axios'
import type { ApiResp } from '@/types'

const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'

/**
 * 简易 Toast 模拟 (后续可替换为 UI 组件库通知)
 */
function toastError(message: string): void {
  // 开发阶段打印 + 派发自定义事件，便于 UI 层监听
  // eslint-disable-next-line no-console
  console.error('[API Error]', message)
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent('app:toast', { detail: { type: 'error', message } }))
  }
}

const service: AxiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: 15_000,
  headers: {
    'Content-Type': 'application/json'
  }
})

/** Request 拦截: 注入 token */
service.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    let token: string | null = null
    try {
      token = localStorage.getItem('anticheat_token')
    } catch { /* noop */ }
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

/** Response 拦截: 统一 {code, message, data} */
service.interceptors.response.use(
  (response: AxiosResponse<ApiResp<unknown>>) => {
    const body = response.data
    // 允许非标准响应 (如二进制文件) 原样返回
    if (!body || typeof body !== 'object' || !('code' in body)) {
      return body as unknown as never
    }
    if (body.code > 0) {
      toastError(body.message || `请求失败 (code=${body.code})`)
      throw new Error(body.message || String(body.code))
    }
    return body.data as never
  },
  (error) => {
    const status = error?.response?.status
    let msg = error?.message || '网络异常'
    if (status === 401) {
      try {
        localStorage.removeItem('anticheat_token')
        localStorage.removeItem('anticheat_user')
        if (typeof window !== 'undefined') {
          window.dispatchEvent(new CustomEvent('app:auth-expired', { detail: { reason: 'token_expired' } }))
        }
      } catch { /* noop */ }
      msg = '登录已过期，请重新登录'
    } else if (status === 403) msg = '无权限访问该资源'
    else if (status === 404) msg = '接口不存在'
    else if (status >= 500) msg = '服务器内部错误'
    toastError(msg)
    return Promise.reject(error)
  }
)

/** 封装通用 HTTP 方法，返回 data 本身 */
export const request = {
  get<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return service.get(url, config) as Promise<T>
  },
  post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return service.post(url, data, config) as Promise<T>
  },
  put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return service.put(url, data, config) as Promise<T>
  },
  delete<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return service.delete(url, config) as Promise<T>
  }
}

export default service

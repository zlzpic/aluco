import { useCallback, useEffect, useState } from 'react'
import type { Page } from '@/types/api'

interface PagedQuery<T> {
  rows: T[]
  total: number
  page: number
  size: number
  loading: boolean
  setPage: (page: number, size?: number) => void
  reload: () => void
}

/** 分页表格通用加载逻辑（信封：{ list, total, page, size }） */
export function usePagedQuery<T>(
  fetcher: (page: number, size: number) => Promise<Page<T>>,
  deps: unknown[] = [],
): PagedQuery<T> {
  const [rows, setRows] = useState<T[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPageState] = useState(1)
  const [size, setSize] = useState(20)
  const [loading, setLoading] = useState(false)
  const [version, setVersion] = useState(0)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    fetcher(page, size)
      .then((res) => {
        if (cancelled) return
        setRows(res.list)
        setTotal(res.total)
      })
      .catch(() => {
        /* 错误提示由 http 拦截器统一处理 */
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, size, version, ...deps])

  const setPage = useCallback((p: number, s?: number) => {
    setPageState(p)
    if (s) setSize(s)
  }, [])

  const reload = useCallback(() => setVersion((v) => v + 1), [])

  return { rows, total, page, size, loading, setPage, reload }
}

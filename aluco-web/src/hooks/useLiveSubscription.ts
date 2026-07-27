import { useEffect } from 'react'
import { liveClient } from '@/ws/liveClient'

/** 进入页面 subscribe、离开 unsubscribe（对接文档 3.2：订阅是增量的） */
export function useLiveSubscription(deviceIds: string[]): void {
  const stable = deviceIds.slice().sort().join(',')
  useEffect(() => {
    const keys = stable ? stable.split(',') : []
    if (!keys.length) return
    liveClient.subscribe(keys)
    return () => liveClient.unsubscribe(keys)
  }, [stable])
}

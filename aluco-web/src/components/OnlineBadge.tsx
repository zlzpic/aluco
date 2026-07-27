import { Badge } from 'antd'
import { useDeviceStore } from '@/stores/deviceStore'

interface Props {
  deviceKey: string
  /** REST 返回的在线状态；WS presence 覆盖优先 */
  online: boolean
}

export function OnlineBadge({ deviceKey, online }: Props) {
  const override = useDeviceStore((s) => s.presence[deviceKey])
  const effective = override ?? online
  return (
    <Badge
      status={effective ? 'processing' : 'default'}
      color={effective ? '#52c41a' : '#5a667f'}
      text={effective ? '在线' : '离线'}
    />
  )
}

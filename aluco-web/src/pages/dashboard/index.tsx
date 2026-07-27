import { useEffect, useState } from 'react'
import { Card, Col, Row, Statistic, List, Typography, Tag, Empty } from 'antd'
import { HddOutlined, ThunderboltOutlined, AlertOutlined, ApiOutlined } from '@ant-design/icons'
import { Link } from 'react-router-dom'
import { pageDevices } from '@/api/devices'
import { pageAlerts } from '@/api/alerts'
import { useAlertStore } from '@/stores/alertStore'
import { currentIngestRate } from '@/stores/telemetryStore'
import { useLiveSubscription } from '@/hooks/useLiveSubscription'
import { fmtTime, fmtVal } from '@/utils/format'
import type { AlertEvent, Device } from '@/types/api'

const DASHBOARD_DEVICE_LIMIT = 200

export default function DashboardPage() {
  const [deviceTotal, setDeviceTotal] = useState(0)
  const [onlineCount, setOnlineCount] = useState(0)
  const [deviceKeys, setDeviceKeys] = useState<string[]>([])
  const [firingTotal, setFiringTotal] = useState(0)
  const [firingList, setFiringList] = useState<AlertEvent[]>([])
  const [rate, setRate] = useState(0)
  const alertVersion = useAlertStore((s) => s.alertVersion)

  // 设备总数 / 在线数（接口 ③）
  useEffect(() => {
    let cancelled = false
    pageDevices('', 1, DASHBOARD_DEVICE_LIMIT).then((res) => {
      if (cancelled) return
      setDeviceTotal(res.total)
      setOnlineCount(res.list.filter((d: Device) => d.online).length)
      setDeviceKeys(res.list.map((d) => d.deviceKey))
    })
    return () => {
      cancelled = true
    }
  }, [])

  // FIRING 告警数 + 滚动列表（接口 ⑫）；收到 WS alert 后重拉一次 REST 保证一致性
  useEffect(() => {
    let cancelled = false
    pageAlerts({ status: 'FIRING', page: 1, size: 10 }).then((res) => {
      if (cancelled) return
      setFiringTotal(res.total)
      setFiringList(res.list)
    })
    return () => {
      cancelled = true
    }
  }, [alertVersion])

  // 订阅机群以统计接入速率（订阅是增量的，离开页面自动退订）
  useLiveSubscription(deviceKeys)

  // 接入速率每秒刷新一次（非响应式读取，避免高频渲染）
  useEffect(() => {
    const timer = window.setInterval(() => setRate(currentIngestRate()), 1_000)
    return () => window.clearInterval(timer)
  }, [])

  const cards = [
    { title: '设备总数', value: deviceTotal, icon: <HddOutlined />, color: '#4f8cff' },
    { title: '在线设备', value: onlineCount, icon: <ApiOutlined />, color: '#52c41a' },
    { title: 'FIRING 告警', value: firingTotal, icon: <AlertOutlined />, color: '#ff4d4f' },
    { title: '接入速率（条/秒）', value: rate.toFixed(1), icon: <ThunderboltOutlined />, color: '#faad14' },
  ]

  return (
    <>
      <Row gutter={[16, 16]}>
        {cards.map((c) => (
          <Col xs={12} md={6} key={c.title}>
            <Card>
              <Statistic
                title={c.title}
                value={c.value}
                prefix={<span style={{ color: c.color, marginRight: 4 }}>{c.icon}</span>}
              />
            </Card>
          </Col>
        ))}
      </Row>

      <Card
        title="实时告警（FIRING）"
        style={{ marginTop: 16 }}
        extra={<Tag color="red">{firingTotal} 条进行中</Tag>}
      >
        {firingList.length === 0 ? (
          <Empty description="当前没有进行中的告警" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <List
            dataSource={firingList}
            renderItem={(item) => (
              <List.Item>
                <List.Item.Meta
                  title={
                    <>
                      <Typography.Text strong>{item.ruleName ?? `规则 #${item.ruleId}`}</Typography.Text>
                      <Typography.Text type="secondary" style={{ marginLeft: 12, fontSize: 13 }}>
                        设备 <Link to={`/devices/${item.deviceKey}`}>{item.deviceKey}</Link>
                      </Typography.Text>
                    </>
                  }
                  description={`触发值 ${fmtVal(item.triggerVal)} · ${fmtTime(item.triggeredAt)}`}
                />
                <Tag color="red">告警中</Tag>
              </List.Item>
            )}
          />
        )}
      </Card>
    </>
  )
}

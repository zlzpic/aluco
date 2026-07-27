import { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Form,
  InputNumber,
  Modal,
  Radio,
  Row,
  Select,
  Space,
  Statistic,
  Typography,
  App as AntdApp,
} from 'antd'
import { ArrowLeftOutlined, FieldTimeOutlined, SendOutlined } from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import { getDevice, getDeviceState, getTelemetry, setReportInterval } from '@/api/devices'
import { useTelemetryStore } from '@/stores/telemetryStore'
import { useLiveSubscription } from '@/hooks/useLiveSubscription'
import { OnlineBadge } from '@/components/OnlineBadge'
import { TelemetryChart } from '@/components/TelemetryChart'
import { TIME_WINDOWS, INTERVAL_OPTIONS } from '@/utils/timeWindow'
import { fmtTime, fmtVal } from '@/utils/format'
import type { Device, TelemetryInterval, TelemetryPoint } from '@/types/api'

export default function DeviceDetailPage() {
  const { deviceKey = '' } = useParams()
  const navigate = useNavigate()
  const { message } = AntdApp.useApp()

  const [device, setDevice] = useState<Device | null>(null)
  const [notFound, setNotFound] = useState(false)

  const live = useTelemetryStore((s) => s.byDevice[deviceKey])
  const seedSnapshot = useTelemetryStore((s) => s.seedSnapshot)
  const clearDevice = useTelemetryStore((s) => s.clearDevice)

  const [metric, setMetric] = useState<string>('')
  const [windowKey, setWindowKey] = useState('1h')
  const [interval_, setInterval_] = useState<TelemetryInterval>('raw')
  const [history, setHistory] = useState<TelemetryPoint[]>([])
  const [historyLoading, setHistoryLoading] = useState(false)
  const [truncated, setTruncated] = useState(false)
  const [cmdOpen, setCmdOpen] = useState(false)
  const [cmdSending, setCmdSending] = useState(false)
  const [cmdForm] = Form.useForm()

  // ④ 设备详情 + ⑥ 实时状态快照（先 REST 播种，再 subscribe 等 WS 推）
  useEffect(() => {
    let cancelled = false
    setNotFound(false)
    getDevice(deviceKey)
      .then((d) => !cancelled && setDevice(d))
      .catch(() => !cancelled && setNotFound(true))
    getDeviceState(deviceKey)
      .then((state) => !cancelled && seedSnapshot(state))
      .catch(() => {})
    return () => {
      cancelled = true
      clearDevice(deviceKey)
    }
  }, [deviceKey, seedSnapshot, clearDevice])

  // 进入详情页 subscribe，离开 unsubscribe
  useLiveSubscription(deviceKey ? [deviceKey] : [])

  // 指标选择器：已见过的 metrics key 并集；默认选第一个
  const seenMetrics = live?.seenMetrics ?? []
  useEffect(() => {
    if (!metric && seenMetrics.length) setMetric(seenMetrics[0])
  }, [seenMetrics, metric])

  const liveSeries = useMemo(() => {
    if (!metric || !live) return []
    return (live.points[metric] ?? []).map((p) => [p.ts, p.val] as [number, number])
  }, [live, metric])

  // 时间窗切换时自动映射 interval（15m/1h→raw，6h→5m，24h→1h，7d→1d）
  const handleWindowChange = (key: string) => {
    setWindowKey(key)
    const w = TIME_WINDOWS.find((x) => x.key === key)
    if (w) setInterval_(w.interval)
  }

  const queryHistory = async () => {
    if (!metric) return
    const w = TIME_WINDOWS.find((x) => x.key === windowKey) ?? TIME_WINDOWS[1]
    const to = Date.now()
    const from = to - w.ms
    setHistoryLoading(true)
    try {
      const res = await getTelemetry(deviceKey, metric, from, to, interval_)
      setHistory(res.data.points)
      setTruncated(res.truncated)
    } catch {
      /* 错误由拦截器 toast */
    } finally {
      setHistoryLoading(false)
    }
  }

  const handleSetInterval = async () => {
    const { intervalSec } = (await cmdForm.validateFields()) as { intervalSec: number }
    setCmdSending(true)
    try {
      const res = await setReportInterval(deviceKey, intervalSec)
      setCmdOpen(false)
      message.success(`命令已下发（cmdId: ${res.cmdId}）。v1 无设备回执，成功仅表示命令已发到 broker。`)
    } catch {
      /* 错误由拦截器 toast */
    } finally {
      setCmdSending(false)
    }
  }

  if (notFound) {
    return (
      <Card>
        <Empty description={`设备 ${deviceKey} 不存在`}>
          <Button onClick={() => navigate('/devices')}>返回设备列表</Button>
        </Empty>
      </Card>
    )
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        title={
          <Space size={12}>
            <Button icon={<ArrowLeftOutlined />} type="text" onClick={() => navigate('/devices')} />
            <span>{device?.name ?? deviceKey}</span>
            <Typography.Text type="secondary" style={{ fontWeight: 400 }}>
              {deviceKey}
            </Typography.Text>
            {device && <OnlineBadge deviceKey={deviceKey} online={device.online} />}
          </Space>
        }
        extra={
          <Button type="primary" icon={<FieldTimeOutlined />} onClick={() => setCmdOpen(true)}>
            修改上报频率
          </Button>
        }
      >
        <Space size={32} wrap>
          <Statistic title="站点" value={device?.siteId ?? '—'} />
          <Statistic title="最近上报" value={fmtTime(live?.lastSeenAt ?? device?.lastSeenAt ?? null)} />
          <Statistic title="创建时间" value={fmtTime(device?.createdAt ?? null)} />
        </Space>
      </Card>

      {/* 最新状态卡片：各指标当前值 */}
      <Card title="最新状态" size="small">
        {Object.keys(live?.current ?? {}).length === 0 ? (
          <Typography.Text type="secondary">该设备尚未上报数据</Typography.Text>
        ) : (
          <Row gutter={[16, 16]}>
            {Object.entries(live?.current ?? {}).map(([k, v]) => (
              <Col key={k} xs={12} sm={8} md={6} lg={4}>
                <Card size="small" style={{ background: '#101a33' }}>
                  <Statistic title={k} value={fmtVal(v)} valueStyle={{ fontSize: 22 }} />
                </Card>
              </Col>
            ))}
          </Row>
        )}
      </Card>

      {/* 实时曲线（WS 驱动，滚动保留最近 200 点） */}
      <Card
        title="实时曲线"
        size="small"
        extra={
          <Space>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              指标
            </Typography.Text>
            <Select
              size="small"
              style={{ minWidth: 140 }}
              value={metric || undefined}
              placeholder={seenMetrics.length ? '选择指标' : '等待数据…'}
              options={seenMetrics.map((m) => ({ value: m, label: m }))}
              onChange={setMetric}
            />
          </Space>
        }
      >
        {liveSeries.length === 0 ? (
          <Empty description="等待实时数据推送…" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <TelemetryChart series={[{ name: metric, data: liveSeries }]} height={300} />
        )}
      </Card>

      {/* 历史查询（降采样折线） */}
      <Card
        title="历史查询"
        size="small"
        extra={
          <Space wrap>
            <Radio.Group
              size="small"
              value={windowKey}
              onChange={(e) => handleWindowChange(e.target.value)}
              options={TIME_WINDOWS.map((w) => ({ value: w.key, label: w.label }))}
              optionType="button"
            />
            <Select
              size="small"
              style={{ minWidth: 190 }}
              value={interval_}
              options={INTERVAL_OPTIONS}
              onChange={setInterval_}
            />
            <Button
              size="small"
              type="primary"
              icon={<SendOutlined />}
              loading={historyLoading}
              disabled={!metric}
              onClick={queryHistory}
            >
              查询
            </Button>
          </Space>
        }
      >
        {truncated && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message="数据量超过 raw 上限 10000 点，已截断（X-Truncated）。建议改用 1m/5m 等降采样档位。"
          />
        )}
        {history.length === 0 ? (
          <Empty description="选择时间窗后点击「查询」" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <TelemetryChart
            series={[{ name: metric, data: history.map((p) => [p.ts, p.val] as [number, number]) }]}
            height={300}
            zoom
          />
        )}
      </Card>

      {/* ⑭ 下发上报频率（表单校验 1~3600） */}
      <Modal
        title={`修改上报频率：${deviceKey}`}
        open={cmdOpen}
        onOk={handleSetInterval}
        confirmLoading={cmdSending}
        okText="下发命令"
        cancelText="取消"
        onCancel={() => setCmdOpen(false)}
        destroyOnClose
      >
        <Form form={cmdForm} layout="vertical" preserve={false} initialValues={{ intervalSec: 5 }}>
          <Form.Item
            name="intervalSec"
            label="上报间隔（秒）"
            rules={[
              { required: true, message: '请输入上报间隔' },
              { type: 'integer', min: 1, max: 3600, message: '合法范围 1~3600 秒' },
            ]}
            extra="命令经 MQTT 下发到设备；v1 无设备回执，成功仅表示命令已发到 broker。"
          >
            <InputNumber style={{ width: '100%' }} min={1} max={3600} precision={0} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}

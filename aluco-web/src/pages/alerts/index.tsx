import { useEffect, useState } from 'react'
import { Button, Card, Input, Popconfirm, Space, Table, Tabs, App as AntdApp } from 'antd'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { Link } from 'react-router-dom'
import { ackAlert, pageAlerts } from '@/api/alerts'
import { usePagedQuery } from '@/hooks/usePagedQuery'
import { useAlertStore } from '@/stores/alertStore'
import { AlertStatusTag } from '@/components/AlertStatusTag'
import { fmtTime, fmtVal } from '@/utils/format'
import type { AlertEvent, AlertStatus } from '@/types/api'

type StatusFilter = AlertStatus | 'ALL'

export default function AlertsPage() {
  const [status, setStatus] = useState<StatusFilter>('ALL')
  const [deviceKey, setDeviceKey] = useState('')
  const [searchText, setSearchText] = useState('')
  const { rows, total, page, size, loading, setPage, reload } = usePagedQuery<AlertEvent>(
    (p, s) => pageAlerts({ status: status === 'ALL' ? undefined : status, deviceKey, page: p, size: s }),
    [status, deviceKey],
  )
  const alertVersion = useAlertStore((s) => s.alertVersion)
  const { message } = AntdApp.useApp()

  // 收到 WS alert（FIRING 新建 / RESOLVED 恢复）后重拉一次 REST，保证与后端一致
  useEffect(() => {
    if (alertVersion > 0) reload()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [alertVersion])

  const columns: ColumnsType<AlertEvent> = [
    { title: '触发时间', dataIndex: 'triggeredAt', width: 180, render: fmtTime },
    {
      title: '规则',
      dataIndex: 'ruleName',
      render: (v: string | null, row) => v ?? `规则 #${row.ruleId}`, // v1 列表可能为 null，ruleId 兜底
    },
    {
      title: '设备',
      dataIndex: 'deviceKey',
      width: 130,
      render: (v: string) => <Link to={`/devices/${v}`}>{v}</Link>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (v: AlertStatus) => <AlertStatusTag status={v} />,
    },
    { title: '触发值', dataIndex: 'triggerVal', width: 100, render: fmtVal },
    { title: '恢复时间', dataIndex: 'resolvedAt', width: 180, render: fmtTime },
    { title: '确认时间', dataIndex: 'ackedAt', width: 180, render: fmtTime },
    {
      title: '操作',
      key: 'actions',
      width: 100,
      render: (_, row) =>
        row.status === 'FIRING' ? (
          <Popconfirm
            title="确认该告警？"
            okText="确认"
            cancelText="取消"
            onConfirm={async () => {
              await ackAlert(row.id)
              message.success(`告警 #${row.id} 已确认`)
              reload()
            }}
          >
            <Button size="small" type="link">
              确认
            </Button>
          </Popconfirm>
        ) : null,
    },
  ]

  return (
    <Card
      title="告警事件"
      extra={
        <Space>
          <Input
            allowClear
            prefix={<SearchOutlined />}
            placeholder="按设备 deviceKey 筛选"
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
            onPressEnter={() => {
              setPage(1)
              setDeviceKey(searchText.trim())
            }}
            style={{ width: 220 }}
          />
          <Button
            icon={<SearchOutlined />}
            onClick={() => {
              setPage(1)
              setDeviceKey(searchText.trim())
            }}
          >
            筛选
          </Button>
          <Button icon={<ReloadOutlined />} onClick={reload} />
        </Space>
      }
    >
      <Tabs
        activeKey={status}
        onChange={(k) => {
          setStatus(k as StatusFilter)
          setPage(1)
        }}
        items={[
          { key: 'ALL', label: '全部' },
          { key: 'FIRING', label: '告警中' },
          { key: 'RESOLVED', label: '已恢复' },
          { key: 'ACKED', label: '已确认' },
        ]}
      />
      <Table<AlertEvent>
        rowKey="id"
        columns={columns}
        dataSource={rows}
        loading={loading}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: setPage,
        }}
      />
    </Card>
  )
}

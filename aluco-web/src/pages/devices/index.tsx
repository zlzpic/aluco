import { useState } from 'react'
import { Button, Card, Form, Input, Modal, Popconfirm, Space, Table, App as AntdApp } from 'antd'
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { Link, useNavigate } from 'react-router-dom'
import { createDevice, deleteDevice, pageDevices } from '@/api/devices'
import { usePagedQuery } from '@/hooks/usePagedQuery'
import { OnlineBadge } from '@/components/OnlineBadge'
import { TokenRevealModal } from '@/components/TokenRevealModal'
import { fmtTime } from '@/utils/format'
import type { Device, DeviceCreated } from '@/types/api'

export default function DevicesPage() {
  const [keyword, setKeyword] = useState('')
  const [searchText, setSearchText] = useState('')
  const { rows, total, page, size, loading, setPage, reload } = usePagedQuery<Device>(
    (p, s) => pageDevices(keyword, p, s),
    [keyword],
  )
  const [createOpen, setCreateOpen] = useState(false)
  const [creating, setCreating] = useState(false)
  const [created, setCreated] = useState<DeviceCreated | null>(null)
  const [form] = Form.useForm()
  const { message } = AntdApp.useApp()
  const navigate = useNavigate()

  const columns: ColumnsType<Device> = [
    {
      title: '设备标识',
      dataIndex: 'deviceKey',
      render: (v: string) => <Link to={`/devices/${v}`}>{v}</Link>,
    },
    { title: '名称', dataIndex: 'name' },
    { title: '站点', dataIndex: 'siteId', width: 110 },
    {
      title: '状态',
      dataIndex: 'online',
      width: 100,
      render: (v: boolean, row) => <OnlineBadge deviceKey={row.deviceKey} online={v} />,
    },
    { title: '最近上报', dataIndex: 'lastSeenAt', width: 180, render: fmtTime },
    { title: '创建时间', dataIndex: 'createdAt', width: 180, render: fmtTime },
    {
      title: '操作',
      key: 'actions',
      width: 140,
      render: (_, row) => (
        <Space>
          <Button size="small" type="link" onClick={() => navigate(`/devices/${row.deviceKey}`)}>
            详情
          </Button>
          <Popconfirm
            title={`删除设备 ${row.deviceKey}？`}
            description="实时状态将级联删除，历史遥测与告警保留。"
            okText="删除"
            okButtonProps={{ danger: true }}
            cancelText="取消"
            onConfirm={async () => {
              await deleteDevice(row.deviceKey)
              message.success(`设备 ${row.deviceKey} 已删除`)
              reload()
            }}
          >
            <Button size="small" type="link" danger>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  const handleCreate = async () => {
    const values = await form.validateFields()
    setCreating(true)
    try {
      const res = await createDevice(values)
      setCreateOpen(false)
      form.resetFields()
      setCreated(res) // token 仅此一次，必须弹窗展示
      reload()
    } catch {
      /* 409 等错误由拦截器 toast */
    } finally {
      setCreating(false)
    }
  }

  return (
    <Card
      title="设备管理"
      extra={
        <Space>
          <Input
            allowClear
            prefix={<SearchOutlined />}
            placeholder="搜索 deviceKey / 名称"
            value={searchText}
            onChange={(e) => setSearchText(e.target.value)}
            onPressEnter={() => {
              setPage(1)
              setKeyword(searchText.trim())
            }}
            style={{ width: 240 }}
          />
          <Button
            icon={<SearchOutlined />}
            onClick={() => {
              setPage(1)
              setKeyword(searchText.trim())
            }}
          >
            搜索
          </Button>
          <Button icon={<ReloadOutlined />} onClick={reload} />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            新建设备
          </Button>
        </Space>
      }
    >
      <Table<Device>
        rowKey="deviceKey"
        columns={columns}
        dataSource={rows}
        loading={loading}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 台`,
          onChange: setPage,
        }}
      />

      <Modal
        title="新建设备"
        open={createOpen}
        onOk={handleCreate}
        confirmLoading={creating}
        okText="创建"
        cancelText="取消"
        onCancel={() => setCreateOpen(false)}
        destroyOnClose
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item
            name="deviceKey"
            label="设备标识（deviceKey）"
            rules={[
              { required: true, message: '请输入 deviceKey' },
              { pattern: /^[a-zA-Z0-9_-]{1,64}$/, message: '仅支持字母、数字、下划线、中划线，≤64 字符' },
            ]}
          >
            <Input placeholder="如 TH-0001" />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input placeholder="如 Sensor 1" />
          </Form.Item>
          <Form.Item name="siteId" label="站点（siteId）" rules={[{ required: true, message: '请输入 siteId' }]}>
            <Input placeholder="如 site-01" />
          </Form.Item>
        </Form>
      </Modal>

      <TokenRevealModal
        open={created !== null}
        deviceKey={created?.deviceKey ?? ''}
        token={created?.token ?? ''}
        onClose={() => setCreated(null)}
      />
    </Card>
  )
}

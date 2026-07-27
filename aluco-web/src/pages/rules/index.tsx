import { useEffect, useState } from 'react'
import {
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  App as AntdApp,
} from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import { createRule, deleteRule, pageRules, setRuleEnabled } from '@/api/rules'
import { pageDevices } from '@/api/devices'
import { usePagedQuery } from '@/hooks/usePagedQuery'
import { OP_OPTIONS, OP_TEXT } from '@/theme/semantic'
import { fmtTime } from '@/utils/format'
import type { Device, Rule, RuleOp } from '@/types/api'

const ALL_DEVICES = '__ALL__'

export default function RulesPage() {
  const { rows, total, page, size, loading, setPage, reload } = usePagedQuery<Rule>((p, s) =>
    pageRules(p, s),
  )
  const [createOpen, setCreateOpen] = useState(false)
  const [creating, setCreating] = useState(false)
  const [deviceOptions, setDeviceOptions] = useState<Device[]>([])
  const [form] = Form.useForm()
  const { message } = AntdApp.useApp()

  // 设备下拉数据源（showSearch 按 deviceKey 过滤）
  useEffect(() => {
    pageDevices('', 1, 200)
      .then((res) => setDeviceOptions(res.list))
      .catch(() => {})
  }, [])

  const columns: ColumnsType<Rule> = [
    { title: '规则名称', dataIndex: 'name' },
    { title: '指标', dataIndex: 'metric', width: 120 },
    {
      title: '条件',
      key: 'cond',
      width: 140,
      render: (_, r) => (
        <Tag color="orange">{`${r.metric} ${OP_TEXT[r.op]} ${r.thresholdVal}`}</Tag>
      ),
    },
    {
      title: '作用范围',
      dataIndex: 'deviceKey',
      width: 140,
      render: (v: string | null) => (v === null ? <Tag color="blue">全部设备</Tag> : <Tag>{v}</Tag>),
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      width: 90,
      render: (v: boolean, r) => (
        <Switch
          size="small"
          checked={v}
          onChange={async (checked) => {
            await setRuleEnabled(r.id, checked)
            message.success(checked ? `规则「${r.name}」已启用` : `规则「${r.name}」已停用`)
            reload()
          }}
        />
      ),
    },
    { title: '更新时间', dataIndex: 'updatedAt', width: 180, render: fmtTime },
    {
      title: '操作',
      key: 'actions',
      width: 100,
      render: (_, r) => (
        <Popconfirm
          title={`删除规则「${r.name}」？`}
          description="其下 FIRING 告警事件将自动置为已恢复。"
          okText="删除"
          okButtonProps={{ danger: true }}
          cancelText="取消"
          onConfirm={async () => {
            await deleteRule(r.id)
            message.success(`规则「${r.name}」已删除`)
            reload()
          }}
        >
          <Button size="small" type="link" danger>
            删除
          </Button>
        </Popconfirm>
      ),
    },
  ]

  const handleCreate = async () => {
    const values = (await form.validateFields()) as {
      name: string
      metric: string
      op: RuleOp
      threshold: number
      deviceKey: string
    }
    setCreating(true)
    try {
      await createRule({
        name: values.name,
        metric: values.metric,
        op: values.op,
        threshold: values.threshold, // 请求字段 threshold（响应回来叫 thresholdVal）
        deviceKey: values.deviceKey === ALL_DEVICES ? null : values.deviceKey,
      })
      setCreateOpen(false)
      form.resetFields()
      message.success('规则创建成功')
      reload()
    } catch {
      /* 400/404 由拦截器 toast */
    } finally {
      setCreating(false)
    }
  }

  return (
    <Card
      title="告警规则"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={reload} />
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
            新建规则
          </Button>
        </Space>
      }
    >
      <Table<Rule>
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

      <Modal
        title="新建告警规则"
        open={createOpen}
        onOk={handleCreate}
        confirmLoading={creating}
        okText="创建"
        cancelText="取消"
        onCancel={() => setCreateOpen(false)}
        destroyOnClose
      >
        <Form
          form={form}
          layout="vertical"
          preserve={false}
          initialValues={{ op: 'GT' as RuleOp, deviceKey: ALL_DEVICES }}
        >
          <Form.Item name="name" label="规则名称" rules={[{ required: true, message: '请输入规则名称' }]}>
            <Input placeholder="如 温度过高" />
          </Form.Item>
          <Form.Item name="metric" label="监控指标" rules={[{ required: true, message: '请输入指标名' }]}>
            <Input placeholder="temp、humidity（模拟器默认指标）" />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="op" label="比较符" rules={[{ required: true }]} style={{ minWidth: 180 }}>
              <Select options={OP_OPTIONS} />
            </Form.Item>
            <Form.Item
              name="threshold"
              label="阈值"
              rules={[{ required: true, message: '请输入阈值' }]}
              style={{ flex: 1 }}
            >
              <InputNumber style={{ width: '100%' }} placeholder="如 30" step={0.1} />
            </Form.Item>
          </Space>
          <Form.Item name="deviceKey" label="作用范围" rules={[{ required: true }]}>
            <Select
              showSearch
              optionFilterProp="label"
              placeholder="选择设备，或作用于全部设备"
              options={[
                { value: ALL_DEVICES, label: '全部设备' },
                ...deviceOptions.map((d) => ({
                  value: d.deviceKey,
                  label: `${d.deviceKey}（${d.name}）`,
                })),
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}

import { useState } from 'react'
import { Button, Card, Form, Input, Typography, App as AntdApp, Space, Switch, Tooltip } from 'antd'
import { ApiOutlined, LockOutlined, UserOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { login } from '@/api/auth'
import { useAuthStore } from '@/stores/authStore'
import { useMockStore } from '@/stores/mockStore'

export default function LoginPage() {
  const [loading, setLoading] = useState(false)
  const setAuth = useAuthStore((s) => s.setAuth)
  const mockEnabled = useMockStore((s) => s.enabled)
  const setMockEnabled = useMockStore((s) => s.setEnabled)
  const navigate = useNavigate()
  const { message } = AntdApp.useApp()

  const onFinish = async (values: { username: string; password: string }) => {
    setLoading(true)
    try {
      const res = await login(values)
      setAuth(res.token, res.expiresAt)
      navigate('/dashboard', { replace: true })
    } catch {
      /* 401 提示由拦截器 toast；这里仅复位按钮 */
    } finally {
      setLoading(false)
    }
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'radial-gradient(ellipse at 50% 30%, #16224a 0%, #0b1020 65%)',
      }}
    >
      <Card style={{ width: 380, boxShadow: '0 8px 40px rgba(0,0,0,0.45)' }}>
        <Space direction="vertical" align="center" style={{ width: '100%', marginBottom: 24 }}>
          <ApiOutlined style={{ fontSize: 36, color: '#4f8cff' }} />
          <Typography.Title level={3} style={{ margin: 0 }}>
            Aluco 机群监控平台
          </Typography.Title>
          <Typography.Text type="secondary">IoT 设备机群监控 · v1</Typography.Text>
        </Space>
        <Form layout="vertical" onFinish={onFinish} initialValues={{ username: 'admin' }}>
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="admin" autoComplete="username" />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="admin123" autoComplete="current-password" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block loading={loading}>
            登 录
          </Button>
        </Form>
        <div style={{ marginTop: 16, textAlign: 'center' }}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            演示账号：admin / admin123
          </Typography.Text>
        </div>
        <div style={{ marginTop: 12, textAlign: 'center' }}>
          <Tooltip title="无后端环境下使用内置模拟数据演示全部功能；对接真实后端时请关闭">
            <Space size={6}>
              <Typography.Text type={mockEnabled ? 'warning' : 'secondary'} style={{ fontSize: 12 }}>
                Mock 演示模式
              </Typography.Text>
              <Switch
                size="small"
                checked={mockEnabled}
                onChange={(v) => {
                  setMockEnabled(v)
                  message.info(v ? '已开启 Mock 演示模式' : '已切换为真实后端模式')
                }}
              />
            </Space>
          </Tooltip>
        </div>
      </Card>
    </div>
  )
}

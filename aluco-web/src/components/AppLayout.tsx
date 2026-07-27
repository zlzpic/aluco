import { useEffect } from 'react'
import { Layout, Menu, Button, Space, Switch, Tooltip, Typography, App as AntdApp } from 'antd'
import {
  DashboardOutlined,
  HddOutlined,
  AlertOutlined,
  BellOutlined,
  LogoutOutlined,
  ApiOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate, Navigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/authStore'
import { useMockStore } from '@/stores/mockStore'
import { useAlertStore } from '@/stores/alertStore'
import { liveClient } from '@/ws/liveClient'
import { registerErrorToast } from '@/api/http'

const { Sider, Header, Content } = Layout

/** 桥接：把 axios 错误 toast 与 WS 告警 toast 接入 AntD App context（深色主题生效） */
function ToastBridge() {
  const { message, notification } = AntdApp.useApp()
  const toasts = useAlertStore((s) => s.toasts)
  const consumeToast = useAlertStore((s) => s.consumeToast)

  useEffect(() => {
    registerErrorToast((msg) => message.error(msg))
  }, [message])

  useEffect(() => {
    for (const t of toasts) {
      const e = t.event
      if (e.status === 'FIRING') {
        notification.error({
          message: `告警触发：${e.ruleName ?? `规则 #${e.ruleId ?? '-'}`}`,
          description: `设备 ${e.deviceId} 触发值 ${e.value}`,
          placement: 'bottomRight',
        })
      } else {
        notification.success({
          message: `告警恢复：${e.ruleName ?? `规则 #${e.ruleId ?? '-'}`}`,
          description: `设备 ${e.deviceId} 已恢复正常（当前值 ${e.value}）`,
          placement: 'bottomRight',
        })
      }
      consumeToast(t.key)
    }
  }, [toasts, consumeToast, notification])

  return null
}

const MENU_ITEMS = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '总览' },
  { key: '/devices', icon: <HddOutlined />, label: '设备' },
  { key: '/rules', icon: <AlertOutlined />, label: '告警规则' },
  { key: '/alerts', icon: <BellOutlined />, label: '告警事件' },
]

export function AppLayout() {
  const token = useAuthStore((s) => s.token)
  const clear = useAuthStore((s) => s.clear)
  const mockEnabled = useMockStore((s) => s.enabled)
  const setMockEnabled = useMockStore((s) => s.setEnabled)
  const navigate = useNavigate()
  const location = useLocation()

  // 登录后建立 WS 单例连接（登出/401 由对应入口 disconnect）
  useEffect(() => {
    if (token) liveClient.connect()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, mockEnabled])

  if (!token) return <Navigate to="/login" replace />

  const selectedKey =
    MENU_ITEMS.map((i) => i.key)
      .filter((k) => location.pathname.startsWith(k))
      .sort((a, b) => b.length - a.length)[0] ?? '/dashboard'

  const handleLogout = () => {
    liveClient.disconnect()
    clear()
    navigate('/login', { replace: true })
  }

  const handleMockToggle = (checked: boolean) => {
    setMockEnabled(checked)
    // 整页刷新，保证 REST/WS 通道与内存状态干净重建
    window.setTimeout(() => window.location.reload(), 150)
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider width={208} theme="dark">
        <div
          style={{
            height: 56,
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: '0 20px',
            color: '#fff',
            fontSize: 17,
            fontWeight: 700,
            letterSpacing: 0.5,
          }}
        >
          <ApiOutlined style={{ color: '#4f8cff', fontSize: 20 }} />
          Aluco 机群监控
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={MENU_ITEMS}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            padding: '0 24px',
            borderBottom: '1px solid #222c47',
            height: 56,
            lineHeight: 'normal',
          }}
        >
          <Space size={16}>
            <Tooltip title="内置模拟 REST + WebSocket 数据，仅用于开发/预览演示，默认关闭">
              <Space size={6}>
                <Typography.Text type={mockEnabled ? 'warning' : 'secondary'} style={{ fontSize: 13 }}>
                  Mock 演示模式
                </Typography.Text>
                <Switch size="small" checked={mockEnabled} onChange={handleMockToggle} />
              </Space>
            </Tooltip>
            <Typography.Text type="secondary" style={{ fontSize: 13 }}>
              admin
            </Typography.Text>
            <Button size="small" icon={<LogoutOutlined />} onClick={handleLogout}>
              退出登录
            </Button>
          </Space>
        </Header>
        <Content style={{ padding: 24 }}>
          <Outlet />
        </Content>
      </Layout>
      <ToastBridge />
    </Layout>
  )
}

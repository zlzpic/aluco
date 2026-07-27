import { ConfigProvider, App as AntdApp, theme as antdTheme } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { HashRouter, Navigate, Route, Routes } from 'react-router-dom'
import { alucoTheme } from '@/theme/antdTheme'
import { AppLayout } from '@/components/AppLayout'
import LoginPage from '@/pages/login'
import DashboardPage from '@/pages/dashboard'
import DevicesPage from '@/pages/devices'
import DeviceDetailPage from '@/pages/deviceDetail'
import RulesPage from '@/pages/rules'
import AlertsPage from '@/pages/alerts'

// 静态预览无服务端路由重写，使用 HashRouter 保证任意路由可达
export default function App() {
  return (
    <ConfigProvider locale={zhCN} theme={{ ...alucoTheme, algorithm: antdTheme.darkAlgorithm }}>
      <AntdApp>
        <HashRouter>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route element={<AppLayout />}>
              <Route path="/dashboard" element={<DashboardPage />} />
              <Route path="/devices" element={<DevicesPage />} />
              <Route path="/devices/:deviceKey" element={<DeviceDetailPage />} />
              <Route path="/rules" element={<RulesPage />} />
              <Route path="/alerts" element={<AlertsPage />} />
              <Route path="/" element={<Navigate to="/dashboard" replace />} />
              <Route path="*" element={<Navigate to="/dashboard" replace />} />
            </Route>
          </Routes>
        </HashRouter>
      </AntdApp>
    </ConfigProvider>
  )
}

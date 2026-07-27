import { theme as antdThemeAlgorithm } from 'antd'
import type { ThemeConfig } from 'antd'

/** 深色 IoT 科技风：AntD 5 暗色主题算法（与 Grafana 面板视觉统一） */
export const alucoTheme: ThemeConfig = {
  algorithm: antdThemeAlgorithm.darkAlgorithm,
  token: {
    colorPrimary: '#4f8cff',
    colorInfo: '#4f8cff',
    colorBgLayout: '#0b1020',
    colorBgContainer: '#141b2e',
    colorBgElevated: '#1a2340',
    colorBorder: '#2a3554',
    colorBorderSecondary: '#222c47',
    borderRadius: 8,
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif",
  },
  components: {
    Layout: {
      siderBg: '#0d1326',
      headerBg: '#0d1326',
    },
    Table: {
      headerBg: '#182138',
      rowHoverBg: '#1b2742',
    },
    Card: {
      colorBgContainer: '#141b2e',
    },
  },
}

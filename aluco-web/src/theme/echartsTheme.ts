import * as echarts from 'echarts'

export const ALUCO_ECHARTS_THEME = 'aluco-dark'

let registered = false

/** ECharts 深色主题，与 AntD darkAlgorithm 面板配色统一 */
export function registerEchartsTheme(): void {
  if (registered) return
  registered = true
  echarts.registerTheme(ALUCO_ECHARTS_THEME, {
    backgroundColor: 'transparent',
    textStyle: { color: '#c7d0e8' },
    title: { textStyle: { color: '#c7d0e8' } },
    legend: { textStyle: { color: '#c7d0e8' } },
    grid: { borderColor: '#2a3554' },
    categoryAxis: {
      axisLine: { lineStyle: { color: '#2a3554' } },
      axisTick: { lineStyle: { color: '#2a3554' } },
      axisLabel: { color: '#8b97b8' },
      splitLine: { lineStyle: { color: '#222c47' } },
    },
    valueAxis: {
      axisLine: { lineStyle: { color: '#2a3554' } },
      axisTick: { lineStyle: { color: '#2a3554' } },
      axisLabel: { color: '#8b97b8' },
      splitLine: { lineStyle: { color: '#222c47' } },
    },
    timeAxis: {
      axisLine: { lineStyle: { color: '#2a3554' } },
      axisLabel: { color: '#8b97b8' },
      splitLine: { lineStyle: { color: '#222c47' } },
    },
    dataZoom: {
      backgroundColor: '#141b2e',
      fillerColor: 'rgba(79,140,255,0.18)',
      borderColor: '#2a3554',
      handleStyle: { color: '#4f8cff' },
      textStyle: { color: '#8b97b8' },
    },
    tooltip: {
      backgroundColor: '#1a2340',
      borderColor: '#2a3554',
      textStyle: { color: '#c7d0e8' },
    },
  })
}

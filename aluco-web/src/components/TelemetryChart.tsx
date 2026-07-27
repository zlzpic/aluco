import { useMemo } from 'react'
import ReactECharts from 'echarts-for-react'
import { registerEchartsTheme, ALUCO_ECHARTS_THEME } from '@/theme/echartsTheme'

registerEchartsTheme()

export interface ChartSeries {
  name: string
  data: [number, number][]
}

interface Props {
  series: ChartSeries[]
  height?: number
  /** 历史查询开启 dataZoom；实时滚动曲线默认关闭 */
  zoom?: boolean
  unit?: string
}

const PALETTE = ['#4f8cff', '#52c41a', '#faad14', '#eb2f96', '#13c2c2', '#a0d911']

export function TelemetryChart({ series, height = 320, zoom = false, unit }: Props) {
  const option = useMemo(
    () => ({
      animation: false,
      color: PALETTE,
      grid: { left: 56, right: 24, top: 36, bottom: zoom ? 64 : 40 },
      tooltip: {
        trigger: 'axis',
        valueFormatter: (v: number) => (unit ? `${v} ${unit}` : String(v)),
      },
      legend: series.length > 1 ? { top: 0 } : undefined,
      xAxis: {
        type: 'time',
        axisLabel: { hideOverlap: true },
      },
      yAxis: {
        type: 'value',
        scale: true,
        name: unit,
      },
      dataZoom: zoom
        ? [
            { type: 'inside', throttle: 50 },
            { type: 'slider', height: 20, bottom: 8 },
          ]
        : undefined,
      series: series.map((s) => ({
        name: s.name,
        type: 'line',
        showSymbol: false,
        smooth: 0.2,
        lineStyle: { width: 2 },
        emphasis: { disabled: true },
        data: s.data,
      })),
    }),
    [series, zoom, unit],
  )

  return (
    <ReactECharts
      theme={ALUCO_ECHARTS_THEME}
      option={option}
      notMerge
      style={{ height, width: '100%' }}
    />
  )
}

import { Alert, Modal, Typography } from 'antd'
import { ExclamationCircleFilled } from '@ant-design/icons'

interface Props {
  open: boolean
  deviceKey: string
  token: string
  onClose: () => void
}

/**
 * 新建设备成功后的 token 一次性展示弹窗（验收要求）。
 * token 仅创建时返回一次（对接文档 3.1-②），「仅显示一次」警示必须醒目。
 */
export function TokenRevealModal({ open, deviceKey, token, onClose }: Props) {
  return (
    <Modal
      open={open}
      title={`设备 ${deviceKey} 创建成功`}
      footer={null}
      closable={false}
      maskClosable={false}
      onCancel={onClose}
      width={520}
    >
      <Alert
        type="warning"
        showIcon
        icon={<ExclamationCircleFilled />}
        message="此接入凭证仅显示一次，关闭后无法再次查看，请立即复制保存！"
        style={{ marginBottom: 16, fontWeight: 600 }}
      />
      <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
        接入凭证（v2 起用于设备认证，v1 无需配置）：
      </Typography.Paragraph>
      <Typography.Paragraph
        copyable={{ text: token, tooltips: ['复制', '已复制'] }}
        code
        style={{ fontSize: 15, wordBreak: 'break-all', padding: '8px 12px' }}
      >
        {token}
      </Typography.Paragraph>
      <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
        说明：v1 的 MQTT broker 为匿名模式，该 token 为预留凭证，当前无需（也无法）用于设备连接配置。
      </Typography.Paragraph>
      <div style={{ textAlign: 'right', marginTop: 8 }}>
        <Typography.Link onClick={onClose} strong>
          我已保存，关闭
        </Typography.Link>
      </div>
    </Modal>
  )
}

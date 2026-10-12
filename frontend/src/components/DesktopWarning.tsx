/**
 * PHASE98 R5 修复说明：
 * - 原为 `position:'fixed'` + `zIndex:9999`，且调用方（Screen）没有传 onDismiss，
 *   于是这条横幅**既关不掉、又因为没有 padding 补偿而永久遮挡页面顶部**
 *   （在 SchemaDesigner 里会盖住 <h1>）。
 * - 改为 `position:'sticky'`：横幅进入正常文档流，内容自然下推，不再遮挡；
 *   同时外层补 flexWrap，避免窄屏下长文案换行后高度失控。
 */
export function DesktopWarning({ onDismiss }: { onDismiss?: () => void }) {
  return (
    <div
      role="region"
      aria-label="移动端访问提示"
      style={{
        position: 'sticky',
        top: 0,
        left: 0,
        right: 0,
        padding: '12px 16px',
        background: 'var(--color-warning)',
        color: 'var(--color-text-on-warning)',
        textAlign: 'center',
        zIndex: 9999,
        boxShadow: '0 2px 4px rgba(0,0,0,0.1)',
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flexWrap: 'wrap',
          gap: 8,
          maxWidth: 1200,
          margin: '0 auto',
        }}
      >
        <span style={{ fontSize: 18 }}>⚠️</span>
        <span style={{ fontWeight: 500 }}>该页面建议使用桌面端访问，以获得最佳体验。</span>
        {onDismiss && (
          <button
            onClick={onDismiss}
            style={{
              marginLeft: '12px',
              background: 'transparent',
              border: '1px solid currentColor',
              borderRadius: 4,
              padding: '4px 12px',
              cursor: 'pointer',
              fontSize: 14,
            }}
          >
            知道了
          </button>
        )}
      </div>
    </div>
  );
}
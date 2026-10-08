export function DesktopWarning({ onDismiss }: { onDismiss?: () => void }) {
  return (
    <div style={{
      position: 'fixed',
      top: 0,
      left: 0,
      right: 0,
      padding: '16px',
      background: 'var(--color-warning)',
      color: 'var(--color-text-on-warning)',
      textAlign: 'center',
      zIndex: 9999,
      boxShadow: '0 2px 4px rgba(0,0,0,0.1)',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, maxWidth: 1200, margin: '0 auto' }}>
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
import React, { useState } from 'react';
import { DesktopWarning } from './DesktopWarning';
import { useIsMobile } from '../hooks/useIsMobile';

const DISMISS_KEY = 'desktop-warning-dismissed';

/**
 * PHASE98 R5 修复说明：
 * 原实现 `{isMobile && <DesktopWarning />}` **不传 onDismiss**，
 * 导致 DesktopWarning 里的"知道了"按钮（`{onDismiss && ...}`）根本不渲染
 * —— 移动端用户看到的是一条**关不掉**的横幅。
 * 现在传入 onDismiss 并把"已忽略"持久化到 localStorage。
 */
export function Screen({ children }: { children: React.ReactNode }) {
  const isMobile = useIsMobile();
  const [dismissed, setDismissed] = useState(
    () => localStorage.getItem(DISMISS_KEY) === '1'
  );

  const handleDismiss = () => {
    localStorage.setItem(DISMISS_KEY, '1');
    setDismissed(true);
  };

  return (
    <>
      {isMobile && !dismissed && <DesktopWarning onDismiss={handleDismiss} />}
      {children}
    </>
  );
}

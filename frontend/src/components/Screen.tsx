import React from 'react';
import { DesktopWarning } from './DesktopWarning';
import { useIsMobile } from '../hooks/useIsMobile';

export function Screen({ children }: { children: React.ReactNode }) {
  const isMobile = useIsMobile();
  return (
    <>
      {isMobile && <DesktopWarning />}
      {children}
    </>
  );
}
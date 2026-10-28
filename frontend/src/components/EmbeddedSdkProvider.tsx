import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { useJsSdk, type JsPlatform, type UseJsSdkResult } from '@/hooks/useJsSdk';

/**
 * 工作台嵌入 SDK 全局上下文。
 *
 * <p>用途：在应用根部一次性注入钉钉 / 企微 / 微信 / 飞书的 JS-SDK config，
 * 供任意子页面通过 {@link useEmbeddedSdk} 读取 `platform` / `ready` / `error`，
 * 无需每个页面各自重复注入（重复 config 会互相覆盖且浪费一次签名请求）。
 *
 * <p>普通浏览器下 `platform` 恒为 `null`、`ready` 恒为 `false`，
 * 页面据此跳过原生能力调用，不影响 Web 端功能。
 */

const EmbeddedSdkContext = createContext<UseJsSdkResult>({
  platform: null,
  ready: false,
  error: null,
});

export interface EmbeddedSdkProviderProps {
  children: ReactNode;
  /** 是否启用注入（默认 true；测试或纯 Web 部署可传 false 彻底关闭） */
  enabled?: boolean;
  /** ticket 类型：企微应用级能力传 'agent' */
  type?: string;
}

/**
 * 包裹应用子树，完成 JS-SDK 全局注入。
 *
 * @example
 * <EmbeddedSdkProvider>
 *   <AppLayout />
 * </EmbeddedSdkProvider>
 */
export function EmbeddedSdkProvider({ children, enabled = true, type }: EmbeddedSdkProviderProps) {
  const sdk = useJsSdk({ enabled, type });
  const value = useMemo(
    () => ({ platform: sdk.platform, ready: sdk.ready, error: sdk.error }),
    [sdk.platform, sdk.ready, sdk.error]
  );
  return <EmbeddedSdkContext.Provider value={value}>{children}</EmbeddedSdkContext.Provider>;
}

/** 读取全局 SDK 注入状态。 */
export function useEmbeddedSdk(): UseJsSdkResult {
  return useContext(EmbeddedSdkContext);
}

/** 判断是否处于指定工作台且 SDK 已就绪。 */
export function useEmbeddedReady(platform: JsPlatform): boolean {
  const { platform: current, ready } = useEmbeddedSdk();
  return current === platform && ready;
}

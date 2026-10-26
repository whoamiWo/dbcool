import { useEffect, useState } from 'react';
import apiClient from '@/api/client';

/**
 * 工作台嵌入 JS-SDK 注入（钉钉 / 企业微信 / 飞书）。
 *
 * <p>用途：页面在钉钉 / 企微 / 飞书内置浏览器打开时，注入各平台 config 完成鉴权，
 * 之后才能调用原生能力（选人、拍照、定位、分享）。普通浏览器不执行任何逻辑。
 *
 * <p>流程：检测 UA → 加载平台 SDK 脚本 → 拉签名（GET /api/js-signature）→ 调用 config。
 */

export type JsPlatform = 'dingtalk' | 'wecom' | 'feishu';

/** 各平台 JS-SDK 脚本地址（可由调用方覆盖，便于内网/CDN 替换）。 */
const SDK_URLS: Record<JsPlatform, string> = {
  dingtalk: 'https://g.alicdn.com/dingding/open-develop/1.9.0/dingtalk.open.js',
  wecom: 'https://res.wx.qq.com/open/js/jweixin-1.6.0.js',
  feishu: 'https://lf1-cdn-tos.bytegoofy.com/goofy/ee/ee-sdk/current/h5-js-sdk.js',
};

/** 通过 UA 判断当前所处工作台；非三平台返回 null（普通浏览器不注入）。 */
export function detectPlatform(): JsPlatform | null {
  if (typeof navigator === 'undefined') return null;
  const ua = navigator.userAgent;
  if (/DingTalk/i.test(ua)) return 'dingtalk';
  if (/wxwork/i.test(ua)) return 'wecom';
  if (/Lark/i.test(ua) || /Feishu/i.test(ua)) return 'feishu';
  return null;
}

/** 动态加载脚本（幂等：已存在同 URL 的 script 则复用）。 */
function loadScript(src: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${src}"]`);
    if (existing) {
      resolve();
      return;
    }
    const script = document.createElement('script');
    script.src = src;
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error(`加载 JS-SDK 失败：${src}`));
    document.head.appendChild(script);
  });
}

interface JsSignatureData {
  appId: string;
  nonceStr: string;
  timestamp: number;
  url: string;
  signature: string;
  type: string;
}

export interface UseJsSdkOptions {
  /** ticket 类型：企微 agent 级能力传 'agent'，默认 'corp' */
  type?: string;
  /** 覆盖默认 SDK 脚本地址 */
  sdkUrls?: Partial<Record<JsPlatform, string>>;
  /** 是否启用（默认 true；可传 false 便于测试禁用） */
  enabled?: boolean;
}

export interface UseJsSdkResult {
  platform: JsPlatform | null;
  /** 是否已成功注入 config */
  ready: boolean;
  error: string | null;
}

/**
 * 注入工作台 JS-SDK。
 *
 * @example
 * const { platform, ready, error } = useJsSdk();
 * // ready 为 true 后可调用 window.dd.xxx / window.wx.xxx
 */
export function useJsSdk(options: UseJsSdkOptions = {}): UseJsSdkResult {
  const { type, sdkUrls, enabled = true } = options;

  const [platform] = useState<JsPlatform | null>(() =>
    enabled ? detectPlatform() : null
  );
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!platform) return;
    let cancelled = false;

    (async () => {
      try {
        // 1. 加载平台 SDK
        const url = sdkUrls?.[platform] ?? SDK_URLS[platform];
        if (url) await loadScript(url);
        if (cancelled) return;

        // 2. 拉签名（url 必须不含 # 及其后部分）
        const pageUrl = window.location.href.split('#')[0];
        const params = new URLSearchParams({ platform, url: pageUrl });
        if (type) params.set('type', type);
        const res = await apiClient.get(`/js-signature?${params.toString()}`);
        const envelope = (res as any)?.data ?? res;
        if (!envelope || envelope.code !== 0) {
          throw new Error(envelope?.message ?? '获取签名失败');
        }
        const sig = envelope.data as JsSignatureData;
        if (cancelled) return;

        // 3. 注入 config
        const anyWin = window as any;
        if (platform === 'dingtalk' && anyWin.dd) {
          anyWin.dd.config({
            agentId: sig.appId,
            corpId: sig.appId,
            timeStamp: String(sig.timestamp),
            nonceStr: sig.nonceStr,
            signature: sig.signature,
            type: 0,
          });
        } else if (platform === 'wecom' && anyWin.wx) {
          if (sig.type === 'agent') {
            // 应用级能力（选人、打开会话）走 agentConfig
            anyWin.wx.agentConfig({
              corpid: sig.appId,
              agentid: (sig as any).agentId ?? '',
              timestamp: sig.timestamp,
              nonceStr: sig.nonceStr,
              signature: sig.signature,
              jsApiList: ['selectExternalContact', 'openEnterpriseChat'],
            });
          } else {
            anyWin.wx.config({
              beta: true,
              appId: sig.appId,
              timestamp: sig.timestamp,
              nonceStr: sig.nonceStr,
              signature: sig.signature,
              jsApiList: ['chooseImage', 'getLocation', 'onMenuShareAppMessage'],
            });
          }
        } else if (platform === 'feishu') {
          // 飞书：h5Webview / JSBridge 注入
          const bridge = anyWin.FeishuJSBridge ?? anyWin.h5Webview;
          if (bridge?.call) {
            bridge.call('config', {
              appId: sig.appId,
              timestamp: sig.timestamp,
              nonceStr: sig.nonceStr,
              signature: sig.signature,
            });
          }
        }

        if (!cancelled) setReady(true);
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : String(e));
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [platform, type, sdkUrls]);

  return { platform, ready, error };
}

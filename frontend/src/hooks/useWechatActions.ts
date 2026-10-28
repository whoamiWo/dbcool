/**
 * 微信 JS-SDK 功能封装 — 拍照、分享、上传等。
 *
 * <p>使用前提：<br/>
 * 1. 已在微信内打开（`detectPlatform() === 'wechat'`）<br/>
 * 2. `useJsSdk()` 的 `ready === true`（config 注入成功）<br/>
 * 3. 微信公众号后台已配置 JS 安全域名
 *
 * <h2>使用示例</h2>
 *
 * ```tsx
 * import { useWechatActions } from '@/hooks/useWechatActions';
 *
 * function ShareButton() {
 *   const { platform, ready, error } = useJsSdk();
 *   const { shareAppMessage, chooseImage, uploadImage } = useWechatActions();
 *
 *   if (platform !== 'wechat') return null;
 *   if (!ready) return <div>初始化中...</div>;
 *
 *   const handleShare = async () => {
 *     const ok = await shareAppMessage({
 *       title: '分享标题',
 *       desc: '分享内容',
 *       link: window.location.href,
 *     });
 *     console.log('分享结果:', ok ? '成功' : '失败');
 *   };
 *
 *   const handlePhoto = async () => {
 *     const result = await chooseImage({ count: 3 });
 *     if (result.localIds.length > 0) {
 *       const serverId = await uploadImage(result.localIds[0]);
 *       console.log('图片上传 ID:', serverId);
 *     }
 *   };
 *
 *   return (
 *     <>
 *       <button onClick={handleShare}>分享</button>
 *       <button onClick={handlePhoto}>拍照</button>
 *     </>
 *   );
 * }
 * ```
 *
 * <h2>功能列表</h2>
 *
 * <ul>
 *   <li>{@link useWechatShare} — 分享给朋友/朋友圈</li>
 *   <li>{@link useWechatPhoto} — 拍照/从相册选择</li>
 *   <li>{@link useWechatUpload} — 上传图片/语音</li>
 *   <li>{@link useWechatLocation} — 获取地理位置</li>
 * </ul>
 */

import { useCallback } from 'react';

export interface ShareMessageParams {
  title: string;      // 分享标题
  desc?: string;      // 分享描述
  link?: string;      // 分享链接，默认当前 URL
  imgUrl?: string;    // 分享图标
}

export interface ChooseImageParams {
  sourceType?: ('album' | 'camera')[];  // 来源类型
  count?: number;                       // 最大图片数，默认 9
  sizeType?: ('original' | 'compressed')[];  // 图片大小
}

export interface ChooseImageResult {
  localIds: string[];      // 本地图片 ID
  images: string[];        // 本地图片路径（新版本）
}

export interface UploadResult {
  serverId: string;        // 上传到服务器的图片 ID
}

export interface LocationResult {
  latitude: number;        // 纬度
  longitude: number;       // 经度
  precision: number;       // 精度
}

/**
 * 微信分享功能。
 */
export function useWechatShare() {
  const shareAppMessage = useCallback(async (params: ShareMessageParams) => {
    const wx = (window as any).wx;
    if (!wx || !wx.ready) {
      console.warn('[WeChat] wx not ready');
      return false;
    }

    return new Promise<boolean>((resolve) => {
      wx.updateAppMessageShareData({
        title: params.title,
        desc: params.desc || '',
        link: params.link || window.location.href,
        imgUrl: params.imgUrl || '',
        success: () => resolve(true),
        cancel: () => resolve(false),
      });

      // 兼容性：旧版 API
      wx.updateTimelineShareData({
        title: params.title,
        link: params.link || window.location.href,
        imgUrl: params.imgUrl || '',
      });

      wx.onMenuShareAppMessage({
        title: params.title,
        desc: params.desc || '',
        link: params.link || window.location.href,
        imgUrl: params.imgUrl || '',
        success: () => resolve(true),
        cancel: () => resolve(false),
      });
    });
  }, []);

  const shareFriendMessage = useCallback(async (params: ShareMessageParams) => {
    const wx = (window as any).wx;
    if (!wx || !wx.ready) return false;

    return new Promise<boolean>((resolve) => {
      wx.updateFriendsShareData({
        title: params.title,
        desc: params.desc || '',
        link: params.link || window.location.href,
        imgUrl: params.imgUrl || '',
        success: () => resolve(true),
        cancel: () => resolve(false),
      });
    });
  }, []);

  return { shareAppMessage, shareFriendMessage };
}

/**
 * 微信拍照/选择图片功能。
 */
export function useWechatPhoto() {
  const chooseImage = useCallback(async (params: ChooseImageParams = {}): Promise<ChooseImageResult> => {
    const wx = (window as any).wx;
    if (!wx || !wx.ready) {
      throw new Error('[WeChat] wx not ready');
    }

    return new Promise((resolve, reject) => {
      wx.chooseImage({
        sourceType: params.sourceType || ['album', 'camera'],
        count: params.count || 9,
        sizeType: params.sizeType || ['original', 'compressed'],
        success: (res: any) => {
          resolve({
            localIds: res.localIds || [],
            images: res.images || [],
          });
        },
        fail: (err: any) => {
          reject(new Error(err.errMsg || '选择图片失败'));
        },
      });
    });
  }, []);

  const scanQRCode = useCallback(async (): Promise<string> => {
    const wx = (window as any).wx;
    if (!wx || !wx.ready) {
      throw new Error('[WeChat] wx not ready');
    }

    return new Promise((resolve, reject) => {
      wx.scanQRCode({
        needResult: 1,  // 扫描结果
        scanType: ['qrCode', 'barCode'],
        success: (res: any) => {
          resolve(res.resultStr);
        },
        fail: (err: any) => {
          reject(new Error(err.errMsg || '扫码失败'));
        },
      });
    });
  }, []);

  return { chooseImage, scanQRCode };
}

/**
 * 微信上传/下载功能。
 */
export function useWechatUpload() {
  const uploadImage = useCallback(async (localId: string): Promise<UploadResult> => {
    const wx = (window as any).wx;
    if (!wx || !wx.ready) {
      throw new Error('[WeChat] wx not ready');
    }

    return new Promise((resolve, reject) => {
      wx.uploadImage({
        localId: localId,
        isShowProgressTips: 1,
        success: (res: any) => {
          resolve({ serverId: res.serverId });
        },
        fail: (err: any) => {
          reject(new Error(err.errMsg || '上传图片失败'));
        },
      });
    });
  }, []);

  const downloadImage = useCallback(async (serverId: string): Promise<string> => {
    const wx = (window as any).wx;
    if (!wx || !wx.ready) {
      throw new Error('[WeChat] wx not ready');
    }

    return new Promise((resolve, reject) => {
      wx.downloadImage({
        serverId: serverId,
        isShowProgressTips: 1,
        success: (res: any) => {
          resolve(res.localId);
        },
        fail: (err: any) => {
          reject(new Error(err.errMsg || '下载图片失败'));
        },
      });
    });
  }, []);

  return { uploadImage, downloadImage };
}

/**
 * 微信地理位置功能。
 */
export function useWechatLocation() {
  const getLocation = useCallback(async (): Promise<LocationResult> => {
    const wx = (window as any).wx;
    if (!wx || !wx.ready) {
      throw new Error('[WeChat] wx not ready');
    }

    return new Promise((resolve, reject) => {
      wx.getLocation({
        type: 'wgs84',  // wgs84 GPS 坐标，gcj02 用于微信地图
        success: (res: any) => {
          resolve({
            latitude: parseFloat(res.latitude),
            longitude: parseFloat(res.longitude),
            precision: parseFloat(res.accuracy),
          });
        },
        fail: (err: any) => {
          reject(new Error(err.errMsg || '获取位置失败'));
        },
      });
    });
  }, []);

  return { getLocation };
}

/**
 * 组合 Hooks — 一次性导出所有微信功能。
 */
export function useWechatActions() {
  const share = useWechatShare();
  const photo = useWechatPhoto();
  const upload = useWechatUpload();
  const location = useWechatLocation();

  return { ...share, ...photo, ...upload, ...location };
}

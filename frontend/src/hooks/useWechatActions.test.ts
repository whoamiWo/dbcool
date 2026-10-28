/**
 * useWechatActions 单元测试
 *
 * 使用 @testing-library/react 的 renderHook 测试 hooks 真实行为。
 * window.wx 在测试前被模拟（mock）。
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import {
  useWechatShare,
  useWechatPhoto,
  useWechatUpload,
  useWechatLocation,
  useWechatActions,
} from './useWechatActions';

const mockWx = {
  ready: false,
  updateAppMessageShareData: vi.fn(),
  updateTimelineShareData: vi.fn(),
  onMenuShareAppMessage: vi.fn(),
  updateFriendsShareData: vi.fn(),
  chooseImage: vi.fn(),
  scanQRCode: vi.fn(),
  uploadImage: vi.fn(),
  downloadImage: vi.fn(),
  getLocation: vi.fn(),
};

function setupWx() {
  (window as any).wx = mockWx;
}

function resetWx() {
  (window as any).wx = undefined;
  Object.values(mockWx).forEach((fn) => {
    if (typeof fn === 'function' && fn.mockClear) fn.mockClear();
  });
}

beforeEach(() => {
  setupWx();
});

afterEach(() => {
  resetWx();
});

describe('useWechatShare', () => {
  it('wx 未就绪时 shareAppMessage 返回 false', async () => {
    mockWx.ready = false;
    setupWx();

    const { result } = renderHook(() => useWechatShare());
    const { shareAppMessage } = result.current;

    const ok = await shareAppMessage({ title: 'test' });
    expect(ok).toBe(false);
  });

  it('wx ready 时 shareAppMessage 成功返回 true', async () => {
    mockWx.ready = true;
    mockWx.updateAppMessageShareData.mockImplementation((opts: any) => {
      opts.success();
    });
    setupWx();

    const { result } = renderHook(() => useWechatShare());
    const { shareAppMessage } = result.current;

    const ok = await shareAppMessage({ title: 'Test', desc: 'Desc', link: 'http://example.com' });

    expect(ok).toBe(true);
    expect(mockWx.updateAppMessageShareData).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'Test',
        desc: 'Desc',
        link: 'http://example.com',
        imgUrl: '',
      })
    );
  });

  it('shareFriendMessage 调用 updateFriendsShareData 并成功', async () => {
    mockWx.ready = true;
    mockWx.updateFriendsShareData.mockImplementation((opts: any) => {
      opts.success();
    });
    setupWx();

    const { result } = renderHook(() => useWechatShare());
    const { shareFriendMessage } = result.current;

    const ok = await shareFriendMessage({ title: 'Friend Share' });

    expect(ok).toBe(true);
    expect(mockWx.updateFriendsShareData).toHaveBeenCalled();
  });

  it('wx 未就绪时 shareFriendMessage 返回 false', async () => {
    mockWx.ready = false;
    setupWx();

    const { result } = renderHook(() => useWechatShare());
    const { shareFriendMessage } = result.current;

    const ok = await shareFriendMessage({ title: 'x' });
    expect(ok).toBe(false);
  });
});

describe('useWechatPhoto', () => {
  it('wx 未就绪时 chooseImage 抛错', async () => {
    mockWx.ready = false;
    setupWx();

    const { result } = renderHook(() => useWechatPhoto());
    const { chooseImage } = result.current;

    await expect(chooseImage()).rejects.toThrow('[WeChat] wx not ready');
  });

  it('wx ready 时 chooseImage 成功返回结果', async () => {
    mockWx.ready = true;
    mockWx.chooseImage.mockImplementation((opts: any) => {
      opts.success({ localIds: ['id1', 'id2'], images: ['img1', 'img2'] });
    });
    setupWx();

    const { result } = renderHook(() => useWechatPhoto());
    const { chooseImage } = result.current;

    const res = await chooseImage({ sourceType: ['camera'], count: 1 });

    expect(res).toEqual({
      localIds: ['id1', 'id2'],
      images: ['img1', 'img2'],
    });
    expect(mockWx.chooseImage).toHaveBeenCalledWith(
      expect.objectContaining({ sourceType: ['camera'], count: 1 })
    );
  });

  it('chooseImage 失败时 reject 错误', async () => {
    mockWx.ready = true;
    mockWx.chooseImage.mockImplementation((opts: any) => {
      opts.fail({ errMsg: 'cancelled' });
    });
    setupWx();

    const { result } = renderHook(() => useWechatPhoto());
    const { chooseImage } = result.current;

    await expect(chooseImage()).rejects.toThrow('cancelled');
  });

  it('scanQRCode: wx 未就绪时抛错', async () => {
    mockWx.ready = false;
    setupWx();

    const { result } = renderHook(() => useWechatPhoto());
    const { scanQRCode } = result.current;

    await expect(scanQRCode()).rejects.toThrow('[WeChat] wx not ready');
  });

  it('scanQRCode: 成功返回结果', async () => {
    mockWx.ready = true;
    mockWx.scanQRCode.mockImplementation((opts: any) => {
      opts.success({ resultStr: 'https://example.com' });
    });
    setupWx();

    const { result } = renderHook(() => useWechatPhoto());
    const { scanQRCode } = result.current;

    const res = await scanQRCode();

    expect(res).toBe('https://example.com');
    expect(mockWx.scanQRCode).toHaveBeenCalledWith(
      expect.objectContaining({ needResult: 1, scanType: ['qrCode', 'barCode'] })
    );
  });
});

describe('useWechatUpload', () => {
  it('uploadImage: wx 未就绪时抛错', async () => {
    mockWx.ready = false;
    setupWx();

    const { result } = renderHook(() => useWechatUpload());
    const { uploadImage } = result.current;

    await expect(uploadImage('local123')).rejects.toThrow('[WeChat] wx not ready');
  });

  it('uploadImage: 成功返回 serverId', async () => {
    mockWx.ready = true;
    mockWx.uploadImage.mockImplementation((opts: any) => {
      opts.success({ serverId: 'server_123' });
    });
    setupWx();

    const { result } = renderHook(() => useWechatUpload());
    const { uploadImage } = result.current;

    const res = await uploadImage('local_1');

    expect(res).toEqual({ serverId: 'server_123' });
    expect(mockWx.uploadImage).toHaveBeenCalledWith(
      expect.objectContaining({ localId: 'local_1', isShowProgressTips: 1 })
    );
  });

  it('downloadImage: 成功返回 localId', async () => {
    mockWx.ready = true;
    mockWx.downloadImage.mockImplementation((opts: any) => {
      opts.success({ localId: 'local_456' });
    });
    setupWx();

    const { result } = renderHook(() => useWechatUpload());
    const { downloadImage } = result.current;

    const res = await downloadImage('server_789');

    expect(res).toBe('local_456');
    expect(mockWx.downloadImage).toHaveBeenCalledWith(
      expect.objectContaining({ serverId: 'server_789', isShowProgressTips: 1 })
    );
  });
});

describe('useWechatLocation', () => {
  it('getLocation: wx 未就绪时抛错', async () => {
    mockWx.ready = false;
    setupWx();

    const { result } = renderHook(() => useWechatLocation());
    const { getLocation } = result.current;

    await expect(getLocation()).rejects.toThrow('[WeChat] wx not ready');
  });

  it('getLocation: 成功返回坐标', async () => {
    mockWx.ready = true;
    mockWx.getLocation.mockImplementation((opts: any) => {
      opts.success({ latitude: '31.2304', longitude: '121.4737', accuracy: '10.5' });
    });
    setupWx();

    const { result } = renderHook(() => useWechatLocation());
    const { getLocation } = result.current;

    const res = await getLocation();

    expect(res).toEqual({
      latitude: 31.2304,
      longitude: 121.4737,
      precision: 10.5,
    });
    expect(mockWx.getLocation).toHaveBeenCalledWith(expect.objectContaining({ type: 'wgs84' }));
  });

  it('getLocation: 失败 reject 错误', async () => {
    mockWx.ready = true;
    mockWx.getLocation.mockImplementation((opts: any) => {
      opts.fail({ errMsg: 'permission denied' });
    });
    setupWx();

    const { result } = renderHook(() => useWechatLocation());
    const { getLocation } = result.current;

    await expect(getLocation()).rejects.toThrow('permission denied');
  });
});

describe('useWechatActions 组合导出', () => {
  it('返回所有子 hooks 的方法', async () => {
    mockWx.ready = true;
    setupWx();

    const { result } = renderHook(() => useWechatActions());
    const actions = result.current;

    expect(actions).toHaveProperty('shareAppMessage');
    expect(actions).toHaveProperty('shareFriendMessage');
    expect(actions).toHaveProperty('chooseImage');
    expect(actions).toHaveProperty('scanQRCode');
    expect(actions).toHaveProperty('uploadImage');
    expect(actions).toHaveProperty('downloadImage');
    expect(actions).toHaveProperty('getLocation');

    // 确保所有方法都是函数
    expect(typeof actions.shareAppMessage).toBe('function');
    expect(typeof actions.chooseImage).toBe('function');
    expect(typeof actions.uploadImage).toBe('function');
    expect(typeof actions.getLocation).toBe('function');
  });
});
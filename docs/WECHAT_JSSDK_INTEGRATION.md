# 微信 JS-SDK 集成文档

## 概述

本项目已实现微信公众号/企业微信的 JS-SDK 完整集成，包括：

- ✅ **后端签名服务** (`WeChatJsSignatureProvider`)
- ✅ **前端 Hook 封装** (`useJsSdk`, `useWechatActions`)
- ✅ **功能调用示例** (拍照、分享、上传、定位)

## 快速开始

### 1️⃣ 配置微信公众号凭证

在后台管理系统或环境变量中配置：

```bash
# 租户级凭证（推荐）
INTEGRATION_CREDENTIAL_TENANT_X_WECHAT_APP_ID=<你的公众号 APPID>
INTEGRATION_CREDENTIAL_TENANT_X_WECHAT_APP_SECRET=<你的 APPSECRET>

# 或者在全局凭证
INTEGRATION_CREDENTIAL_GLOBAL_WECHAT_APP_ID=<你的公众号 APPID>
INTEGRATION_CREDENTIAL_GLOBAL_WECHAT_APP_SECRET=<你的 APPSECRET>
```

### 2️⃣ 配置 JS 安全域名

在 [微信公众平台](https://mp.weixin.qq.com) 配置：

**路径**：设置 > 基本设置 > 网站配置 > JS 接口安全域名

- 填写你的域名（例如：`your-domain.com`）
- 下载 `MP_verify_xxx.txt` 放到网站根目录

### 3️⃣ 在前端页面使用

#### 基础用法（简单集成）

```tsx
import { useJsSdk } from '@/hooks/useJsSdk';

function MyPage() {
  const { platform, ready, error } = useJsSdk();

  if (error) {
    return <div>初始化失败：{error}</div>;
  }

  if (platform !== 'wechat') {
    return <div>请在微信中打开</div>;
  }

  if (!ready) {
    return <div>正在初始化...</div>;
  }

  return <div>微信环境就绪，可以使用原生能力了</div>;
}
```

#### 高级用法（具体功能调用）

```tsx
import { useJsSdk } from '@/hooks/useJsSdk';
import { useWechatActions } from '@/hooks/useWechatActions';

function ShareAndPhotoPage() {
  const { platform, ready } = useJsSdk();
  const { shareAppMessage, chooseImage, uploadImage } = useWechatActions();

  const handleShare = async () => {
    const ok = await shareAppMessage({
      title: '分享标题',
      desc: '分享内容',
      link: window.location.href,
      imgUrl: 'https://your-domain.com/share.jpg',
    });
    alert(ok ? '分享成功' : '分享取消');
  };

  const handlePhoto = async () => {
    try {
      // 拍照/选图
      const result = await chooseImage({
        sourceType: ['camera'],  // 只用相机
        count: 1,
        sizeType: ['compressed'], // 压缩图
      });
      
      // 上传到服务器
      const serverId = await uploadImage(result.localIds[0]);
      console.log('上传成功，serverId:', serverId);
      
      // TODO: 将 serverId 保存到数据库或通过 API 传给后端
    } catch (err) {
      console.error('拍照失败:', err);
    }
  };

  if (platform !== 'wechat' || !ready) return null;

  return (
    <div>
      <button onClick={handleShare}>分享给朋友</button>
      <button onClick={handlePhoto}>拍照</button>
    </div>
  );
}
```

#### 扫码功能

```tsx
import { useWechatActions } from '@/hooks/useWechatActions';

function ScanPage() {
  const { scanQRCode } = useWechatActions();

  const handleScan = async () => {
    try {
      const result = await scanQRCode();
      alert('扫码结果：' + result);
      // 处理二维码内容
    } catch (err) {
      alert('扫码失败：' + (err as Error).message);
    }
  };

  return <button onClick={handleScan}>扫描二维码</button>;
}
```

#### 定位功能

```tsx
import { useWechatActions } from '@/hooks/useWechatActions';

function LocationPage() {
  const { getLocation } = useWechatActions();

  const handleGetLocation = async () => {
    try {
      const { latitude, longitude, precision } = await getLocation();
      console.log(`纬度：${latitude}, 经度：${longitude}, 精度：${precision}米`);
      // TODO: 显示在地图上
    } catch (err) {
      console.error('获取位置失败:', err);
    }
  };

  return <button onClick={handleGetLocation}>获取当前位置</button>;
}
```

## 注意事项

### 1. 页面 URL 要求

- **必须在微信内打开**：普通浏览器不会触发 SDK 注入
- **URL 不能带 hash**：签名时会自动去除 `#` 后的部分
- **HTTPS 环境**：生产环境建议使用 HTTPS

### 2. 微信后台配置

| 配置项 | 说明 | 路径 |
|--------|------|------|
| JS 接口安全域名 | 允许调用 JS-SDK 的域名 | 设置 > 基本设置 > 网站配置 |
| IP 白名单 | 调用微信 API 的服务器 IP | 开发 > 基本设置 > 服务器 IP |
|  OAuth scope | 用户授权范围 | 开发 > 开发者资质 |

### 3. 常见问题

#### Q: config 不生效，提示 invalid url domain

**原因**：当前页面不在 JS 安全域名列表中

**解决**：
1. 检查公众平台是否配置了对应的域名
2. 确保代码中的 `url` 参数与当前页面 URL 一致（不含 hash）

#### Q: chooseImage 失败

**原因**：
1. config 未注入或失败
2. 未在 `wx.ready` 回调内调用
3. 公众号未认证（部分功能需认证）

**解决**：
```tsx
const { ready, error } = useJsSdk();

if (!ready) return <div>等待初始化...</div>;
if (error) return <div>Error: {error}</div>;

// 此时才调用功能
<button onClick={handlePhoto}>拍照</button>
```

#### Q: 上传图片需要后端配合

微信的 `uploadImage` 只是传到微信服务器，拿到 `serverId` 后需要调用后端 API 才能下载保存：

```tsx
// 前端
const serverId = await uploadImage(localId);
await apiClient.post('/api/upload/save-wechat-image', {
  serverId,
  type: 'image',
});

// 后端（伪代码）
@PostMapping("/save-wechat-image")
public ResponseEntity<?> saveWechatImage(@RequestBody Map req) {
    String serverId = req.get("serverId");
    String mediaUrl = "https://api.weixin.qq.com/cgi-bin/media/get?access_token=" + accessToken + "&media_id=" + serverId;
    // 下载到本地/Object Storage
    return ResponseEntity.ok(Map.of("saved", true));
}
```

### 4. 企业微信 vs 微信公众号

两者都用同一个 SDK，但调用方式略有不同：

| 功能 | 企业微信 | 微信公众号 |
|------|----------|-----------|
| Config | `wx.agentConfig` | `wx.config` |
| 选人 | `selectExternalContact` | ❌ |
| 分享 | `updateAppMessageShareData` | `updateAppMessageShareData` |
| 拍照 | `chooseImage` | `chooseImage` |

`useJsSdk` 会根据 `type='agent'` 自动选择 `agentConfig`。

## 测试

### 本地开发

1. 使用 ngrok 暴露本地服务：
```bash
ngrok http 3000
```

2. 在手机微信中访问 ngrok 地址

3. 确认 U A 识别正确：
```tsx
console.log('Platform:', detectPlatform()); // 应输出 'wechat'
```

### 生产环境

1. 确保域名已在微信后台配置
2. 使用 HTTPS
3. 检查证书有效性

## 参考链接

- [微信公众号 JS-SDK 文档](https://developers.weixin.qq.com/doc/offiaccount/OA_Web_Apps/JS_SDK.html)
- [企业微信 H5 微应用](https://work.weixin.qq.com/api/doc/90000/90135/90236)
- 后端实现：[`WeChatJsSignatureProvider.java`](../../backend-java/src/main/java/com/nocobase/integration/common/WeChatJsSignatureProvider.java)
- 前端 Hook: [`useJsSdk.ts`](./useJsSdk.ts), [`useWechatActions.ts`](./useWechatActions.ts)

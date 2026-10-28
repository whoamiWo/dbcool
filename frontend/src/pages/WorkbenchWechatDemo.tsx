import { useEmbeddedSdk } from '@/components/EmbeddedSdkProvider';
import { useWechatActions } from '@/hooks/useWechatActions';
import { Card, CardContent, Typography, Button, Box, Alert, Stack, Chip } from '@mui/material';

/**
 * 工作台：微信 JS-SDK 功能演示页
 *
 * <p>仅在微信内显示，用于验证拍照、分享、上传等功能。
 */
export function WorkbenchWechatDemo() {
  const { platform, ready, error } = useEmbeddedSdk();
  const { shareAppMessage, chooseImage, uploadImage, getLocation, scanQRCode } = useWechatActions();

  // 非微信环境隐藏整个演示页
  if (platform !== 'wechat') {
    return null;
  }

  const handleShare = async () => {
    try {
      const ok = await shareAppMessage({
        title: 'NocoBase - 企业级零代码平台',
        desc: '快速构建业务系统，无需编码',
        link: window.location.href,
      });
      alert(ok ? '✅ 分享成功' : '❌ 分享取消');
    } catch (e) {
      alert('❌ 分享失败：' + (e as Error).message);
    }
  };

  const handlePhoto = async () => {
    try {
      const result = await chooseImage({ count: 1 });
      if (result.localIds.length > 0) {
        alert(`✅ 已选择 ${result.localIds.length} 张图片\nLocalID: ${result.localIds[0]}`);
        
        // 自动上传演示
        const uploadResult = await uploadImage(result.localIds[0]);
        alert(`✅ 上传成功\nServerID: ${uploadResult.serverId}`);
      }
    } catch (e) {
      alert('❌ 选择图片失败：' + (e as Error).message);
    }
  };

  const handleScan = async () => {
    try {
      const result = await scanQRCode();
      alert(`✅ 扫码结果：${result}`);
    } catch (e) {
      alert('❌ 扫码失败：' + (e as Error).message);
    }
  };

  const handleLocation = async () => {
    try {
      const result = await getLocation();
      alert(
        `✅ 地理位置\n` +
        `纬度：${result.latitude}\n` +
        `经度：${result.longitude}\n` +
        `精度：${result.precision.toFixed(2)}m`
      );
    } catch (e) {
      alert('❌ 获取位置失败：' + (e as Error).message);
    }
  };

  return (
    <Box sx={{ p: 3 }}>
      <Typography variant="h4" gutterBottom>
        微信原生能力演示
      </Typography>

      {/* SDK 状态 */}
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
            <Chip 
              label={`平台：${platform === 'wechat' ? '微信' : platform}`} 
              color="primary" 
              size="small" 
            />
            <Chip 
              label={`状态：${ready ? '已就绪' : '未就绪'}`} 
              color={ready ? 'success' : 'error'} 
              size="small" 
            />
            {error && (
              <Alert severity="error" sx={{ mt: 1, width: '100%' }}>
                {error}
              </Alert>
            )}
            {!error && ready && (
              <Alert severity="success" sx={{ mt: 1, width: '100%' }}>
                微信 JS-SDK 初始化成功
              </Alert>
            )}
            {!error && !ready && (
              <Alert severity="info" sx={{ mt: 1, width: '100%' }}>
                SDK 正在初始化...
              </Alert>
            )}
          </Stack>
        </CardContent>
      </Card>

      {!ready ? (
        <Alert severity="info" sx={{ mb: 2 }}>
          ⏳ SDK 初始化中，请稍后再试...
        </Alert>
      ) : (
        <>
          {/* 分享功能 */}
          <Card sx={{ mb: 2 }}>
            <CardContent>
              <Typography variant="h6" gutterBottom>
                📢 分享功能
              </Typography>
              <Button
                variant="contained"
                onClick={handleShare}
                fullWidth
                sx={{ mt: 1 }}
              >
                分享到微信好友
              </Button>
            </CardContent>
          </Card>

          {/* 拍照/扫描 */}
          <Card sx={{ mb: 2 }}>
            <CardContent>
              <Typography variant="h6" gutterBottom>
                📷 拍照与扫描
              </Typography>
              <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap' }}>
                <Button
                  variant="outlined"
                  onClick={handlePhoto}
                  disabled={!ready}
                >
                  拍照/选图
                </Button>
                <Button
                  variant="outlined"
                  onClick={handleScan}
                  disabled={!ready}
                >
                  扫描二维码
                </Button>
              </Stack>
            </CardContent>
          </Card>

          {/* 地理位置 */}
          <Card>
            <CardContent>
              <Typography variant="h6" gutterBottom>
                📍 地理位置
              </Typography>
              <Button
                variant="outlined"
                onClick={handleLocation}
                disabled={!ready}
                fullWidth
              >
                获取当前位置
              </Button>
            </CardContent>
          </Card>
        </>
      )}
    </Box>
  );
}

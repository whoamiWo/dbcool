import { useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Button,
  IconButton,
  Box,
  Typography,
} from "@mui/material";
import { X, Download } from "lucide-react";
import axios from "axios";

interface AttachmentLightboxProps {
  open: boolean;
  onClose: () => void;
  storageKey: string;
}

export function AttachmentLightbox({ open, onClose, storageKey }: AttachmentLightboxProps) {
  const { t } = useTranslation();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const previewUrl = storageKey ? `/api/attachments/preview?storageKey=${encodeURIComponent(storageKey)}` : '';
  const filename = storageKey ? storageKey.substring(storageKey.lastIndexOf("/") + 1) : '未知文件';

  const handleDownload = useCallback(async () => {
    try {
      const res = await axios.get(previewUrl, { responseType: "blob" });
      const url = URL.createObjectURL(res.data);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err: any) {
      console.error("下载失败:", err);
    }
  }, [previewUrl, filename]);

  const handleClose = useCallback(() => {
    setLoading(true);
    setError(null);
    onClose();
  }, [onClose]);

  return (
    <Dialog
      open={open}
      onClose={handleClose}
      maxWidth="lg"
      fullWidth
      slotProps={{
        paper: {
          sx: {
            bgcolor: "rgba(0,0,0,0.85)",
            backdropFilter: "blur(8px)",
            borderRadius: 2,
            overflow: "hidden",
          },
        },
      }}
    >
      <DialogTitle
        sx={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          p: 1,
          m: 0,
          bgcolor: "rgba(0,0,0,0.5)",
        }}
      >
        <Typography variant="subtitle2" sx={{ color: "white", maxWidth: "60%" }} noWrap>
          {filename}
        </Typography>
        <IconButton
          onClick={handleClose}
          aria-label={t("common.close")}
          sx={{ color: "white", "&:hover": { bgcolor: "rgba(255,255,255,0.1)" } }}
        >
          <X size={20} />
        </IconButton>
      </DialogTitle>

      <DialogContent
        sx={{
          p: 0,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          minHeight: 300,
        }}
      >
        {loading && !error && (
          <Box sx={{ p: 4, position: "absolute" }}>
            <Typography sx={{ color: "white", opacity: 0.6 }}>加载中...</Typography>
          </Box>
        )}
        {error && (
          <Box sx={{ p: 4, textAlign: "center" }}>
            <Typography sx={{ color: "error.main" }}>{error}</Typography>
            <Button onClick={handleClose} sx={{ mt: 1 }}>关闭</Button>
          </Box>
        )}
        {previewUrl && !error && (
          // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- onLoad/onError 是图片生命周期事件，非交互事件
          <img
            src={previewUrl}
            alt={filename}
            style={{
              maxWidth: "90vw",
              maxHeight: "85vh",
              objectFit: "contain",
              visibility: loading ? "hidden" : "visible",
            }}
            onLoad={() => setLoading(false)}
            onError={() => {
              setLoading(false);
              setError("预览加载失败");
            }}
          />
        )}
      </DialogContent>

      <DialogActions
        sx={{
          p: 1,
          justifyContent: "flex-end",
          bgcolor: "rgba(0,0,0,0.5)",
        }}
      >
        <Button startIcon={<Download size={16} />} onClick={handleDownload}>
          下载原图
        </Button>
        <Button onClick={handleClose}>关闭</Button>
      </DialogActions>
    </Dialog>
  );
}

export default AttachmentLightbox;

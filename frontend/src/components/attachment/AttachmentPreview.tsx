import { useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { Box, Typography, Button, Card, IconButton } from "@mui/material";
import { ImageOff, FileText, Download, Trash2 } from "lucide-react";
import axios from "axios";
import { getFileInfo, decodeFilename } from "./fileIcon";

interface AttachmentPreviewProps {
  value?: string;
  /** Edit 模式:允许上传、删除;readonly 模式:仅展示 */
  mode?: "edit" | "readonly";
  size?: "sm" | "md" | "lg";
  accept?: string;
  onChange?: (key: string) => void;
  onUploadSuccess?: (key: string) => void;
  onUploadError?: (err: string) => void;
}

export function AttachmentPreview({
  value,
  mode = "readonly",
  size = "md",
  accept = "image/*,.pdf,.doc,.docx,.xls,.xlsx,.zip",
  onChange,
  onUploadSuccess,
  onUploadError,
}: AttachmentPreviewProps) {
  const { t } = useTranslation();
  const [uploading, setUploading] = useState(false);
  const [progress, setProgress] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [imgError, setImgError] = useState(false);

  const storageKey = value;
  const info = storageKey ? getFileInfo(decodeFilename(storageKey)) : { ext: "", label: "文件" };
  const fileSize = storageKey ? `${info.label} · ${decodeFilename(storageKey).length} 字节` : "";

  const thumbnailUrl = storageKey
    ? `/api/attachments/thumbnail?storageKey=${encodeURIComponent(storageKey)}`
    : null;
  const previewUrl = storageKey
    ? `/api/attachments/preview?storageKey=${encodeURIComponent(storageKey)}`
    : null;

  const handleFileSelect = useCallback(
    async (file: File) => {
      if (mode !== "edit") return;
      const formData = new FormData();
      formData.append("file", file);
      try {
        setUploading(true);
        setProgress(0);
        setError(null);
        const res = await axios.post("/api/attachments/upload", formData, {
          headers: { "Content-Type": "multipart/form-data" },
          onUploadProgress: (e) => {
            if (e.total) setProgress(Math.round((100 * e.loaded) / e.total));
          },
        });
        const newKey = res.data.data.storageKey;
        onChange?.(newKey);
        onUploadSuccess?.(newKey);
      } catch (err: any) {
        const msg = err.response?.data?.message || "上传失败";
        setError(msg);
        onUploadError?.(msg);
      } finally {
        setUploading(false);
      }
    },
    [mode, onChange, onUploadSuccess, onUploadError]
  );

  const handleDelete = useCallback(() => {
    if (mode !== "edit" || !storageKey) return;
    onChange?.("");
  }, [mode, storageKey, onChange]);

  const handleDownload = useCallback(async () => {
    if (!storageKey || !previewUrl) return;
    try {
      const res = await axios.get(previewUrl, { responseType: "blob" });
      const url = URL.createObjectURL(res.data);
      const a = document.createElement("a");
      a.href = url;
      a.download = decodeFilename(storageKey);
      a.click();
      URL.revokeObjectURL(url);
    } catch (err: any) {
      console.error("下载失败:", err);
    }
  }, [storageKey, previewUrl]);

  const sizePx = { sm: 40, md: 56, lg: 80 }[size];

  const renderPlaceholder = () => (
    <Box
      sx={{
        width: sizePx,
        height: sizePx,
        borderRadius: 1,
        border: "1px dashed var(--color-border)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        bgcolor: "var(--color-background-secondary)",
      }}
    >
      <FileText size={sizePx * 0.5} color="var(--color-text-disabled)" />
    </Box>
  );

  const renderImage = () => {
    if (!thumbnailUrl) return renderPlaceholder();
    return (
      <Box
        component="button"
        type="button"
        aria-label={t("attachment.download")}
        onClick={() => handleDownload()}
        sx={{
          p: 0,
          border: "none",
          background: "none",
          cursor: "pointer",
          lineHeight: 0,
        }}
      >
        {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- img 的 onError 是生命周期事件，非交互事件；交互已由外层 button 承载 */}
        <img
          src={thumbnailUrl}
          alt={t("attachment.thumbnailAlt")}
          loading="lazy"
          style={{
            width: sizePx,
            height: sizePx,
            objectFit: "cover",
            borderRadius: 1,
            border: "1px solid var(--color-border)",
          }}
          onError={() => setImgError(true)}
        />
      </Box>
    );
  };

  if (imgError && storageKey) {
    // 降级为图标
    return (
      <Card
        sx={{
          display: "flex",
          alignItems: "center",
          gap: 1,
          p: 1,
          boxShadow: 1,
          position: "relative",
        }}
      >
        <Box
          sx={{
            width: sizePx,
            height: sizePx,
            borderRadius: 1,
            border: "1px solid var(--color-border)",
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            bgcolor: "var(--color-background-tertiary)",
          }}
        >
          <ImageOff size={sizePx * 0.5} color="var(--color-text-disabled)" />
        </Box>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography variant="caption" noWrap title={decodeFilename(storageKey)}>
            {decodeFilename(storageKey)}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {info.label}
          </Typography>
        </Box>
        {mode === "edit" && (
          <IconButton size="small" onClick={handleDelete} aria-label={t("attachment.delete")}>
            <Trash2 size={14} />
          </IconButton>
        )}
      </Card>
    );
  }

  return (
    <Card
      sx={{
        display: "flex",
        alignItems: "center",
        gap: 1,
        p: 1,
        boxShadow: 1,
        position: "relative",
        border: "1px solid var(--color-border)",
        "&:hover": { borderColor: "var(--color-primary)" },
      }}
    >
      {renderImage()}
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Typography variant="body2" noWrap title={storageKey || ''}>
          {storageKey ? decodeFilename(storageKey) : '-'}
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {fileSize}
        </Typography>
      </Box>
      {mode === "edit" && !uploading && storageKey && (
        <>
          <Button size="small" startIcon={<Download size={14} />} onClick={handleDownload}>
            下载
          </Button>
          <Button size="small" color="error" startIcon={<Trash2 size={14} />} onClick={handleDelete}>
            删除
          </Button>
        </>
      )}
      {uploading && (
        <Box
          sx={{
            position: "absolute",
            bottom: 0,
            left: 0,
            height: 2,
            bgcolor: "primary.main",
            width: `${progress}%`,
            transition: "width 0.3s ease",
          }}
        />
      )}
      {error && (
        <Typography variant="caption" color="error" sx={{ mt: 0.5, width: "100%" }}>
          {error}
        </Typography>
      )}
      {mode === "edit" && !storageKey && (
        <UploadZone accept={accept} onFileSelect={handleFileSelect} uploading={uploading} />
      )}
    </Card>
  );
}

function UploadZone({
  accept,
  onFileSelect,
  uploading,
}: {
  accept: string;
  onFileSelect: (file: File) => void;
  uploading: boolean;
}) {
  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      if (uploading) return;
      const file = e.dataTransfer.files[0];
      if (file) onFileSelect(file);
    },
    [uploading, onFileSelect]
  );

  return (
    <Box
      sx={{
        border: "2px dashed var(--color-border)",
        borderRadius: 2,
        p: 2,
        textAlign: "center",
        bgcolor: "var(--color-background-secondary)",
        cursor: uploading ? "not-allowed" : "pointer",
        opacity: uploading ? 0.5 : 1,
        transition: "all 0.2s ease",
        "&:hover": {
          borderColor: "var(--color-primary)",
          bgcolor: "var(--color-background-tertiary)",
        },
      }}
      onDrop={handleDrop}
      onDragOver={(e) => e.preventDefault()}
      onClick={() => !uploading && (document.getElementById("file-input-attach") as HTMLInputElement)?.click()}
    >
      <input
        id="file-input-attach"
        type="file"
        accept={accept}
        style={{ display: "none" }}
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) onFileSelect(file);
        }}
      />
      <Box sx={{ color: "var(--color-text-secondary)", fontSize: 12 }}>
        <Typography variant="caption" sx={{ display: "block" }}>
          点击或拖拽文件上传
        </Typography>
        <Typography variant="caption" sx={{ display: "block", color: "var(--color-text-disabled)" }}>
          支持图片、PDF、Office 文档
        </Typography>
      </Box>
    </Box>
  );
}

export default AttachmentPreview;

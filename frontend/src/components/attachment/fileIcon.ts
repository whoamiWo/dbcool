import { useMemo } from "react";

/**
 * 文件类型工具 —— 按扩展名归类附件,供表单/表格/画廊共用。
 */

const IMAGE_EXTS = new Set(["png", "jpg", "jpeg", "gif", "bmp", "webp", "svg"]);
const DOC_EXTS = new Set(["pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv"]);
const ARCHIVE_EXTS = new Set(["zip", "rar", "7z", "tar", "gz"]);

export type FileCategory = "image" | "doc" | "archive" | "other";

export interface FileInfo {
  ext: string;
  category: FileCategory;
  icon: string;
  label: string;
}

export function getFileInfo(name: string): FileInfo {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  let category: FileCategory;
  let icon: string;
  let label: string;

  if (IMAGE_EXTS.has(ext)) {
    category = "image";
    icon = "file-icon-image";
    label = "图片";
  } else if (DOC_EXTS.has(ext)) {
    category = "doc";
    icon = "file-icon-doc";
    label = "文档";
  } else if (ARCHIVE_EXTS.has(ext)) {
    category = "archive";
    icon = "file-icon-archive";
    label = "压缩包";
  } else {
    category = "other";
    icon = "file-icon-other";
    label = "文件";
  }

  return { ext, category, icon, label };
}

export function isImage(name: string): boolean {
  return getFileInfo(name).category === "image";
}

export function formatFileSize(value: unknown): string {
  if (typeof value !== "number" || value <= 0) return "";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let size = value;
  let unitIndex = 0;
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex++;
  }
  return `${size.toFixed(unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`;
}

export function decodeFilename(storageKey: string): string {
  const idx = storageKey.lastIndexOf("/");
  return idx >= 0 ? storageKey.substring(idx + 1) : storageKey;
}

export function getFileExtension(name: string): string {
  const parts = name.split(".");
  return parts.length > 1 ? parts.pop()!.toUpperCase() : "";
}

export function useAttachmentUrls(storageKey: string | undefined) {
  return useMemo(() => {
    if (!storageKey) return { thumbnail: null, preview: null };
    const encoded = encodeURIComponent(storageKey);
    return {
      thumbnail: `/api/attachments/thumbnail?storageKey=${encoded}`,
      preview: `/api/attachments/preview?storageKey=${encoded}`,
    };
  }, [storageKey]);
}

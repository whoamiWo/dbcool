import { useState, useCallback, useRef } from 'react';
import {
  Box,
  Typography,
  Paper,
  Chip,
  LinearProgress,
  IconButton,
  Tooltip,
} from '@mui/material';
import {
  CloudUpload,
  Delete,
  InsertDriveFile,
  PictureAsPdf,
  Image,
} from '@mui/icons-material';

interface AttachmentFile {
  file: File;
  status: 'uploading' | 'done' | 'error';
  url?: string;
  error?: string;
}

interface WikiAttachmentUploadProps {
  kbId: string;
  pageId: string;
  onUploadComplete?: (url: string, fileName: string) => void;
  onUploadError?: (error: string) => void;
}

const ALLOWED_TYPES = [
  'application/pdf',
  'application/msword',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'image/png',
  'image/jpeg',
  'image/gif',
  'text/markdown',
  'text/plain',
];

const MAX_SIZE = 10 * 1024 * 1024; // 10MB

export function WikiAttachmentUpload({
  kbId,
  pageId,
  onUploadComplete,
  onUploadError,
}: WikiAttachmentUploadProps) {
  const [files, setFiles] = useState<AttachmentFile[]>([]);
  const [dragActive, setDragActive] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const validateFile = (file: File): string | null => {
    if (!ALLOWED_TYPES.includes(file.type)) {
      return `不支持的文件类型: ${file.type}`;
    }
    if (file.size > MAX_SIZE) {
      return '文件过大，最大允许 10MB';
    }
    return null;
  };

  const uploadFile = useCallback(
    async (file: File) => {
      const error = validateFile(file);
      if (error) {
        setFiles((prev) => [
          ...prev,
          { file, status: 'error', error },
        ]);
        onUploadError?.(error);
        return;
      }

      const newFile: AttachmentFile = { file, status: 'uploading' };
      setFiles((prev) => [...prev, newFile]);

      try {
        const formData = new FormData();
        formData.append('file', file);
        formData.append('kbId', kbId);
        formData.append('pageId', pageId);

        // 调用后端附件上传 API
        const response = await fetch(`/api/wiki/attachments/upload`, {
          method: 'POST',
          body: formData,
        });

        if (!response.ok) {
          throw new Error(`上传失败: ${response.statusText}`);
        }

        const data = await response.json();
        setFiles((prev) =>
          prev.map((f) =>
            f.file === file ? { ...f, status: 'done', url: data.url } : f
          )
        );
        onUploadComplete?.(data.url, file.name);
      } catch (err) {
        const message = err instanceof Error ? err.message : '上传失败';
        setFiles((prev) =>
          prev.map((f) =>
            f.file === file ? { ...f, status: 'error', error: message } : f
          )
        );
        onUploadError?.(message);
      }
    },
    [kbId, pageId, onUploadComplete, onUploadError]
  );

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      setDragActive(false);
      const files = Array.from(e.dataTransfer.files);
      files.forEach(uploadFile);
    },
    [uploadFile]
  );

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    setDragActive(true);
  }, []);

  const handleDragLeave = useCallback(() => {
    setDragActive(false);
  }, []);

  const handleFileChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const files = Array.from(e.target.files || []);
      files.forEach(uploadFile);
      if (inputRef.current) inputRef.current.value = '';
    },
    [uploadFile]
  );

  const removeFile = useCallback((index: number) => {
    setFiles((prev) => prev.filter((_, i) => i !== index));
  }, []);

  const getIcon = (type: string) => {
    if (type.startsWith('image/')) return <Image />;
    if (type === 'application/pdf') return <PictureAsPdf />;
    return <InsertDriveFile />;
  };

  return (
    <Box>
      <Paper
        sx={{
          p: 3,
          border: dragActive ? '2px dashed var(--color-info)' : '2px dashed var(--color-border-light)',
          backgroundColor: dragActive ? 'var(--color-bg-tertiary)' : 'background.paper',
          cursor: 'pointer',
          transition: 'all 0.2s',
        }}
        onDrop={handleDrop}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onClick={() => inputRef.current?.click()}
      >
        <input
          ref={inputRef}
          type="file"
          multiple
          accept=".pdf,.doc,.docx,.png,.jpg,.jpeg,.gif,.md,.txt"
          onChange={handleFileChange}
          style={{ display: 'none' }}
        />
        <Box sx={{ textAlign: 'center' }}>
          <CloudUpload sx={{ fontSize: 48, color: 'text.secondary', mb: 1 }} />
          <Typography variant="body1" color="text.secondary">
            拖拽文件到此处或点击上传
          </Typography>
          <Typography variant="caption" color="text.secondary" sx={{ mt: 1 }}>
            支持 PDF, Word, PNG, JPG, GIF, MD, TXT (最大 10MB)
          </Typography>
        </Box>
      </Paper>

      {files.length > 0 && (
        <Box sx={{ mt: 2 }}>
          {files.map((file, index) => (
            <Paper
              key={index}
              sx={{
                p: 1.5,
                mt: 1,
                display: 'flex',
                alignItems: 'center',
                gap: 1,
                backgroundColor:
                  file.status === 'error' ? 'rgba(239,68,68,0.1)' : 'background.paper',
              }}
            >
              {getIcon(file.file.type)}
              <Box sx={{ flex: 1 }}>
                <Typography variant="body2" noWrap>
                  {file.file.name}
                </Typography>
                {file.status === 'uploading' && <LinearProgress />}
                {file.status === 'done' && (
                  <Chip label="上传成功" color="success" size="small" />
                )}
                {file.status === 'error' && (
                  <Chip label={file.error || '上传失败'} color="error" size="small" />
                )}
              </Box>
              <Tooltip title="移除">
                <IconButton size="small" onClick={() => removeFile(index)}>
                  <Delete fontSize="small" />
                </IconButton>
              </Tooltip>
            </Paper>
          ))}
        </Box>
      )}
    </Box>
  );
}

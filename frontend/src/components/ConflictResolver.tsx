import { useState, useCallback, useEffect } from "react";
import { useTranslation } from "react-i18next";
import {
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  List,
  ListItem,
  ListItemButton,
  ListItemText,
  Paper,
  Snackbar,
  Stack,
  Typography,
  Chip,
} from "@mui/material";
import {
  AlertCircle,
  CheckCircle,
  RefreshCw,
} from "lucide-react";
import apiClient from "@/api/client";
import type { AwarenessState } from "@/features/realtime/CollabEditor";

interface VersionEntry {
  version: number;
  createdAt: string;
  size: number;
}

interface ConflictState {
  docId: string;
  currentVersion: number;
  since: number;
  conflicting: boolean;
  versions: VersionEntry[];
}

interface Props {
  /** 当前文档 ID */
  docId: string;
  /** 当前已知的最新 base64 state（本地保存时提交，用于服务端合并后对比） */
  localBase64State?: string;
  /** 远程文档内容字符串（来自远端消息的 latest text） */
  remoteText?: string;
  /** 本地未保存的原始文本（进入编辑前的基准文本） */
  localOriginalText?: string;
  /** 检测冲突后需要显示的协同者列表（含光标位置等） */
  collaborators?: AwarenessState[];
  /** 冲突已解决后的回调（通知父组件刷新） */
  onResolved?: () => void;
}

export default function ConflictResolver(props: Props) {
  const { t } = useTranslation();
  const {
    docId,
    remoteText,
    localOriginalText,
    collaborators,
    onResolved,
  } = props;

  const [open, setOpen] = useState(false);
  const [conflict, setConflict] = useState<ConflictState | null>(null);
  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);
  const [applying, setApplying] = useState(false);
  const [snackOpen, setSnackOpen] = useState(false);
  const [snackMsg, setSnackMsg] = useState("");
  const [snackType, setSnackType] = useState<"success" | "error">("success");

  // PHASE127: 拉取冲突信息
  const fetchConflicts = useCallback(async () => {
    try {
      const resp = await apiClient.get<{
        code: number;
        data: ConflictState;
      }>(`/crdt/conflicts/${encodeURIComponent(docId)}`, {
        params: { since: 0 },
      });
      const data = (resp as any)?.data;
      if (data) {
        setConflict(data);
        setOpen(true);
      }
    } catch (err) {
      console.error("[ConflictResolver] 拉取冲突失败:", err);
      setSnackMsg(t("conflict.fetchFailed"));
      setSnackType("error");
      setSnackOpen(true);
    }
  }, [docId, t]);

  // PHASE127: 当 remoteText 变化且与本地不一致时，主动拉取冲突
  useEffect(() => {
    if (!remoteText || !localOriginalText) return;
    if (remoteText === localOriginalText) return;
    fetchConflicts();
  }, [remoteText, localOriginalText, fetchConflicts]);

  // 选择某个版本展示 diff（当前仅用于判断是否显示预览区域）

  // 回滚到指定版本
  const handleRollback = useCallback(
    async (targetVersion: number) => {
      setApplying(true);
      try {
        const resp = await apiClient.post(
          `/crdt/rollback/${encodeURIComponent(docId)}`,
          { version: targetVersion },
        );
        console.log("[ConflictResolver] 回滚成功:", resp);
        setSnackMsg(
          t("conflict.rollBackSuccess", { version: targetVersion }),
        );
        setSnackType("success");
        setSnackOpen(true);
        setOpen(false);
        onResolved?.();
      } catch (err) {
        console.error("[ConflictResolver] 回滚失败:", err);
        setSnackMsg(t("conflict.rollBackFailed"));
        setSnackType("error");
        setSnackOpen(true);
      } finally {
        setApplying(false);
      }
    },
    [docId, t, onResolved],
  );

  // 手动应用远程版本
  const handleApplyRemote = useCallback(() => {
    setOpen(false);
    onResolved?.();
  }, [onResolved]);

  // 保留本地修改，忽略远程
  const handleKeepLocal = useCallback(() => {
    setOpen(false);
    onResolved?.();
  }, [onResolved]);

  return (
    <>
      {/* 触发按钮 — 浮在编辑器角落 */}
      {conflict?.conflicting && !open && (
        <IconButton
        onClick={fetchConflicts}
        sx={{
          position: "fixed",
          bottom: 24,
          right: 24,
          bgcolor: "warning.main",
          color: "warning.contrastText",
          boxShadow: 3,
          zIndex: 1300,
          "&:hover": { bgcolor: "warning.dark" },
        }}
        aria-label={t("conflict.resolveLabel")}
        title={t("conflict.resolveTooltip")}
        >
        <AlertCircle size={20} style={{ color: "white" }} />
        </IconButton>
      )}

      {/* 冲突解决对话框 */}
      <Dialog
        open={open}
        onClose={() => setOpen(false)}
        maxWidth="md"
        fullWidth
        aria-labelledby="conflict-dialog-title"
      >
        <DialogTitle id="conflict-dialog-title">
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <AlertCircle size={20} style={{ color: '#ed6c02' }} />
            <span>{t("conflict.title")}</span>
            {conflict && (
              <Chip
                size="small"
                label={`v${conflict.currentVersion}`}
                color="info"
                variant="outlined"
              />
            )}
          </Box>
        </DialogTitle>
        <DialogContent dividers>
          <Stack spacing={3}>
            {/* 冲突提示 */}
            {conflict?.conflicting ? (
              <Typography variant="body2" color="warning.dark">
                {t("conflict.alert", {
                  currentVersion: conflict.currentVersion,
                  since: conflict.since,
                  conflicts: conflict.versions.length,
                })}
              </Typography>
            ) : (
              <Typography variant="body2" color="success.dark">
                {t("conflict.noConflict")}
              </Typography>
            )}

            {/* 版本历史列表 */}
            <Box>
              <Typography variant="subtitle2" gutterBottom>
                {t("conflict.versionHistory")}
              </Typography>
              <Paper variant="outlined" elevation={0}>
                <List dense>
                  {conflict?.versions.map((v) => (
                    <ListItem key={v.version} disablePadding>
                      <ListItemButton
                        selected={selectedVersion === v.version}
                        onClick={() => setSelectedVersion(v.version)}
                      >
                        <ListItemText
                          primary={t("conflict.versionLabel", { version: v.version })}
                          secondary={new Date(v.createdAt).toLocaleString()}
                        />
                        {v.size && (
                          <Chip
                            size="small"
                            label={`${(v.size / 1024).toFixed(1)} KB`}
                            variant="outlined"
                          />
                        )}
                      </ListItemButton>
                    </ListItem>
                  ))}
                </List>
              </Paper>
            </Box>

            {/* 差异预览 — 远程 vs 本地基准 */}
            {selectedVersion != null && remoteText && localOriginalText && remoteText !== localOriginalText && (
              <Box>
                <Typography variant="subtitle2" gutterBottom>
                  {t("conflict.diffPreview", { version: selectedVersion })}
                </Typography>
                <Paper
                  variant="outlined"
                  sx={{
                    p: 2,
                    maxHeight: 240,
                    overflow: "auto",
                    fontFamily: "monospace",
                    fontSize: 12,
                    whiteSpace: "pre-wrap",
                    wordBreak: "break-all",
                    bgcolor: "background.paper",
                  }}
                >
                  {/* 绿色高亮：远端新增内容 */}
                  <mark style={{ backgroundColor: "#c8e6c9", padding: "0 2px" }}>
                    {remoteText}
                  </mark>
                  {/* 删除线：本地原有内容 */}
                  <del style={{ color: "error.main", padding: "0 2px" }}>
                    {localOriginalText}
                  </del>
                </Paper>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
                  {t("conflict.diffHint")}
                </Typography>
              </Box>
            )}

            {/* 协同者列表 */}
            {collaborators && collaborators.length > 0 && (
              <Box>
                <Typography variant="subtitle2" gutterBottom>
                  {t("conflict.collaborators", { count: collaborators.length })}
                </Typography>
                <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                  {collaborators.map((c) => (
                    <Chip
                      key={c.userId}
                      size="small"
                      label={
                        c.username ||
                        c.userId.slice(0, 6) +
                          (c.cursorPosition != null
                            ? ` @${c.cursorPosition}`
                            : "")
                      }
                      avatar={
                        <Box
                          sx={{
                            width: 20,
                            height: 20,
                            borderRadius: "50%",
                            bgcolor: "primary.main",
                            display: "flex",
                            alignItems: "center",
                            justifyContent: "center",
                            color: "white",
                            fontSize: 10,
                          }}
                        >
                          {(c.username || "?").slice(0, 1).toUpperCase()}
                        </Box>
                      }
                    />
                  ))}
                </Box>
              </Box>
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>
            {t("conflict.cancel")}
          </Button>
          <Button
            variant="outlined"
            color="info"
            onClick={handleKeepLocal}
            disabled={applying}
            startIcon={<CheckCircle size={16} />}
          >
            {t("conflict.keepLocal")}
          </Button>
          <Button
            variant="contained"
            onClick={handleApplyRemote}
            disabled={applying}
            startIcon={<RefreshCw size={16} />}
          >
            {t("conflict.applyRemote")}
          </Button>
          {selectedVersion != null && (
            <Button
              variant="contained"
              color="warning"
              onClick={() => handleRollback(selectedVersion)}
              disabled={applying}
            >
              {t("conflict.rollbackTo", { version: selectedVersion })}
            </Button>
          )}
        </DialogActions>
      </Dialog>

      <Snackbar
        open={snackOpen}
        autoHideDuration={3000}
        onClose={() => setSnackOpen(false)}
        message={
          <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
            {snackType === "success" ? (
              <CheckCircle size={16} style={{ color: '#2e7d32' }} />
            ) : (
              <AlertCircle size={16} style={{ color: '#d32f2f' }} />
            )}
            <span>{snackMsg}</span>
          </Box>
        }
      />
    </>
  );
}

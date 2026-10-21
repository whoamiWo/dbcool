import { useState, useEffect } from "react";
import { Box, Alert } from "@mui/material";

export function OfflineBanner() {
  const [online, setOnline] = useState(typeof navigator !== "undefined" ? navigator.onLine : true);

  useEffect(() => {
    const onOnline = () => setOnline(true);
    const onOffline = () => setOnline(false);

    window.addEventListener("online", onOnline);
    window.addEventListener("offline", onOffline);

    return () => {
      window.removeEventListener("online", onOnline);
      window.removeEventListener("offline", onOffline);
    };
  }, []);

  if (online) return null;

  return (
    <Box
      sx={{
        position: "fixed",
        top: 0,
        left: 0,
        right: 0,
        zIndex: 9999,
        animation: "slideDown 0.3s ease-out",
      }}
    >
      <Alert severity="warning" sx={{ borderRadius: 0 }}>
        您当前处于离线状态，部分功能可能受限。请检查网络连接后重试。
      </Alert>
    </Box>
  );
}

export default OfflineBanner;

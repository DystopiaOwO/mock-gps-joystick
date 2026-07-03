# 隱私與地圖網路說明

本 App 需要 `INTERNET` 權限是為了載入 OpenStreetMap 地圖圖磚。

App 不會上傳座標、收藏、設定或使用者資料。

但地圖圖磚供應商仍可能看到你的 IP 與請求的地圖區域。

## 網路使用範圍

- 執行時只向 `https://tile.openstreetmap.org/{z}/{x}/{y}.png` 載入目前畫面需要的圖磚。
- Leaflet JavaScript 與 CSS 已隨 APK 打包，不從 CDN 載入。
- 不含 analytics、廣告、帳號登入、自訂後端或遊戲整合。
- 不背景預抓、不提供離線圖磚下載，也不批次下載區域或多層縮放圖磚。
- WebView 使用一般 HTTP cache 並遵守圖磚伺服器的 cache headers。

OpenStreetMap 圖磚是 best-effort 服務，可能因網路、政策或服務狀態暫時無法顯示。若未來公開發布或大量分享，應重新評估正式地圖服務或 map／noMap variants。

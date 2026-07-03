# Mock GPS Joystick

Android 免 Root 的 Mock Location 測試工具，使用官方 `LocationManager` test provider、可拖曳懸浮搖桿與 OpenStreetMap 地圖選點。

## 限制

- 需要 Android 8.0（API 26）以上。
- 使用者必須自行開啟開發人員選項，並選擇本 App 為「模擬位置應用程式」。
- 不使用 Root、Xposed、Magisk，不修改其他 App，不攔截封包，不做反偵測。
- `INTERNET` 僅用於載入 OpenStreetMap 圖磚；App 不上傳座標、收藏、設定或其他使用者資料。

## 快速開始

1. 依 [安裝說明](docs/install.md) 安裝並完成權限設定。
2. 輸入起始經緯度，或按「開啟地圖選點」在 OpenStreetMap 上選擇位置。
3. 從可見的主畫面按「啟動」。
4. 切換到 GPS status 或 maps 類 App，用透明懸浮搖桿驗證外部位置讀值。
5. 搖桿上方的數字鍵可切換 1／3／5／10 km/h；書籤鍵可命名、保存、套用或刪除本機座標。
6. 從通知按「停止」，或回到主畫面按「停止」。

通知只提供停止與回到 App，不會從背景啟動 Mock GPS。從最近任務滑除 App 或以返回手勢真正結束主畫面，也會清除服務與懸浮窗；旋轉或其他 configuration change 不會停止服務。

已儲存座標只存在 App 的本機 `SharedPreferences`，不會上傳。點選座標後會清零搖桿並在下一次更新推送該位置。

建置與簽名請參閱 [APK 建置](docs/build-apk.md)，常見問題請參閱 [疑難排解](docs/troubleshooting.md)，網路用途請參閱 [隱私說明](docs/privacy.md)。

地圖使用隨 APK 打包的 Leaflet 1.9.4（BSD-2-Clause），授權文字位於 `third_party/leaflet/LICENSE`。

# 疑難排解

## Mock Location 啟動失敗

確認已在開發人員選項的「選取模擬位置應用程式」選擇本 App。重新安裝或切換 Debug／Release 後，請再次確認系統仍選擇本 App。

## 搖桿沒有出現

重新確認「顯示在其他應用程式上層」權限。停止再啟動；不要重複快速按 START。

## App 顯示移動，但外部位置沒變

App 內座標不是系統輸出證據。使用 GPS status／maps App 或 `adb shell dumpsys location` 驗證，並確認系統定位已開啟。

## Foreground Service 啟動失敗

START 必須從可見的 MainActivity 觸發，且需先授予精確定位、通知、懸浮窗權限並開啟系統定位。

## App 被系統停止

Samsung、Xiaomi、OPPO、ASUS 等裝置可能需要允許背景執行或關閉該 App 的電池最佳化。前景服務執行時必須保留常駐通知。

## APK 無法安裝或覆蓋

Debug 與 Release 簽章不同，不能互相覆蓋。Release 更新必須使用首次發布時相同的 JKS；必要時先卸載不同簽章的版本。

## Release 缺少簽名檔

依 [APK 建置](build-apk.md) 執行金鑰產生工具。請勿把 `.jks`、`keystore.properties` 或密碼加入 Git。

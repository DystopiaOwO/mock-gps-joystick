# APK 建置與簽名

## 環境

- JDK 17 以上
- Android SDK Platform 36
- Android SDK Build Tools 35.0.0 以上
- Android SDK Platform Tools

專案附帶 Gradle Wrapper，不需要全域 Gradle。

## Debug APK

```powershell
./gradlew.bat assembleDebug
./gradlew.bat packageDebugApk
```

一般輸出位於 `app/build/outputs/apk/debug/app-debug.apk`；版本化交付檔位於 `dist/MockGpsJoystick-v0.2.0-debug.apk`。

## 建立 Release 金鑰

在專案根目錄執行：

```powershell
./tools/generate-release-keystore.ps1
```

工具以安全隨機密碼建立：

- `mockgps-release.jks`
- `keystore.properties`

密碼只寫入被 Git 忽略的 properties，不會輸出到終端或 Gradle build log。工具拒絕覆寫既有金鑰。

> 請立即安全備份這兩個檔案。日後 APK 更新必須使用同一組金鑰；遺失後無法覆蓋安裝舊版。

## Release APK

```powershell
./gradlew.bat assembleRelease
./gradlew.bat packageReleaseApk
```

版本化輸出位於 `dist/MockGpsJoystick-v0.2.0-release.apk`。

若簽名檔不存在，Release 會立即停止並顯示：

```text
缺少 release keystore，請先依 docs/build-apk.md 建立簽名檔。
```

這不影響 `assembleDebug` 或 `packageDebugApk`。`packageApks` 會強制先產生 Debug APK，再驗證並建置 Release：

```powershell
./gradlew.bat packageApks
```

## 驗證與安裝

```powershell
./gradlew.bat lint
adb install -r ./dist/MockGpsJoystick-v0.2.0-debug.apk
apksigner verify --verbose --print-certs ./dist/MockGpsJoystick-v0.2.0-release.apk
```

Debug 與 Release 使用不同簽章，不能直接互相覆蓋。切換 variant 前需先卸載；同一個 Release APK 可用 `adb install -r` 驗證覆蓋升級。

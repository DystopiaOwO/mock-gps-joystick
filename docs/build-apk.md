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

## GitHub Actions 簽章 Release

`.github/workflows/release.yml` 在兩種情況下建置已簽章的 release APK：

- 推送 `v*` tag（例如 `v0.2.0`）：檔名為 `MockGpsJoystick-vX.Y.Z.apk`（版本字串就是 tag），並上傳到該 tag 的 GitHub Release。
- 手動 `workflow_dispatch`：只把 APK 上傳為 workflow artifact，不建立 GitHub Release。檔名使用 `versionName`，目前為 `MockGpsJoystick-v0.2.0.apk`。

Workflow 會用 `apksigner verify --print-certs` 驗證簽章。手動試跑請用 Actions 的 Run workflow，這樣不會建立 Release。

### Repository secrets

在 GitHub repo 的 Settings → Secrets and variables → Actions 設定這些 repository secrets。不要把值寫進 repo、issue、PR 或 build log。

| Secret | 內容 |
| --- | --- |
| `MOCKGPS_KEYSTORE_BASE64` | release JKS 的 base64。建議單行；含換行也可以。 |
| `MOCKGPS_STORE_PASSWORD` | keystore store 密碼 |
| `MOCKGPS_KEY_ALIAS` | key alias。本機產生工具的預設 alias 是 `mockgps`。 |
| `MOCKGPS_KEY_PASSWORD` | key 密碼 |

把 `mockgps-release.jks` 編成 base64 後，只貼進 secret 欄位：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path .\mockgps-release.jks)))
```

```bash
base64 -w 0 mockgps-release.jks
```

CI 沒有 `keystore.properties`。Workflow 把解出的 JKS 放在 runner 暫存路徑，並以環境變數交給 Gradle：

- `MOCKGPS_KEYSTORE_FILE`
- `MOCKGPS_STORE_PASSWORD`
- `MOCKGPS_KEY_ALIAS`
- `MOCKGPS_KEY_PASSWORD`

本機若已有 `keystore.properties`，仍只使用該檔與其中的 `storeFile`，環境變數不會覆寫。兩者都不存在時，`assembleDebug` 與 `packageDebugApk` 仍可建置；`assembleRelease` 會停止。密碼不會寫進 Gradle task 輸出，也不要在 workflow 裡 `echo` 這些 secret。

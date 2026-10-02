# Android（工廠內部 App）× GitHub Actions：Commit 後自動建置 APK Demo

工廠端 App 不上 Google Play。流程是：改程式 → commit、push → GitHub Actions 自動建置**已簽章**的 APK（Demo 用 repo 內的固定金鑰，正式環境用 Secrets 內的金鑰） → 發佈到 GitHub Release → 工廠裝置下載安裝（直接覆蓋更新）。App 畫面會顯示版本號與 Commit，現場可以確認手機上跑的就是剛剛 push 的版本。

**App 內建自動更新**：每次打開 App（或按「檢查更新」）都會讀取最新的 `version.json`，發現新版就自動下載並安裝，工廠人員不用自己去找 APK。

## 專案結構

```
android-demo/
├─ .github/workflows/android.yml        # Lint 檢查 → 建置 APK → 發佈 Release
├─ ci/demo-signing.p12                  # Demo 用固定簽章金鑰（正式環境改用 Secrets）
├─ app/src/main/res/values/strings.xml  # Demo 時改這裡的 title
├─ app/src/main/java/.../MainActivity.kt   # 畫面；開啟時自動檢查更新
├─ app/src/main/java/.../Updater.kt        # 自動更新：讀 version.json → 下載 → 安裝
├─ app/src/main/java/.../InstallReceiver.kt# 接收安裝結果、跳出確認畫面
├─ app/build.gradle.kts                 # 版本號自動 = 1.0.<執行次數>
└─ settings.gradle.kts / build.gradle.kts
```

App 沒有任何第三方套件，只用 Android 內建元件，建置最快、最不容易出錯。

## 一次性準備（Demo 約 5 分鐘）

### 1. 簽章金鑰（Demo 不用自己建）

Android 規定 APK 必須簽章才能安裝，而且每一版都要用**同一把**金鑰，裝置才能直接覆蓋更新。

- **Demo（預設）**：專案內已附一把固定的 Demo 金鑰 `ci/demo-signing.p12`（密碼 `android`，別名 `demo`）。本機 Debug 版和 GitHub Actions 的 Release 版都用它簽，所以可以互相覆蓋安裝，**不用設定任何 Secrets**。
  - 注意：這把金鑰在公開 repo 裡任何人都拿得到，只適合 Demo，不要用在工廠正式環境。
- **工廠正式使用**：自己建立金鑰並放進 GitHub Secrets，workflow 偵測到 `KEYSTORE_B64` 就會自動改用，不需要改程式。步驟見下一節。

### 2.（正式環境才需要）建立金鑰並設定 GitHub Secrets

```bash
keytool -genkeypair -v -keystore factory.jks -alias factory \
  -keyalg RSA -keysize 2048 -validity 10000
# 依提示設定密碼與名稱

base64 -w0 factory.jks > factory.jks.b64     # Mac：base64 -i factory.jks -o factory.jks.b64
```

到 GitHub **Settings → Secrets and variables → Actions → Secrets** 新增：

| Secret | 內容 |
|---|---|
| `KEYSTORE_B64` | `factory.jks.b64` 的內容 |
| `KEYSTORE_PASSWORD` | keystore 密碼 |
| `KEY_ALIAS` | `factory` |
| `KEY_PASSWORD` | key 密碼（未另外設定就與 keystore 密碼相同） |

`factory.jks` 本身不要 commit（`.gitignore` 已排除），另存一份到公司保管位置。從 Demo 金鑰換成正式金鑰時，裝置上的 App 要先解除安裝一次。

### 3. 上傳專案並先跑一次

```bash
cd android-demo
git init && git add . && git commit -m "初始版本"
git branch -M main
git remote add origin https://github.com/<帳號或組織>/android-demo.git
git push -u origin main
```

Actions 會自動跑（約 3–6 分鐘）。完成後到 **Releases** 會看到 `工廠 Demo v1.0.1` 與 APK。把這版先裝在 Demo 用的手機上。

> 手機需允許「安裝未知來源的應用程式」（設定 → 安全性，或安裝時依提示允許瀏覽器／檔案管理員安裝）。

## 自動更新怎麼運作

```
GitHub Actions 每次建置 ──► Release 附上 factory-demo-v1.0.N.apk + version.json
                                     │
工廠裝置開啟 App ──► 讀 version.json ──► versionCode 比較新？──► 下載 APK ──► 系統安裝（覆蓋更新）
```

- **更新來源網址**：預設是 `https://github.com/<帳號>/<repo>/releases/latest/download/`，只有**公開 repo** 不用登入就能下載，適合 Demo。
- **工廠正式使用（建議）**：把 `version.json` 和 APK 放到公司內部伺服器（例如 `http://mes-files.local/factory-app/`），在 GitHub **Settings → Secrets and variables → Actions → Variables** 設定 `UPDATE_BASE_URL` 為該網址，重新建置一次即可。上傳到內部伺服器可在 workflow 最後加一步，由**公司內的自架 runner** 執行（例如 `cp` 到 NAS 的共享目錄）。若內部伺服器只有 http，需另外在 App 設定允許明文連線，建議直接用 https。
- **需不需要人按確認**：

  | 情況 | 行為 |
  |---|---|
  | 第一次自動更新 | 系統會先請使用者允許本 App「安裝不明應用程式」（只需一次），並跳出「更新」確認畫面 |
  | 之後的更新（Android 12 以上） | 本 App 已成為自己的安裝來源，符合系統條件時可**不跳確認直接更新** |
  | Android 11 以下 | 每次都會跳出確認畫面，按一下「更新」即可 |
  | 要求完全無人操作 | 裝置需由 MDM 設為 Device Owner（專用裝置／Kiosk 模式），由 MDM 靜默安裝 |

- 更新完成後系統會關閉舊版 App，重新點開就是新版。

## Demo 當天腳本（約 6–8 分鐘）

| 步驟 | 操作 | 要講的話 |
|---|---|---|
| 1 | 打開手機上的「工廠 Demo」，指出畫面上的版本 `1.0.1` 與 Commit | 這是目前工廠裝置上的版本 |
| 2 | 編輯 `strings.xml`，把 `Hello, 自動部署 v1` 改成 `v2` | 開發者只改程式碼 |
| 3 | `git diff` | 改了什麼一目了然 |
| 4 | `git commit -am "標題改成 v2" && git push` | 之後只要做這一步 |
| 5 | GitHub → Actions，看 Lint → Build → Release 依序執行 | 不用在某台工程師電腦上手動打包、手動簽章 |
| 6 | 等待期間：說明簽章金鑰放在 Secrets、每版自動編號、每版都有 Release 紀錄 | 誰在什麼時候發了哪一版，全部可查 |
| 7 | 綠燈後打開手機上的 App（或按「檢查更新」），看它自動下載、安裝 | 工廠人員不用做任何事，App 自己更新 |
| 8 | 重新開啟 App | 畫面變成 v2，版本變成 1.0.2 |

**建議**：建置要等數分鐘，可先講第 6 步；或事先準備一次已完成的執行紀錄，萬一現場網路慢就切過去講解。

**Demo 前一定要先演練一次自動更新**：先裝 1.0.1，再 push 產生 1.0.2，確認手機能自動更新（第一次的權限允許在演練時就完成，現場才不會卡在設定畫面）。

## 工廠端怎麼拿到 APK（依公司環境選一種）

| 方式 | 適合情境 | 說明 |
|---|---|---|
| App 自動更新 ＋ 內部伺服器 | **建議做法** | 只有第一次要手動安裝，之後 App 自己更新 |
| GitHub Release 下載 | Demo、少量裝置 | 私有 repo 需要登入 GitHub 才能下載，工廠裝置通常不適合 |
| 自動複製到內部檔案伺服器／NAS | 工廠裝置無 GitHub 帳號 | 在 workflow 最後加一步上傳；需用**公司內的自架 runner**才連得到內網 |
| MDM 推送（如 Intune、Samsung Knox） | 裝置數量多、要集中管理 | 由 MDM 自動派送更新，現場人員不用手動安裝 |

## 常見問題

- **想改用正式金鑰**：照上面「建立金鑰並設定 GitHub Secrets」設定 4 個 Secrets，名稱要完全一致；沒設定時 Actions 會自動使用 Demo 金鑰。
- **安裝時「與現有套件衝突」**：新舊版簽章金鑰不同。確認每次都用同一把 `factory.jks`；Demo 手機上若裝過其他簽章的版本，先解除安裝一次。
- **App 顯示「更新失敗：HTTP 404」**：repo 是私有的，或還沒有任何 Release；Demo 請用公開 repo，正式環境請設定 `UPDATE_BASE_URL`。
- **一直顯示「已是最新版」**：新版還沒建置完成，或 Release 沒有被標成 Latest。
- **Lint 失敗**：打開 Actions 紀錄看錯誤；這也是品質關卡的一環，有問題就不會產出 APK。
- **想改用公司內 GHES**：流程相同；runner 需能連到 Google Maven（`dl.google.com`）下載 Android 建置工具。

## 設定 UPDATE_BASE_URL（自動更新來源）

App 會到 `<UPDATE_BASE_URL>/version.json` 檢查新版。依使用情境擇一設定：

| 情境 | 設定位置 | 值 |
|---|---|---|
| **A. Mac 本機測試**（不用等 GitHub Actions） | `gradle.properties` 的 `updateBaseUrl` | `http://10.0.2.2:8000`（預設已填好） |
| **B. Demo：GitHub Release**（公開 repo） | 不用設定，Actions 自動使用 `https://github.com/<repo>/releases/latest/download` | 本機建置想用此來源時，在 `gradle.properties` 註解掉 `updateBaseUrl`，填 `githubRepo=帳號/repo` |
| **C. 工廠正式：內部伺服器** | GitHub → Settings → Secrets and variables → Actions → **Variables** → `UPDATE_BASE_URL` | 例如 `https://factory-files.tti.local/factory-app` |

`gradle.properties` 裡的 `updateBaseUrl` **只在本機生效**，GitHub Actions 建置時會自動忽略，不會把 10.0.2.2 發佈出去。

### A. 在 Mac + 模擬器上測試自動更新（約 10 分鐘）

`10.0.2.2` 是 Android 模擬器裡代表「這台 Mac」的固定位址。本機建置都用同一把 debug 金鑰，所以可以直接覆蓋更新。

1. **安裝第 1 版**：在 Android Studio 按 Run（版本 1.0.1）。
2. **改畫面**：把 `strings.xml` 的標題改成 `Hello, 自動部署 v2`。
3. **建置第 2 版**（不要按 Run，只建置）：
   ```bash
   ./gradlew assembleDebug -PbuildNumber=2
   ```
   沒有 `gradlew` 時，用 Android Studio 的 Terminal 執行同樣指令，或在 `gradle.properties` 暫時加一行 `buildNumber=2` 後選 Build → Build APK(s)。
4. **發佈到本機更新伺服器**：
   ```bash
   ./scripts/publish-local.sh 2
   cd update-server && python3 -m http.server 8000
   ```
   在 Mac 瀏覽器打開 `http://localhost:8000/version.json` 確認看得到內容。
5. **在模擬器打開 App**（或按「檢查更新」）→ 允許「安裝不明應用程式」→ 按「更新」→ 重新開啟 App，畫面變成 v2、版本 1.0.2。

> 要再測一次：改標題 → `-PbuildNumber=3` 建置 → `./scripts/publish-local.sh 3`（http.server 不用重開）。

### C. 工廠內部伺服器注意事項

- 伺服器只要能用網址下載 `version.json` 和 APK 即可（IIS、Nginx、NAS 的 Web 共享都行），兩個檔案放在同一個資料夾。
- 盡量用 **https**。若只能用 http，要把該主機加進 `app/src/main/res/xml/network_security_config.xml`，否則 Android 9 以上會拒絕連線。
- 檔案要自動放上去，需要在公司內架 **self-hosted runner**，在 workflow 最後加一步把 `factory-demo-v*.apk` 和 `version.json` 複製到該資料夾。
- 改了 `UPDATE_BASE_URL` 之後要**重新建置並安裝一次**：舊版 App 仍會去舊網址找更新。

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 讀取設定：環境變數（GitHub Actions）優先，其次是 gradle.properties 或指令參數 -Pxxx=
fun setting(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(prop) as String?)?.takeIf { it.isNotBlank() }

// 版本號：GitHub Actions 用執行次數；本機可用 -PbuildNumber=2 指定
val runNumber = (setting("GITHUB_RUN_NUMBER", "buildNumber") ?: "1").toInt()
val commitSha = (System.getenv("GITHUB_SHA") ?: "local").take(7)

// 自動更新來源：App 會到 <UPDATE_BASE_URL>/version.json 檢查新版
// 優先順序：
//   1. 環境變數 UPDATE_BASE_URL（GitHub Variables 設定，例如工廠內部伺服器）
//   2. gradle.properties 的 updateBaseUrl（只在本機有效；在 GitHub Actions 上會被忽略，避免把測試網址發佈出去）
//   3. GitHub Release 最新版網址（需要知道 repo：Actions 會自動帶入，本機則讀 gradle.properties 的 githubRepo）
val isCI = System.getenv("GITHUB_ACTIONS") == "true"
val githubRepo = setting("GITHUB_REPOSITORY", "githubRepo")
val updateBaseUrl = System.getenv("UPDATE_BASE_URL")?.takeIf { it.isNotBlank() }
    ?: (if (isCI) null else (project.findProperty("updateBaseUrl") as String?)?.takeIf { it.isNotBlank() })
    ?: githubRepo?.let { "https://github.com/$it/releases/latest/download" }
    ?: error("請設定更新來源：gradle.properties 的 updateBaseUrl 或 githubRepo")

android {
    namespace = "com.tti.factorydemo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tti.factorydemo"
        minSdk = 24
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
        buildConfigField("String", "COMMIT_SHA", "\"$commitSha\"")
        buildConfigField("String", "UPDATE_BASE_URL", "\"$updateBaseUrl\"")
    }

    // 每一版都要用同一把金鑰簽章，裝置才能直接覆蓋更新。兩種來源：
    //   demo    ：Demo 用的固定金鑰，放在 repo 的 ci/demo-signing.p12（密碼公開，只適合 Demo）。
    //             本機 Debug 版與 GitHub Actions 的 Release 版都用它，所以可以互相覆蓋安裝。
    //   release ：正式用。設定 KEYSTORE_PATH 等環境變數（來自 GitHub Secrets）時自動改用。
    signingConfigs {
        create("demo") {
            storeFile = rootProject.file("ci/demo-signing.p12")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "demo"
            keyPassword = "android"
        }
        create("release") {
            val ks = System.getenv("KEYSTORE_PATH")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("demo")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(
                if (System.getenv("KEYSTORE_PATH") != null) "release" else "demo"
            )
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

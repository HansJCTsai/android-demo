package com.tti.factorydemo

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 顯示本次建置的版本與 Commit，現場可確認手機上跑的就是剛 push 的版本
        findViewById<TextView>(R.id.version).text =
            getString(R.string.version_format, BuildConfig.VERSION_NAME, BuildConfig.COMMIT_SHA)

        status = findViewById(R.id.update_status)
        findViewById<Button>(R.id.check_update).setOnClickListener { checkUpdate() }
    }

    // 每次回到 App 畫面就自動檢查一次更新
    override fun onResume() {
        super.onResume()
        checkUpdate()
    }

    private fun checkUpdate() {
        Updater.check(this) { msg -> runOnUiThread { status.text = msg } }
    }
}

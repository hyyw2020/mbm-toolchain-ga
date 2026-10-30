/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * MBM v1.0.28：应用内离线查看开源协议全文。
 *
 * 合规动机：APK 内必须随附开源协议说明，且必须能**离线**查看——
 * 原 LicensesFragment 点击第三方许可时直接 startActivity(ACTION_VIEW) 跳浏览器，
 * 需要联网，与本项目「零 INTERNET 权限、全离线」的定位冲突，且 APK 里
 * 根本不含 LICENSE/NOTICE 文件（合规缺口）。
 *
 * 这里改为：协议文本由 :app 的 syncLicenseAssets 任务从仓库根目录同步进
 * assets/licenses/，随 APK 分发；本页面以纯文本方式离线渲染，不联网。
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R
import timber.log.Timber

class LicenseTextActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val assetPath = intent.getStringExtra(EXTRA_ASSET)
        val title = intent.getStringExtra(EXTRA_TITLE)
            ?: getString(R.string.licenses_of_third_party_libraries)

        val text = assetPath?.let { path ->
            runCatching {
                assets.open(path).bufferedReader().use { it.readText() }
            }.onFailure {
                Timber.w(it, "读取许可文件失败: $path")
            }.getOrNull()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        if (text == null) {
            root.addView(TextView(this).apply {
                this.text = "许可文件读取失败：$assetPath"
                setTextColor(ContextCompat.getColor(this@LicenseTextActivity, android.R.color.holo_red_dark))
                gravity = Gravity.CENTER
            })
            setContentView(root)
            return
        }

        // 纯文本协议（AGPL 全文 / NOTICE / 上游清单）：等宽字体保持条款排版
        val body = TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            movementMethod = LinkMovementMethod.getInstance()
            setTextColor(ContextCompat.getColor(this@LicenseTextActivity, R.color.license_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val scroll = ScrollView(this).apply {
            addView(body)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(scroll)
        setContentView(root)

        // edge-to-edge：让 ScrollView 吸收系统栏 inset（见 MbmActivityUi.fitInsets 的踩坑说明）
        MbmActivityUi.fitInsets(this, scroll)

        this.title = title
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val EXTRA_ASSET = "asset"
        private const val EXTRA_TITLE = "title"

        fun launch(context: Context, assetPath: String, title: String) {
            context.startActivity(Intent(context, LicenseTextActivity::class.java).apply {
                putExtra(EXTRA_ASSET, assetPath)
                putExtra(EXTRA_TITLE, title)
            })
        }

    /** 仓库根目录文件同步进 assets 后的路径，与 :app 的 syncLicenseAssets 任务保持一致 */
    const val ASSET_AGPL = "licenses/AGPL-3.0.txt"
    const val ASSET_NOTICE = "licenses/NOTICE.txt"
    const val ASSET_UPSTREAM = "licenses/UPSTREAM.txt"

    /** MBM v1.0.32：隐私政策（PRIVACY.txt 由 syncLicenseAssets 同步进 assets） */
    const val ASSET_PRIVACY = "licenses/PRIVACY.txt"
    }
}

package com.litepan.app

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.litepan.app.databinding.ActivitySetupBinding
import java.net.HttpURLConnection
import java.net.URL

/**
 * 首次启动 / 手动修改服务器地址时使用。
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 给根容器预留状态栏空间，避免顶栏与状态栏重叠
        // 注意：不能用 v.paddingTop + statusBars.top（会在每次 inset 重派发时累加）
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(0, top, 0, v.paddingBottom)
            insets
        }
        binding.root.requestApplyInsets()

        ServerPrefs.getServerUrl(this)
            .takeIf { it.isNotBlank() }
            ?.let { binding.editUrl.setText(it) }

        binding.btnSave.setOnClickListener { saveAndEnter() }
        binding.btnTest.setOnClickListener { testConnection() }
        binding.editUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveAndEnter()
                true
            } else {
                false
            }
        }
    }

    private fun saveAndEnter() {
        val raw = binding.editUrl.text?.toString().orEmpty()
        if (raw.isBlank()) {
            binding.layoutUrl.error = getString(R.string.server_url_empty)
            return
        }
        val normalized = ServerPrefs.normalize(raw)
        if (normalized == null) {
            binding.layoutUrl.error = getString(R.string.server_url_invalid)
            return
        }
        binding.layoutUrl.error = null
        hideKeyboard()
        ServerPrefs.setServerUrl(this, normalized)

        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    private fun testConnection() {
        val raw = binding.editUrl.text?.toString().orEmpty()
        if (raw.isBlank()) {
            binding.layoutUrl.error = getString(R.string.server_url_empty)
            return
        }
        val normalized = ServerPrefs.normalize(raw)
        if (normalized == null) {
            binding.layoutUrl.error = getString(R.string.server_url_invalid)
            return
        }
        binding.layoutUrl.error = null

        binding.btnTest.isEnabled = false
        binding.txtTestResult.text = getString(R.string.testing)
        binding.txtTestResult.setTextColor(
            ContextCompat.getColor(this, R.color.text_secondary)
        )

        Thread {
            val reachable = ping(normalized)
            runOnUiThread {
                binding.btnTest.isEnabled = true
                if (reachable) {
                    binding.txtTestResult.text = getString(R.string.test_ok)
                    binding.txtTestResult.setTextColor(
                        ContextCompat.getColor(this, R.color.result_ok)
                    )
                } else {
                    binding.txtTestResult.text = getString(R.string.test_fail)
                    binding.txtTestResult.setTextColor(
                        ContextCompat.getColor(this, R.color.result_fail)
                    )
                }
            }
        }.start()
    }

    /** 只要拿到任意 HTTP 响应码即视为网络可达 */
    private fun ping(baseUrl: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "LitePanApp")
            }
            connection.connect()
            connection.responseCode > 0
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        currentFocus?.let {
            imm?.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }
}

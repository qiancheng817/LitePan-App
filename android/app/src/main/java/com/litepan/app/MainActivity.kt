package com.litepan.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.litepan.app.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URLConnection
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var serverUrl: String = ""

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var loadedUrl: String? = null

    /** 网页 <input type="file"> 选择文件（支持多选） */
    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val callback = filePathCallback
        filePathCallback = null
        callback?.onReceiveValue(
            if (uris.isNotEmpty()) uris.toTypedArray() else null
        )
    }

    /** 存储 / 通知 / 摄像头运行时权限统一申请入口 */
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果不阻断流程，用户重试即可 */ }

    private val blobSessions = mutableMapOf<String, BlobSession>()

    private class BlobSession(
        val output: OutputStream,
        val displayName: String,
        val mime: String,
        val mediaUri: Uri?,
        val tempFile: File?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val configured = ServerPrefs.getServerUrl(this)
        if (configured.isBlank()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        serverUrl = configured

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 给根容器预留状态栏空间，避免网页顶栏与系统状态栏重叠
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, v.paddingTop + statusBars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        binding.root.requestApplyInsets()

        setupWebView()
        setupSwipeRefresh()
        setupErrorActions()
        setupMenu()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    finish()
                }
            }
        })

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            binding.webView.loadUrl(serverUrl)
            loadedUrl = serverUrl
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回且服务器地址发生变化时重新加载
        val current = ServerPrefs.getServerUrl(this)
        if (current.isNotBlank() && current != loadedUrl && this::binding.isInitialized) {
            serverUrl = current
            loadedUrl = current
            showError(false)
            binding.webView.loadUrl(current)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (this::binding.isInitialized) {
            binding.webView.saveState(outState)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }

        WebView.setWebContentsDebuggingEnabled(true)
        binding.webView.addJavascriptInterface(BlobBridge(), "Android")
        binding.webView.webViewClient = LitePanWebClient()
        binding.webView.webChromeClient = LitePanChromeClient()

        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (URLUtil.isNetworkUrl(url)) {
                enqueueSystemDownload(url, userAgent, contentDisposition, mimeType)
            }
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.brand)
        )
        binding.swipeRefresh.setOnRefreshListener {
            binding.webView.reload()
        }
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            binding.webView.scrollY > 0
        }
    }

    private fun setupErrorActions() {
        binding.btnRetry.setOnClickListener {
            showError(false)
            binding.webView.reload()
        }
        binding.btnChangeServer.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    private fun setupMenu() {
        addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: android.view.MenuInflater) {
                menuInflater.inflate(R.menu.menu_main, menu)
            }

            override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
                R.id.action_refresh -> {
                    binding.webView.reload(); true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this@MainActivity, SetupActivity::class.java)); true
                }
                R.id.action_open_browser -> {
                    val openUrl = binding.webView.url ?: serverUrl
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(openUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                        toast("未找到可用浏览器")
                    }
                    true
                }
                R.id.action_reset -> {
                    confirmReset(); true
                }
                else -> false
            }
        })
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_reset)
            .setMessage("将清除本应用保存的登录状态与缓存，确定继续吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                binding.webView.clearCache(true)
                binding.webView.clearHistory()
                toast(getString(R.string.reset_done))
                binding.webView.loadUrl(serverUrl)
            }
            .show()
    }

    private fun showError(show: Boolean, detail: String? = null) {
        binding.errorView.visibility = if (show) View.VISIBLE else View.GONE
        if (show && !detail.isNullOrBlank()) {
            binding.txtErrorDetail.text = detail
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        if (this::binding.isInitialized) {
            (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
            binding.webView.stopLoading()
            binding.webView.destroy()
        }
        synchronized(blobSessions) {
            blobSessions.values.forEach { runCatching { it.output.close() } }
            blobSessions.clear()
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // WebViewClient
    // ------------------------------------------------------------------

    private inner class LitePanWebClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val uri = request?.url ?: return false
            val scheme = uri.scheme ?: return false
            // http(s) 全部留在 WebView 内（包含云盘 OAuth 跳转）
            if (scheme == "http" || scheme == "https") return false
            // 其他 scheme（mailto/tel/自定义 scheme/intent）交给外部应用
            return try {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            } catch (_: Exception) {
                toast("无法打开该链接")
                true
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            showError(false)
            binding.progressBar.visibility = View.VISIBLE
            view?.evaluateJavascript(BLOB_HOOK_JS, null)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            binding.progressBar.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
            view?.evaluateJavascript(BLOB_HOOK_JS, null)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true) {
                binding.swipeRefresh.isRefreshing = false
                binding.progressBar.visibility = View.GONE
                showError(true, error?.description?.toString())
            }
        }
    }

    // ------------------------------------------------------------------
    // WebChromeClient
    // ------------------------------------------------------------------

    private inner class LitePanChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            binding.progressBar.progress = newProgress
            binding.progressBar.visibility =
                if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onShowFileChooser(
            webView: WebView?,
            callback: ValueCallback<Array<Uri>>?,
            params: FileChooserParams?
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            val acceptTypes = params?.acceptTypes
                ?.filter { it.isNotBlank() }
                ?.toTypedArray()
                ?.takeIf { it.isNotEmpty() }
                ?: arrayOf("*/*")
            return try {
                pickFilesLauncher.launch(acceptTypes)
                true
            } catch (_: Exception) {
                filePathCallback = null
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            request ?: return
            val wantsCamera = request.resources.any {
                it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
            }
            if (wantsCamera &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                request.grant(request.resources)
            } else {
                if (wantsCamera) {
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
                request.deny()
            }
        }
    }

    // ------------------------------------------------------------------
    // 普通 http(s) 下载：交给系统 DownloadManager
    // ------------------------------------------------------------------

    private fun enqueueSystemDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            toast(getString(R.string.need_storage_permission))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
            addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
            if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
            setTitle(fileName)
            setDescription("LitePan")
            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or
                    DownloadManager.Request.NETWORK_MOBILE
            )
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, "LitePan/$fileName"
            )
            allowScanningByMediaScanner()
        }
        manager.enqueue(request)
        toast(getString(R.string.download_started, fileName))
    }

    // ------------------------------------------------------------------
    // blob: 下载：JS 分片 -> Java 桥接 -> Download/LitePan
    // ------------------------------------------------------------------

    private inner class BlobBridge {

        @JavascriptInterface
        fun blobBegin(filename: String, mime: String): String? {
            val safeName = sanitizeFileName(filename)
            val safeMime = mime.ifBlank { "application/octet-stream" }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                runOnUiThread {
                    requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    toast(getString(R.string.need_storage_permission))
                }
                return null
            }
            return try {
                val session = openBlobSession(safeName, safeMime)
                val id = UUID.randomUUID().toString()
                synchronized(blobSessions) { blobSessions[id] = session }
                id
            } catch (e: Exception) {
                null
            }
        }

        @JavascriptInterface
        fun blobChunk(id: String, base64: String): Boolean {
            val session = synchronized(blobSessions) { blobSessions[id] } ?: return false
            return try {
                session.output.write(Base64.decode(base64, Base64.DEFAULT))
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun blobEnd(id: String, error: String?): Boolean {
            val session = synchronized(blobSessions) { blobSessions.remove(id) } ?: return false
            return try {
                session.output.flush()
                session.output.close()
                if (!error.isNullOrBlank()) {
                    session.tempFile?.delete()
                    if (session.mediaUri != null) {
                        contentResolver.delete(session.mediaUri, null, null)
                    }
                    false
                } else {
                    finalizeBlobSession(session)
                    runOnUiThread {
                        toast(getString(R.string.download_blob_saved, session.displayName))
                    }
                    true
                }
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun toast(message: String) {
            runOnUiThread { toast(message) }
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\r\\n\\t]"), "")
            .trim()
            .ifBlank { "litepan-${System.currentTimeMillis()}" }
        return cleaned.take(180)
    }

    @SuppressLint("NewApi")
    private fun openBlobSession(name: String, mime: String): BlobSession {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LitePan")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            val output = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Open output stream failed")
            return BlobSession(output, name, mime, uri, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "LitePan"
            )
            dir.mkdirs()
            val file = uniqueFile(File(dir, name))
            return BlobSession(FileOutputStream(file), file.name, mime, null, file)
        }
    }

    @SuppressLint("NewApi")
    private fun finalizeBlobSession(session: BlobSession) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = session.mediaUri ?: return
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            contentResolver.update(uri, values, null, null)
        } else {
            val file = session.tempFile ?: return
            val mime = URLConnection.guessContentTypeFromName(file.name)
                ?: "application/octet-stream"
            android.media.MediaScannerConnection.scanFile(
                this, arrayOf(file.absolutePath), arrayOf(mime), null
            )
        }
    }

    private fun uniqueFile(file: File): File {
        if (!file.exists()) return file
        val name = file.nameWithoutExtension
        val ext = file.extension
        val suffix = "_${System.currentTimeMillis()}"
        return if (ext.isBlank()) File(file.parentFile, "$name$suffix")
        else File(file.parentFile, "$name$suffix.${ext}")
    }

    companion object {
        /**
         * 拦截网页内 blob: 链接点击，分片读出后通过 Android 桥接保存，
         * 规避 Binder 1MB 事务上限与 DownloadManager 不支持 blob 的限制。
         */
        private val BLOB_HOOK_JS = """
            (function () {
              if (window.__litepanBlobHooked) return;
              window.__litepanBlobHooked = true;
              var CHUNK = 256 * 1024;
              var MAX_SIZE = 500 * 1024 * 1024;
              function saveBlob(name, blob) {
                var id = null;
                var p = Promise.resolve().then(function () {
                  id = window.Android.blobBegin(name || 'litepan-download', blob.type || '');
                  if (!id) throw new Error('begin failed');
                  var offset = 0;
                  function step() {
                    if (offset >= blob.size) return;
                    var slice = blob.slice(offset, Math.min(offset + CHUNK, blob.size));
                    return new Promise(function (resolve, reject) {
                      var reader = new FileReader();
                      reader.onloadend = function () {
                        var result = String(reader.result || '');
                        var comma = result.indexOf(',');
                        var data = comma >= 0 ? result.substring(comma + 1) : '';
                        if (!window.Android.blobChunk(id, data)) {
                          reject(new Error('chunk failed'));
                        } else {
                          offset += CHUNK;
                          resolve(step());
                        }
                      };
                      reader.onerror = reject;
                      reader.readAsDataURL(slice);
                    });
                  }
                  return step();
                });
                p.then(function () {
                  window.Android.blobEnd(id, '');
                }).catch(function (err) {
                  if (id) window.Android.blobEnd(id, String(err && err.message || err));
                  window.Android.toast('文件保存失败');
                });
              }
              document.addEventListener('click', function (e) {
                var anchor = e.target && e.target.closest ? e.target.closest('a') : null;
                if (!anchor || typeof anchor.href !== 'string' ||
                    anchor.href.indexOf('blob:') !== 0) return;
                e.preventDefault();
                e.stopPropagation();
                var href = anchor.href;
                fetch(href).then(function (r) { return r.blob(); }).then(function (blob) {
                  if (blob.size > MAX_SIZE) {
                    window.Android.toast('文件过大，请用浏览器下载');
                    return;
                  }
                  saveBlob(anchor.download || 'litepan-download', blob);
                }).catch(function () {
                  window.Android.toast('下载失败');
                });
              }, true);
            })();
        """.trimIndent()
    }
}

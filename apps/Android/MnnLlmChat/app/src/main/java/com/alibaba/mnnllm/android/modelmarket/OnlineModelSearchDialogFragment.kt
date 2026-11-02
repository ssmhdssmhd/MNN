package com.alibaba.mnnllm.android.modelmarket

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.alibaba.mls.api.download.DownloadInfo
import com.alibaba.mls.api.download.DownloadListener
import com.alibaba.mls.api.download.ModelDownloadManager
import com.alibaba.mnnllm.android.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Search results for MNN-format models (HuggingFace taobao-mnn / ModelScope MNN).
 * Each row can be downloaded directly through ModelDownloadManager, so the model
 * becomes available in the local model list as soon as the transfer completes.
 */
class OnlineModelSearchDialogFragment : DialogFragment(), DownloadListener {

    companion object {
        private const val ARG_QUERY = "query"

        fun newInstance(query: String): OnlineModelSearchDialogFragment {
            return OnlineModelSearchDialogFragment().apply {
                arguments = Bundle().apply { putString(ARG_QUERY, query) }
            }
        }
    }

    private lateinit var container: LinearLayout
    private lateinit var downloadManager: ModelDownloadManager
    private val rowButtons = mutableMapOf<String, Button>()
    private val rowStatus = mutableMapOf<String, TextView>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val query = arguments?.getString(ARG_QUERY).orEmpty()
        val context = requireContext()

        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(context).apply { addView(container) }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.online_search_title, query))
            .setView(scroll)
            .setNegativeButton(R.string.close, null)
            .create()

        ModelDownloadManager.getInstance(context).also {
            downloadManager = it
            it.addListener(this)
        }

        renderLoading()
        lifecycleScope.launch {
            val results = OnlineModelSearchRepository.searchAll(requireContext(), query)
            if (!isAdded) return@launch
            renderResults(results)
        }
        return dialog
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::downloadManager.isInitialized) {
            runCatching { downloadManager.removeListener(this) }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun renderLoading() {
        container.removeAllViews()
        container.addView(TextView(requireContext()).apply {
            text = getString(R.string.online_search_loading)
        })
    }

    private fun renderResults(results: List<OnlineModelSearchRepository.Result>) {
        val context = requireContext()
        container.removeAllViews()
        rowButtons.clear()
        rowStatus.clear()

        if (results.isEmpty()) {
            container.addView(TextView(context).apply {
                text = getString(R.string.online_search_empty)
            })
            return
        }

        container.addView(TextView(context).apply {
            text = getString(R.string.online_search_hint)
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        })

        results.forEach { result ->
            container.addView(buildRow(result))
        }
    }

    private fun buildRow(result: OnlineModelSearchRepository.Result): View {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(context).apply {
            text = "${result.source} · ${result.modelName}"
            textSize = 14f
        }
        val status = TextView(context).apply {
            val size = if (result.sizeGb > 0) String.format("%.1f GB", result.sizeGb) else ""
            text = if (size.isNotEmpty()) "${result.repoId} · $size" else result.repoId
            textSize = 11f
        }
        texts.addView(title)
        texts.addView(status)

        val button = Button(context).apply {
            text = getString(R.string.online_search_download)
            setOnClickListener {
                startDownload(result)
            }
        }

        row.addView(texts)
        row.addView(button)
        rowButtons[result.downloadId] = button
        rowStatus[result.downloadId] = status

        // Tap the text area to open the model page in a browser.
        texts.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.url)))
            }
        }
        return row
    }

    private fun startDownload(result: OnlineModelSearchRepository.Result) {
        val context = context ?: return
        rowButtons[result.downloadId]?.isEnabled = false
        rowButtons[result.downloadId]?.text = getString(R.string.online_search_download_started)
        runCatching {
            downloadManager.startDownload(result.downloadId)
        }.onFailure {
            rowButtons[result.downloadId]?.isEnabled = true
            rowButtons[result.downloadId]?.text = getString(R.string.online_search_download)
            Toast.makeText(context, getString(R.string.online_search_download_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // ========================
    // DownloadListener
    // ========================

    override fun onDownloadStart(modelId: String) {
        updateRow(modelId) { button, status ->
            button.isEnabled = false
            button.text = getString(R.string.online_search_download_started)
            status.text = getString(R.string.online_search_downloading, 0)
        }
    }

    override fun onDownloadProgress(modelId: String, downloadInfo: DownloadInfo) {
        val percent = (downloadInfo.progress * 100).toInt().coerceIn(0, 100)
        updateRow(modelId) { button, status ->
            button.isEnabled = false
            button.text = "$percent%"
            val size = if (downloadInfo.totalSize > 0) {
                " · ${formatSize(downloadInfo.savedSize)} / ${formatSize(downloadInfo.totalSize)}"
            } else ""
            status.text = getString(R.string.online_search_downloading, percent) + size
        }
    }

    override fun onDownloadFinished(modelId: String, path: String) {
        updateRow(modelId) { button, status ->
            button.isEnabled = false
            button.text = getString(R.string.online_search_downloaded)
            status.text = getString(R.string.online_search_download_done)
        }
    }

    override fun onDownloadFailed(modelId: String, e: Exception) {
        updateRow(modelId) { button, status ->
            button.isEnabled = true
            button.text = getString(R.string.online_search_download)
            status.text = getString(R.string.online_search_download_failed) + ": " + (e.message ?: "")
        }
    }

    private fun updateRow(modelId: String, block: (Button, TextView) -> Unit) {
        val button = rowButtons[modelId] ?: return
        val status = rowStatus[modelId] ?: return
        if (!isAdded) return
        activity?.runOnUiThread {
            if (isAdded) block(button, status)
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
    }
}

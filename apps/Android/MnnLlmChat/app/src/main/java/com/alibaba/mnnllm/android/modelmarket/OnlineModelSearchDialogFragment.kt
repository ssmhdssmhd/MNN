package com.alibaba.mnnllm.android.modelmarket

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.alibaba.mnnllm.android.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Shows aggregated search results from HuggingFace / ModelScope / Modelers.
 * Tapping a row opens the corresponding model page in a browser, since the raw
 * weights are not MNN format and cannot run on device without conversion.
 */
class OnlineModelSearchDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_QUERY = "query"

        fun newInstance(query: String): OnlineModelSearchDialogFragment {
            return OnlineModelSearchDialogFragment().apply {
                arguments = Bundle().apply { putString(ARG_QUERY, query) }
            }
        }
    }

    private lateinit var container: LinearLayout

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val query = arguments?.getString(ARG_QUERY).orEmpty()
        val context = requireContext()

        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(context).apply { addView(container) }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.online_search_title, query))
            .setView(scroll)
            .setNegativeButton(R.string.close, null)
            .create()

        renderLoading()
        lifecycleScope.launch {
            val results = OnlineModelSearchRepository.searchAll(query)
            if (!isAdded) return@launch
            renderResults(results)
        }
        return dialog
    }

    private fun renderLoading() {
        container.removeAllViews()
        container.addView(TextView(requireContext()).apply {
            text = getString(R.string.online_search_loading)
        })
    }

    private fun renderResults(results: List<OnlineModelSearchRepository.Result>) {
        val context = requireContext()
        container.removeAllViews()

        if (results.isEmpty()) {
            container.addView(TextView(context).apply {
                text = getString(R.string.online_search_empty)
            })
            return
        }

        container.addView(TextView(context).apply {
            text = getString(R.string.online_search_hint)
            textSize = 12f
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, pad)
        })

        val rowHeight = (56 * resources.displayMetrics.density).toInt()
        results.forEach { result ->
            container.addView(TextView(context).apply {
                text = "${result.source} · ${result.modelId}"
                textSize = 14f
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowHeight
                )
                setOnClickListener {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.url)))
                    }
                }
            })
        }
    }
}

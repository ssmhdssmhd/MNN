// Copyright (c) 2026 Alibaba Group Holding Limited All rights reserved.

package com.alibaba.mnnllm.android.mainsettings

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.databinding.ActivityStorageManagementBinding
import com.alibaba.mnnllm.android.modelist.ModelDeletionHelper
import com.alibaba.mnnllm.android.modelsettings.ModelConfig
import com.alibaba.mnnllm.android.utils.MmapUtils
import com.alibaba.mls.api.ModelStorageConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Displays storage usage grouped by model. Tap a group to expand and show indented child rows
 * (model dir, config dir, mmap dir); each child has a delete button. Bottom button cleans all cleanable cache with confirmation.
 */
class StorageManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageManagementBinding
    private lateinit var adapter: StorageListAdapter

    /** Which entry.modelId are expanded; value is cached detail when loaded. */
    private val expandedDetails = mutableMapOf<String, ModelDeletionHelper.ModelStorageDetail?>()
    private val expandedSet = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStorageManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val toolbar: Toolbar = binding.toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.storage_management)

        adapter = StorageListAdapter(
            formatSize = { formatSize(it) },
            getStatusCleanable = { getString(R.string.storage_status_orphan) },
            getStatusTracked = { getString(R.string.storage_status_tracked) },
            onGroupClick = { group -> onGroupClick(group) },
            onChildDelete = { child -> onChildDelete(child) }
        )
        binding.storageRecycler.layoutManager = LinearLayoutManager(this)
        binding.storageRecycler.adapter = adapter

        binding.btnCleanCleanableCache.setOnClickListener { showCleanCleanableConfirm() }

        setupModelStoragePath()

        loadSummary()
        refreshList()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun loadSummary() {
        val analysis = ModelDeletionHelper.getStorageAnalysis(applicationContext)
        val free = analysis.internalStorageTotal - analysis.internalStorageUsed

        binding.tvInternalTotal.text = formatSize(analysis.internalStorageTotal)
        binding.tvInternalUsed.text = formatSize(analysis.internalStorageUsed)
        binding.tvInternalFree.text = formatSize(free.coerceAtLeast(0))

        binding.tvModelFiles.text = formatSize(analysis.modelStorageSize)
        binding.tvMmapCache.text = formatSize(analysis.totalMmapCacheSize)
        binding.tvOrphanCache.text = formatSize(analysis.totalOrphanSize)
    }

    private fun buildFlatList(): List<StorageListItem> {
        val analysis = ModelDeletionHelper.getStorageAnalysis(applicationContext)
        val entries = analysis.mmapCacheEntries.sortedByDescending { it.sizeBytes }
        val list = mutableListOf<StorageListItem>()

        for (entry in entries) {
            val expanded = expandedSet.contains(entry.modelId)
            var detail = expandedDetails[entry.modelId]
            if (expanded && detail == null) {
                detail = ModelDeletionHelper.getStorageDetailForEntry(this, entry)
                expandedDetails[entry.modelId] = detail
            }
            list.add(StorageListItem.Group(entry = entry, expanded = expanded, detail = detail))

            if (expanded && detail != null) {
                // Child: Model directory (only if resolved)
                if (detail.resolvedModelId != null && detail.modelDirPath != null) {
                    list.add(
                        StorageListItem.Child(
                            type = StorageListItem.ChildType.MODEL_DIR,
                            label = getString(R.string.storage_detail_model_dir) + ": " + detail.modelDirPath,
                            path = detail.modelDirPath,
                            sizeBytes = detail.modelDirSize,
                            resolvedModelId = detail.resolvedModelId,
                            entry = entry
                        )
                    )
                }
                // Child: Config directory
                if (detail.resolvedModelId != null && detail.configDirPath != null) {
                    val configSize = detail.configFiles.sumOf { it.second }
                    list.add(
                        StorageListItem.Child(
                            type = StorageListItem.ChildType.CONFIG_DIR,
                            label = getString(R.string.storage_detail_config_dir) + ": " + detail.configDirPath,
                            path = detail.configDirPath,
                            sizeBytes = configSize,
                            resolvedModelId = detail.resolvedModelId,
                            entry = entry
                        )
                    )
                }
                // Child: Mmap directory (always present)
                list.add(
                    StorageListItem.Child(
                        type = StorageListItem.ChildType.MMAP_DIR,
                        label = getString(R.string.storage_detail_mmap_dir) + ": " + detail.mmapPath,
                        path = detail.mmapPath,
                        sizeBytes = detail.mmapSize,
                        resolvedModelId = detail.resolvedModelId,
                        entry = entry
                    )
                )
            }
        }
        return list
    }

    private fun refreshList() {
        adapter.submitList(buildFlatList())
    }

    private fun onGroupClick(group: StorageListItem.Group) {
        if (group.expanded) {
            expandedSet.remove(group.entry.modelId)
        } else {
            expandedSet.add(group.entry.modelId)
        }
        refreshList()
    }

    private fun onChildDelete(child: StorageListItem.Child) {
        when (child.type) {
            StorageListItem.ChildType.MODEL_DIR -> {
                val modelId = child.resolvedModelId
                if (modelId != null) {
                    val result = ModelDeletionHelper.deleteModelWithCleanup(this, modelId)
                    if (!result.modelDeleted && result.errors.isNotEmpty()) {
                        Toast.makeText(this, result.errors.joinToString(), Toast.LENGTH_LONG).show()
                    }
                    expandedSet.remove(child.entry.modelId)
                    expandedDetails.remove(child.entry.modelId)
                }
            }
            StorageListItem.ChildType.CONFIG_DIR -> {
                val modelId = child.resolvedModelId
                if (modelId != null) {
                    val dir = File(ModelConfig.getModelConfigDir(modelId))
                    if (dir.exists()) dir.deleteRecursively()
                    expandedDetails[child.entry.modelId] = null
                    refreshList()
                }
            }
            StorageListItem.ChildType.MMAP_DIR -> {
                if (child.entry.isOrphan) {
                    File(child.entry.path).deleteRecursively()
                    expandedSet.remove(child.entry.modelId)
                    expandedDetails.remove(child.entry.modelId)
                } else {
                    child.resolvedModelId?.let { modelId ->
                        MmapUtils.clearMmapCache(modelId)
                        expandedDetails[child.entry.modelId] = null
                        refreshList()
                    }
                }
            }
        }
        loadSummary()
        refreshList()
    }

    private fun showCleanCleanableConfirm() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.storage_clean_cleanable_confirm_title)
            .setMessage(R.string.storage_clean_cleanable_confirm_message)
            .setPositiveButton(R.string.storage_clean_cleanable_cache) { _, _ ->
                val result = ModelDeletionHelper.cleanOrphanMmapCaches(applicationContext)
                Toast.makeText(
                    this,
                    getString(R.string.storage_clean_cleanable_cache) + ": " + formatSize(result.bytesFreed),
                    Toast.LENGTH_SHORT
                ).show()
                loadSummary()
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- Model storage path ----------

    private fun setupModelStoragePath() {
        refreshModelStoragePath()

        binding.btnPathDefault.setOnClickListener {
            applyModelStoragePath(ModelStorageConfig.getDefaultModelRootPath(this))
        }

        binding.btnPathExternal.setOnClickListener {
            val externalRoot = getExternalFilesDir(null)
            if (externalRoot == null) {
                Toast.makeText(this, getString(R.string.storage_model_path_invalid), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val path = File(externalRoot, ".mnnmodels").absolutePath
            applyModelStoragePath(path)
        }

        binding.btnPathApply.setOnClickListener {
            val path = binding.editCustomPath.text?.toString()?.trim().orEmpty()
            if (path.isEmpty()) {
                Toast.makeText(this, getString(R.string.storage_model_path_invalid), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            applyModelStoragePath(path)
        }

        binding.btnPathReset.setOnClickListener {
            if (ModelStorageConfig.isDefaultPath(this)) {
                Toast.makeText(this, getString(R.string.storage_model_path_already_default), Toast.LENGTH_SHORT).show()
            } else {
                ModelStorageConfig.resetToDefault(this)
                onModelStoragePathChanged()
            }
        }
    }

    private fun refreshModelStoragePath() {
        binding.tvModelStoragePath.text = ModelStorageConfig.getModelRootPath(this)
    }

    /** Validate and persist a model storage path, migrating existing models to the new location. */
    private fun applyModelStoragePath(path: String): Boolean {
        val dir = File(path)
        val valid = try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            dir.exists() && dir.isDirectory && dir.canWrite()
        } catch (e: Exception) {
            false
        }
        if (!valid) {
            Toast.makeText(this, getString(R.string.storage_model_path_invalid), Toast.LENGTH_SHORT).show()
            return false
        }

        val newRoot = dir.absoluteFile
        val oldRoot = ModelStorageConfig.getModelRootDir(this)
        if (oldRoot.absolutePath == newRoot.absolutePath) {
            onModelStoragePathChanged()
            return true
        }

        Toast.makeText(this, R.string.storage_model_path_migrating, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                migrateModelRoot(oldRoot, newRoot)
            }
            ModelStorageConfig.setModelRootPath(this@StorageManagementActivity, newRoot.absolutePath)
            binding.editCustomPath.text?.clear()
            Toast.makeText(
                this@StorageManagementActivity,
                getString(R.string.storage_model_path_migrated, result.movedFiles, formatSize(result.movedBytes)),
                Toast.LENGTH_LONG
            ).show()
            refreshModelStoragePath()
            loadSummary()
            refreshList()
        }
        return true
    }

    private data class MigrationResult(val movedFiles: Int, val movedBytes: Long, val failed: Int)

    /**
     * Move every model directory from the old root into the new root so that already-downloaded
     * models stay discoverable after the storage path changes. Falls back to copy+delete when
     * a direct rename is not possible (e.g. crossing storage volumes).
     */
    private fun migrateModelRoot(oldRoot: File, newRoot: File): MigrationResult {
        var movedFiles = 0
        var movedBytes = 0L
        var failed = 0

        val children = oldRoot.listFiles() ?: return MigrationResult(0, 0, 0)
        for (child in children) {
            try {
                val target = File(newRoot, child.name)
                if (!target.exists()) {
                    if (child.renameTo(target)) {
                        movedFiles += countFiles(child)
                        movedBytes += sizeOf(child)
                        continue
                    }
                }
                // Target exists or rename failed: merge contents then remove the source
                val stats = mergeRecursively(child, target)
                movedFiles += stats.first
                movedBytes += stats.second
                child.deleteRecursively()
            } catch (e: Exception) {
                failed += 1
            }
        }
        return MigrationResult(movedFiles, movedBytes, failed)
    }

    private fun mergeRecursively(src: File, dst: File): Pair<Int, Long> {
        if (src.isDirectory) {
            var files = 0
            var bytes = 0L
            dst.mkdirs()
            src.listFiles()?.forEach { entry ->
                val stats = mergeRecursively(entry, File(dst, entry.name))
                files += stats.first
                bytes += stats.second
            }
            return files to bytes
        }
        dst.parentFile?.mkdirs()
        if (src.copyTo(dst, overwrite = true).exists()) {
            return 1 to src.length()
        }
        return 0 to 0L
    }

    private fun countFiles(file: File): Int {
        if (!file.isDirectory) return 1
        return file.listFiles()?.sumOf { countFiles(it) } ?: 0
    }

    private fun sizeOf(file: File): Long {
        if (!file.isDirectory) return file.length()
        return file.listFiles()?.sumOf { sizeOf(it) } ?: 0L
    }

    private fun onModelStoragePathChanged() {
        refreshModelStoragePath()
        binding.editCustomPath.text?.clear()
        Toast.makeText(this, getString(R.string.storage_model_path_applied), Toast.LENGTH_SHORT).show()
        loadSummary()
        refreshList()
    }
}

// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.

package com.alibaba.mnnllm.android.mainsettings

/**
 * Builds the in-app announcement from CHANGELOG.md so it always reflects the
 * update notes of the running version.
 */
object ChangelogAnnouncement {

    private val headingRegex = Regex("""^##\s+v?(\d+\.\d+\.\d+)[\s　]*(?:\(([^)]*)\))?""")

    private data class Section(
        val version: String,
        val date: String,
        val items: List<String>
    )

    /**
     * Returns the announcement text for [version], falling back to the newest
     * section when the running version has no entry yet. Returns null when the
     * changelog contains no release section.
     */
    fun format(changelog: String, version: String): String? {
        val sections = parseSections(changelog)
        if (sections.isEmpty()) return null

        val normalizedVersion = version.removePrefix("v").trim()
        val section = sections.firstOrNull { it.version == normalizedVersion } ?: sections.first()

        val builder = StringBuilder("v").append(section.version)
        if (section.date.isNotBlank()) {
            builder.append("  ·  ").append(section.date)
        }
        section.items.forEachIndexed { index, item ->
            builder.append("\n\n").append(index + 1).append(". ").append(item)
        }
        return builder.toString()
    }

    private fun parseSections(changelog: String): List<Section> {
        val sections = mutableListOf<Section>()
        var currentVersion: String? = null
        var currentDate = ""
        val currentItems = mutableListOf<String>()

        fun flush() {
            val version = currentVersion ?: return
            sections.add(Section(version, currentDate, currentItems.toList()))
            currentItems.clear()
        }

        for (rawLine in changelog.lineSequence()) {
            val line = rawLine.trim()
            val match = headingRegex.find(line)
            if (match != null) {
                flush()
                currentVersion = match.groupValues[1]
                currentDate = match.groupValues[2]
            } else if (currentVersion != null && line.startsWith("- ")) {
                currentItems.add(line.removePrefix("- ").trim())
            }
        }
        flush()
        return sections
    }
}

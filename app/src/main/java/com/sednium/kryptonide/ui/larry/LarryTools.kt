package com.sednium.kryptonide.ui.larry

val LARRY_TOOL_DEFINITIONS = listOf(
    mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "read_file",
            "description" to "Read file contents with line numbers prepended. Use line numbers for edit_lines.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "Relative file path"),
                    "start_line" to mapOf("type" to "integer", "description" to "Optional start line (1-indexed)"),
                    "end_line" to mapOf("type" to "integer", "description" to "Optional end line (1-indexed, inclusive)")
                ),
                "required" to listOf("path")
            )
        )
    ),
    mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "edit_lines",
            "description" to "Replace a range of lines in existing file. Most token-efficient edit method. Always read_file first.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string"),
                    "start_line" to mapOf("type" to "integer", "description" to "First line to replace (1-indexed, inclusive)"),
                    "end_line" to mapOf("type" to "integer", "description" to "Last line to replace (1-indexed, inclusive)"),
                    "new_content" to mapOf("type" to "string", "description" to "Replacement content. Empty string to delete lines.")
                ),
                "required" to listOf("path", "start_line", "end_line", "new_content")
            )
        )
    ),
    mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "insert_lines",
            "description" to "Insert new lines BEFORE specified line without removing anything.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string"),
                    "at_line" to mapOf("type" to "integer", "description" to "Insert before this line (1-indexed). Past end = append."),
                    "content" to mapOf("type" to "string")
                ),
                "required" to listOf("path", "at_line", "content")
            )
        )
    ),
    mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "patch_file",
            "description" to "Fallback: search-replace in existing file. Prefer edit_lines when you have line numbers.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string"),
                    "search_string" to mapOf("type" to "string", "description" to "Exact case-sensitive string to find"),
                    "replace_string" to mapOf("type" to "string")
                ),
                "required" to listOf("path", "search_string", "replace_string")
            )
        )
    ),
    mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "create_directory",
            "description" to "Create a directory recursively before writing files into it.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string", "description" to "Directory path e.g. src/ui/larry")
                ),
                "required" to listOf("path")
            )
        )
    ),
    mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "write_new_file",
            "description" to "Create a NEW file. NEVER use for existing files — use edit_lines instead.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string"),
                    "content" to mapOf("type" to "string", "description" to "Full initial content of the file")
                ),
                "required" to listOf("path", "content")
            )
        )
    )
)

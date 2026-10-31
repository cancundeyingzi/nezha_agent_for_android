package com.nezhahq.agent.core.mcp

/** Linux filepath.Clean semantics, without touching the filesystem or following a symlink. */
object McpFsPath {
    fun cleanAbsolute(path: String): String {
        require(path.startsWith('/') && '\u0000' !in path)
        val segments = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        return if (segments.isEmpty()) "/" else segments.joinToString("/", prefix = "/")
    }
}

package com.nezhahq.agent.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

class McpFsPathTest {
    @Test fun cleanMatchesLinuxLexicalRulesAndRemovesFinalSlashBeforeNoFollowOpen() {
        mapOf(
            "/" to "/", "////" to "/", "/.." to "/", "/../../a" to "/a",
            "/a//b///" to "/a/b", "/a/./b/." to "/a/b", "/a/b/../c" to "/a/c",
            "/linkdir/" to "/linkdir", "/linkdir/." to "/linkdir", "/linkdir/../file" to "/file",
            "/ 空格 /换\n行/中文/" to "/ 空格 /换\n行/中文", "/a\\b/.. /..." to "/a\\b/.. /..."
        ).forEach { (raw, clean) ->
            assertEquals(clean, McpFsPath.cleanAbsolute(raw))
            val list = (McpRequestRules.prepare(FsListRequest(raw)) as McpValidation.Valid).request as FsListRequest
            val read = (McpRequestRules.prepare(FsReadRequest(raw)) as McpValidation.Valid).request as FsReadRequest
            assertEquals(clean, list.path)
            assertEquals(clean, read.path)
        }
    }
}

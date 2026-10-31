package com.nezhahq.agent.mcp.fs

/** Close the original fd before handing out its independently owned directory anchor. */
internal inline fun <Descriptor, Anchor : AutoCloseable> pinDirectoryDescriptor(
    descriptor: Descriptor,
    pin: (Descriptor) -> Anchor,
    close: (Descriptor) -> Unit
): Anchor {
    var anchor: Anchor? = null
    return withFsCleanup(block = {
        pin(descriptor).also { anchor = it }
    }, cleanup = {
        try { close(descriptor) } catch (failure: Throwable) {
            // The caller cannot receive the anchor if closing the original fd fails.
            withFsCleanup(block = { throw failure }, cleanup = { anchor?.close() })
        }
    })
}

/** Always attempt cleanup, retaining an earlier operation/close failure as the primary error. */
internal inline fun <T> withFsCleanup(block: () -> T, cleanup: () -> Unit): T {
    var failure: Throwable? = null
    try {
        return block()
    } catch (primary: Throwable) {
        failure = primary
        throw primary
    } finally {
        try { cleanup() } catch (secondary: Throwable) {
            val primary = failure
            if (primary == null) throw secondary
            if (primary !== secondary) primary.addSuppressed(secondary)
        }
    }
}

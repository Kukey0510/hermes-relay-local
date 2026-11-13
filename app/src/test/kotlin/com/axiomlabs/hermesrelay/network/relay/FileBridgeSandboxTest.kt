package com.axiomlabs.hermesrelay.network.relay

import com.hermesandroid.relay.network.relay.FileBridgeSandbox
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FileBridgeSandboxTest {
    @Test
    fun rejectsParentTraversal() {
        val root = createTempDirectory().toFile()
        assertNull(FileBridgeSandbox.resolve(listOf(root), "../../etc/passwd"))
    }

    @Test
    fun rejectsAbsolutePath() {
        val root = createTempDirectory().toFile()
        assertNull(FileBridgeSandbox.resolve(listOf(root), "/sdcard/Hermes/x.txt"))
    }

    @Test
    fun rejectsReadOverOneMegabyte() {
        val root = createTempDirectory().toFile()
        val file = root.resolve("large.bin").apply { writeBytes(ByteArray(1_048_577)) }
        val resolved = FileBridgeSandbox.resolve(listOf(root), "large.bin")
        assertFalse(FileBridgeSandbox.readableWithinLimit(requireNotNull(resolved)))
    }

    @Test
    fun rejectsSymlinkPointingOutsideRoot() {
        val root = createTempDirectory().toFile()
        val outside = Files.createTempFile("file-bridge-outside", ".txt")
        Files.createSymbolicLink(root.toPath().resolve("escape"), outside)
        assertNull(FileBridgeSandbox.resolve(listOf(root), "escape"))
    }
}

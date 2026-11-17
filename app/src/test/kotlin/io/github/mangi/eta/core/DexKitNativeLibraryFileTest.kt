package io.github.mangi.eta.core

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DexKitNativeLibraryFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private companion object {
        val WRITE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.OTHERS_WRITE,
        )
    }

    @Test
    fun compressedLibraryUsesOnlyTheSupportedProcessAbi() {
        val apk = apk("arm64-v8a" to "library64", "armeabi-v7a" to "library32")
        val cache = temporaryFolder.newFolder()

        val library = DexKitNativeLibraryFile.extract(apk.path, cache, listOf("x86_64", "arm64-v8a"))

        assertEquals("library64", library.readText())
        assertReadOnly(library)
        assertEquals(library, DexKitNativeLibraryFile.extract(apk.path, cache, listOf("arm64-v8a")))
    }

    @Test
    fun missingCompatibleAbiDoesNotLoadTheOtherBitness() {
        val apk = apk("armeabi-v7a" to "library32")
        val cache = temporaryFolder.newFolder()

        assertThrows(IllegalStateException::class.java) {
            DexKitNativeLibraryFile.extract(apk.path, cache, listOf("arm64-v8a"))
        }
        assertEquals(0, cache.listFiles()!!.size)
    }

    @Test
    fun changedModuleLibraryUsesADifferentCacheFile() {
        val apk = apk("arm64-v8a" to "library-one")
        val cache = temporaryFolder.newFolder()
        val old = DexKitNativeLibraryFile.extract(apk.path, cache, listOf("arm64-v8a"))
        writeApk(apk, arrayOf("arm64-v8a" to "library-two"))

        val current = DexKitNativeLibraryFile.extract(apk.path, cache, listOf("arm64-v8a"))

        assertNotEquals(old, current)
        assertEquals("library-one", old.readText())
        assertEquals("library-two", current.readText())
    }

    @Test
    fun corruptedSameLengthCacheIsReplacedWithApkBytes() {
        val apk = apk("arm64-v8a" to "library-one")
        val cache = temporaryFolder.newFolder()
        val library = DexKitNativeLibraryFile.extract(apk.path, cache, listOf("arm64-v8a"))
        library.setWritable(true)
        library.writeText("library-bad")

        val repaired = DexKitNativeLibraryFile.extract(apk.path, cache, listOf("arm64-v8a"))

        assertArrayEquals("library-one".toByteArray(), repaired.readBytes())
        assertReadOnly(repaired)
        assertEquals(listOf("libdexkit.so"), repaired.parentFile!!.list()!!.toList())
    }

    @Test
    fun unavailableCacheAndOversizedEntryFailBeforeLoading() {
        val apk = apk("arm64-v8a" to "library")
        val unavailable = temporaryFolder.newFile()
        assertThrows(IllegalStateException::class.java) {
            DexKitNativeLibraryFile.extract(apk.path, unavailable, listOf("arm64-v8a"))
        }
        writeApk(apk, arrayOf("arm64-v8a" to "A".repeat(8 * 1024 * 1024 + 1)))
        assertThrows(IllegalStateException::class.java) {
            DexKitNativeLibraryFile.extract(apk.path, temporaryFolder.newFolder(), listOf("arm64-v8a"))
        }
    }

    @Test
    fun nativeFailureDiagnosticsNeverReturnPaths() {
        assertEquals("namespace", nativeLoadFailureReason(UnsatisfiedLinkError("/private/file not accessible for namespace")))
        assertEquals("permission_denied", nativeLoadFailureReason(UnsatisfiedLinkError("/private/file: Permission denied")))
        assertEquals("not_found", nativeLoadFailureReason(UnsatisfiedLinkError("couldn't find /private/file")))
        assertEquals("jni_binding", nativeLoadFailureReason(UnsatisfiedLinkError("No implementation found for method")))
        assertEquals("linker_error", nativeLoadFailureReason(UnsatisfiedLinkError("/private/file unknown failure")))
    }

    private fun apk(vararg libraries: Pair<String, String>): File =
        temporaryFolder.newFile("module.apk").also { writeApk(it, libraries) }

    /**
     * 断言缓存文件已被加固为只读。用 POSIX 权限位而不是 canWrite()：构建容器以 root 运行，
     * root 的 CAP_DAC_OVERRIDE 会让 canWrite() 对 0444 文件仍返回 true，掩盖掉真实权限。
     */
    private fun assertReadOnly(file: File) {
        val permissions = Files.getPosixFilePermissions(file.toPath())
        assertFalse(
            "缓存文件不应保留任何写权限位: $permissions",
            permissions.any { it in WRITE_PERMISSIONS },
        )
    }

    private fun writeApk(file: File, libraries: Array<out Pair<String, String>>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            libraries.forEach { (abi, bytes) ->
                zip.putNextEntry(ZipEntry("lib/$abi/libdexkit.so"))
                zip.write(bytes.toByteArray())
                zip.closeEntry()
            }
        }
    }
}

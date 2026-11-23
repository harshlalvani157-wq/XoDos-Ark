package app.xodos2.ui.runtime

import android.os.Build
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object ArchiveUtils {
    private const val TAG = "ArchiveUtils"

    /**
     * Safely extracts a .tar.xz input stream to [destDir] in pure JVM / Android Bionic.
     * Preserves directories, regular files, symlinks, and executable permissions.
     * Does NOT depend on external shell binaries (xz, pv, tar) or dynamic linkers.
     *
     * @param inputStream The raw stream containing .tar.xz data.
     * @param destDir The base directory where files should be extracted (e.g. context.filesDir).
     * @param totalEstimatedEntries Estimated entry count for progress reporting.
     * @param onProgress Callback invoked with (progressPercent, statusMessage).
     */
    suspend fun extractTarXz(
        inputStream: InputStream,
        destDir: File,
        totalEstimatedEntries: Int = 11400,
        onProgress: suspend (Int, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val destCanonical = destDir.canonicalPath
        var entriesCount = 0
        val buffer = ByteArray(64 * 1024)

        try {
            destDir.mkdirs()
            onProgress(0, "Opening archive…")

            BufferedInputStream(inputStream, 64 * 1024).use { bufferedIn ->
                XZInputStream(bufferedIn).use { xzIn ->
                    TarArchiveInputStream(xzIn).use { tarIn ->
                        var entry: TarArchiveEntry? = tarIn.nextTarEntry
                        while (entry != null) {
                            val entryName = entry.name
                            val targetFile = File(destDir, entryName)

                            // Security: guard against directory traversal (Zip Slip / Tar Slip)
                            val targetCanonical = targetFile.canonicalPath
                            if (!targetCanonical.startsWith(destCanonical)) {
                                Log.w(TAG, "Skipping out-of-bounds archive path: $entryName")
                                entry = tarIn.nextTarEntry
                                continue
                            }

                            if (entry.isDirectory) {
                                targetFile.mkdirs()
                            } else if (entry.isSymbolicLink) {
                                targetFile.parentFile?.mkdirs()
                                removeFileOrSymlink(targetFile)
                                try {
                                    Os.symlink(entry.linkName, targetFile.absolutePath)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Symlink failed: $entryName -> ${entry.linkName}: ${e.message}")
                                }
                            } else if (entry.isLink) {
                                // Hard link
                                targetFile.parentFile?.mkdirs()
                                removeFileOrSymlink(targetFile)
                                val linkTarget = File(destDir, entry.linkName)
                                try {
                                    Os.link(linkTarget.absolutePath, targetFile.absolutePath)
                                } catch (_: Exception) {
                                    try {
                                        if (linkTarget.exists()) {
                                            linkTarget.copyTo(targetFile, overwrite = true)
                                        }
                                    } catch (_: Exception) {}
                                }
                            } else {
                                // Regular file
                                targetFile.parentFile?.mkdirs()
                                removeFileOrSymlink(targetFile)
                                FileOutputStream(targetFile).use { out ->
                                    var read: Int
                                    while (tarIn.read(buffer).also { read = it } != -1) {
                                        out.write(buffer, 0, read)
                                    }
                                }

                                val mode = entry.mode
                                if ((mode and 0b001_000_000) != 0 || entryName.startsWith("usr/bin/")) {
                                    targetFile.setExecutable(true, false)
                                }
                                targetFile.setReadable(true, false)
                                try {
                                    Os.chmod(targetFile.absolutePath, mode and 0b111_111_111)
                                } catch (_: Exception) {}
                            }

                            entriesCount++
                            if (entriesCount % 100 == 0) {
                                val pct = if (totalEstimatedEntries > 0) {
                                    ((entriesCount * 100) / totalEstimatedEntries).coerceIn(0, 99)
                                } else 50
                                onProgress(pct, "Installing bootstrap… $pct%")
                            }

                            entry = tarIn.nextTarEntry
                        }
                    }
                }
            }

            // Post-extraction: ensure all binaries in usr/bin have execute bits
            val usrBin = File(destDir, "usr/bin")
            if (usrBin.isDirectory) {
                usrBin.listFiles()?.forEach { it.setExecutable(true, false) }
            }

            onProgress(100, "Bootstrap installed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed at entry #$entriesCount", e)
            onProgress(-1, "Bootstrap failed: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    fun removeFileOrSymlink(file: File) {
        if (file.isDirectory) {
            file.deleteRecursively()
            return
        }
        try {
            Os.remove(file.absolutePath)
        } catch (_: Exception) {
            file.delete()
        }
    }
}

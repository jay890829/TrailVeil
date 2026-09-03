package app.trailveil.map.fog

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

data class FogDiskTileCacheStats(
    val entryCount: Int,
    val byteCount: Long,
)

data class FogDiskMutationResult(
    val removedEntries: Int,
    val complete: Boolean,
)

/**
 * Rebuildable, byte-bounded storage for derived fog masks.
 *
 * The caller must provide a cache-only directory. Files include their complete tile identity and a
 * checksum, so partial, stale, or corrupt entries become misses instead of exposing unknown areas.
 */
class FogDiskTileCache(
    rootDirectory: File,
    private val maxBytes: Long,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val deleteFile: (File) -> Boolean = File::delete,
) {
    private val rootDirectory = rootDirectory.absoluteFile

    init {
        require(maxBytes > HEADER_BYTES) { "maxBytes must fit a cache header and payload" }
        check(trimToSize()) { "Unable to enforce fog disk-cache byte bound" }
    }

    @Synchronized
    fun get(key: FogTileKey): FogPixelMask? {
        val file = tileFile(key)
        if (!file.isFile) return null

        val mask = runCatching { read(file, key) }.getOrNull()
        if (mask == null) {
            deleteEntry(file)
            return null
        }

        file.setLastModified(nowMillis())
        return mask
    }

    /** Returns false when one complete entry is larger than the configured cache. */
    @Synchronized
    fun put(key: FogTileKey, mask: FogPixelMask): Boolean {
        val alpha = mask.copyAlpha()
        val payloadBytes = validatedPayloadBytes(mask, alpha)
        val entryBytes = HEADER_BYTES + payloadBytes.toLong()
        val destination = tileFile(key)
        if (entryBytes > maxBytes) {
            check(!destination.exists() || deleteEntry(destination) && !destination.exists()) {
                "Unable to remove oversized stale fog cache entry"
            }
            return false
        }

        val destinationDirectory = requireNotNull(destination.parentFile)
        ensureDirectory(destinationDirectory)
        val temporary = Files.createTempFile(
            destinationDirectory.toPath(),
            ".${destination.name}.",
            ".tmp",
        ).toFile()
        try {
            write(temporary, key, mask, alpha)
            replaceAtomically(temporary, destination)
            destination.setLastModified(nowMillis())
        } finally {
            temporary.delete()
        }

        check(trimToSize()) { "Unable to enforce fog disk-cache byte bound" }
        return destination.isFile
    }

    @Synchronized
    fun invalidate(keys: Collection<FogTileKey>): FogDiskMutationResult =
        removeEntries(keys.distinct().map(::tileFile))

    /** Drops every entry whose key uses a different renderer/style identity. */
    @Synchronized
    fun retainRenderVersion(renderVersion: Int): FogDiskMutationResult {
        require(renderVersion >= 0) { "renderVersion must be non-negative" }
        val obsolete = maskFiles(rootDirectory).filter { file ->
            entryRenderVersion(file) != renderVersion
        }
        return removeEntries(obsolete)
    }

    @Synchronized
    fun clear(): Boolean {
        val entries = maskFiles(rootDirectory)
        val mutation = removeEntries(entries)
        cleanupTemporaryFiles(rootDirectory)
        return mutation.complete && maskFiles(rootDirectory).isEmpty()
    }

    @Synchronized
    fun stats(): FogDiskTileCacheStats {
        val files = maskFiles(rootDirectory)
        return FogDiskTileCacheStats(
            entryCount = files.size,
            byteCount = files.sumOf(File::length),
        )
    }

    /** Enumerates bounded derived entries by path only; payload validation remains lazy in [get]. */
    @Synchronized
    fun keys(): Set<FogTileKey> = keyFiles(rootDirectory).mapNotNull(::entryKey).toSet()

    private fun read(file: File, expectedKey: FogTileKey): FogPixelMask {
        require(file.length() in (HEADER_BYTES + 1)..maxBytes) { "invalid entry size" }
        DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            require(input.readInt() == MAGIC) { "invalid cache magic" }
            require(input.readInt() == FORMAT_VERSION) { "unsupported cache format" }
            require(input.readInt() == expectedKey.zoom) { "zoom mismatch" }
            require(input.readInt() == expectedKey.x) { "x mismatch" }
            require(input.readInt() == expectedKey.y) { "y mismatch" }
            require(input.readInt() == expectedKey.renderVersion) { "render version mismatch" }
            val width = input.readInt()
            val height = input.readInt()
            val payloadBytes = input.readInt()
            val checksum = input.readLong()
            require(width > 0 && height > 0) { "invalid mask dimensions" }
            require(Math.multiplyExact(width, height) == payloadBytes) { "invalid payload size" }
            require(HEADER_BYTES + payloadBytes.toLong() == file.length()) {
                "truncated or trailing cache data"
            }
            val alpha = ByteArray(payloadBytes)
            input.readFully(alpha)
            require(checksum(alpha) == checksum) { "cache checksum mismatch" }
            return FogPixelMask(width, height, alpha)
        }
    }

    private fun write(
        file: File,
        key: FogTileKey,
        mask: FogPixelMask,
        alpha: ByteArray,
    ) {
        FileOutputStream(file).use { output ->
            DataOutputStream(BufferedOutputStream(output)).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(FORMAT_VERSION)
                data.writeInt(key.zoom)
                data.writeInt(key.x)
                data.writeInt(key.y)
                data.writeInt(key.renderVersion)
                data.writeInt(mask.width)
                data.writeInt(mask.height)
                data.writeInt(alpha.size)
                data.writeLong(checksum(alpha))
                data.write(alpha)
                data.flush()
                output.fd.sync()
            }
        }
    }

    private fun replaceAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun trimToSize(): Boolean {
        cleanupTemporaryFiles(rootDirectory)
        val files = maskFiles(rootDirectory).sortedWith(
            compareBy<File>(File::lastModified).thenBy(File::getAbsolutePath),
        )
        var byteCount = files.sumOf(File::length)
        files.forEach { file ->
            if (byteCount > maxBytes) {
                val length = file.length()
                if (deleteEntry(file)) byteCount -= length
            }
        }
        return byteCount <= maxBytes
    }

    private fun removeEntries(files: Collection<File>): FogDiskMutationResult {
        var removedEntries = 0
        var complete = true
        files.forEach { file ->
            if (!file.exists()) return@forEach
            if (deleteEntry(file) && !file.exists()) {
                removedEntries += 1
            } else {
                complete = false
            }
        }
        return FogDiskMutationResult(removedEntries, complete)
    }

    private fun validatedPayloadBytes(mask: FogPixelMask, alpha: ByteArray): Int {
        require(mask.width > 0 && mask.height > 0) { "mask dimensions must be positive" }
        val expected = Math.multiplyExact(mask.width, mask.height)
        require(alpha.size == expected) { "mask payload must match its dimensions" }
        return expected
    }

    private fun tileFile(key: FogTileKey): File =
        File(
            rootDirectory,
            "${versionDirectoryName(key.renderVersion)}/z${key.zoom}/x${key.x}/y${key.y}.mask",
        )

    private fun versionDirectoryName(renderVersion: Int) = "v$renderVersion"

    private fun ensureDirectory(directory: File) {
        check((directory.isDirectory || directory.mkdirs()) && directory.isDirectory) {
            "Unable to create fog cache directory: $directory"
        }
    }

    private fun deleteEntry(file: File): Boolean {
        val deleted = file.isFile && deleteFile(file)
        if (deleted) removeEmptyParents(file.parentFile)
        return deleted
    }

    private fun removeEmptyParents(start: File?) {
        var directory = start
        while (directory != null && directory != rootDirectory) {
            val children = directory.listFiles() ?: return
            if (children.isNotEmpty() || !directory.delete()) return
            directory = directory.parentFile
        }
    }

    private fun cleanupTemporaryFiles(file: File) {
        if (!file.exists() || Files.isSymbolicLink(file.toPath())) return
        if (file.isFile) {
            val isOwnedTemporaryFile = file.name.startsWith(".y") &&
                file.name.contains(".mask.") &&
                file.name.endsWith(".tmp")
            if (isOwnedTemporaryFile) file.delete()
            return
        }
        if (file.isDirectory) file.listFiles().orEmpty().forEach(::cleanupTemporaryFiles)
    }

    private fun maskFiles(file: File): List<File> {
        if (!file.exists() || Files.isSymbolicLink(file.toPath())) return emptyList()
        if (file.isFile) return if (entryRenderVersion(file) != null) listOf(file) else emptyList()
        if (!file.isDirectory) return emptyList()
        return file.listFiles().orEmpty().flatMap(::maskFiles)
    }

    private fun entryRenderVersion(file: File): Int? = entryKey(file)?.renderVersion

    private fun entryKey(file: File): FogTileKey? {
        val relative = runCatching {
            rootDirectory.toPath().relativize(file.absoluteFile.toPath())
        }.getOrNull() ?: return null
        if (relative.nameCount != 4) return null
        val versionName = relative.getName(0).toString()
        val zoomName = relative.getName(1).toString()
        val xName = relative.getName(2).toString()
        if (!versionName.startsWith("v") || !zoomName.startsWith("z") || !xName.startsWith("x")) {
            return null
        }
        val renderVersion = versionName.removePrefix("v").toIntOrNull() ?: return null
        val zoom = zoomName.removePrefix("z").toIntOrNull() ?: return null
        val x = xName.removePrefix("x").toIntOrNull() ?: return null
        val yName = relative.getName(3).toString()
        if (!yName.startsWith("y") || !yName.endsWith(".mask")) return null
        val y = yName.removePrefix("y").removeSuffix(".mask").toIntOrNull() ?: return null
        return runCatching { FogTileKey(zoom, x, y, renderVersion) }.getOrNull()
    }

    private fun keyFiles(file: File): List<File> {
        if (!file.exists() || Files.isSymbolicLink(file.toPath())) return emptyList()
        if (file.isFile) return if (entryKey(file) != null) listOf(file) else emptyList()
        require(file.isDirectory) { "Fog cache path is not a directory: $file" }
        val children = checkNotNull(file.listFiles()) { "Unable to enumerate fog disk cache: $file" }
        return children.flatMap(::keyFiles)
    }

    private fun checksum(alpha: ByteArray): Long = CRC32().apply { update(alpha) }.value

    private companion object {
        const val MAGIC = 0x54564647 // TVFG
        const val FORMAT_VERSION = 1
        const val HEADER_BYTES = 44L
    }
}

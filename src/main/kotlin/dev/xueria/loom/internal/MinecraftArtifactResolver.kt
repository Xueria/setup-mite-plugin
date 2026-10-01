package dev.xueria.loom.internal

import dev.xueria.loom.Constants
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves the local cache entry of one minecraft artifact, downloading it only when the copy
 * already present cannot be shown to be valid.
 *
 * The cache layout is `<gradleUserHome>/caches/fml-loom/<version>/`, holding
 * `<version>.jar` next to a locally written `<version>.jar.sha1` sidecar. The sidecar records the
 * checksum the jar was accepted with, which is what makes an offline build able to re-validate a
 * cached jar without contacting the repository.
 */
internal class MinecraftArtifactResolver(
    private val url: String,
    private val gradleUserHome: Path,
    private val version: String,
    private val log: (String) -> Unit = {},
) {

    private val directory: Path = DirectoryLayout.directory(gradleUserHome, version)
    private val fileName: String = DirectoryLayout.fileName(version)

    private val target: Path = directory.resolve(fileName)
    private val targetChecksum: Path = directory.resolve(fileName + Constants.CHECKSUM_FILE_SUFFIX)

    /** URL of the checksum sidecar published next to the artifact by the repository. */
    private val remoteChecksumUrl: String = "$url${Constants.CHECKSUM_FILE_SUFFIX}"

    /**
     * Resolves the artifact, reporting every failure as an [ArtifactResolveException] so callers
     * only ever have to handle one exception type.
     */
    fun resolve(): ResolvedArtifact =
        try {
            resolveUnchecked()
        } catch (exception: ArtifactResolveException) {
            throw exception
        } catch (exception: InterruptedException) {
            // Restore the flag: swallowing an interrupt would hide a cancelled build.
            Thread.currentThread().interrupt()

            throw ArtifactResolveException("interrupted while resolving $fileName", cause = exception)
        } catch (exception: Exception) {
            // The downloader reports intentional failures itself; what is left here is a transport
            // or file system problem, which is worth retrying once the environment recovers.
            throw ArtifactResolveException(
                "cannot resolve $url: ${exception.message ?: exception::class.simpleName}",
                recoverable = true,
                cause = exception
            )
        }

    private fun resolveUnchecked(): ResolvedArtifact {
        val expected = downloadExpectedChecksum()

        val cached = validateCache(expected)
        if (cached != null) {
            return ResolvedArtifact(cached, "cached ${target.fileName}")
        }

        // Reaching the repository to fetch the jar implies the checksum must be reachable too, so
        // an earlier transport failure is retried here and reported as an artifact failure.
        val checksum = when (expected) {
            is RemoteChecksumResult.Found -> expected.checksum
            else -> downloadExpectedChecksum(tolerateFailure = false).checksumOrNull()
        }

        if (checksum == null) {
            throw ArtifactResolveException(
                "the repository publishes no usable ${Constants.CHECKSUM_FILE_SUFFIX} for $fileName"
            )
        }

        return downloadArtifact(checksum)
    }

    /**
     * Verifies the cached jar against the expected checksum.
     *
     * Returns `null` when the artifact has to be (re)downloaded, which covers a missing jar, an
     * unusable jar and a checksum mismatch. When the repository cannot be reached the locally
     * recorded checksum takes over, so an offline build keeps working.
     */
    private fun validateCache(expected: RemoteChecksumResult): Path? {
        if (!isUsableJar(target)) return null

        when (expected) {
            is RemoteChecksumResult.Found -> {
                val stored = readStoredChecksum()

                // The sidecar is written right after a jar is accepted, so a sidecar that is still
                // newer than the jar describes it and the archive does not have to be hashed again.
                // A jar replaced from elsewhere keeps an older timestamp and is re-hashed.
                if (stored == expected.checksum && isFreshSidecar()) return target

                val computed = hashOrNull(target)

                // Adopt the published checksum so the next build can take the shortcut above.
                if (computed != null && computed == expected.checksum) {
                    writeStoredChecksum(expected.checksum)

                    return target
                }

                log("checksum mismatch for the cached ${target.fileName}, downloading again")
            }

            RemoteChecksumResult.Missing -> {
                log("no checksum published for ${target.fileName}, using the cached copy")

                return target
            }

            is RemoteChecksumResult.Failure -> {
                val stored = readStoredChecksum()
                val computed = hashOrNull(target)

                if (stored != null && computed != null && stored == computed) {
                    log("cannot reach the repository (${expected.message}), using the cached ${target.fileName}")

                    return target
                }

                // Neither the repository nor a local record can vouch for the jar, so it has to be
                // fetched again; that fails with its own readable message when the host is down.
                log("cannot verify the cached ${target.fileName} (${expected.message}), downloading again")
            }
        }

        return null
    }

    /** Downloads the jar and accepts it only when it matches [checksum]. */
    private fun downloadArtifact(checksum: String): ResolvedArtifact {
        log("downloading $url")

        val result = Downloader.download(url, directory, fileName)
        if (result is DownloadResult.Failure) {
            throw ArtifactResolveException(result.message, recoverable = true)
        }

        val actual = Sha1.hash(target)

        // A jar that does not match the published checksum must not be left behind, otherwise the
        // next build would find and trust it.
        if (actual != checksum) {
            Files.deleteIfExists(target)

            throw ArtifactResolveException(
                "checksum mismatch for $fileName: expected $checksum but got $actual"
            )
        }

        writeStoredChecksum(checksum)

        return ResolvedArtifact(target, "downloaded ${target.fileName}")
    }

    /**
     * Fetches the published `.sha1` sidecar.
     *
     * With [tolerateFailure] a transport problem becomes a [RemoteChecksumResult.Failure] so the
     * caller can still fall back on a cached jar; without it the problem is thrown, because a
     * missing checksum has to be reported as a plain resolution failure instead.
     */
    private fun downloadExpectedChecksum(tolerateFailure: Boolean = true): RemoteChecksumResult {
        log("resolving checksum from $remoteChecksumUrl")

        val content = try {
            Downloader.fetch(remoteChecksumUrl)
        } catch (exception: IOException) {
            if (!tolerateFailure) throw exception

            return RemoteChecksumResult.Failure(exception.message ?: "connection failed")
        }

        if (content == null) return RemoteChecksumResult.Missing

        val checksum = Sha1.parseChecksum(content)
            ?: return RemoteChecksumResult.Failure("the published checksum is not a valid SHA-1 value")

        return RemoteChecksumResult.Found(checksum)
    }

    private fun RemoteChecksumResult.checksumOrNull(): String? =
        (this as? RemoteChecksumResult.Found)?.checksum

    private fun isUsableJar(path: Path): Boolean =
        Files.isRegularFile(path) && Files.size(path) > 0L

    /** Whether the recorded checksum still describes the jar, judged by write order. */
    private fun isFreshSidecar(): Boolean =
        try {
            Files.getLastModifiedTime(targetChecksum) >= Files.getLastModifiedTime(target)
        } catch (exception: IOException) {
            false
        }

    private fun hashOrNull(path: Path): String? =
        try {
            Sha1.hash(path)
        } catch (exception: IOException) {
            log("cannot read the cached ${path.fileName}: ${exception.message}")
            null
        }

    private fun readStoredChecksum(): String? {
        if (!Files.isRegularFile(targetChecksum)) return null

        return try {
            Sha1.parseChecksum(Files.readString(targetChecksum))
        } catch (exception: IOException) {
            // An unreadable sidecar only costs a verification, never a build.
            log("cannot read ${targetChecksum.fileName}: ${exception.message}")
            null
        }
    }

    private fun writeStoredChecksum(checksum: String) {
        try {
            Files.writeString(targetChecksum, checksum + System.lineSeparator())
        } catch (exception: IOException) {
            // The jar itself is fine; a missing sidecar only costs a verification next build.
            log("cannot record ${targetChecksum.fileName}: ${exception.message}")
        }
    }

}

internal data class ResolvedArtifact(val path: Path, val description: String)

/**
 * Signals that the artifact could not be resolved.
 *
 * [recoverable] marks a transport problem, where retrying the build once the repository is
 * reachable again is the answer, as opposed to a checksum that does not match, which means the
 * repository content itself is not trustworthy and retrying will not change anything.
 */
internal class ArtifactResolveException(
    message: String,
    val recoverable: Boolean = false,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    constructor(message: String, cause: Throwable) : this(message, recoverable = true, cause = cause)

}

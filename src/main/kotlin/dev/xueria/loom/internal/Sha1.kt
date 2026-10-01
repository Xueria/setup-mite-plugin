package dev.xueria.loom.internal

import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat

internal object Sha1 {

    private const val ALGORITHM = "SHA-1"

    /**
     * Parses the payload of a maven `.sha1` sidecar file.
     *
     * Such a file may contain the hash alone, the classic `"<hash>  <fileName>"` checksum-tool
     * form, or a short HTML/text error page served with a 200 status code. Anything that is not
     * a 40 characters hex string is reported as invalid instead of being trusted.
     */
    fun parseChecksum(content: String): String? {
        val token = content.trim().split(Regex("\\s+"), limit = 2).firstOrNull()?.trim().orEmpty()

        if (token.length != 40) return null
        if (!token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null

        return token.lowercase()
    }

    /** Streams [file] through a SHA-1 digest, without loading it into memory. */
    fun hash(file: Path): String {
        val digest = MessageDigest.getInstance(ALGORITHM)

        Files.newInputStream(file).use { input ->
            DigestInputStream(input, digest).use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

                while (stream.read(buffer) != -1) {
                    // DigestInputStream updates the digest while reading, nothing else to do.
                }
            }
        }

        return HexFormat.of().formatHex(digest.digest())
    }

}

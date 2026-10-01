package dev.xueria.loom.internal

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration

internal object Downloader {

    private const val TEMP_SUFFIX = ".temp"

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun download(url: String, directory: Path, fileName: String): DownloadResult {
        runCatching {
            Files.createDirectories(directory)
        }.onFailure { throwable ->
            return DownloadResult.Failure("failed create target directory: ${throwable.message}")
        }

        val target = directory.resolve(fileName)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", "Gradle")
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val body = response.body()

        body.use { body ->
            if (response.statusCode() !in 200..299) {
                return DownloadResult.Failure("HTTP StatusCode ${response.statusCode()}")
            }

            val temp = Files.createTempFile(directory, "$fileName.", TEMP_SUFFIX)

            try {
                Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                    .use { output -> body.copyTo(output) }

                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }

                return DownloadResult.Success
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }

    /**
     * Reads a small remote text resource, used for the `.sha1` sidecar files of the repository.
     *
     * A missing resource is reported as `null`, so callers can tell "the repository does not
     * publish a checksum" apart from a real transfer failure.
     */
    fun fetch(url: String, timeout: Duration = Duration.ofSeconds(10)): String? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(timeout)
            .header("User-Agent", "Gradle")
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))

        if (response.statusCode() == 404 || response.statusCode() == 410) return null
        if (response.statusCode() !in 200..299) {
            throw IOException("HTTP StatusCode ${response.statusCode()}")
        }

        return response.body()
    }

}
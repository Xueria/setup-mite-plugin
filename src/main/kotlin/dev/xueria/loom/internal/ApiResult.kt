package dev.xueria.loom.internal

/**
 * Outcome of querying a remote checksum.
 *
 * [Missing] is deliberately separated from [Failure]: a repository that does not publish the
 * sidecar is a different situation from one that cannot be reached, and both end up refusing the
 * artifact, only with a different explanation.
 */
internal sealed interface RemoteChecksumResult {

    data class Found(val checksum: String) : RemoteChecksumResult

    object Missing : RemoteChecksumResult

    data class Failure(val message: String) : RemoteChecksumResult

}

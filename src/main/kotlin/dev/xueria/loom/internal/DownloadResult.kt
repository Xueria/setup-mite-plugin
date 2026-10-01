package dev.xueria.loom.internal

internal interface DownloadResult {

    object Success : DownloadResult

    data class Failure(val message: String) : DownloadResult

}
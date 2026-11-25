package xyz.aresius.audiobooks.core.download

/** A download cannot go on. [retryable] failures are retried later with backoff (WorkManager); the others end the job in [JobState.FAILED]. */
class DownloadException(
    val reason: FailReason,
    message: String? = null,
    val retryable: Boolean = false,
    val retryAfterSec: Int? = null,
    cause: Throwable? = null,
) : Exception(message ?: reason.name, cause)

package io.github.jiangyuyi.lightnovel.core.offline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.jiangyuyi.lightnovel.LightNovelApplication
import io.github.jiangyuyi.lightnovel.core.source.NovelKey
import io.github.jiangyuyi.lightnovel.core.source.SourceErrorKind
import io.github.jiangyuyi.lightnovel.core.source.SourceException
import java.io.IOException

class OfflineDownloadWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val sourceId = inputData.getString(KEY_SOURCE_ID).orEmpty()
        val novelId = inputData.getString(KEY_NOVEL_ID).orEmpty()
        if (sourceId.isBlank() || novelId.isBlank()) return Result.failure()
        val library = (applicationContext as LightNovelApplication).container.offlineLibrary
        return try {
            library.executeDownload(
                novelKey = NovelKey(sourceId, novelId),
                selectedVolumeId = inputData.getString(KEY_VOLUME_ID),
            )
            Result.success()
        } catch (error: SourceException) {
            when (error.kind) {
                SourceErrorKind.TIMEOUT,
                SourceErrorKind.RATE_LIMITED,
                SourceErrorKind.NETWORK,
                SourceErrorKind.SERVER,
                -> Result.retry()

                SourceErrorKind.AUTHENTICATION,
                SourceErrorKind.PARSING,
                SourceErrorKind.UNKNOWN,
                -> Result.failure()
            }
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Throwable) {
            Result.failure()
        }
    }

    companion object {
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_NOVEL_ID = "novel_id"
        const val KEY_VOLUME_ID = "volume_id"
    }
}

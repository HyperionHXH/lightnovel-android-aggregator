package io.github.jiangyuyi.lightnovel.core.network

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import okio.Buffer

internal class CronetImageFetcher(
    private val url: String,
    private val options: Options,
    private val api: LightNovelApi,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bytes = api.getBytes(url)
        return SourceResult(
            source = ImageSource(Buffer().write(bytes), options.context),
            mimeType = url.mimeType(),
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val api: LightNovelApi) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val host = data.host.orEmpty().lowercase()
            if (data.scheme != "https" || (host != "lightnovel.fun" && !host.endsWith(".lightnovel.fun"))) return null
            return CronetImageFetcher(data.toString(), options, api)
        }
    }
}

private fun String.mimeType(): String? = when (substringBefore('?').substringAfterLast('.').lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> null
}

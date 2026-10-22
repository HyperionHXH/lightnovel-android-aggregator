package io.github.jiangyuyi.lightnovel.core.cache

import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import java.util.concurrent.atomic.AtomicLong

/** Prevent a request started before clear from repopulating Coil's bitmap cache. */
class ImageMemoryCacheOwner(private val imageLoader: () -> ImageLoader) : Interceptor {
    private val generation = AtomicLong()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val started = generation.get()
        val result = chain.proceed(chain.request)
        if (started != generation.get() && result is SuccessResult) {
            result.memoryCacheKey?.let { imageLoader().memoryCache?.remove(it) }
        }
        return result
    }

    fun clear(loader: ImageLoader) {
        generation.incrementAndGet()
        loader.memoryCache?.clear()
    }
}

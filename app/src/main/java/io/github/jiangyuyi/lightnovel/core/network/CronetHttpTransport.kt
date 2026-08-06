package io.github.jiangyuyi.lightnovel.core.network

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.UploadDataProviders
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class HttpResponse(
    val code: Int,
    val body: String,
    val protocol: String,
)

internal data class HttpBytesResponse(
    val code: Int,
    val body: ByteArray,
    val protocol: String,
)

internal interface HttpTransport {
    suspend fun postJson(url: String, body: String): HttpResponse
    suspend fun getBytes(url: String): HttpBytesResponse
}

internal class CronetHttpTransport(context: Context) : HttpTransport {
    private val applicationContext = context.applicationContext
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "lightnovel-cronet").apply { isDaemon = true }
    }

    @Volatile
    private var routeIndex = 0

    @Volatile
    private var engine: CronetEngine = createEngine(CLOUDFLARE_ROUTES[routeIndex])

    private fun createEngine(route: String): CronetEngine =
        ExperimentalCronetEngine.Builder(applicationContext).run {
        enableQuic(true)
        enableHttp2(true)
        enableBrotli(true)
        // The mainland route resets TCP connections to these hosts on some networks.
        // Both hosts serve HTTP/3, so try QUIC immediately instead of waiting for Alt-Svc.
        addQuicHint("www.lightnovel.fun", 443, 443)
        addQuicHint("api.lightnovel.fun", 443, 443)
        addQuicHint("res.lightnovel.fun", 443, 443)
        // Some mainland resolvers return a TCP-only compatibility CDN that resets
        // non-browser clients. Map API and image hosts to their public
        // Cloudflare anycast edge; the QUIC hints above preserve SNI and TLS checks.
        setExperimentalOptions(
            """{"HostResolverRules":{"host_resolver_rules":"MAP www.lightnovel.fun $route, MAP api.lightnovel.fun $route, MAP res.lightnovel.fun $route"}}""",
        )
        build()
    }

    override suspend fun postJson(url: String, body: String): HttpResponse {
        val response = request(url, "POST", body.toByteArray(Charsets.UTF_8))
        return HttpResponse(response.code, response.body.toString(Charsets.UTF_8), response.protocol)
    }

    override suspend fun getBytes(url: String): HttpBytesResponse = request(url, "GET", null)

    private suspend fun request(url: String, method: String, upload: ByteArray?): HttpBytesResponse {
        var lastFailure: IOException? = null
        repeat(CLOUDFLARE_ROUTES.size + 1) { attempt ->
            try {
                return executeOnce(engine, url, method, upload)
            } catch (failure: IOException) {
                lastFailure = failure
                if (attempt == CLOUDFLARE_ROUTES.size || !failure.isRetryableConnectionFailure()) {
                    throw failure
                }
                rebuildEngine()
            }
        }
        throw lastFailure ?: IOException("网络连接失败")
    }

    @Synchronized
    private fun rebuildEngine() {
        routeIndex = (routeIndex + 1) % CLOUDFLARE_ROUTES.size
        engine = createEngine(CLOUDFLARE_ROUTES[routeIndex])
    }

    private suspend fun executeOnce(
        requestEngine: CronetEngine,
        url: String,
        method: String,
        upload: ByteArray?,
    ): HttpBytesResponse =
        suspendCancellableCoroutine { continuation ->
            val responseBytes = ByteArrayOutputStream()
            val readBuffer = ByteBuffer.allocateDirect(32 * 1024)

            val callback = object : UrlRequest.Callback() {
                override fun onRedirectReceived(
                    request: UrlRequest,
                    info: UrlResponseInfo,
                    newLocationUrl: String,
                ) = request.followRedirect()

                override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                    request.read(readBuffer)
                }

                override fun onReadCompleted(
                    request: UrlRequest,
                    info: UrlResponseInfo,
                    byteBuffer: ByteBuffer,
                ) {
                    byteBuffer.flip()
                    val chunk = ByteArray(byteBuffer.remaining())
                    byteBuffer.get(chunk)
                    responseBytes.write(chunk)
                    byteBuffer.clear()
                    request.read(byteBuffer)
                }

                override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                    if (continuation.isActive) {
                        continuation.resume(
                            HttpBytesResponse(
                                code = info.httpStatusCode,
                                body = responseBytes.toByteArray(),
                                protocol = info.negotiatedProtocol,
                            ),
                        )
                    }
                }

                override fun onFailed(
                    request: UrlRequest,
                    info: UrlResponseInfo?,
                    error: CronetException,
                ) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IOException("网络连接失败：${error.message}", error),
                        )
                    }
                }

                override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IOException("网络请求已取消"))
                    }
                }
            }

            val builder = requestEngine.newUrlRequestBuilder(url, callback, executor)
                .setHttpMethod(method)
                .addHeader("Accept", if (method == "GET") "image/*,*/*;q=0.8" else "application/json")
                .addHeader("Origin", "https://www.lightnovel.fun")
                .addHeader("Referer", "https://www.lightnovel.fun/")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9")
            if (upload != null) {
                builder.addHeader("Content-Type", "application/json; charset=utf-8")
                    .setUploadDataProvider(UploadDataProviders.create(upload), executor)
            }
            val request = builder.build()

            continuation.invokeOnCancellation { request.cancel() }
            request.start()
        }

    private fun IOException.isRetryableConnectionFailure(): Boolean {
        val cronetError = cause as? CronetException ?: return false
        return cronetError.message.orEmpty().contains("ERR_CONNECTION_RESET") ||
            cronetError.message.orEmpty().contains("ERR_QUIC_PROTOCOL_ERROR")
    }

    private companion object {
        val CLOUDFLARE_ROUTES = arrayOf("104.26.6.43", "104.26.7.43", "172.67.73.171")
    }
}

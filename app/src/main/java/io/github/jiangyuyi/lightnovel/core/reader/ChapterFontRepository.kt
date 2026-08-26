package io.github.jiangyuyi.lightnovel.core.reader

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

class ChapterFontRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val apiOrigin: HttpUrl = DEFAULT_API_ORIGIN.toHttpUrl(),
    private val decodeWoff2: (ByteArray) -> ByteArray? = ChapterFontDecoder::decode,
) : ChapterFontAccess {
    private val directory = File(context.applicationContext.filesDir, FONT_DIRECTORY)
    private val mutex = Mutex()
    private val loaded = mutableMapOf<String, FontFamily>()

    override suspend fun load(fontUrl: String?): FontFamily? {
        val resolvedUrl = resolveUrl(fontUrl) ?: return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                loaded[resolvedUrl]?.let { return@withLock it }
                val target = File(directory, "${stableHash(resolvedUrl)}.ttf")
                val fontFile = target.takeIf(::isSupportedFontFile) ?: downloadFont(resolvedUrl, target)
                val family = try {
                    FontFamily(Typeface.createFromFile(fontFile))
                } catch (error: RuntimeException) {
                    fontFile.delete()
                    throw ChapterFontException("章节字体无法加载，请重试", error)
                }
                loaded[resolvedUrl] = family
                family
            }
        }
    }

    private fun resolveUrl(fontUrl: String?): String? {
        val value = fontUrl?.trim().orEmpty()
        if (value.isEmpty()) return null
        val resolved = apiOrigin.resolve(value)
            ?: throw ChapterFontException("章节字体地址无效")
        if (resolved.scheme !in setOf("http", "https")) {
            throw ChapterFontException("章节字体地址无效")
        }
        return resolved.toString()
    }

    private fun downloadFont(url: String, target: File): File {
        if (target.exists()) target.delete()
        val request = Request.Builder().url(url).get().build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw ChapterFontException("章节字体下载失败，请检查网络", error)
        }
        val downloaded = response.use {
            if (!it.isSuccessful) {
                throw ChapterFontException("章节字体下载失败（${it.code}）")
            }
            val body = it.body ?: throw ChapterFontException("章节字体响应为空")
            if (body.contentLength() > MAX_FONT_BYTES) {
                throw ChapterFontException("章节字体文件过大")
            }
            body.byteStream().use(::readLimited)
        }
        val prepared = when (fontMagic(downloaded)) {
            WOFF2_MAGIC -> try {
                decodeWoff2(downloaded)
                    ?: throw ChapterFontException("章节字体转换失败")
            } catch (error: ChapterFontException) {
                throw error
            } catch (error: Throwable) {
                throw ChapterFontException("章节字体转换失败", error)
            }

            TTF_MAGIC, TRUE_TYPE_MAGIC, OTF_MAGIC -> downloaded
            else -> throw ChapterFontException("章节字体格式无法识别")
        }
        if (!isSupportedEngineFont(prepared)) {
            throw ChapterFontException("章节字体转换结果无效")
        }
        return atomicWrite(target, prepared)
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_FONT_BYTES) throw ChapterFontException("章节字体文件过大")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun atomicWrite(target: File, bytes: ByteArray): File {
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile(".${target.name}.", ".tmp", target.parentFile)
        try {
            temporary.outputStream().use { output ->
                output.write(bytes)
                output.flush()
                (output as java.io.FileOutputStream).fd.sync()
            }
            try {
                Typeface.createFromFile(temporary)
            } catch (error: RuntimeException) {
                throw ChapterFontException("章节字体文件无效", error)
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return target
        } finally {
            temporary.delete()
        }
    }

    private fun isSupportedFontFile(file: File): Boolean =
        file.isFile && runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                input.read(header) == header.size && isSupportedEngineFont(header)
            }
        }.getOrDefault(false)

    private fun isSupportedEngineFont(bytes: ByteArray): Boolean =
        fontMagic(bytes) in setOf(TTF_MAGIC, TRUE_TYPE_MAGIC, OTF_MAGIC)

    private fun fontMagic(bytes: ByteArray): Int? {
        if (bytes.size < 4) return null
        return ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
    }

    private fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val DEFAULT_API_ORIGIN = "https://api.lightnovel.life/"
        const val FONT_DIRECTORY = "reader_fonts"
        const val MAX_FONT_BYTES = 32L * 1024L * 1024L
        const val WOFF2_MAGIC = 0x774F4632
        const val OTF_MAGIC = 0x4F54544F
        const val TTF_MAGIC = 0x00010000
        const val TRUE_TYPE_MAGIC = 0x74727565
    }
}

class ChapterFontException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

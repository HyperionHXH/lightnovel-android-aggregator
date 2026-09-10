package io.github.jiangyuyi.lightnovel.core.reader

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class UserFontDefinition(
    val id: String,
    val name: String,
    val preview: String,
    val urls: List<String>,
    val sizeLabel: String,
    val license: String,
    val fileName: String,
    val sha256: String,
)

/** Local, opt-in fonts. Files are validated before being exposed to the reader. */
class UserFontRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val definitions: List<UserFontDefinition> = catalog,
) {
    private val directory = File(context.applicationContext.filesDir, FONT_DIRECTORY).apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _installed = MutableStateFlow<Set<String>>(emptySet())
    private val loaded = ConcurrentHashMap<String, FontFamily>()
    private val mutationMutex = Mutex()
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    init {
        scope.launch { _installed.value = scanInstalled() }
    }

    suspend fun download(definition: UserFontDefinition): Result<Unit> = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            runCatching {
                val target = File(directory, definition.fileName)
                if (isInstalled(target, definition.sha256)) {
                    _installed.value = scanInstalled()
                    return@runCatching
                }
                val bytes = downloadBytes(definition.urls)
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .toHexString()
                if (!digest.equals(definition.sha256, ignoreCase = true)) {
                    throw UserFontException("字体文件校验失败")
                }
                val temporary = File.createTempFile(".${definition.id}.", ".tmp", directory)
                try {
                    temporary.writeBytes(bytes)
                    if (!isValid(temporary)) throw UserFontException("字体文件无效")
                    if (!temporary.renameTo(target)) {
                        target.delete()
                        if (!temporary.renameTo(target)) throw UserFontException("字体保存失败")
                    }
                } finally {
                    temporary.delete()
                }
                _installed.value = scanInstalled()
                loaded.remove(definition.id)
            }
        }
    }

    suspend fun delete(definition: UserFontDefinition) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            File(directory, definition.fileName).delete()
            loaded.remove(definition.id)
            _installed.value = scanInstalled()
        }
    }

    suspend fun load(id: String?): FontFamily? = withContext(Dispatchers.IO) {
        val definition = definitions.firstOrNull { it.id == id } ?: return@withContext null
        loaded[definition.id]?.let { return@withContext it }
        val file = File(directory, definition.fileName)
        if (!isInstalled(file, definition.sha256)) return@withContext null
        runCatching { FontFamily(Typeface.createFromFile(file)) }.getOrNull()?.also { loaded[definition.id] = it }
    }

    private fun scanInstalled(): Set<String> = definitions
        .filter { isInstalled(File(directory, it.fileName), it.sha256) }
        .mapTo(linkedSetOf(), UserFontDefinition::id)

    private fun isValid(file: File): Boolean = file.isFile && file.length() <= MAX_BYTES &&
        runCatching { Typeface.createFromFile(file); true }.getOrDefault(false)

    private fun isInstalled(file: File, expectedSha256: String): Boolean =
        isValid(file) && runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actualSha256 = digest.digest().toHexString()
            actualSha256.equals(expectedSha256, ignoreCase = true)
        }.getOrDefault(false)

    private fun downloadBytes(urls: List<String>): ByteArray {
        var lastError: Throwable? = null
        urls.forEach { url ->
            val request = Request.Builder().url(url).get().build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = UserFontException("字体下载失败（${response.code}）")
                        return@use
                    }
                    val body = response.body ?: throw UserFontException("字体响应为空")
                    if (body.contentLength() > MAX_BYTES) throw UserFontException("字体文件过大")
                    return body.byteStream().use(::readLimited)
                }
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw UserFontException("字体下载失败，请检查网络", lastError)
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BYTES) throw UserFontException("字体文件过大")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun ByteArray.toHexString(): String = joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        private const val FONT_DIRECTORY = "user_fonts"
        const val MAX_BYTES = 32L * 1024L * 1024L
        val catalog = listOf(
            UserFontDefinition(
                id = "source-han-serif-cn",
                name = "思源宋体",
                preview = "长夜将尽，窗外的雨声渐渐停了。适合长篇正文阅读。",
                urls = listOf(
                    "https://cdn.jsdelivr.net/gh/adobe-fonts/source-han-serif@7889f11bf31170b5d092a083b357c8c8130f89e0/SubsetOTF/CN/SourceHanSerifCN-Regular.otf",
                    "https://raw.githubusercontent.com/adobe-fonts/source-han-serif/7889f11bf31170b5d092a083b357c8c8130f89e0/SubsetOTF/CN/SourceHanSerifCN-Regular.otf",
                ),
                sizeLabel = "约 11 MB",
                license = "SIL OFL 1.1",
                fileName = "source-han-serif-cn.otf",
                sha256 = "3754EA669C530E2473354F8F6D9F79680A44D7E26EC7D00EEABEE4A7E0753C5D",
            ),
            UserFontDefinition(
                id = "source-han-sans-cn",
                name = "思源黑体",
                preview = "她抬头望向远处，街灯在薄雾里排列成一条温柔的线。清晰现代。",
                urls = listOf(
                    "https://cdn.jsdelivr.net/gh/adobe-fonts/source-han-sans@a4f7cf94edfb9d7ffbdfc4841de276358bd7e0f2/SubsetOTF/CN/SourceHanSansCN-Regular.otf",
                    "https://raw.githubusercontent.com/adobe-fonts/source-han-sans/a4f7cf94edfb9d7ffbdfc4841de276358bd7e0f2/SubsetOTF/CN/SourceHanSansCN-Regular.otf",
                ),
                sizeLabel = "约 8 MB",
                license = "SIL OFL 1.1",
                fileName = "source-han-sans-cn.otf",
                sha256 = "E2BC8A2E7F37474B774FFF8DB758681ECE40BB6947A90D571BCE9DD60671A8E4",
            ),
            UserFontDefinition(
                id = "lxgw-wenkai",
                name = "霞鹜文楷",
                preview = "纸页微微卷起，墨色像溪水一样舒缓。温润自然，适合正文。",
                urls = listOf(
                    "https://cdn.jsdelivr.net/gh/lxgw/LxgwWenKai@50f4b182415a8c33d9a456df220b66a284e2509b/fonts/TTF/LXGWWenKai-Regular.ttf",
                    "https://raw.githubusercontent.com/lxgw/LxgwWenKai/50f4b182415a8c33d9a456df220b66a284e2509b/fonts/TTF/LXGWWenKai-Regular.ttf",
                ),
                sizeLabel = "约 25 MB",
                license = "SIL OFL 1.1",
                fileName = "lxgw-wenkai.ttf",
                sha256 = "39AD71264B588165B469E35E6AFB162A378DACD1F95348160240BA9038AC3009",
            ),
            UserFontDefinition(
                id = "lxgw-wenkai-mono",
                name = "霞鹜文楷等宽",
                preview = "第一卷  旅人手记：字宽整齐，适合代码、注释与特殊排版。",
                urls = listOf(
                    "https://cdn.jsdelivr.net/gh/lxgw/LxgwWenKai@50f4b182415a8c33d9a456df220b66a284e2509b/fonts/TTF/LXGWWenKaiMono-Regular.ttf",
                    "https://raw.githubusercontent.com/lxgw/LxgwWenKai/50f4b182415a8c33d9a456df220b66a284e2509b/fonts/TTF/LXGWWenKaiMono-Regular.ttf",
                ),
                sizeLabel = "约 25 MB",
                license = "SIL OFL 1.1",
                fileName = "lxgw-wenkai-mono.ttf",
                sha256 = "BC068E4E395C396F2909FFDFAC3A3751578B73ED3D64A79C9C31BFA84E43DEBE",
            ),
        )
    }
}

class UserFontException(message: String, cause: Throwable? = null) : Exception(message, cause)

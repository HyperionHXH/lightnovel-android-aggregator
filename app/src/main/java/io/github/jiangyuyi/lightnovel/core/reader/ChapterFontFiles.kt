package io.github.jiangyuyi.lightnovel.core.reader

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Only source-supplied chapter fonts; installed user fonts have a different owner. */
internal class ChapterFontFiles(
    private val temporary: File,
    private val offline: File,
    private val legacy: File,
    val maxBytes: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun find(name: String): File? = listOf(offline, legacy, temporary)
        .map { File(it, name) }.firstOrNull(File::isFile)?.also { it.setLastModified(now()) }

    fun temporaryTarget(name: String): File = File(temporary, name)

    fun offlineTarget(name: String): File = File(offline, name)

    fun reserve(bytes: Long, protectedNames: Set<String>) {
        if (bytes > maxBytes) throw IOException("章节字体超过临时缓存上限")
        trim(maxBytes - bytes, protectedNames)
        if (temporaryBytes() + bytes > maxBytes) {
            throw IOException("正在使用的章节字体占满临时缓存，请关闭其他阅读页面后重试")
        }
    }

    fun pin(name: String) {
        val source = find(name) ?: throw IOException("离线章节字体不存在")
        val target = offlineTarget(name)
        if (source == target) return
        offline.mkdirs()
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun migrateLegacy(requiredNames: Set<String>, protectedNames: Set<String>) {
        // Pin every existing downloaded chapter before any legacy file becomes evictable.
        requiredNames.forEach { name -> if (find(name) != null) pin(name) }
        legacy.listFiles().orEmpty().filter(File::isFile).forEach { source ->
            val target = if (source.name in requiredNames) offlineTarget(source.name)
                else temporaryTarget(source.name)
            target.parentFile?.mkdirs()
            if (target.exists()) {
                if (!source.delete()) throw IOException("旧章节字体清理失败")
            } else {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        trim(maxBytes, protectedNames)
    }

    fun clearTemporary(protectedNames: Set<String>) {
        trim(0, protectedNames)
    }

    fun trimTemporary(protectedNames: Set<String>) {
        trim(maxBytes, protectedNames)
    }

    fun temporaryBytes(): Long = bytes(temporary)
    fun offlineBytes(): Long = bytes(offline) + bytes(legacy)

    private fun trim(targetBytes: Long, protectedNames: Set<String>) {
        var size = temporaryBytes()
        temporary.listFiles().orEmpty().filter(File::isFile).sortedBy(File::lastModified)
            .forEach { file ->
                if (size > targetBytes && file.name !in protectedNames) {
                    val length = file.length()
                    if (!file.delete()) throw IOException("章节字体缓存清理失败")
                    size -= length
                }
            }
    }

    private fun bytes(directory: File): Long = directory.listFiles().orEmpty()
        .filter(File::isFile).sumOf(File::length)
}

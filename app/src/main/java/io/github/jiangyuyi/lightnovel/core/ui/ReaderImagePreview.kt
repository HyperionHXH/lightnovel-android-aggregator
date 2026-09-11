package io.github.jiangyuyi.lightnovel.core.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.SubcomposeAsyncImage
import io.github.jiangyuyi.lightnovel.LightNovelApplication
import io.github.jiangyuyi.lightnovel.core.model.ReaderImageScale
import kotlinx.coroutines.launch

/** Shared reader image preview. The overlay itself remains dismissible outside the image. */
@Composable
fun ReaderImagePreview(
    url: String,
    modifier: Modifier,
    imageScale: ReaderImageScale,
    contentDescription: String,
    errorText: String,
    errorColor: Color = MaterialTheme.colorScheme.error,
) {
    var zoomed by remember(url) { mutableStateOf(false) }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = modifier.clickable { zoomed = true },
        contentScale = imageScale.contentScale(),
        loading = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LinearProgressIndicator(Modifier.fillMaxWidth(0.45f))
            }
        },
        error = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(errorText, color = errorColor)
            }
        },
    )
    if (zoomed) {
        ReaderImageDialog(
            url = url,
            imageScale = imageScale,
            contentDescription = "放大$contentDescription",
            onDismiss = { zoomed = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderImageDialog(
    url: String,
    imageScale: ReaderImageScale,
    contentDescription: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Keep the action bar visible in the zoomed state. A long-press still reveals it
    // after it has been dismissed, but download is never hidden behind an invisible gesture.
    var actionVisible by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf(false) }
    val saveImage: () -> Unit = {
        if (!saving) {
            saving = true
            message = null
            scope.launch {
                val result = saveReaderImage(context, url)
                saving = false
                message = result.fold(
                    onSuccess = { "已保存到相册" },
                    onFailure = { "保存失败：${it.message ?: "无法写入相册"}" },
                )
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingSave) saveImage()
        if (!granted) {
            saving = false
            message = "未获得存储权限，无法保存图片"
        }
        pendingSave = false
    }
    val requestSave: () -> Unit = {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSave = true
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveImage()
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(
                Modifier.fillMaxSize().clickable {
                    if (actionVisible) {
                        actionVisible = false
                        message = null
                    } else {
                        onDismiss()
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                SubcomposeAsyncImage(
                    model = url,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize().padding(16.dp).combinedClickable(
                        onClick = {},
                        onLongClick = { actionVisible = true },
                    ),
                    contentScale = imageScale.contentScale(),
                    loading = { CircularProgressIndicator(color = Color.White) },
                )
            }
            if (actionVisible || saving || message != null) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
                    color = Color(0xEE202124),
                    tonalElevation = 6.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            message ?: if (saving) "正在保存图片…" else "图片操作",
                            color = Color.White,
                            modifier = Modifier.weight(1f),
                        )
                        if (!saving && message == null) {
                            IconButton(onClick = requestSave) {
                                Icon(Icons.Filled.Download, contentDescription = "保存到相册", tint = Color.White)
                            }
                        }
                        IconButton(onClick = { message = null; actionVisible = false }) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭操作栏", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

suspend fun saveReaderImage(context: Context, url: String): Result<Unit> = runCatching {
    val app = context.applicationContext as? LightNovelApplication
        ?: error("应用环境不可用")
    val bytes = app.container.api.getBytes(url)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片格式无法识别" }
    val resolver = context.contentResolver
    val extension = when {
        bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)) -> "png"
        bytes.size >= 2 && bytes.copyOfRange(0, 2).contentEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte())) -> "jpg"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x52, 0x49, 0x46, 0x46)) &&
            bytes.copyOfRange(8, 12).contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50)) -> "webp"
        else -> "jpg"
    }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Mixn_${System.currentTimeMillis()}.$extension")
        put(MediaStore.Images.Media.MIME_TYPE, "image/$extension")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Mixn")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建相册文件")
    try {
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入相册文件")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
    } catch (error: Throwable) {
        resolver.delete(uri, null, null)
        throw error
    }
}

private fun ReaderImageScale.contentScale(): ContentScale = when (this) {
    ReaderImageScale.FIT -> ContentScale.Fit
    ReaderImageScale.FILL -> ContentScale.FillBounds
    ReaderImageScale.FIT_WIDTH -> ContentScale.FillWidth
    ReaderImageScale.FIT_HEIGHT -> ContentScale.FillHeight
    ReaderImageScale.ORIGINAL -> ContentScale.Inside
    ReaderImageScale.SMART -> ContentScale.Crop
}

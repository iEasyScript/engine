package com.projectx.ui.compose.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/** A script's badge: its store icon when the store has one, otherwise its initials. */
@Composable
fun ScriptBadge(name: String, tint: Color, size: Dp, iconUrl: String?, modifier: Modifier = Modifier) {
    val icon = iconUrl?.let { StoreArt.icon(it) }
    if (icon == null) {
        Monogram(name, tint, size, modifier)
        return
    }
    val shape = RoundedCornerShape(size * 0.26f)
    Image(
        icon,
        contentDescription = name,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(shape).border(1.dp, tint.copy(alpha = 0.32f), shape),
    )
}

/**
 * Store icons, fetched once per URL off the render thread and kept on disk so a restart draws them straight away.
 * Each is cropped square and shrunk to [ICON_PX], since some store art is a full-size card.
 */
object StoreArt {
    private const val ICON_PX = 128
    private const val RETRY_AFTER_MS = 60_000L
    private val REFETCH_AFTER: Duration = Duration.ofDays(7)

    private val icons = mutableStateMapOf<String, ImageBitmap>()
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "projectx-store-art").apply { isDaemon = true } }
    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
    }
    private val cacheDir: Path = Paths.get(System.getProperty("user.home"), ".projectx", "cache", "store-art")

    fun icon(url: String): ImageBitmap? {
        icons[url]?.let { return it }
        val failed = failedAt[url]
        if (failed != null && System.currentTimeMillis() - failed >= RETRY_AFTER_MS) {
            failedAt.remove(url)
            requested.remove(url)
        }
        if (requested.add(url)) worker.execute { if (!runCatching { load(url) }.getOrDefault(false)) failedAt[url] = System.currentTimeMillis() }
        return null
    }

    private fun load(url: String): Boolean {
        val file = cacheDir.resolve(sha256(url))
        val bytes = cached(file) ?: download(url)?.also { fresh ->
            Files.createDirectories(cacheDir)
            Files.write(file, fresh)
        } ?: cachedEvenIfOld(file) ?: return false
        icons[url] = square(SkiaImage.makeFromEncoded(bytes)).toComposeImageBitmap()
        return true
    }

    private fun cached(file: Path): ByteArray? {
        if (!Files.exists(file)) return null
        val fresh = Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minus(REFETCH_AFTER))
        return if (fresh) Files.readAllBytes(file) else null
    }

    private fun cachedEvenIfOld(file: Path): ByteArray? = if (Files.exists(file)) Files.readAllBytes(file) else null

    private fun download(url: String): ByteArray? = runCatching {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        response.body().takeIf { response.statusCode() in 200..299 && it.isNotEmpty() }
    }.getOrNull()

    private fun square(image: SkiaImage): SkiaImage {
        val side = minOf(image.width, image.height)
        val left = (image.width - side) / 2f
        val top = (image.height - side) / 2f
        val out = minOf(side, ICON_PX)
        val surface = Surface.makeRasterN32Premul(out, out)
        surface.canvas.drawImageRect(image, Rect.makeXYWH(left, top, side.toFloat(), side.toFloat()), Rect.makeWH(out.toFloat(), out.toFloat()))
        return surface.makeImageSnapshot()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

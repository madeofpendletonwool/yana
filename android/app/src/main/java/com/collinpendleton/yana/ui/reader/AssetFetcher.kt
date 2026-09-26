package com.collinpendleton.yana.ui.reader

import android.webkit.WebResourceResponse
import java.io.File
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Serves the reader's images: a relative `![](pic.png)` becomes a
 * request on the app-assets origin, and this fetcher turns it into the
 * server's `/api/files/` with the bearer token attached — the token
 * never enters the page. A disk cache (OkHttp's, in the app's cache
 * directory) keeps what the server served, so a note read in airplane
 * mode still shows the images it showed online; stale entries are
 * revalidated, and served as-is when the server cannot be reached.
 */
class AssetFetcher(
    plain: OkHttpClient,
    cacheDir: File,
    private val token: () -> String?,
) {
    private val http: OkHttpClient = plain.newBuilder()
        .cache(Cache(File(cacheDir, "reader-images"), CACHE_BYTES))
        .build()

    /** Fetches one asset path (percent-encoded segments joined by /) for the reader. */
    fun fetch(server: HttpUrl, encodedPath: String): WebResourceResponse {
        val url = server.newBuilder()
            .addPathSegments("api/files")
            .addEncodedPathSegments(encodedPath)
            .build()
        val response = run(url, null) ?: run(url, CacheControl.FORCE_CACHE)
            ?: return missing()
        try {
            val body = response.body
            val contentType = body?.contentType()
            return if (response.isSuccessful && body != null) {
                WebResourceResponse(
                    contentType?.toString() ?: "application/octet-stream",
                    null,
                    response.code,
                    response.message.ifEmpty { "OK" },
                    response.headers.toMultimap().mapValues { (_, v) -> v.joinToString(", ") },
                    body.byteStream(),
                )
            } else {
                body?.close()
                WebResourceResponse("text/plain", "utf-8", response.code, response.message.ifEmpty { "error" }, emptyMap(), "".byteInputStream())
            }
        } catch (_: Exception) {
            return missing()
        }
    }

    private fun run(url: HttpUrl, control: CacheControl?): okhttp3.Response? = try {
        val builder = Request.Builder().url(url).get()
        token()?.let { builder.header("Authorization", "Bearer $it") }
        control?.let { builder.cacheControl(it) }
        http.newCall(builder.build()).execute()
    } catch (_: Exception) {
        null
    }

    private fun missing(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "no such image", emptyMap(), "".byteInputStream())

    companion object {
        /** Images are worth a big cache; they do not change under the same name. */
        private const val CACHE_BYTES = 64L * 1024 * 1024

        /** The path prefix the reader's pages ask for images under. */
        const val IMAGE_PATH = "/yana-img/"

        fun serverOf(raw: String?): HttpUrl? = raw?.toHttpUrlOrNull()
    }
}

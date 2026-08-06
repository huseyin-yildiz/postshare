package com.postshare.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private enum class Platform { LINKEDIN, TWITTER, INSTAGRAM, FACEBOOK, OTHER }

    private data class PostData(val body: String, val author: String, val images: List<String>)

    private lateinit var textInput: EditText
    private lateinit var statusText: TextView
    private lateinit var statusRow: LinearLayout
    private lateinit var statusSpinner: ProgressBar
    private lateinit var imageContainer: LinearLayout
    private lateinit var sendButton: Button
    private lateinit var retryButton: Button
    private lateinit var avatarText: TextView
    private lateinit var authorText: TextView
    private lateinit var platformChip: TextView

    private val cachedImageUris = mutableListOf<Uri>()
    private var pendingUrl: String? = null
    private var pendingPlatform: Platform? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textInput = findViewById(R.id.textInput)
        statusText = findViewById(R.id.statusText)
        statusRow = findViewById(R.id.statusRow)
        statusSpinner = findViewById(R.id.statusSpinner)
        imageContainer = findViewById(R.id.imageContainer)
        sendButton = findViewById(R.id.sendButton)
        retryButton = findViewById(R.id.retryButton)
        avatarText = findViewById(R.id.avatarText)
        authorText = findViewById(R.id.authorText)
        platformChip = findViewById(R.id.platformChip)

        sendButton.setOnClickListener { sharePost() }
        retryButton.setOnClickListener {
            pendingUrl?.let { url -> pendingPlatform?.let { p -> fetchPost(url, p) } }
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        textInput.setText("")
        imageContainer.removeAllViews()
        cachedImageUris.clear()
        pendingUrl = null
        pendingPlatform = null
        retryButton.visibility = View.GONE
        statusRow.visibility = View.GONE
        statusSpinner.visibility = View.GONE
        statusText.visibility = View.GONE
        avatarText.text = getString(R.string.author_default).take(1).uppercase()
        authorText.text = getString(R.string.author_default)
        platformChip.visibility = View.GONE

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) textInput.setText(text)
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { cacheImage(it) }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) textInput.setText(text)
                val streams = IntentCompat.getParcelableArrayListExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java
                )
                streams?.forEach { cacheImage(it) }
            }
        }

        imageContainer.visibility =
            if (cachedImageUris.isEmpty()) View.GONE else View.VISIBLE

        val sharedText = textInput.text?.toString().orEmpty()
        val socialUrl = findSocialUrl(sharedText)
        val platform = socialUrl?.let { platformOf(it) }
        if (socialUrl != null &&
            platform != null &&
            platform in setOf(Platform.LINKEDIN, Platform.TWITTER, Platform.INSTAGRAM) &&
            cachedImageUris.isEmpty()
        ) {
            fetchPost(socialUrl, platform)
        }
    }

    // --- Social post fetching ---------------------------------------------------

    private fun findSocialUrl(text: String): String? {
        val m = Regex(
            "https?://[\\w.-]*(linkedin\\.com|lnkd\\.in|x\\.com|twitter\\.com|t\\.co|instagram\\.com|instagr\\.am|facebook\\.com|fb\\.watch|fb\\.me)/[^\\s]+"
        ).find(text) ?: return null
        return m.value.trimEnd('.', ',', ')', ';', '"')
    }

    private fun platformOf(url: String): Platform {
        val host = try { Uri.parse(url).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        return when {
            host == "linkedin.com" || host == "lnkd.in" || host.endsWith(".linkedin.com") ->
                Platform.LINKEDIN
            host == "x.com" || host == "twitter.com" || host == "t.co" ||
                host.endsWith(".x.com") || host.endsWith(".twitter.com") ->
                Platform.TWITTER
            host == "instagram.com" || host == "instagr.am" ||
                host.endsWith(".instagram.com") ->
                Platform.INSTAGRAM
            host == "facebook.com" || host.endsWith(".facebook.com") ||
                host == "fb.watch" || host == "fb.me" ->
                Platform.FACEBOOK
            else -> Platform.OTHER
        }
    }

    private fun fetchPost(url: String, platform: Platform) {
        pendingUrl = url
        pendingPlatform = platform
        statusRow.visibility = View.VISIBLE
        statusSpinner.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = getString(R.string.status_loading)
        statusText.setTextColor(resources.getColor(R.color.textSecondary))
        retryButton.visibility = View.GONE
        sendButton.isEnabled = false

        Thread {
            val result = try {
                when (platform) {
                    Platform.LINKEDIN -> fetchLinkedIn(url)
                    Platform.TWITTER -> fetchTwitter(url)
                    Platform.INSTAGRAM -> fetchInstagram(url)
                    else -> null
                }
            } catch (e: Exception) {
                null
            }

            val downloaded = mutableListOf<Uri>()
            if (result != null) {
                result.images.forEach { imageUrl ->
                    try {
                        downloadImageToCache(imageUrl)?.let { downloaded.add(it) }
                    } catch (e: Exception) {
                        // Skip images that fail to load.
                    }
                }
            }

            val post = result
            runOnUiThread {
                sendButton.isEnabled = true
                statusSpinner.visibility = View.GONE
                if (post != null && (post.body.isNotBlank() || downloaded.isNotEmpty())) {
                    if (post.body.isNotBlank()) textInput.setText(post.body)
                    downloaded.forEach { addThumbnail(it) }
                    imageContainer.visibility =
                        if (downloaded.isEmpty()) View.GONE else View.VISIBLE

                    val author = post.author.trim()
                    if (author.isNotBlank()) {
                        authorText.text = getString(R.string.author_posted, author)
                        avatarText.text = author.take(1).uppercase()
                    } else {
                        authorText.text = getString(R.string.author_default)
                        avatarText.text = getString(R.string.author_default).take(1).uppercase()
                    }
                    platformChip.text = platformLabel(platform)
                    platformChip.backgroundTintList =
                        ColorStateList.valueOf(platformColor(platform))
                    platformChip.visibility = View.VISIBLE

                    statusText.setTextColor(resources.getColor(R.color.statusSuccess))
                    statusText.text = when {
                        author.isNotBlank() -> getString(R.string.status_ok_author, author)
                        downloaded.isNotEmpty() -> getString(R.string.status_ok_image)
                        else -> getString(R.string.status_ok_content)
                    }
                } else {
                    statusText.setTextColor(resources.getColor(R.color.statusError))
                    statusText.text = getString(R.string.status_fail)
                    retryButton.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun fetchLinkedIn(url: String): PostData {
        return parseLinkedIn(httpGet(url)) ?: throw IOException("no content")
    }

    private fun fetchTwitter(url: String): PostData {
        var u = url
        if (Regex("(^|\\.)t\\.co/").containsMatchIn(u)) u = resolveRedirects(u)
        val id = Regex("status/(\\d+)").find(u)?.groupValues?.get(1)
            ?: throw IOException("not a status URL")
        val json = JSONObject(httpGet("https://api.fxtwitter.com/i/status/$id"))
        if (json.optInt("code", 200) != 200) throw IOException("API error")

        val tweet = json.getJSONObject("tweet")
        val text = decodeHtml(tweet.optString("text")).trim()
        val author = tweet.optJSONObject("author")?.optString("screen_name").orEmpty()

        val images = mutableListOf<String>()
        val media = tweet.optJSONObject("media")
        media?.optJSONArray("photos")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("url")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { images.add(it) }
            }
        }
        if (images.isEmpty()) {
            media?.optJSONArray("all")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i)
                    if (item?.optString("type") == "photo") {
                        item.optString("url")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { images.add(it) }
                    }
                }
            }
        }
        return PostData(text, author, images)
    }

    private fun fetchInstagram(url: String): PostData {
        val code = Regex("(?:p|reel|tv|reels)/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
            ?: throw IOException("not a post URL")
        val html = httpGet("https://www.instagram.com/p/$code/", GOOGLEBOT_UA)
        val author = Regex(" - ([A-Za-z0-9_.]+) on ").find(html)?.groupValues?.get(1).orEmpty()
        var caption = ""
        val desc = Regex("""<meta name="description" content="(.*?)" />""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1).orEmpty()
        val capStart = desc.indexOf(": &quot;")
        val capEnd = desc.lastIndexOf("&quot;")
        if (capStart >= 0 && capEnd > capStart) {
            caption = decodeHtml(desc.substring(capStart + 8, capEnd)).trim()
        }
        return PostData(caption, author, listOf("https://www.instagram.com/p/$code/media/?size=l"))
    }

    private fun resolveRedirects(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", BROWSER_UA)
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        val finalUrl = conn.url.toString()
        conn.disconnect()
        return finalUrl
    }

    private fun httpGet(url: String, userAgent: String = BROWSER_UA): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", userAgent)
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        val code = conn.responseCode
        if (code !in 200..399) throw IOException("HTTP $code")
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun parseLinkedIn(html: String): PostData? {
        val scriptRegex = Regex(
            """<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val match = scriptRegex.find(html) ?: return null
        val json = JSONObject(match.groupValues[1])

        val body = decodeHtml(json.optString("articleBody")).trim()
        val author = json.optJSONObject("author")?.optString("name").orEmpty()

        val images = mutableListOf<String>()
        when (val img = json.opt("image")) {
            is JSONObject -> {
                img.optString("url").takeIf { it.isNotBlank() }?.let { images.add(it) }
            }
            is JSONArray -> {
                for (i in 0 until img.length()) {
                    val item = img.get(i)
                    val url = when (item) {
                        is JSONObject -> item.optString("url")
                        is String -> item
                        else -> ""
                    }
                    url.takeIf { it.isNotBlank() }?.let { images.add(it) }
                }
            }
            is String -> if (img.isNotBlank()) images.add(img)
        }
        images.removeAll { it.contains("profile-displayphoto-") }
        return PostData(body, author, images)
    }

    private fun decodeHtml(input: String): String {
        var s = input
        s = s.replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
        val numeric = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
        s = numeric.replace(s) { m ->
            val code = m.groupValues[1]
            try {
                val value = if (code.startsWith("x")) code.substring(1).toInt(16) else code.toInt()
                value.toChar().toString()
            } catch (e: NumberFormatException) {
                m.value
            }
        }
        return s
    }

    // --- Image handling ----------------------------------------------------------

    private fun downloadImageToCache(imageUrl: String): Uri? {
        val conn = URL(imageUrl).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", BROWSER_UA)
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        val code = conn.responseCode
        if (code !in 200..399) throw IOException("HTTP $code")
        return conn.inputStream.use { cacheStream(it, extFor(conn.contentType)) }
    }

    private fun cacheImage(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.let { input ->
                cacheStream(input, extFor(contentResolver.getType(uri)))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not load image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cacheStream(input: InputStream, ext: String): Uri? {
        return try {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val target = File(dir, "img_${System.currentTimeMillis()}_${cachedImageUris.size}.$ext")
            FileOutputStream(target).use { output -> input.copyTo(output) }
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", target)
                .also { cachedImageUris.add(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun extFor(mime: String?): String = when (mime?.substringBefore(";")?.lowercase()) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private fun addThumbnail(uri: Uri) {
        val thumb = ImageView(this)
        thumb.layoutParams = LinearLayout.LayoutParams(108, 108).apply {
            marginEnd = 10
        }
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.setImageURI(uri)
        thumb.setOnClickListener { openInViewer(uri) }
        val radius = 12 * resources.displayMetrics.density
        thumb.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(0xFFDDE9E7.toInt())
        }
        thumb.clipToOutline = true
        thumb.outlineProvider = ViewOutlineProvider.BACKGROUND
        imageContainer.addView(thumb)
    }

    private fun openInViewer(uri: Uri) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, contentResolver.getType(uri) ?: "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(viewIntent)
        } catch (e: ActivityNotFoundException) {
            // No image viewer installed; ignore.
        }
    }

    private fun platformColor(platform: Platform): Int = when (platform) {
        Platform.LINKEDIN -> resources.getColor(R.color.linkedinBlue)
        Platform.TWITTER -> resources.getColor(R.color.xBlack)
        Platform.INSTAGRAM -> resources.getColor(R.color.instagramPink)
        Platform.FACEBOOK -> resources.getColor(R.color.facebookBlue)
        Platform.OTHER -> resources.getColor(R.color.colorPrimary)
    }

    private fun platformLabel(platform: Platform): String = when (platform) {
        Platform.LINKEDIN -> getString(R.string.platform_linkedin)
        Platform.TWITTER -> getString(R.string.platform_twitter)
        Platform.INSTAGRAM -> getString(R.string.platform_instagram)
        Platform.FACEBOOK -> getString(R.string.platform_facebook)
        Platform.OTHER -> getString(R.string.platform_other)
    }

    // --- Sending ------------------------------------------------------------------

    private fun sharePost() {
        val text = textInput.text?.toString().orEmpty()
        val base = when {
            cachedImageUris.isEmpty() -> Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }

            cachedImageUris.size == 1 -> Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, cachedImageUris[0])
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            else -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(cachedImageUris))
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        try {
            startActivity(Intent.createChooser(base, getString(R.string.share_to)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_found, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val GOOGLEBOT_UA =
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
    }
}

package eu.kanade.tachiyomi.extension.zh.laimanhua

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.nio.charset.Charset

/** Mihon 通过 AndroidManifest 的 tachiyomi.extension.class 反射此类来取得 Source。 */
class LaiManHuaFactory : SourceFactory {
    override fun createSources() = listOf(LaiManHua())
}

/** [风险2] 供上层识别 CF 挑战、决定是否走 WebView。本类不实现 WebView。 */
class CloudflareChallengeException(url: String) :
    Exception("Cloudflare JS challenge @ $url")

/**
 * 改用 HttpSource 而非 ParsedHttpSource。
 *
 * 原因（实测）：站点响应头是 `Content-Type: text/html`，不带 charset，
 * 而实际字节是 GB18030。ParsedHttpSource 内部自行把 Response 交给 Jsoup，
 * 会按 UTF-8 解码，中文全部乱码 —— 表现为"能装上、能点开，但标题章节全是
 * 乱码或空列表"。因此这里接管全部解析，用 bytes + GB18030 自行构建 Document。
 */
class LaiManHua : HttpSource() {

    override val name = "来漫画"
    override val baseUrl = "https://www.laimanhua88.com"
    override val lang = "zh"
    override val supportsLatest = true

    override val client = super.client.newBuilder()
        .addInterceptor(RefererInterceptor())
        .build()

    private val gb18030: Charset = Charset.forName("GB18030")

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    private val siteHeaders: Headers = Headers.Builder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", ua)
        .build()

    // ------------------------------------------------------------ 抓取/解码

    /**
     * 解析已拿到的 Response，绝不再发二次请求。
     *
     * 为什么必须自己解析：站点响应头是 `Content-Type: text/html`，不带 charset，
     * 而实际字节是 GB18030。body.string() 会按 UTF-8 解，中文全部乱码。
     * 所以统一走 bytes() + GB18030。
     */
    private fun parseBody(response: Response): Document {
        val url = response.request.url.toString()

        if (!response.isSuccessful) {
            // [风险2] 区分两类 403：
            //  A. CF JS Challenge —— 可用 WebView 拿 cf_clearance 后重试
            //  B. 站点自身封禁 —— 实测 /kanmanhua/ 是 IIS 风格 403，WebView 也过不去
            if (response.code == 403) {
                val body = response.body?.bytes()?.toString(gb18030).orEmpty()
                val isCf = body.contains("Just a moment") ||
                    body.contains("cf-challenge") ||
                    body.contains("challenge-platform") ||
                    response.header("cf-mitigated")?.equals("challenge", true) == true
                if (isCf) throw CloudflareChallengeException(url)
                throw Exception("站点拒绝访问(非CF挑战) HTTP 403 @ $url")
            }
            throw Exception("HTTP ${response.code} @ $url")
        }

        val bytes = response.body!!.bytes()
        return Jsoup.parse(bytes.toString(gb18030), url)
    }

    // ------------------------------------------------------- mangaDetailsParse

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(absoluteUrl(manga.url), siteHeaders)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = parseBody(response)
        fun meta(p: String): String =
            document.selectFirst("meta[property=\"$p\"]")?.attr("content")?.trim().orEmpty()

        return SManga.create().apply {
            title = meta("og:title")
            author = meta("og:novel:author")
            description = meta("og:description")
            thumbnail_url = meta("og:image").replace(Regex("@!\\d+x\\d+$"), "")
            genre = meta("og:novel:category")
            status = when {
                meta("og:novel:status").contains("完结") -> SManga.COMPLETED
                meta("og:novel:status").contains("连载") -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
            initialized = true
        }
    }

    // ------------------------------------------------------- chapterListParse

    override fun chapterListRequest(manga: SManga): Request =
        GET(absoluteUrl(manga.url), siteHeaders)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = parseBody(response)

        // 实测 www 桌面模板为 div.plist，m 站移动模板为 #chapterList，用选择器组一次覆盖
        val anchors = document.select("div.plist li > a[href]").ifEmpty {
            document.select("#chapterList li a")
        }
        if (anchors.isEmpty()) {
            throw Exception("章节列表为空，模板可能再次改版 @ ${response.request.url}")
        }

        return anchors.map { a ->
            SChapter.create().apply {
                name = a.text().trim().ifEmpty { a.attr("title").trim() }
                url = absoluteUrl(a.attr("href"))
            }
        }
    }

    // --------------------------------------------------------- pageListParse

    override fun pageListRequest(chapter: SChapter): Request =
        GET(absoluteUrl(chapter.url), siteHeaders)

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString()
        val document = parseBody(response)
        val payload = extractPicTree(document, chapterUrl)

        // 含 mh160tuku 即明文，否则 Base64；解码结果为 UTF-8 字节
        val raw: String = if (payload.contains("mh160tuku")) {
            payload
        } else {
            String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
        }

        val paths = raw.split("\$qingtiandy\$").filter { it.isNotBlank() }
        if (paths.isEmpty()) throw Exception("picTree 切分后为空 @ $chapterUrl")

        // [风险1] 只探测一次：用第一张图的相对路径选主机
        val host = resolveImageHost(chapterUrl, paths.first())

        return paths.mapIndexed { index, path ->
            // Page 第二参数为 Referer，图片主机做域名白名单校验，必须传章节页 URL
            Page(index, chapterUrl, host + path)
        }
    }

    override fun imageUrlParse(response: Response): String = ""

    /**
     * [风险1] 单次探测选主机。
     * 关键：必须带章节页 Referer —— 无 Referer 的 HEAD 实测返回 404，
     * 漏加会把全部主机误判为不可用，探测形同虚设。
     */
    private fun resolveImageHost(chapterUrl: String, firstPath: String): String {
        for (host in IMAGE_HOSTS) {
            try {
                val req = Request.Builder()
                    .url(host + firstPath)
                    .head()
                    .header("Referer", chapterUrl)
                    .header("User-Agent", ua)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return host
                }
            } catch (_: IOException) {
                // 该主机不可用，继续下一个
            }
        }
        return IMAGE_HOSTS.first() // 全部失败：回退首个，不阻断阅读
    }

    /**
     * 提取内联脚本中的 picTree 值。
     * 用不动点循环跳过可能的 `picTree = ''` 空声明。
     */
    private fun extractPicTree(document: Document, chapterUrl: String): String {
        val block: String = document.select("script:containsData(picTree)").firstOrNull()?.data()
            ?: throw Exception("未找到 picTree 脚本块 @ $chapterUrl")

        val marker = "picTree"
        var from = block.indexOf(marker)
        while (from >= 0) {
            val v = Regex("picTree\\s*=\\s*'([^']*)'")
                .find(block, from)?.groupValues?.get(1)
                ?.replace(Regex("\\s"), "")
            if (!v.isNullOrBlank() && v.length > 32) return v
            from = block.indexOf(marker, from + marker.length)
        }
        throw Exception("picTree 赋值解析失败 @ $chapterUrl")
    }

    // ------------------------------------------- popular / latest / search

    // [风险3] 独立热门页 /kanmanhua/zaixian_hit.html（漫画排行榜）
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/kanmanhua/zaixian_hit.html", siteHeaders)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = parseBody(response)
        val mangas = document.select("div.allTop li a.vtip").map { a ->
            SManga.create().apply {
                url = absoluteUrl(a.attr("href"))
                title = a.attr("title").trim()
            }
        }
        return MangasPage(mangas, false)
    }

    // [风险3] latestUpdates 走最新更新页
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/kanmanhua/zaixian_recent.html", siteHeaders)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = parseBody(response)
        val mangas = document.select("div.updateList li a.video").map { a ->
            SManga.create().apply {
                url = absoluteUrl(a.attr("href"))
                title = a.text().trim()
            }
        }
        return MangasPage(mangas, false)
    }

    // [风险4] 搜索入口实测不可用：/cse/search/ → 404；/cse1/search/ POST → 405。
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        throw Exception("该图源暂不支持搜索")

    override fun searchMangaParse(response: Response): MangasPage =
        MangasPage(emptyList(), false)

    // ---------------------------------------------------------------- 工具

    private fun absoluteUrl(href: String): String =
        if (href.startsWith("http")) href else baseUrl + href

    companion object {
        private val IMAGE_HOSTS = listOf(
            "https://mhpic5eer.tgmhfc.uk",
            "https://mhpic7ffr.tgmhfc.uk",
            "https://mhpicwwx.tgmhfc.uk",
        )
    }

    /** 跨域图片需 laimanhua88.com 的 Referer，Page 已带；此处兜底 */
    private class RefererInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            return if (req.url.host.endsWith("laimanhua88.com")) {
                chain.proceed(req)
            } else {
                chain.proceed(
                    req.newBuilder()
                        .header("Referer", "https://www.laimanhua88.com/")
                        .build(),
                )
            }
        }
    }
}

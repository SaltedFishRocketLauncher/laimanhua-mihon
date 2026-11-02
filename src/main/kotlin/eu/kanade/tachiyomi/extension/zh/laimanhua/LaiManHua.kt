package eu.kanade.tachiyomi.extension.zh.laimanhua

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.nio.charset.Charset

/** Mihon 通过 AndroidManifest 的 tachiyomi.extension.class 反射此类来取得 Source。 */
class LaiManHuaFactory : SourceFactory {
    override fun createSources() = listOf(LaiManHua())
}

/** [风险2] 供上层识别 CF 挑战、决定是否走 WebView。本类不实现 WebView。 */
class CloudflareChallengeException(url: String) :
    Exception("Cloudflare JS challenge @ $url")

class LaiManHua : ParsedHttpSource() {

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

    private fun fetchDoc(url: String): Document {
        val request = Request.Builder().url(url).headers(siteHeaders).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // [风险2] 区分两类 403：
                //  A. CF JS Challenge —— body 含 "Just a moment"/"cf-challenge"/
                //     "challenge-platform"，或响应头 cf-mitigated: challenge。
                //  B. 站点自身封禁 —— 实测 /kanmanhua/ 的 403 是 IIS 风格
                //     "403 - 禁止访问"，"Just a moment" 出现 0 次、无 cf-mitigated。
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
            // 解码：响应头无 charset，须 bytes + GB18030
            return Jsoup.parse(response.body!!.bytes().toString(gb18030), url)
        }
    }

    // ------------------------------------------------------- mangaDetailsParse

    // 修正：ParsedHttpSource 的入口是 mangaDetailsParse(Document)，不是 getMangaDetails
    override fun mangaDetailsParse(document: Document): SManga {
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
        }
    }

    // ------------------------------------------------------- 章节列表

    // 修正：改为 chapterListSelector + chapterFromElement
    // 实测 www 桌面模板为 div.plist，m 站移动模板为 #chapterList，用选择器组一次覆盖
    override fun chapterListSelector(): String =
        "div.plist li > a[href], #chapterList li a"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        name = element.text().trim().ifEmpty { element.attr("title").trim() }
        // 实测 href 全为相对路径 /kanmanhua/{slug}/{id}.html；
        // ParsedHttpSource 的 chapterListParse 会交由 setUrlWithoutDomain 处理，这里存原始 href
        url = element.attr("href")
    }

    // ------------------------------------------------------- pageListParse

    // 修正：改为 pageListParse(Document)
    override fun pageListParse(document: Document): List<Page> {
        val chapterUrl = document.location()
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

    // 修正：ParsedHttpSource 还要求实现 imageUrlParse(Document)（与 imageUrlParse(Response) 重载）
    // 本图源的图片地址全部由 pageListParse 一次给出，故此处返回空串表示"无需二次解析"。
    override fun imageUrlParse(document: Document): String = ""

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

    // ------------------------------------------- popular / latest / search

    // [风险3] 独立热门页 /kanmanhua/zaixian_hit.html（漫画排行榜）
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/kanmanhua/zaixian_hit.html", siteHeaders)

    override fun popularMangaSelector(): String = "div.allTop li a.vtip"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        url = element.attr("href")           // 实测全为相对路径
        title = element.attr("title").trim() // 实测 225 条 title 全非空
    }

    override fun popularMangaNextPageSelector(): String? = null

    // [风险3] latestUpdates 保持原样
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/kanmanhua/zaixian_recent.html", siteHeaders)

    override fun latestUpdatesSelector(): String = "div.updateList li a.video"

    override fun latestUpdatesFromElement(element: Element): SManga = SManga.create().apply {
        url = element.attr("href")
        title = element.text().trim()
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    // [风险4] 搜索入口实测不可用：/cse/search/ → 404；/cse1/search/ POST → 405。
    // 抛错优于返回最新列表，避免"搜任何词都有结果"的误导。
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        throw Exception("该图源暂不支持搜索")

    override fun searchMangaSelector(): String = "div.__no_search__"

    override fun searchMangaFromElement(element: Element): SManga = SManga.create()

    override fun searchMangaNextPageSelector(): String? = null
}

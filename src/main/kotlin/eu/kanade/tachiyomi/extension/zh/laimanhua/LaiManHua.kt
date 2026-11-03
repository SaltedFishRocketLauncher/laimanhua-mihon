package eu.kanade.tachiyomi.extension.zh.laimanhua

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.Filter
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

class LaiManHuaFactory : SourceFactory {
    override fun createSources() = listOf(LaiManHua())
}

/** [风险2] 供上层识别 CF 挑战。本类不实现 WebView。 */
class CloudflareChallengeException(url: String) :
    Exception("Cloudflare JS challenge @ $url")

/**
 * 基于 HttpSource：站点响应头无 charset（实际 GB18030），
 * ParsedHttpSource 会按 UTF-8 解析导致中文全乱码，故接管全部解析。
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

    // ------------------------------------------------------------ 解码

    private fun parseBody(response: Response): Document {
        val url = response.request.url.toString()
        if (!response.isSuccessful) {
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
        return Jsoup.parse(response.body!!.bytes().toString(gb18030), url)
    }

    // ------------------------------------------------------- 详情

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(baseUrl + manga.url, siteHeaders)

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

    // ------------------------------------------------------- 章节

    override fun chapterListRequest(manga: SManga): Request =
        GET(baseUrl + manga.url, siteHeaders)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = parseBody(response)
        val anchors = document.select("div.plist li > a[href]").ifEmpty {
            document.select("#chapterList li a")
        }
        if (anchors.isEmpty()) {
            throw Exception("章节列表为空 @ ${response.request.url}")
        }

        return anchors.map { a ->
            val chapter = SChapter.create()
            chapter.name = a.text().trim().ifEmpty { a.attr("title").trim() }
            // 必须存相对路径：HttpSource.getChapterUrl() 会做 baseUrl + url
            chapter.url = stripDomain(a.attr("href"))
            chapter
        }
    }

    // ------------------------------------------------------- 图片列表（bug 修复点）

    override fun pageListRequest(chapter: SChapter): Request =
        GET(baseUrl + chapter.url, siteHeaders)

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString()
        val document = parseBody(response)
        val payload = extractPicTree(document, chapterUrl)

        val raw: String = if (payload.contains("mh160tuku")) {
            payload
        } else {
            String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
        }

        val paths = raw.split("\$qingtiandy\$").filter { it.isNotBlank() }
        if (paths.isEmpty()) throw Exception("picTree 切分后为空 @ $chapterUrl")

        val host = resolveImageHost(chapterUrl, paths.first())

        return paths.mapIndexed { index, path ->
            Page(index, chapterUrl, host + path)
        }
    }

    override fun imageUrlParse(response: Response): String = ""

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
                // next host
            }
        }
        return IMAGE_HOSTS.first()
    }

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

    // ------------------------------------------------------- 热门（默认榜单）

    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/kanmanhua/zaixian_hit.html", siteHeaders)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = parseBody(response)
        // 默认展示第一个榜单「所有漫画排行」
        return MangasPage(parseRankBlock(document, 0), false)
    }

    // ------------------------------------------------------- 最新

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/kanmanhua/zaixian_recent.html", siteHeaders)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = parseBody(response)
        val mangas = document.select("div.updateList li a.video").map { a ->
            val manga = SManga.create()
            manga.url = stripDomain(a.attr("href"))
            manga.title = a.text().trim()
            // 实测：最新页的封面不在 <img>，而在 <a> 的自定义属性 i 上
            // 形如 i="https://p.miyeye.cn/.../xxx.jpg@!180x240"
            manga.thumbnail_url = normalizeThumb(a.attr("i"))
            manga
        }
        return MangasPage(mangas, false)
    }

    // --------------------------------------- 分类筛选 + 风云榜（统一走 search）

    /**
     * Mihon 只在 search 流程传入 FilterList，因此「分类筛选」和「榜单切换」
     * 都放在这里处理：不填搜索词时按所选模式浏览，填了则走站点搜索。
     */
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val mode = filters.filterIsInstance<ModeFilter>().firstOrNull()?.state ?: 0
        val rank = filters.filterIsInstance<RankFilter>().firstOrNull()?.state ?: 0
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.state ?: 0

        // 模式 0：风云榜（不翻页，榜单只有一屏）
        if (mode == MODE_RANK && query.isBlank()) {
            rankIndexForParse = rank
            return GET("$baseUrl/kanmanhua/zaixian_hit.html", siteHeaders)
        }

        // 模式 1：分类浏览（支持翻页）
        if (query.isBlank()) {
            val path = CATEGORY_PATHS.getOrElse(category) { CATEGORY_PATHS[0] }
            return GET("$baseUrl/kanmanhua/$path/$page.html", siteHeaders)
        }

        // 有搜索词但站点搜索接口不可用（/cse/search/ 404、/cse1/search/ 405 POST）。
        // 这里不拿分类页冒充结果，交给解析层返回空列表。
        rankIndexForParse = -1
        return GET("$baseUrl/kanmanhua/rexue/1.html", siteHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = parseBody(response)
        val url = response.request.url.toString()

        // 风云榜分支
        if (url.contains("zaixian_hit.html")) {
            val idx = if (rankIndexForParse >= 0) rankIndexForParse else 0
            return MangasPage(parseRankBlock(document, idx), false)
        }

        // 分类页分支
        // 实测结构：<li><p class="fl cover"><a class="pic"><img src="封面"></a></p>
        //                <dl><dt><a href="/kanmanhua/xxx/" title="标题">…
        // 封面与标题在不同分支，必须从整个 li 取，不能只取 dt a
        val lis = document.select("div.dmList li")
        if (lis.isNotEmpty()) {
            val mangas = lis.mapNotNull { li ->
                val a = li.selectFirst("dl dt a[href]") ?: return@mapNotNull null
                val manga = SManga.create()
                manga.url = stripDomain(a.attr("href"))
                manga.title = a.attr("title").trim().ifEmpty { a.text().trim() }
                // 封面在 p.cover img 的 src；部分条目没有，此时留空由 Mihon 走详情页补
                manga.thumbnail_url = normalizeThumb(
                    li.selectFirst("p.cover img")?.attr("src").orEmpty()
                )
                manga
            }
            val hasNext = document.select("div.pages a").any { it.text().contains("下一页") }
            return MangasPage(mangas, hasNext)
        }

        return MangasPage(emptyList(), false)
    }

    /** 解析指定序号的风云榜容器（div.allTop，实测 15 个） */
    private fun parseRankBlock(document: Document, index: Int): List<SManga> {
        val blocks = document.select("div.allTop")
        if (blocks.isEmpty()) {
            throw Exception("未找到榜单容器 div.allTop")
        }
        val block = blocks[index.coerceIn(0, blocks.size - 1)]
        return block.select("li a.vtip").map { a ->
            val manga = SManga.create()
            manga.url = stripDomain(a.attr("href"))
            manga.title = a.attr("title").trim().ifEmpty { a.text().trim() }
            // 实测：榜单页封面同样在 <a class="vtip" i="..."> 的 i 属性上
            manga.thumbnail_url = normalizeThumb(a.attr("i"))
            manga
        }
    }

    override fun getFilterList(): FilterList = FilterList(
        ModeFilter(),
        Filter.Separator(),
        Filter.Header("【风云榜】选择要查看的榜单"),
        RankFilter(),
        Filter.Separator(),
        Filter.Header("【分类浏览】选择分类，留空搜索框即可翻页"),
        CategoryFilter(),
    )

    /** 浏览模式：风云榜 / 分类 */
    private class ModeFilter : Filter.Select<String>("浏览模式", MODE_NAMES)

    private class RankFilter : Filter.Select<String>("风云榜榜单", RANK_NAMES)

    private class CategoryFilter : Filter.Select<String>("漫画分类", CATEGORY_NAMES)

    // ---------------------------------------------------------------- 工具

    /** 去掉域名只留路径，HttpSource 会自行拼 baseUrl */
    private fun stripDomain(href: String): String =
        if (href.startsWith("http")) href.removePrefix(baseUrl) else href

    /**
     * 统一处理封面地址。
     *
     * 实测站点有两处坑：
     *  1. 地址带 `@!180x240` 缩略参数（服务端按此裁剪），保留它可显著减小流量；
     *     该参数不影响可访问性（实测带与不带均 200）。
     *  2. 协议可能是 http，统一升到 https，避免 Android 9+ 明文流量被拦。
     * 空值直接返回空串，交由 Mihon 回落到详情页封面。
     */
    private fun normalizeThumb(raw: String): String {
        var v = raw.trim()
        if (v.isEmpty()) return ""
        if (v.startsWith("//")) v = "https:$v"
        if (v.startsWith("http://")) v = "https://" + v.removePrefix("http://")
        return v
    }

    companion object {
        private val IMAGE_HOSTS = listOf(
            "https://mhpic5eer.tgmhfc.uk",
            "https://mhpic7ffr.tgmhfc.uk",
            "https://mhpicwwx.tgmhfc.uk",
        )

        private const val MODE_RANK = 0

        private val MODE_NAMES = arrayOf("风云榜", "分类浏览")

        // 与 div.allTop 顺序严格一致（实测 15 个）
        private val RANK_NAMES = arrayOf(
            "所有漫画排行", "少年热血", "武侠格斗", "科幻魔幻", "竞技体育",
            "爆笑喜剧", "侦探推理", "恐怖灵异", "耽美人生", "少女爱情",
            "大陆漫画", "日韩漫画", "港台漫画", "欧美漫画", "日排行",
        )

        // 注：zaixian_lianzai / zaixian_wanjie 是单页入口，不支持 /{page}.html 分页，
        // 故不作为分类项，避免选中后 404
        private val CATEGORY_NAMES = arrayOf(
            "日韩漫画", "国产漫画", "欧美漫画", "港台漫画",
            "少年热血", "武侠格斗", "科幻魔幻", "竞技体育", "爆笑喜剧",
            "侦探推理", "恐怖灵异", "耽美人生", "少女爱情", "恋爱生活",
            "生活漫画", "其他漫画", "战争漫画",
        )

        // 与 CATEGORY_NAMES 一一对应，实测均可访问且每页 30 条
        private val CATEGORY_PATHS = arrayOf(
            "zaixian_rhmh", "zaixian_dlmh", "zaixian_ommh", "zaixian_gtmh",
            "rexue", "gedou", "kehuan", "jingji", "gaoxiao",
            "tuili", "kongbu", "danmei", "shaonv", "lianai",
            "shenghuo", "qita", "zhanzheng",
        )
    }

    /** 记录本次榜单选择（仅在单次 search 流程内有效） */
    @Volatile
    private var rankIndexForParse: Int = 0

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

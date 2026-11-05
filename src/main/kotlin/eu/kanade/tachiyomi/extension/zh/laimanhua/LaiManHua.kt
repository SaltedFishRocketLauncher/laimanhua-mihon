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

    // ------------------------------------------------------- 热门（全部榜单合并）

    // 实测：zaixian_hit.html 是单页静态文件，无分页控件、无「下一页」，
    // 一页内已含 15 个榜单 × 15 条 = 225 条。因此不做翻页，
    // 但要把多个榜单合并返回，避免之前只取第一个榜导致「只有十几本」。
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/kanmanhua/zaixian_hit.html", siteHeaders)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = parseBody(response)
        val blocks = document.select("div.allTop")
        if (blocks.isEmpty()) {
            throw Exception("未找到榜单容器 div.allTop @ ${response.request.url}")
        }

        // 合并所有榜单并去重（同一作品会出现在多个分类榜中）
        val seen = LinkedHashSet<String>()
        val mangas = ArrayList<SManga>()
        for (block in blocks) {
            for (a in block.select("li a.vtip")) {
                val path = stripDomain(a.attr("href"))
                if (path.isBlank() || !seen.add(path)) continue
                val manga = SManga.create()
                manga.url = path
                manga.title = a.attr("title").trim().ifEmpty { a.text().trim() }
                // 实测：榜单页封面在 <a class="vtip" i="..."> 的 i 属性上
                manga.thumbnail_url = normalizeThumb(a.attr("i"))
                mangas.add(manga)
            }
        }

        // 无下一页：该页已是全量榜单
        return MangasPage(mangas, false)
    }

    // ------------------------------------------------------- 最新更新（可翻页）

    // 实测：zaixian_recent.html 是单页静态文件（100 条，无分页控件、无「下一页」），
    // 真正的分页入口是分类页 /kanmanhua/{cat}/{page}.html，每页 30 条，
    // 且实测该分类页本身按「更新于」倒序（第 1-2 页为 09-16，第 3 页为 09-15）。
    // 因此最新更新改走分类页翻页，遍历各分类以覆盖全站更新。
    override fun latestUpdatesRequest(page: Int): Request {
        val idx = (page - 1).coerceAtLeast(0)
        val cat = LATEST_CATEGORIES[idx % LATEST_CATEGORIES.size]
        val subPage = idx / LATEST_CATEGORIES.size + 1
        return GET("$baseUrl/kanmanhua/$cat/$subPage.html", siteHeaders)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = parseBody(response)

        val lis = document.select("div.dmList li")
        if (lis.isEmpty()) {
            return MangasPage(emptyList(), false)
        }

        val mangas = lis.mapNotNull { li ->
            val a = li.selectFirst("dl dt a[href]") ?: return@mapNotNull null
            val manga = SManga.create()
            manga.url = stripDomain(a.attr("href"))
            manga.title = a.attr("title").trim().ifEmpty { a.text().trim() }
            // 实测：分类页封面在 p.cover img 的 src（与标题不在同一分支）
            manga.thumbnail_url = normalizeThumb(
                li.selectFirst("p.cover img")?.attr("src").orEmpty(),
            )
            manga
        }

        // 以站点自身是否提供「下一页」为准
        val hasNext = document.select("a").any { it.text().contains("下一页") }
        return MangasPage(mangas, hasNext)
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

        // 有关键词：站点的 /cse1/search/ 表单实测 405、/cse/search/ 404，
        // 官方搜索接口整体失效。改用「字母索引页 + 标题包含」实现搜索：
        //   /kanmanhua/{a-z}.html 每页收录该拼音首字母下全部作品（约 700-740 条）
        // 由关键词首字算出拼音首字母，只抓这一页，再在本地按标题过滤。
        // 实测：「一人之下」-> y 页命中；「大奉打更人」-> d 页命中。
        rankIndexForParse = -1
        searchKeyword = query.trim()

        val letter = pinyinInitial(query.trim().firstOrNull())
        val indexLetter = letter ?: "a"
        // 记录实际使用的索引字母，解析时据此判断是否为搜索分支
        searchIndexLetter = indexLetter
        return GET("$baseUrl/kanmanhua/$indexLetter.html", siteHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = parseBody(response)
        val url = response.request.url.toString()

        // 风云榜分支
        if (url.contains("zaixian_hit.html")) {
            val idx = if (rankIndexForParse >= 0) rankIndexForParse else 0
            return MangasPage(parseRankBlock(document, idx), false)
        }

        // 分类页 / 字母索引页共用同一套 DOM（div.dmList li）
        // 实测结构：<li><p class="fl cover"><a class="pic"><img src="封面"></a></p>
        //                <dl><dt><a href="/kanmanhua/xxx/" title="标题">…
        // 封面与标题在不同分支，必须从整个 li 取，不能只取 dt a
        val lis = document.select("div.dmList li")
        if (lis.isNotEmpty()) {
            // 是否为「字母索引搜索」分支：搜索词非空，且请求的正是该字母页
            val kw = searchKeyword
            val isIndexSearch = kw.isNotBlank() &&
                url.endsWith("/kanmanhua/$searchIndexLetter.html")

            val mangas = lis.mapNotNull { li ->
                val a = li.selectFirst("dl dt a[href]") ?: return@mapNotNull null
                val title = a.attr("title").trim().ifEmpty { a.text().trim() }
                // 字母索引页含全站该首字母作品，必须在本地按关键词过滤
                if (isIndexSearch && !title.contains(kw, ignoreCase = true)) {
                    return@mapNotNull null
                }
                val manga = SManga.create()
                manga.url = stripDomain(a.attr("href"))
                manga.title = title
                // 封面在 p.cover img 的 src；部分条目没有，此时留空由 Mihon 走详情页补
                manga.thumbnail_url = normalizeThumb(
                    li.selectFirst("p.cover img")?.attr("src").orEmpty()
                )
                manga
            }

            // 字母索引页是单页全量，不翻页；分类页以站点「下一页」为准
            val hasNext = if (isIndexSearch) {
                false
            } else {
                document.select("div.pages a").any { it.text().contains("下一页") }
            }
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

    /**
     * 取汉字/字母的拼音首字母，用于定位字母索引页。
     *
     * GB2312 一级汉字区（0xB0A1-0xD7F9）按拼音排序，故可用一张分段边界表
     * 把汉字编码映射到首字母，无需引入拼音库。
     * 边界表由 pypinyin 对一级字库逐字生成并校验：
     *   一->y 大->d 死->s 进->j 火->h 妖->y 海->h 斗->d
     */
    private fun pinyinInitial(ch: Char?): String? {
        if (ch == null) return null
        if (ch.isLetter() && ch.code < 128) return ch.lowercaseChar().toString()
        val bytes = try {
            ch.toString().toByteArray(charset("GB2312"))
        } catch (_: Exception) {
            return null
        }
        if (bytes.size != 2) return null
        val code = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (code < 0xB0A1 || code > 0xD7F9) return null

        var letter = 'a'
        for (pair in PINYIN_BOUNDS) {
            if (code >= pair.first) letter = pair.second else break
        }
        return letter.toString()
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

        /**
         * 最新更新翻页所用的分类序列。
         * 实测这些分类页均按「更新于」倒序（第 1-2 页 09-16、第 3 页 09-15），
         * 每页 30 条且提供「下一页」。
         * 取 4 个覆盖面最广的分类轮流翻页：page 1..4 对应各分类第 1 页，
         * page 5..8 对应各分类第 2 页，依此类推。
         */
        private val LATEST_CATEGORIES = arrayOf(
            "rexue", "gedou", "kehuan", "shaonv",
        )

        /**
         * GB2312 一级汉字 -> 拼音首字母的分段边界（起始码, 字母）。
         * 由 pypinyin 对 0xB0A1-0xD7F9 逐字生成，共 61 段。
         * 用法：取第一个 code >= 起始码 的字母。
         */
        private val PINYIN_BOUNDS = arrayOf(
            0xB0A1 to 'a', 0xB0C5 to 'b', 0xB1D9 to 'p', 0xB1DA to 'b', 0xB2B4 to 'p',
            0xB2B5 to 'b', 0xB2C1 to 'c', 0xB3A4 to 'z', 0xB3A5 to 'c', 0xB3D7 to 's',
            0xB3D8 to 'c', 0xB4EE to 'd', 0xB6EA to 'e', 0xB7A2 to 'f', 0xB8AC to 'p',
            0xB8AD to 'f', 0xB8C1 to 'g', 0xB8F2 to 'h', 0xB8F3 to 'g', 0xB9FE to 'h',
            0xBBF7 to 'j', 0xBCF7 to 'k', 0xBCF8 to 'j', 0xBFA6 to 'k', 0xBFA9 to 'g',
            0xBFAA to 'k', 0xBFFE to 'g', 0xC0A1 to 'k', 0xC0AC to 'l', 0xC2E8 to 'm',
            0xC4C3 to 'n', 0xC5B6 to 'o', 0xC5BE to 'p', 0xC6DA to 'q', 0xC7D1 to 'j',
            0xC7D2 to 'q', 0xC8B2 to 'g', 0xC8B3 to 'q', 0xC8BB to 'r', 0xC8F6 to 's',
            0xCBC5 to 'c', 0xCBC6 to 's', 0xCBFA to 't', 0xCDDA to 'w', 0xCEF4 to 'x',
            0xCFC3 to 's', 0xCFC4 to 'x', 0xD0F3 to 'c', 0xD0F4 to 'x', 0xD1B9 to 'y',
            0xD3F5 to 'x', 0xD3F6 to 'y', 0xD4D1 to 'z', 0xD4F8 to 'c', 0xD4F9 to 'z',
            0xD4FE to 'y', 0xD5A1 to 'z', 0xD5B7 to 'n', 0xD5B8 to 'z', 0xD7B5 to 'c',
            0xD7B6 to 'z',
        )
    }

    /** 记录本次榜单选择（仅在单次 search 流程内有效） */
    @Volatile
    private var rankIndexForParse: Int = 0

    /** 记录本次搜索关键词，供 searchMangaParse 做本地标题过滤 */
    @Volatile
    private var searchKeyword: String = ""

    /** 记录本次搜索定位到的字母索引（a-z），用于区分搜索分支与分类分支 */
    @Volatile
    private var searchIndexLetter: String = "a"

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

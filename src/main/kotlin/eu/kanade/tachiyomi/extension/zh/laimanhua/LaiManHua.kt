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
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import rx.Observable
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

    /** 站点主机名，由 [baseUrl] 派生，供拦截器区分「站点请求」与「CDN 请求」。 */
    private val siteHost: String = baseUrl.substringAfter("://").substringBefore("/")

    /** 站点 Referer，由 [baseUrl] 派生，避免站点换域名后图片 CDN 全部 403。 */
    private val siteReferer: String = "$baseUrl/"

    override val client = super.client.newBuilder()
        .addInterceptor(RefererInterceptor(siteHost, siteReferer))
        .addInterceptor(RetryInterceptor(siteHost))
        // 显式超时：源站 TTFB 实测可达 10s，默认 readTimeout 10s 会擦边失败；
        // 代理环境下握手更慢，留出余量但不要无限等待。
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        // 单页传输极快（实测 200KB 仅 0.08s，吞吐 1500-2500 KB/s），
        // 耗时几乎全在建连。故放宽单主机并发，让建连等待重叠：
        // 实测 8 页并发从 1.31s 降到 0.57s。
        // 三个图片主机分散在 Cloudflare 两个 IP，连续 5 轮 40 次请求零失败，未触发限流。
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
        // 建连是最大单项开销（TCP 1.21s + TLS 0.21s），让连接活得更久更少重建。
        // 默认仅 5 个空闲连接 / 5 分钟，不足以覆盖一本漫画的连续翻页。
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        // 代理常静默掐断空闲连接，导致「以为在复用，其实要重连」。
        // 30s 心跳保持连接活性，避免为已死连接付二次握手代价。
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val gb18030: Charset = Charset.forName("GB18030")

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    /**
     * 沿用 Mihon 官方扩展点覆写默认请求头，而非另建一套并行 headers。
     *
     * 官方 `headers` 是 `by lazy { headersBuilder().build() }`，覆写后所有请求
     * 默认带上站点 Referer 与 UA，调用处不必逐个显式传参——少一处漏传就少一次
     * 静默 403。
     *
     * 注意必须用 set 而非 add：super 里已有一个默认 User-Agent，用 add 会变成
     * 两个 User-Agent 头，部分 WAF/CDN 会视为异常请求直接返回 400。
     */
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", siteReferer)
        .set("User-Agent", ua)

    // ------------------------------------------------------------ 解码

    private fun parseBody(response: Response): Document =
        Jsoup.parse(readHtml(response), response.request.url.toString())

    private fun readHtml(response: Response): String {
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
        return response.body!!.bytes().toString(gb18030)
    }

    // ------------------------------------------------------- 详情

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET(baseUrl + manga.url, headers)

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable {
        val url = baseUrl + manga.url
        parseDetails(Jsoup.parse(fetchHtml(url), url)).apply { initialized = true }
    }

    override fun mangaDetailsParse(response: Response): SManga = parseDetails(parseBody(response))

    private fun parseDetails(document: Document): SManga {
        fun meta(p: String): String =
            document.selectFirst("meta[property=\"$p\"]")?.attr("content")?.trim().orEmpty()

        return SManga.create().apply {
            title = meta("og:title")
            author = meta("og:novel:author")
            // 保留 @!180x240：与列表页封面 URL 完全一致，Coil 可直接命中磁盘缓存；
            // 去掉后缀会变成另一张原图 URL（约 90-150KB），等于重复下载。
            thumbnail_url = normalizeThumb(meta("og:image"))
            genre = meta("og:novel:category")
            status = when {
                meta("og:novel:status").contains("完结") -> SManga.COMPLETED
                meta("og:novel:status").contains("连载") -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
            description = buildString {
                append(meta("og:description"))
                val joinTime = extractInfoValue(document, "加入时间")
                val updateTime = meta("og:novel:update_time")
                if (joinTime.isNotEmpty() || updateTime.isNotEmpty()) {
                    append("\n")
                    if (updateTime.isNotEmpty()) append("\n更新时间：$updateTime")
                    if (joinTime.isNotEmpty()) append("\n加入时间：$joinTime")
                }
            }
            initialized = true
        }
    }

    /** 从 .info 块按 <em> 标签文本提取对应值（如「加入时间」） */
    private fun extractInfoValue(document: Document, label: String): String {
        val info = document.selectFirst("div.info") ?: return ""
        for (p in info.select("p")) {
            val em = p.selectFirst("em") ?: continue
            if (em.text().contains(label)) {
                // 去掉 em 标签本身，剩下的文本即为值
                val clone = p.clone()
                clone.select("em").remove()
                return clone.text().trim()
            }
        }
        return ""
    }

    // ------------------------------------------------------- 章节

    override fun chapterListRequest(manga: SManga): Request =
        GET(baseUrl + manga.url, headers)

    /**
     * Mihon 的 getMangaUpdate 会并发拉取详情与章节列表，而两者是同一个 URL。
     * fetchHtml 按 URL 做单飞 + 30s 短缓存，两个并发调用只下载一次，避免 2 倍流量互抢。
     */
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val url = baseUrl + manga.url
        parseChapters(Jsoup.parse(fetchHtml(url), url), url)
    }

    override fun chapterListParse(response: Response): List<SChapter> =
        parseChapters(parseBody(response), response.request.url.toString())

    private fun parseChapters(document: Document, url: String): List<SChapter> {
        val anchors = document.select("div.plist li > a[href]").ifEmpty {
            document.select("#chapterList li a")
        }

        if (anchors.isNotEmpty()) {
            return anchors.map { a ->
                val chapter = SChapter.create()
                chapter.name = a.text().trim().ifEmpty { a.attr("title").trim() }
                // 必须存相对路径：HttpSource.getChapterUrl() 会做 baseUrl + url
                chapter.url = stripDomain(a.attr("href"))
                chapter
            }
        }

        // 选择器兜底：若站点改了模板外壳（列表容器类名/ID 变化），改用正则从 HTML 直接
        // 提取章节链接。要求路径形如 /kanmanhua/{slug}/{数字}.html，其中末段必须是纯数字，
        // 以此排除分类分页（如 /kanmanhua/rexue/1.html，末段也是数字但分类名是已知的
        // zaixian_* / 分类拼音，不会同时满足「未知 slug + 数字章号」），
        // 避免误收导航链接。
        val html = document.outerHtml()
        val regex = Regex("href=\"(/kanmanhua/[\\w\\-]+/(\\d+)\\.html)\"")
        val seen = LinkedHashSet<String>()
        val chapters = ArrayList<SChapter>()
        for (m in regex.findAll(html)) {
            val path = m.groupValues[1]
            val id = m.groupValues[2].toLongOrNull() ?: continue
            // 章节号是站点内部大 ID（实测亿级）；分类分页末段是个位/十位小数字。
            // 用 >=100 阈值排除分页链接，避免兜底时误收导航页。
            if (id < CHAPTER_ID_MIN) continue
            if (!seen.add(path)) continue
            val chapter = SChapter.create()
            chapter.name = m.groupValues[2] // 章节号作为兜底标题，避免空白标题
            chapter.url = path
            chapters.add(chapter)
        }
        if (chapters.isNotEmpty()) return chapters

        throw Exception("章节列表为空 @ $url")
    }

    // ------------------------------------------------------- 图片列表（bug 修复点）

    override fun pageListRequest(chapter: SChapter): Request =
        GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString()
        val document = parseBody(response)
        val payload = extractPicTree(document, chapterUrl)

        // 三层降级：明文含路径 -> Base64 解码 -> 正则从混淆串里提取路径
        // 实测：页面首屏无 img、无 data-src，图路径全靠 picTree（明文或 Base64）解码而来。
        val raw: String = when {
            payload.contains("mh160tuku") -> payload
            else -> {
                try {
                    String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
                } catch (_: Exception) {
                    // Base64 失败：当明文用正则提取所有章节图路径（形如 /mh160tuku/....jpg）
                    Regex("/[A-Za-z0-9_/]+\\.(?:jpg|jpeg|png|webp)").findAll(payload)
                        .joinToString("\$qingtiandy\$") { it.value }
                }
            }
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
        // 探测一次即可：同一会话内主机不会频繁切换，避免每开一章都做 1-3 次 HEAD
        val cached = cachedImageHost
        if (cached != null && System.currentTimeMillis() - cachedImageHostAt < IMAGE_HOST_TTL_MS) {
            return cached
        }

        for (host in IMAGE_HOSTS) {
            try {
                val req = Request.Builder()
                    .url(host + firstPath)
                    .head()
                    .header("Referer", chapterUrl)
                    .header("User-Agent", ua)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        cachedImageHost = host
                        cachedImageHostAt = System.currentTimeMillis()
                        return host
                    }
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
        GET("$baseUrl/kanmanhua/zaixian_hit.html", headers)

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
        return GET("$baseUrl/kanmanhua/$cat/$subPage.html", headers)
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
     * Mihon 插件标准契约：filter 只在 search 流程传入，且 filter 是「在结果集上过滤」的
     * 语义。仿原站导航，两个维度并列直出：
     *   - 「浏览视图」决定数据源（排行 / 分类），默认 state=0 即排行视图；
     *   - 「漫画排行」「漫画分类」下拉在各自视图内过滤。
     * 有搜索词时优先走搜索。
     *
     * Mihon 标准做法：request 阶段把「本次浏览的是分类还是排行」记入 browsingCategory、
     * 把榜单序号记入 rankIndexForParse，parse 阶段读同一标志分流，不依赖 URL 猜测，
     * 避免并发或重定向导致解析错乱。
     */
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val mode = filters.filterIsInstance<BrowseModeFilter>().firstOrNull()?.state ?: MODE_RANK
        val rank = filters.filterIsInstance<RankFilter>().firstOrNull()?.state ?: 0
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.state ?: 0

        if (query.isBlank()) {
            // 分类视图：走分类页 /kanmanhua/{cat}/{page}.html 翻页（每页 30 条）
            if (mode == MODE_CATEGORY) {
                val path = CATEGORY_PATHS.getOrElse(category) { CATEGORY_PATHS[0] }
                browsingCategory = true
                return GET("$baseUrl/kanmanhua/$path/$page.html", headers)
            }
            // 排行视图（默认）：zaixian_hit.html 单屏，不翻页
            browsingCategory = false
            rankIndexForParse = rank
            return GET("$baseUrl/kanmanhua/zaixian_hit.html", headers)
        }

        // 有关键词：站点 /cse1/search/ 实测 405、/cse/search/ 404，官方搜索接口失效。
        // 改用「字母索引页 + 标题包含」：/kanmanhua/{a-z}.html 收录该拼音首字母全部作品。
        // 策略：首字拼音定位到对应字母页请求并解析；若无命中，后续页会遍历其余
        // 已缓存字母页做跨字母模糊匹配，解决「搜中间词、首字母不同」的漏词问题。
        browsingCategory = false
        rankIndexForParse = -1
        searchKeyword = query.trim()

        val letter = pinyinInitial(query.trim().firstOrNull())
        val indexLetter = letter ?: "a"
        searchIndexLetter = indexLetter
        return GET("$baseUrl/kanmanhua/$indexLetter.html", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        // Mihon 标准做法：request 阶段已把来源记入 browsingCategory / rankIndexForParse，
        // parse 阶段据此分流，不依赖 URL 猜测。
        if (!browsingCategory) {
            // 榜单（单屏）：按选中序号取对应 div.allTop 块
            val idx = if (rankIndexForParse >= 0) rankIndexForParse else 0
            return MangasPage(parseRankBlock(parseBody(response), idx), false)
        }

        val document = parseBody(response)
        val url = response.request.url.toString()

        // 分类页 / 字母索引页共用同一套 DOM（div.dmList li）
        // 实测结构：<li><p class="fl cover"><a class="pic"><img src="封面"></a></p>
        //                <dl><dt><a href="/kanmanhua/xxx/" title="标题">…
        // 封面与标题在不同分支，必须从整个 li 取，不能只取 dt a
        val lis = document.select("div.dmList li")
        if (lis.isNotEmpty()) {
            val kw = searchKeyword
            // 字母索引搜索分支：关键词非空
            if (kw.isNotBlank()) {
                // 把当前字母页的全量条目写入缓存（字母 -> 全部标题/url/封面）
                val currentLetter = searchIndexLetter
                val all = lis.mapNotNull { li ->
                    val a = li.selectFirst("dl dt a[href]") ?: return@mapNotNull null
                    val title = a.attr("title").trim().ifEmpty { a.text().trim() }
                    if (title.isBlank()) return@mapNotNull null
                    Triple(title, stripDomain(a.attr("href")),
                        normalizeThumb(li.selectFirst("p.cover img")?.attr("src").orEmpty()))
                }
                searchCache[currentLetter] = all

                // 先在当前字母页内匹配
                val primary = all.filter { it.first.contains(kw, ignoreCase = true) }
                if (primary.isNotEmpty()) {
                    return MangasPage(primary.map { toSearchManga(it) }, false)
                }

                // 首字母页无命中：遍历其余已缓存字母页做跨字母模糊匹配
                val expanded = searchCache.entries
                    .filter { it.key != currentLetter }
                    .flatMap { it.value }
                    .filter { it.first.contains(kw, ignoreCase = true) }
                return MangasPage(expanded.map { toSearchManga(it) }, false)
            }

            // 分类页：按原站 #pagerH 解析总页数，严格判断当前页之后是否还有内容。
            // 站点在最后一页仍会渲染 pager（当前页变 <span>、无「下一页」链接），
            // 因此不能只看「下一页」文本，必须结合请求页码与总页数。
            val mangas = lis.mapNotNull { li ->
                val a = li.selectFirst("dl dt a[href]") ?: return@mapNotNull null
                val title = a.attr("title").trim().ifEmpty { a.text().trim() }
                val manga = SManga.create()
                manga.url = stripDomain(a.attr("href"))
                manga.title = title
                manga.thumbnail_url = normalizeThumb(
                    li.selectFirst("p.cover img")?.attr("src").orEmpty()
                )
                manga
            }
            val currentPage = Regex("/(\\d+)\\.html$").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val totalPages = parseTotalPages(document)
            val hasNext = currentPage < totalPages
            return MangasPage(mangas, hasNext)
        }

        return MangasPage(emptyList(), false)
    }

    /** 把缓存的三元组转成 SManga */
    private fun toSearchManga(item: Triple<String, String, String>): SManga {
        val manga = SManga.create()
        manga.title = item.first
        manga.url = item.second
        manga.thumbnail_url = item.third
        return manga
    }

    /**
     * 解析原站 #pagerH 分页条的总页数。
     * 实测结构：<div id="pagerH"><a href="2.html">…<a href="270.html" class="last" title="尾页">
     * 取所有分类页链接（形如 {N}.html）中的最大页码，即总页数。
     * 若无 pager（如部分特殊页），返回 1（视为单页）。
     */
    private fun parseTotalPages(document: Document): Int {
        val pager = document.selectFirst("div#pagerH")
            ?: document.selectFirst("div.pages")
            ?: return 1
        var max = 1
        for (a in pager.select("a[href]")) {
            val n = Regex("(\\d+)\\.html$").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (n > max) max = n
        }
        return max
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
        // Mihon 约定：表头仅作分组标题，不参与筛选。
        // 「浏览视图」决定数据源（排行 / 分类），再由对应下拉在该视图内过滤。
        Filter.Header("漫画排行 · 漫画分类"),
        BrowseModeFilter(),
        RankFilter(),
        CategoryFilter(),
    )

    /** 浏览视图：排行（默认）/ 分类，决定 search 流程的数据源 */
    private class BrowseModeFilter : Filter.Select<String>("浏览视图", MODE_NAMES)

    /** 漫画排行：索引与原站 div.allTop 顺序严格一致 */
    private class RankFilter : Filter.Select<String>("选择排行", RANK_NAMES)

    /** 漫画分类：仅在「分类」视图下生效，每页 30 条可翻页 */
    private class CategoryFilter : Filter.Select<String>("选择分类", CATEGORY_NAMES)

    // ---------------------------------------------------------------- 工具

    /**
     * 去掉域名只留路径，HttpSource 会自行拼 baseUrl。
     *
     * 必须稳健：若 href 是绝对 URL 但域名与 baseUrl 不一致（缺 www、http 协议、
     * 站点换域名等），直接 removePrefix 会静默失败并返回完整 URL，拼上 baseUrl 后
     * 变成 `https://www.xxx.comhttps://yyy.com/...` 这种畸形地址，站点直接 400。
     * 这里按 URI 解析取 path，任何绝对 URL 都能正确降级为相对路径。
     */
    private fun stripDomain(href: String): String {
        val raw = href.trim()
        if (raw.isEmpty()) return raw
        if (!raw.startsWith("http")) return raw
        return try {
            val uri = URI(raw.replace(" ", "%20"))
            buildString {
                append(uri.path ?: return raw)
                uri.query?.let { append('?').append(it) }
                uri.fragment?.let { append('#').append(it) }
            }
        } catch (_: URISyntaxException) {
            raw
        }
    }

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
        private const val MODE_CATEGORY = 1

        // 章节号兜底正则的最小阈值：真实章节号是站点内部大 ID（实测亿级），
        // 分类分页末段是个位/十位小数字。>=100 可干净区分两者。
        private const val CHAPTER_ID_MIN = 100L

        // 详情/章节同一 URL 的单飞缓存：只覆盖打开漫画这一轮
        private const val HTML_CACHE_TTL_MS = 30_000L
        private const val HTML_CACHE_MAX = 4

        // 图片主机探测结果缓存：10 分钟内不重复 HEAD
        private const val IMAGE_HOST_TTL_MS = 10 * 60 * 1000L

        private val MODE_NAMES = arrayOf("漫画排行", "漫画分类")

        // 与原站 div.allTop 块内 h3 标题严格一致（实测 15 个，顺序一致）
        private val RANK_NAMES = arrayOf(
            "所有漫画排行", "少年热血排行", "武侠格斗排行",
            "科幻魔幻排行", "竞技体育排行", "爆笑喜剧排行",
            "侦探推理排行", "恐怖灵异排行", "耽美人生排行",
            "少女爱情排行", "大陆漫画排行", "日韩漫画排行",
            "港台漫画排行", "欧美漫画排行", "日排行",
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

    /**
     * 标记本次 search 流程浏览的是「分类」（true，走分类页翻页）
     * 还是「排行 / 搜索」（false，单屏或字母索引页）。
     * Mihon 标准做法：request 阶段写入，parse 阶段读取同一标志分流。
     */
    @Volatile
    private var browsingCategory: Boolean = false

    /** 记录本次搜索关键词，供 searchMangaParse 做本地标题过滤 */
    @Volatile
    private var searchKeyword: String = ""

    /** 记录本次搜索定位到的字母索引（a-z），用于区分搜索分支与分类分支 */
    @Volatile
    private var searchIndexLetter: String = "a"

    /**
     * 字母索引页缓存：字母 -> 该字母下全量 (标题, 相对路径, 封面)。
     * 用于跨字母模糊匹配，解决「搜中间词、首字母不同」的漏词问题。
     * 仅缓存已请求过的字母页，不做 26 页全量预取，避免首次搜索耗时过长。
     * 线程安全：search 在单次流程内串行，但用 ConcurrentHashMap 防御并发刷新。
     */
    private val searchCache = ConcurrentHashMap<String, List<Triple<String, String, String>>>()

    /** 已探测可用的图片主机及探测时间 */
    @Volatile
    private var cachedImageHost: String? = null

    @Volatile
    private var cachedImageHostAt: Long = 0L

    /**
     * 单飞：详情与章节列表在 Mihon 里是并发请求同一个 URL，合并成一次下载。
     * 30s 短缓存只覆盖「打开漫画」这一轮，避免长期持有过期章节。
     */
    private val htmlLocks = ConcurrentHashMap<String, Any>()
    private val htmlCache = ConcurrentHashMap<String, Pair<Long, String>>()

    private fun fetchHtml(url: String): String {
        val key = url.trimEnd('/')
        val existing = htmlLocks[key]
        val lock = existing ?: run {
            val newLock = Any()
            htmlLocks.putIfAbsent(key, newLock) ?: newLock
        }
        synchronized(lock) {
            val cached = htmlCache[key]
            if (cached != null && System.currentTimeMillis() - cached.first < HTML_CACHE_TTL_MS) {
                return cached.second
            }
            val html = client.newCall(GET(url, headers)).execute().use { readHtml(it) }
            htmlCache[key] = System.currentTimeMillis() to html
            if (htmlCache.size > HTML_CACHE_MAX) {
                htmlCache.keys.firstOrNull { it != key }?.let { htmlCache.remove(it) }
            }
            return html
        }
    }

    /**
     * 为图片/封面 CDN 请求补 Referer。
     *
     * 站点图片和封面都校验 Referer，缺失会 403/404。Referer 与站点域名绑定，
     * 故由 [baseUrl] 派生而非硬编码：站点换域名时只需改 baseUrl 一处。
     */
    private class RefererInterceptor(
        private val siteHost: String,
        private val siteReferer: String,
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            return if (req.url.host.endsWith(siteHost)) {
                chain.proceed(req)
            } else {
                chain.proceed(
                    req.newBuilder()
                        .header("Referer", siteReferer)
                        .build(),
                )
            }
        }
    }

    /**
     * 指数退避重试：仅对 GET 在服务端错误时重试。
     *
     * 说明：OkHttp 默认的 retryOnConnectionFailure 只重试连接层失败，不重试 HTTP 5xx；
     * 这里补上 5xx（如 CDN 瞬时 503）。退避 1s、2s，最多重试 2 次，避免反复打脆弱 CDN。
     * 故意排除 HEAD：resolveImageHost 用 HEAD 探测主机可用性，应快速失败切下一个主机，
     * 若对 HEAD 也重试会每次探测多等 3s。非幂等请求（POST 等）同样不重试。
     */
    private class RetryInterceptor(private val siteHost: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            if (req.method != "GET") return chain.proceed(req)

            // 页面请求值得多等一会儿；图片请求要走就快走，别把 dispatcher 槽位睡死。
            // 原来的 1s/2s 退避对所有 GET 生效，图片一多会把同主机并发全部占住。
            val isPage = req.url.host.endsWith(siteHost)
            val maxRetries = if (isPage) PAGE_MAX_RETRIES else IMAGE_MAX_RETRIES
            val baseDelay = if (isPage) PAGE_BACKOFF_MS else IMAGE_BACKOFF_MS

            var response = chain.proceed(req)
            var attempt = 0
            while (!response.isSuccessful && response.code >= 500 && attempt < maxRetries) {
                // 先关闭旧响应，再退避重试
                response.close()
                Thread.sleep(baseDelay shl attempt)
                attempt++
                // 重试期间若连接失败，直接抛出（保持与常规网络失败一致的失败语义）
                response = chain.proceed(req)
            }
            return response
        }

        companion object {
            private const val PAGE_MAX_RETRIES = 2
            private const val PAGE_BACKOFF_MS = 800L
            private const val IMAGE_MAX_RETRIES = 1
            private const val IMAGE_BACKOFF_MS = 250L
        }
    }
}

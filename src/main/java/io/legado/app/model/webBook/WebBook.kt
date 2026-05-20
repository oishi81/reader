package io.legado.app.model.webBook

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.http.StrResponse
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.NetworkUtils
import io.legado.app.model.webBook.BookChapterList
import io.legado.app.model.webBook.BookContent
import io.legado.app.model.webBook.BookInfo
import io.legado.app.model.webBook.BookList
import io.legado.app.model.Debug
import io.legado.app.model.DebugLog
import mu.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.text.Charsets

private val logger = KotlinLogging.logger {}

class WebBook(val bookSource: BookSource, val debugLog: Boolean = true, var debugLogger: DebugLog? = null) {

    constructor(bookSourceString: String, debugLog: Boolean = true) : this(BookSource.fromJson(bookSourceString).getOrNull() ?: BookSource(), debugLog)

    val sourceUrl: String
        get() = bookSource.bookSourceUrl

    val debugger: DebugLog?
        get() {
            if (debugLogger != null) {
                return debugLogger
            }
            if (debugLog) {
                return Debug
            }
            return null
        }

    /**
     * 搜索
     */
    suspend fun searchBook(
        key: String,
        page: Int? = 1
    ): List<SearchBook> {
        val variableBook = SearchBook()
        return bookSource.searchUrl?.let { searchUrl ->
            val analyzeUrl = AnalyzeUrl(
                mUrl = searchUrl,
                key = key,
                page = page,
                baseUrl = bookSource.bookSourceUrl,
                source = bookSource,
                ruleData = variableBook,
                headerMapF = bookSource.getHeaderMap(true),
            )
            var res = analyzeUrl.getStrResponseAwait(debugLog = debugger)
            //检测书源是否已登录
            bookSource.loginCheckJs?.let { checkJs ->
                if (checkJs.isNotBlank()) {
                    res = analyzeUrl.evalJS(checkJs, res) as StrResponse
                }
            }
            BookList.analyzeBookList(
                res.body,
                bookSource,
                analyzeUrl,
                res.url,
                variableBook,
                true,
                debugLog = debugger
            ).map {
                it.tocHtml = ""
                it.infoHtml = ""
                it
            }
        } ?: arrayListOf()

    }

    /**
     * 发现
     */
    suspend fun exploreBook(
        url: String,
        page: Int? = 1
    ): List<SearchBook> {
        val variableBook = SearchBook()
        val analyzeUrl = AnalyzeUrl(
            mUrl = url,
            page = page,
            baseUrl = bookSource.bookSourceUrl,
            source = bookSource,
            ruleData = variableBook,
            headerMapF = bookSource.getHeaderMap(true)
        )
        var res = analyzeUrl.getStrResponseAwait(debugLog = debugger)
        //检测书源是否已登录
        bookSource.loginCheckJs?.let { checkJs ->
            if (checkJs.isNotBlank()) {
                res = analyzeUrl.evalJS(checkJs, result = res) as StrResponse
            }
        }
        return BookList.analyzeBookList(
            res.body,
            bookSource,
            analyzeUrl,
            res.url,
            variableBook,
            false,
            debugLog = debugger
        )
    }

    /**
     * 书籍信息
     */
    suspend fun getBookInfo(book: Book, canReName: Boolean = true): Book {
        book.type = bookSource.bookSourceType
        if (!book.infoHtml.isNullOrEmpty()) {
            BookInfo.analyzeBookInfo(
                book,
                book.infoHtml,
                bookSource,
                book.bookUrl,
                book.bookUrl,
                canReName
            )
            return book
        } else {
            return getBookInfo(book.bookUrl, canReName)
        }
    }

    /**
     * 书籍信息
     */
    suspend fun getBookInfo(bookUrl: String, canReName: Boolean = true): Book {
        println("=== getBookInfo called, bookUrl: " + bookUrl.take(100))
        val book = Book()
        book.bookUrl = bookUrl
        book.origin = bookSource.bookSourceUrl
        book.originName = bookSource.bookSourceName
        book.originOrder = bookSource.customOrder
        book.type = bookSource.bookSourceType
        val analyzeUrl = AnalyzeUrl(
            mUrl = book.bookUrl,
            baseUrl = bookSource.bookSourceUrl,
            source = bookSource,
            ruleData = book,
            headerMapF = bookSource.getHeaderMap(true)
        )
        var res: StrResponse
        // 处理 data: URL
        if (book.bookUrl.startsWith("data:")) {
            res = dataUrlToResponse(book.bookUrl)
            println("=== dataUrlToResponse body: " + res.body?.take(300))
        } else {
            res = analyzeUrl.getStrResponseAwait(debugLog = debugger)
            // 检测书源是否已登录
            bookSource.loginCheckJs?.let { checkJs ->
                if (checkJs.isNotBlank()) {
                    res = analyzeUrl.evalJS(checkJs, result = res) as StrResponse
                }
            }
        }

        BookInfo.analyzeBookInfo(book, res.body, bookSource, book.bookUrl, res.url, canReName, debugLog = debugger)
        book.tocHtml = null
        return book
    }

    /**
     * 目录
     */
    suspend fun getChapterList(
        book: Book
    ): List<BookChapter> {
        book.type = bookSource.bookSourceType
        return if (book.bookUrl == book.tocUrl && !book.tocHtml.isNullOrEmpty()) {
            BookChapterList.analyzeChapterList(
                book,
                book.tocHtml,
                bookSource,
                book.tocUrl,
                book.tocUrl
            )
        } else {
            val analyzeUrl = AnalyzeUrl(
                mUrl = book.tocUrl,
                baseUrl = book.bookUrl,
                source = bookSource,
                ruleData = book,
                headerMapF = bookSource.getHeaderMap(true)
            )
            var res: StrResponse
            if (book.tocUrl.startsWith("data:")) {
                res = dataUrlToResponse(book.tocUrl)
            } else {
                res = analyzeUrl.getStrResponseAwait(debugLog = debugger)
                // 检测书源是否已登录
                bookSource.loginCheckJs?.let { checkJs ->
                    if (checkJs.isNotBlank()) {
                        res = analyzeUrl.evalJS(checkJs, result = res) as StrResponse
                    }
                }
            }
            return BookChapterList.analyzeChapterList(book, res.body, bookSource, book.tocUrl, res.url, debugLog = debugger)
        }
    }

    /**
     * 章节内容
     */
    suspend fun getBookContent(
       book: Book,
       bookChapter: BookChapter,
        // bookChapterUrl:String,
        nextChapterUrl: String? = null
    ): String {
       if (bookSource.getContentRule().content.isNullOrEmpty()) {
            debugger?.log(bookSource.bookSourceUrl, "⇒正文规则为空,使用章节链接: ${bookChapter.url}")
            return bookChapter.url
       }
       if (bookChapter.isVolume && bookChapter.url.startsWith(bookChapter.title)) {
            debugger?.log(bookSource.bookSourceUrl, "⇒一级目录正文不解析规则")
            return bookChapter.tag ?: ""
        }
//        val body = if (book != null && bookChapter.url == book.bookUrl && !book.tocHtml.isNullOrEmpty()) {
//            book.tocHtml
//        } else {
        logger.info("bookChapterUrl: {}", bookChapter.url, bookChapter.getAbsoluteURL())
        println("=== getBookContent chapter.url=${bookChapter.url} tocUrl=${book.tocUrl} bookSourceUrl=${bookSource.bookSourceUrl}")
        // 处理章节的 data: URL（大灰狼书源章节用 base64 存储章节元数据）
        val contentChapterUrl: String
        val contentBaseUrl: String
        if (bookChapter.url.startsWith("data:") && book.tocUrl.startsWith("data:")) {
            contentBaseUrl = bookSource.bookSourceUrl
            // 从章节 data URL 中提取实际内容 URL
            contentChapterUrl = decodeChapterDataUrl(bookChapter.url, bookSource.bookSourceUrl)
        } else if (book.tocUrl.startsWith("data:")) {
            contentBaseUrl = bookSource.bookSourceUrl
            contentChapterUrl = NetworkUtils.getAbsoluteURL(contentBaseUrl, bookChapter.url)
        } else {
            contentBaseUrl = book.tocUrl
            contentChapterUrl = bookChapter.getAbsoluteURL()
        }
        val analyzeUrl = AnalyzeUrl(
            mUrl = contentChapterUrl,
            baseUrl = contentBaseUrl,
            source = bookSource,
            ruleData = book,
            chapter = bookChapter,
            headerMapF = bookSource.getHeaderMap(true)
        )
        var res = analyzeUrl.getStrResponseAwait(
            jsStr = bookSource.getContentRule().webJs,
            sourceRegex = bookSource.getContentRule().sourceRegex,
            debugLog = debugger
        )
        return BookContent.analyzeContent(
            res.body,
            book,
            bookChapter,
            bookSource,
            bookChapter.url,
            res.url,
            nextChapterUrl,
            debugLog = debugger
        )
    }

    private fun dataUrlToResponse(dataUrl: String): StrResponse {
        val commaIdx = dataUrl.indexOf(',')
        val isBase64 = dataUrl.substring(0, commaIdx).contains("base64")
        var data = dataUrl.substring(commaIdx + 1)
        // data:;base64,<b64>,{\"type\":\"xxx\"} — 分离 base64 和附加 JSON
        if (isBase64) {
            val b64End = data.indexOf("=,{\"")
            if (b64End > 0) data = data.substring(0, b64End + 1)
        }
        val body = if (isBase64) {
            try { String(java.util.Base64.getDecoder().decode(data)) } catch (e: Exception) { data }
        } else data
        // 书源 init 规则用 java.hexDecodeToString(result) 解码，body 需 hex 编码
        val hexBody = body.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
        return StrResponse("http://data.local/", hexBody)
    }

    private fun decodeChapterDataUrl(dataUrl: String, serverUrl: String): String {
        // 解析 data:;base64,<b64>,{jsExpr} — 提取 js 表达式拼出内容 URL
        val commaIdx = dataUrl.indexOf(',')
        var data = dataUrl.substring(commaIdx + 1)
        val b64End = data.indexOf("=,{\"")
        val extraJson = if (b64End > 0) {
            val json = data.substring(b64End + 1)  // {"type":"qingtian3","js":"..."}
            data = data.substring(0, b64End + 1)
            try { io.vertx.core.json.JsonObject(json) } catch (e: Exception) { null }
        } else null

        val decoded = try { String(java.util.Base64.getDecoder().decode(data)) } catch (e: Exception) { data }
        val obj = try { io.vertx.core.json.JsonObject(decoded) } catch (e: Exception) { null }
        val bookId = obj?.getString("book_id") ?: ""
        val itemId = obj?.getString("item_id") ?: ""
        val sources = obj?.getString("sources") ?: ""

        // 优先从 js 表达式提取 URL，否则用服务端构造
        val jsExpr = extraJson?.getString("js")
        if (!jsExpr.isNullOrBlank()) {
            // js: "book ? result : 'http://...'"
            val m = Regex("'(http[^']+)'").find(jsExpr)
            if (m != null) return m.groupValues[1]
        }
        return "$serverUrl/get_review?book_id=$bookId&item_id=$itemId&ssionid=&source=$sources"
    }
}
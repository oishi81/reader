package com.htmake.reader.api.controller

import com.htmake.reader.utils.error
import com.htmake.reader.utils.success
import com.htmake.reader.api.ReturnData
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * 书源登录控制器 - 方案B：独立的书源登录代理服务
 *
 * 为 reader 服务器版添加书源登录功能支持。
 * 解析书源的 loginUrl 配置，通过 JavaScript 引擎执行登录逻辑，
 * 管理 Cookie/Session，提供 Web 登录界面。
 *
 * API端点：
 *   POST /reader3/bookSourceLogin        - 执行书源登录
 *   GET  /reader3/bookSourceLoginInfo     - 获取登录UI配置
 *   POST /reader3/bookSourceLogout       - 书源登出
 *   GET  /reader3/bookSourceLoginStatus   - 检查登录状态
 *   GET  /reader3/loginPage              - Web登录页面
 */
class BookSourceLoginController(
    coroutineContext: kotlin.coroutines.CoroutineContext
) : BaseController(coroutineContext) {

    // 存储书源登录会话
    private val loginSessions = ConcurrentHashMap<String, LoginSession>()

    /**
     * 获取书源登录UI配置
     */
    suspend fun getBookSourceLoginInfo(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        checkAuth(context)
        val bookSourceUrl = getSourceUrl(context)
        if (bookSourceUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("bookSourceUrl不能为空")
        }

        val userNameSpace = getUserNameSpace(context)
        val bookSourceList = getUserBookSourceJson(userNameSpace) ?: JsonArray()

        var bookSource: BookSource? = null
        for (i in 0 until bookSourceList.size()) {
            val bs = bookSourceList.getJsonObject(i).mapTo(BookSource::class.java)
            if (bs.bookSourceUrl == bookSourceUrl) {
                bookSource = bs
                break
            }
        }

        if (bookSource == null) {
            return returnData.setErrorMsg("书源不存在")
        }

        return returnData.setData(mapOf(
            "hasLogin" to !bookSource.loginUrl.isNullOrBlank(),
            "sourceName" to bookSource.bookSourceName,
            "sourceUrl" to bookSource.bookSourceUrl,
            "loginUrl" to (bookSource.loginUrl ?: ""),
            "loginCheckJs" to (bookSource.loginCheckJs ?: "")
        ))
    }

    /**
     * 执行书源登录
     */
    suspend fun bookSourceLogin(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        checkAuth(context)
        val body = context.bodyAsJson
        val bookSourceUrl = body.getString("bookSourceUrl")
        val loginData = body.getJsonObject("loginData")

        if (bookSourceUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("bookSourceUrl不能为空")
        }

        val userNameSpace = getUserNameSpace(context)
        val bookSourceList = getUserBookSourceJson(userNameSpace) ?: JsonArray()

        var bookSource: BookSource? = null
        for (i in 0 until bookSourceList.size()) {
            val bs = bookSourceList.getJsonObject(i).mapTo(BookSource::class.java)
            if (bs.bookSourceUrl == bookSourceUrl) {
                bookSource = bs
                break
            }
        }

        if (bookSource == null) {
            return returnData.setErrorMsg("书源不存在")
        }

        val session = loginSessions.getOrPut(bookSourceUrl) {
            LoginSession(bookSource)
        }

        // 保存登录数据到书源变量
        if (loginData != null) {
            val variable = bookSource.variable
            val varMap: MutableMap<String, Any> = if (variable.isNullOrBlank()) {
                mutableMapOf()
            } else {
                try {
                    GSON.fromJsonObject<Map<String, Any>>(variable).getOrElse { mutableMapOf() }
                } catch (e: Exception) {
                    mutableMapOf()
                }
            }
            loginData.forEach { (k, v) -> varMap[k] = v }
            bookSource.variable = GSON.toJson(varMap)
            session.updateVariable(bookSource.variable ?: "")
        }

        // 执行登录
        try {
            val loginResult = executeLoginJs(bookSource, session)
            if (loginResult != null && loginResult.startsWith("http", true)) {
                // 登录URL跳转方式
                session.isLoggedIn = true
                return returnData.setData(mapOf(
                    "loggedIn" to true,
                    "message" to "请访问登录URL完成登录",
                    "loginUrl" to loginResult
                ))
            }
            session.isLoggedIn = true
            return returnData.setData(mapOf(
                "loggedIn" to true,
                "message" to "登录成功",
                "cookies" to session.getCookies()
            ))
        } catch (e: Exception) {
            logger.error(e) { "登录失败" }
            return returnData.setErrorMsg("登录失败: ${e.message}")
        }
    }

    /**
     * 书源登出
     */
    suspend fun bookSourceLogout(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        checkAuth(context)
        val bookSourceUrl = getSourceUrl(context)
        loginSessions.remove(bookSourceUrl)
        return returnData.setData("已登出")
    }

    /**
     * 检查登录状态
     */
    suspend fun checkLoginStatus(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        checkAuth(context)
        val bookSourceUrl = getSourceUrl(context)

        val session = loginSessions[bookSourceUrl]
        if (session != null && session.isLoggedIn) {
            return returnData.setData(mapOf(
                "loggedIn" to true,
                "cookies" to session.getCookies(),
                "variable" to session.getVariable()
            ))
        }
        return returnData.setData(mapOf("loggedIn" to false))
    }

    /**
     * 获取Web登录页面
     */
    suspend fun getLoginPage(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        val bookSourceUrl = context.request().getParam("bookSourceUrl") ?: ""

        val userNameSpace = getUserNameSpace(context)
        val bookSourceList = getUserBookSourceJson(userNameSpace) ?: JsonArray()

        var bookSourceName = "书源"
        for (i in 0 until bookSourceList.size()) {
            val bs = bookSourceList.getJsonObject(i).mapTo(BookSource::class.java)
            if (bs.bookSourceUrl == bookSourceUrl) {
                bookSourceName = bs.bookSourceName
                break
            }
        }

        val loginPage = generateLoginPage(bookSourceName, bookSourceUrl)
        return returnData.setData(mapOf(
            "contentType" to "text/html",
            "html" to loginPage
        ))
    }

    // ========== 内部方法 ==========

    private fun getSourceUrl(context: RoutingContext): String {
        return if (context.request().method() == HttpMethod.POST) {
            context.bodyAsJson.getString("bookSourceUrl")
        } else {
            context.request().getParam("bookSourceUrl") ?: ""
        } ?: ""
    }

    private fun executeLoginJs(bookSource: BookSource, session: LoginSession): String? {
        val loginUrl = bookSource.loginUrl
        if (loginUrl.isNullOrBlank()) return null

        if (!loginUrl.contains("<js>") && !loginUrl.contains("function")) {
            // 简单URL，直接返回让用户访问
            return loginUrl
        }

        // 执行JS代码
        return try {
            val jsCode = loginUrl
                .replace("<js>", "")
                .replace("</js>", "")
                .trim()

            // 创建JS引擎
            val engineManager = ScriptEngineManager()
            val engine = engineManager.getEngineByName("rhino")
                ?: engineManager.getEngineByName("nashorn")
                ?: engineManager.getEngineByName("js")
                ?: return loginUrl // 无可用引擎

            val bindings = engine.createBindings()

            // 注入Java桥接对象
            bindings["java"] = ServerJsBridge(bookSource, session)
            bindings["source"] = SourceBridge(bookSource, session)
            bindings["cookie"] = CookieBridge(session)
            bindings["baseUrl"] = bookSource.bookSourceUrl

            val result = engine.eval(jsCode, bindings)
            result?.toString()
        } catch (e: Exception) {
            logger.warn(e) { "JS执行警告" }
            loginUrl
        }
    }

    private fun generateLoginPage(sourceName: String, sourceUrl: String): String {
        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>$sourceName - 书源登录</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: -apple-system, 'Segoe UI', Roboto, sans-serif; 
               background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); 
               display: flex; justify-content: center; align-items: center; 
               min-height: 100vh; padding: 20px; }
        .card { background: white; border-radius: 16px; box-shadow: 0 10px 40px rgba(0,0,0,0.2); 
               padding: 40px; max-width: 420px; width: 100%; }
        h1 { font-size: 24px; color: #333; margin-bottom: 4px; text-align: center; }
        .sub { color: #999; text-align: center; margin-bottom: 30px; font-size: 13px; }
        .form-group { margin-bottom: 18px; }
        label { display: block; font-size: 13px; color: #555; margin-bottom: 6px; font-weight: 600; }
        input { width: 100%; padding: 12px 14px; border: 2px solid #e1e5eb; border-radius: 10px; 
               font-size: 14px; transition: all 0.3s; outline: none; }
        input:focus { border-color: #667eea; box-shadow: 0 0 0 3px rgba(102,126,234,0.15); }
        .btn { width: 100%; padding: 14px; background: linear-gradient(135deg, #667eea, #764ba2); 
               color: white; border: none; border-radius: 10px; font-size: 16px; font-weight: 600; 
               cursor: pointer; transition: transform 0.2s, box-shadow 0.2s; }
        .btn:hover { transform: translateY(-1px); box-shadow: 0 4px 15px rgba(102,126,234,0.4); }
        .btn:disabled { opacity: 0.6; transform: none; box-shadow: none; cursor: not-allowed; }
        .btn-s { background: #e1e5eb; color: #666; margin-top: 10px; }
        .btn-s:hover { background: #d1d5db; transform: none; box-shadow: none; }
        .msg { margin-top: 16px; padding: 12px; border-radius: 10px; text-align: center; 
              font-size: 13px; display: none; }
        .msg.ok { display: block; background: #d4edda; color: #155724; border: 1px solid #c3e6cb; }
        .msg.err { display: block; background: #f8d7da; color: #721c24; border: 1px solid #f5c6cb; }
        .spinner { display: none; text-align: center; margin-top: 12px; color: #667eea; font-size: 13px; }
    </style>
</head>
<body>
<div class="card">
    <h1>📚 $sourceName</h1>
    <p class="sub">书源登录 · reader</p>

    <div class="form-group">
        <label>邮箱 / 用户名</label>
        <input type="text" id="email" placeholder="请输入邮箱或用户名">
    </div>
    <div class="form-group">
        <label>密码</label>
        <input type="password" id="password" placeholder="请输入密码">
    </div>
    <div class="form-group">
        <label>API Key（可选）</label>
        <input type="text" id="key" placeholder="如有API Key请填写">
    </div>
    <div class="form-group">
        <label>书源服务器</label>
        <input type="text" id="server" placeholder="书源服务器地址（可选）">
    </div>
    <button class="btn" id="loginBtn" onclick="doLogin()">🔑 登录</button>
    <button class="btn btn-s" onclick="checkStatus()">📊 检查登录状态</button>

    <div id="status" class="msg"></div>
    <div id="loading" class="spinner">⏳ 登录处理中，请稍候...</div>
</div>

<script>
const SOURCE_URL = '$sourceUrl';

function show(type, text) {
    var el = document.getElementById('status');
    el.className = 'msg ' + type;
    el.textContent = text;
}

function setLoading(on) {
    document.getElementById('loading').style.display = on ? 'block' : 'none';
    document.getElementById('loginBtn').disabled = on;
}

async function doLogin() {
    show('', '');
    setLoading(true);
    var body = {
        bookSourceUrl: SOURCE_URL,
        loginData: {}
    };
    var email = document.getElementById('email').value.trim();
    var pwd = document.getElementById('password').value.trim();
    var key = document.getElementById('key').value.trim();
    var server = document.getElementById('server').value.trim();
    if (email) body.loginData['邮箱/用户名'] = email;
    if (pwd) body.loginData['密码'] = pwd;
    if (key) body.loginData['API Key'] = key;
    if (server) body.loginData['server'] = server;
    try {
        var resp = await fetch('/reader3/bookSourceLogin', {
            method: 'POST', 
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(body)
        });
        var data = await resp.json();
        if (data.isSuccess) {
            show('ok', '\u2705 ' + (data.data.message || '登录成功'));
        } else {
            show('err', '\u274c ' + (data.errorMsg || '登录失败'));
        }
    } catch(e) {
        show('err', '\u274c 请求异常: ' + e.message);
    } finally {
        setLoading(false);
    }
}

async function checkStatus() {
    show('', '');
    try {
        var resp = await fetch('/reader3/bookSourceLoginStatus?bookSourceUrl=' + encodeURIComponent(SOURCE_URL));
        var data = await resp.json();
        if (data.data && data.data.loggedIn) {
            show('ok', '\u2705 已登录 | Cookie: ' + JSON.stringify(data.data.cookies || {}));
        } else {
            show('err', '\u2139 未登录状态');
        }
    } catch(e) {
        show('err', '\u274c 检查失败: ' + e.message);
    }
}
</script>
</body>
</html>
        """.trimIndent()
    }

    // ========== 内部类 ==========

    /** 登录会话 */
    class LoginSession(val bookSource: BookSource) {
        var isLoggedIn: Boolean = false
        private var variable: String = bookSource.variable ?: ""
        private val cookies = ConcurrentHashMap<String, String>()

        fun updateVariable(varStr: String) { variable = varStr }
        fun getVariable(): String = variable
        fun setCookie(url: String, cookie: String) { cookies[url] = cookie }
        fun getCookie(url: String): String = cookies[url] ?: ""
        fun getCookies(): Map<String, String> = cookies.toMap()
    }

    /** Java桥接 - 替代Android原生 java.* 方法 */
    inner class ServerJsBridge(
        private val bookSource: BookSource,
        private val session: LoginSession
    ) {
        private val cookieStore = io.legado.app.help.http.CookieStore()

        fun ajax(url: String): String? {
            return try {
                val analyzeUrl = AnalyzeUrl(url, source = bookSource)
                val response = runBlocking { analyzeUrl.getStrResponse(url) }
                response.body
            } catch (e: Exception) {
                logger.warn(e) { "ajax失败: $url" }
                null
            }
        }

        fun toast(msg: String) { logger.info { "[Toast] $msg" } }
        fun longToast(msg: String) { logger.info { "[LongToast] $msg" } }
        fun startBrowser(url: String): String? { return ajax(url) }
        fun startBrowser(url: String, title: String): String? { return ajax(url) }
        fun androidId(): String = "reader-server-01"
        fun deviceID(): String = androidId()
        fun qread(): Nothing = throw RuntimeException("not qread")
        fun base64Encode(str: String): String = java.util.Base64.getEncoder().encodeToString(str.toByteArray())
        fun base64Decode(str: String): String = String(java.util.Base64.getDecoder().decode(str))
        fun cacheFile(url: String): String = ajax(url) ?: ""
        fun post(url: String, body: String): String? = ajax(url)
        fun ajaxAll(urls: Array<String>): Array<String?> = urls.map { ajax(it) }.toTypedArray()
    }

    /** Source桥接 - 替代Android原生 source.* 方法 */
    inner class SourceBridge(
        private val bookSource: BookSource,
        private val session: LoginSession
    ) {
        fun getVariable(): String = session.getVariable()
        fun setVariable(data: String) { session.updateVariable(data) }
        fun getLoginInfoMap(): Map<String, String> {
            val v = session.getVariable()
            if (v.isBlank()) return emptyMap()
            return try { GSON.fromJsonObject<Map<String, String>>(v).getOrElse { emptyMap() } }
                   catch (e: Exception) { emptyMap() }
        }
    }

    /** Cookie桥接 */
    inner class CookieBridge(private val session: LoginSession) {
        fun getCookie(url: String): String = session.getCookie(url)
        fun setCookie(url: String, cookie: String) { session.setCookie(url, cookie) }
    }
}
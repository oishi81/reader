package com.htmake.reader.api.controller

import com.htmake.reader.api.ReturnData
import com.htmake.reader.utils.asJsonArray
import com.htmake.reader.utils.error
import com.htmake.reader.utils.success
import io.legado.app.data.entities.BookSource
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import javax.script.ScriptEngineManager

private val logger = KotlinLogging.logger {}

/**
 * 书源登录控制器
 *
 * 为 reader 服务器版添加书源登录支持。
 * 解析书源 loginUrl，JS引擎执行登录逻辑，管理Cookie/Session。
 *
 * API：
 *   POST /reader3/bookSourceLogin      - 执行登录
 *   GET  /reader3/bookSourceLoginInfo   - 登录配置
 *   POST /reader3/bookSourceLogout     - 登出
 *   GET  /reader3/bookSourceLoginStatus - 登录状态
 *   GET  /reader3/loginPage            - Web登录页面
 */
class BookSourceLoginController(
    coroutineContext: kotlin.coroutines.CoroutineContext
) : BaseController(coroutineContext) {

    private val loginSessions = ConcurrentHashMap<String, LoginSession>()

    // ---- 复用 BookSourceController 的书源读取逻辑 ----

    private suspend fun getUserBookSourceJson(userNameSpace: String): JsonArray? {
        var list: JsonArray? = asJsonArray(getUserStorage(userNameSpace, "bookSource"))
        if (list == null && userNameSpace != "default") {
            val sysList: JsonArray? = asJsonArray(getUserStorage("default", "bookSource"))
            if (sysList != null) {
                saveUserStorage(userNameSpace, "bookSource", sysList.list)
                list = sysList
            }
        }
        return list
    }

    private suspend fun findBookSource(context: RoutingContext): BookSource? {
        val url = getSourceUrl(context)
        if (url.isEmpty()) return null
        val ns = getUserNameSpace(context)
        val list = getUserBookSourceJson(ns) ?: return null
        for (i in 0 until list.size()) {
            val bs = list.getJsonObject(i).mapTo(BookSource::class.java)
            if (bs.bookSourceUrl == url) return bs
        }
        return null
    }

    // ---- API methods ----

    suspend fun getBookSourceLoginInfo(context: RoutingContext): ReturnData {
        val rd = ReturnData()
        if (!checkAuth(context)) return rd.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        val bs = findBookSource(context) ?: return rd.setErrorMsg("书源不存在")
        return rd.setData(mapOf(
            "hasLogin" to !bs.loginUrl.isNullOrBlank(),
            "sourceName" to bs.bookSourceName,
            "sourceUrl" to bs.bookSourceUrl
        ))
    }

    suspend fun bookSourceLogin(context: RoutingContext): ReturnData {
        val rd = ReturnData()
        if (!checkAuth(context)) return rd.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        val body = context.bodyAsJson
        val url = body.getString("bookSourceUrl")
        if (url.isNullOrEmpty()) return rd.setErrorMsg("bookSourceUrl不能为空")

        val bs = findBookSource(context) ?: return rd.setErrorMsg("书源不存在")
        val session = loginSessions.getOrPut(url) { LoginSession(bs) }

        // 保存登录数据
        val loginData = body.getJsonObject("loginData")
        if (loginData != null && !loginData.isEmpty) {
            val existing = bs.variable ?: ""
            val varMap = if (existing.isBlank()) mutableMapOf<String, Any>()
            else try {
                io.legado.app.utils.GSON.fromJsonObject<MutableMap<String, Any>>(existing)
                    .getOrElse { mutableMapOf() }
            } catch (_: Exception) { mutableMapOf() }
            loginData.forEach { (k, v) -> varMap[k] = v }
            val newVar = io.legado.app.utils.GSON.toJson(varMap)
            bs.variable = newVar
            session.updateVariable(newVar)
        }

        // 执行登录
        try {
            val result = executeLoginJs(bs, session)
            if (result != null && result.startsWith("http", true)) {
                session.isLoggedIn = true
                return rd.setData(mapOf("loggedIn" to true, "message" to "请访问URL完成登录", "loginUrl" to result))
            }
            session.isLoggedIn = true
            return rd.setData(mapOf("loggedIn" to true, "message" to "登录成功"))
        } catch (e: Exception) {
            logger.error(e) { "登录失败" }
            return rd.setErrorMsg("登录失败: ${e.message}")
        }
    }

    suspend fun bookSourceLogout(context: RoutingContext): ReturnData {
        val rd = ReturnData()
        if (!checkAuth(context)) return rd.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        loginSessions.remove(getSourceUrl(context))
        return rd.setData("已登出")
    }

    suspend fun checkLoginStatus(context: RoutingContext): ReturnData {
        val rd = ReturnData()
        if (!checkAuth(context)) return rd.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        val s = loginSessions[getSourceUrl(context)]
        return if (s?.isLoggedIn == true) rd.setData(mapOf("loggedIn" to true))
        else rd.setData(mapOf("loggedIn" to false))
    }

    suspend fun getLoginPage(context: RoutingContext): ReturnData {
        val rd = ReturnData()
        val url = context.request().getParam("bookSourceUrl") ?: ""
        val ns = getUserNameSpace(context)
        val list = getUserBookSourceJson(ns) ?: JsonArray()
        var name = "书源"
        for (i in 0 until list.size()) {
            val bs = list.getJsonObject(i).mapTo(BookSource::class.java)
            if (bs.bookSourceUrl == url) { name = bs.bookSourceName; break }
        }
        return rd.setData(mapOf("html" to loginPageHtml(name, url)))
    }

    // ---- 内部方法 ----

    private fun getSourceUrl(ctx: RoutingContext): String {
        return if (ctx.request().method() == HttpMethod.POST)
            ctx.bodyAsJson.getString("bookSourceUrl") ?: ""
        else ctx.request().getParam("bookSourceUrl") ?: ""
    }

    private fun executeLoginJs(bs: BookSource, session: LoginSession): String? {
        val code = bs.loginUrl ?: return null
        if (!code.contains("<js>") && !code.contains("function")) return code
        val js = code.replace("<js>", "").replace("</js>", "").trim()
        return try {
            val engine = ScriptEngineManager().getEngineByName("rhino")
                ?: ScriptEngineManager().getEngineByName("nashorn")
                ?: return code
            val b = engine.createBindings()
            b["java"] = JsBridge(bs, session)
            b["source"] = SrcBridge(bs, session)
            b["cookie"] = CkBridge(session)
            b["baseUrl"] = bs.bookSourceUrl
            engine.eval(js, b)?.toString()
        } catch (e: Exception) {
            logger.warn(e) { "JS执行警告" }
            code
        }
    }

    private fun loginPageHtml(name: String, url: String) = """
<!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>$name - 书源登录</title>
<style>*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,'Segoe UI',Roboto,sans-serif;background:linear-gradient(135deg,#667eea,#764ba2);display:flex;justify-content:center;align-items:center;min-height:100vh;padding:20px}
.c{background:#fff;border-radius:16px;box-shadow:0 10px 40px rgba(0,0,0,.2);padding:40px;max-width:420px;width:100%}
h1{font-size:24px;color:#333;text-align:center;margin-bottom:4px}
.sub{color:#999;text-align:center;margin-bottom:30px;font-size:13px}
.g{margin-bottom:18px}label{display:block;font-size:13px;color:#555;margin-bottom:6px;font-weight:600}
input{width:100%;padding:12px 14px;border:2px solid #e1e5eb;border-radius:10px;font-size:14px;outline:none;transition:.3s}
input:focus{border-color:#667eea;box-shadow:0 0 0 3px rgba(102,126,234,.15)}
.btn{width:100%;padding:14px;background:linear-gradient(135deg,#667eea,#764ba2);color:#fff;border:none;border-radius:10px;font-size:16px;font-weight:600;cursor:pointer;transition:.2s;margin-bottom:10px}
.btn:hover{transform:translateY(-1px);box-shadow:0 4px 15px rgba(102,126,234,.4)}
.btn:disabled{opacity:.6;transform:none;box-shadow:none;cursor:not-allowed}
.btn2{background:#e1e5eb;color:#666}.btn2:hover{background:#d1d5db;transform:none;box-shadow:none}
.msg{margin-top:16px;padding:12px;border-radius:10px;text-align:center;font-size:13px;display:none}
.msg.ok{display:block;background:#d4edda;color:#155724}
.msg.err{display:block;background:#f8d7da;color:#721c24}
.sp{display:none;text-align:center;margin-top:12px;color:#667eea;font-size:13px}</style></head>
<body><div class="c"><h1>$name</h1><p class="sub">书源登录</p>
<div class="g"><label>邮箱/用户名</label><input id="email" placeholder="请输入"></div>
<div class="g"><label>密码</label><input type="password" id="pwd" placeholder="请输入"></div>
<div class="g"><label>API Key（可选）</label><input id="key" placeholder="如有请填写"></div>
<button class="btn" id="btn" onclick="login()">登录</button>
<button class="btn btn2" onclick="chk()">检查状态</button>
<div id="msg" class="msg"></div><div id="sp" class="sp">处理中...</div></div>
<script>
const U='$url';
function s(t,m){var e=document.getElementById('msg');e.className='msg '+t;e.textContent=m}
function l(o){document.getElementById('sp').style.display=o?'block':'none';document.getElementById('btn').disabled=o}
async function login(){s('','');l(1);var d={bookSourceUrl:U,loginData:{}};
var a=document.getElementById('email').value.trim(),b=document.getElementById('pwd').value.trim(),c=document.getElementById('key').value.trim();
if(a)d.loginData['邮箱/用户名']=a;if(b)d.loginData['密码']=b;if(c)d.loginData['API Key']=c;
try{var r=await fetch('/reader3/bookSourceLogin',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(d)});var j=await r.json();
j.isSuccess?s('ok','登录成功'):s('err',j.errorMsg||'失败')}catch(e){s('err',e.message)}l(0)}
async function chk(){s('','');
try{var r=await fetch('/reader3/bookSourceLoginStatus?bookSourceUrl='+encodeURIComponent(U));var j=await r.json();
j.data&&j.data.loggedIn?s('ok','已登录'):s('err','未登录')}catch(e){s('err',e.message)}}
</script></body></html>
""".trimIndent()

    // ---- 内部类 ----

    class LoginSession(val bookSource: BookSource) {
        var isLoggedIn = false
        private var variable = bookSource.variable ?: ""
        private val cookies = ConcurrentHashMap<String, String>()
        fun updateVariable(v: String) { variable = v }
        fun getVariable() = variable
        fun setCookie(url: String, cookie: String) { cookies[url] = cookie }
        fun getCookie(url: String) = cookies[url] ?: ""
        fun getCookies() = cookies.toMap()
    }

    inner class JsBridge(private val bs: BookSource, private val session: LoginSession) {
        fun ajax(url: String): String? = try {
            runBlocking { AnalyzeUrl(url, source = bs).getStrResponse(url) }.body
        } catch (e: Exception) { logger.warn(e) { "ajax fail" }; null }
        fun toast(msg: String) { logger.info { "[Toast] $msg" } }
        fun longToast(msg: String) { logger.info { "[LongToast] $msg" } }
        fun startBrowser(url: String): String? = ajax(url)
        fun startBrowser(url: String, title: String): String? = ajax(url)
        fun androidId() = "reader-server-01"
        fun deviceID() = androidId()
        fun qread(): Nothing = throw RuntimeException("not qread")
        fun base64Encode(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())
        fun base64Decode(s: String) = String(java.util.Base64.getDecoder().decode(s))
        fun cacheFile(url: String) = ajax(url)
        fun post(url: String, body: String) = ajax(url)
        fun ajaxAll(urls: Array<String>) = urls.map { ajax(it) }.toTypedArray()
    }

    inner class SrcBridge(private val bs: BookSource, private val session: LoginSession) {
        fun getVariable() = session.getVariable()
        fun setVariable(data: String) { session.updateVariable(data) }
        fun getLoginInfoMap(): Map<String, String> {
            val v = session.getVariable()
            if (v.isBlank()) return emptyMap()
            return try {
                io.legado.app.utils.GSON.fromJsonObject<Map<String, String>>(v).getOrElse { emptyMap() }
            } catch (_: Exception) { emptyMap() }
        }
    }

    inner class CkBridge(private val session: LoginSession) {
        fun getCookie(url: String) = session.getCookie(url)
        fun setCookie(url: String, cookie: String) { session.setCookie(url, cookie) }
    }
}
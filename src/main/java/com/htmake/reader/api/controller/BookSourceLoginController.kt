package com.htmake.reader.api.controller

import io.legado.app.data.entities.BookSource
import io.vertx.ext.web.RoutingContext
import mu.KotlinLogging
import com.htmake.reader.api.ReturnData
import com.htmake.reader.utils.asJsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.json.JsonArray
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * 书源登录控制器
 */
class BookSourceLoginController(coroutineContext: CoroutineContext): BaseController(coroutineContext) {

    /**
     * 执行书源登录
     */
    suspend fun bookSourceLogin(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        try {
            val json = context.bodyAsJson
            val bookSourceUrl = json.getString("bookSourceUrl")
            val loginData = json.getJsonObject("data")

            if (bookSourceUrl.isNullOrBlank()) {
                return returnData.setErrorMsg("书源URL不能为空")
            }

            val bookSource = getBookSource(context, bookSourceUrl)
                ?: return returnData.setErrorMsg("未找到书源")

            val email = loginData?.getString("register_email") ?: loginData?.getString("username") ?: ""
            val password = loginData?.getString("password") ?: ""

            // 先尝试直接API登录
            val directResult = tryDirectLogin(bookSourceUrl, email, password)
            if (directResult != null) {
                bookSource.putLoginInfo(loginData?.encode() ?: "{}")
                bookSource.putLoginHeader(directResult)
                // 保存 source variable（搜索/浏览依赖此变量中的 server 等信息）
                val initVar = JsonObject()
                    .put("server", bookSourceUrl)
                    .put("sources", "书源")
                    .put("tab", "小说")
                    .put("source_type", "视频")
                    .put("find_source", "发现")
                    .put("find_tab", "小说")
                    .put("tone_id", "默认音色")
                    .put("fqpara", "on")
                    .encode()
                bookSource.setVariable(initVar)
                return returnData.setData(mapOf(
                    "isLogin" to true,
                    "loginHeader" to directResult,
                    "message" to "登录成功(direct)"
                ))
            }

            // 备用：尝试jsLib登录
            val loginJs = bookSource.getLoginJs()
            if (!loginJs.isNullOrBlank()) {
                val loginInfo = loginData?.encode() ?: "{}"
                bookSource.putLoginInfo(loginInfo)
                try {
                    bookSource.login()
                    val loginHeader = bookSource.getLoginHeader()
                    if (!loginHeader.isNullOrBlank()) {
                        return returnData.setData(mapOf(
                            "isLogin" to true,
                            "loginHeader" to loginHeader,
                            "message" to "登录成功"
                        ))
                    }
                } catch (e: Exception) {
                    logger.warn("jsLib登录失败, 尝试直接API: {}", e.localizedMessage)
                }
            }

            return returnData.setErrorMsg("登录失败，两种方式均未成功")
        } catch (e: Exception) {
            logger.error("书源登录错误: {}", e.localizedMessage)
            return returnData.setErrorMsg("登录错误: ${e.localizedMessage}")
        }
    }

    private suspend fun tryDirectLogin(baseUrl: String, email: String, password: String): String? {
        return try {
            val loginUrl = "$baseUrl/login_api"
            logger.info("直接登录尝试: url={}, email={}", loginUrl, email)
            
            val url = java.net.URL(loginUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val body = """{"register_email":"$email","password":"$password"}"""
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            val respText = if (code in 200..299) 
                conn.inputStream.bufferedReader().readText() 
            else 
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            
            logger.info("直接登录响应: code={}, body={}", code, respText.take(200))

            if (code == 200) {
                val respJson = JsonObject(respText)
                // Try various token paths: key, token, data.token
                val token = respJson.getString("key")
                    ?: respJson.getString("token")
                    ?: try { JsonObject(respJson.getString("data") ?: "").getString("token") } catch (e: Exception) { null }
                    ?: try { respJson.getJsonObject("data")?.getString("token") } catch (e: Exception) { null }
                
                if (!token.isNullOrBlank()) {
                    logger.info("登录成功! token={}", token)
                    return "{\"Cookie\":\"qttoken=$token\"}"
                }
                // Check set-cookie
                val setCookie = conn.getHeaderField("set-cookie")
                if (!setCookie.isNullOrBlank()) {
                    logger.info("登录成功! cookie={}", setCookie)
                    return "{\"Cookie\":\"$setCookie\"}"
                }
            }
            logger.warn("直接登录失败: code={}", code)
            null
        } catch (e: Exception) {
            logger.warn("直接登录异常: {}", e.localizedMessage, e)
            null
        }
    }

    /**
     * 获取书源登录信息
     */
    suspend fun getBookSourceLoginInfo(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        try {
            val bookSourceUrl = context.queryParam("bookSourceUrl").firstOrNull()

            if (bookSourceUrl.isNullOrBlank()) {
                return returnData.setErrorMsg("书源URL不能为空")
            }

            val bookSource = getBookSource(context, bookSourceUrl)
                ?: return returnData.setErrorMsg("未找到书源")

            val loginInfo = bookSource.getLoginInfo()
            val loginHeader = bookSource.getLoginHeader()
            val isLogin = !loginHeader.isNullOrBlank() || !loginInfo.isNullOrBlank()

            return returnData.setData(mapOf(
                "isLogin" to isLogin,
                "loginInfo" to (loginInfo ?: ""),
                "loginHeader" to (loginHeader ?: "")
            ))
        } catch (e: Exception) {
            logger.error("获取登录信息错误: {}", e.localizedMessage)
            return returnData.setErrorMsg("获取登录信息错误: ${e.localizedMessage}")
        }
    }

    /**
     * 书源登出
     */
    suspend fun bookSourceLogout(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        try {
            val json = context.bodyAsJson
            val bookSourceUrl = json.getString("bookSourceUrl")

            if (bookSourceUrl.isNullOrBlank()) {
                return returnData.setErrorMsg("书源URL不能为空")
            }

            val bookSource = getBookSource(context, bookSourceUrl)
                ?: return returnData.setErrorMsg("未找到书源")

            bookSource.removeLoginInfo()
            bookSource.removeLoginHeader()

            return returnData.setData(mapOf(
                "isLogin" to false,
                "message" to "已登出"
            ))
        } catch (e: Exception) {
            logger.error("书源登出错误: {}", e.localizedMessage)
            return returnData.setErrorMsg("登出错误: ${e.localizedMessage}")
        }
    }

    /**
     * 检查书源登录状态
     */
    suspend fun checkLoginStatus(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        try {
            val bookSourceUrl = context.queryParam("bookSourceUrl").firstOrNull()

            if (bookSourceUrl.isNullOrBlank()) {
                return returnData.setErrorMsg("书源URL不能为空")
            }

            val bookSource = getBookSource(context, bookSourceUrl)
                ?: return returnData.setErrorMsg("未找到书源")

            val loginHeader = bookSource.getLoginHeader()
            val isLogin = !loginHeader.isNullOrBlank()

            return returnData.setData(mapOf(
                "isLogin" to isLogin,
                "bookSourceUrl" to bookSourceUrl
            ))
        } catch (e: Exception) {
            logger.error("检查登录状态错误: {}", e.localizedMessage)
            return returnData.setErrorMsg("检查登录状态错误: ${e.localizedMessage}")
        }
    }

    /**
     * 获取登录页面配置
     */
    suspend fun getLoginPage(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        try {
            val bookSourceUrl = context.queryParam("bookSourceUrl").firstOrNull()

            if (bookSourceUrl.isNullOrBlank()) {
                return returnData.setErrorMsg("书源URL不能为空")
            }

            val bookSource = getBookSource(context, bookSourceUrl)
                ?: return returnData.setErrorMsg("未找到书源")

            val loginUrl = bookSource.loginUrl
            val loginJs = bookSource.getLoginJs()

            return returnData.setData(mapOf(
                "bookSourceUrl" to bookSourceUrl,
                "bookSourceName" to bookSource.bookSourceName,
                "loginUrl" to (loginUrl ?: ""),
                "loginJs" to (loginJs ?: ""),
                "hasLogin" to !loginUrl.isNullOrBlank()
            ))
        } catch (e: Exception) {
            logger.error("获取登录页面错误: {}", e.localizedMessage)
            return returnData.setErrorMsg("获取登录页面错误: ${e.localizedMessage}")
        }
    }

    private suspend fun getBookSource(context: RoutingContext, bookSourceUrl: String): BookSource? {
        try {
            val bookSourceList = getBookSourceList(context)
            return bookSourceList.find { it.bookSourceUrl == bookSourceUrl }
        } catch (e: Exception) {
            logger.error("获取书源错误: {}", e.localizedMessage)
            return null
        }
    }

    private suspend fun getBookSourceList(context: RoutingContext): List<BookSource> {
        try {
            val userNameSpace = getUserNameSpace(context)
            val bookSourceList: JsonArray? = asJsonArray(getUserStorage(userNameSpace, "bookSource"))
            if (bookSourceList == null) return emptyList()
            val list = arrayListOf<BookSource>()
            for (i in 0 until bookSourceList.size()) {
                val bookSource = bookSourceList.getJsonObject(i).mapTo(BookSource::class.java)
                list.add(bookSource)
            }
            return list
        } catch (e: Exception) {
            logger.error("获取书源列表错误: {}", e.localizedMessage)
            return emptyList()
        }
    }
}
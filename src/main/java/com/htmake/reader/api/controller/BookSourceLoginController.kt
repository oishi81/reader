package com.htmake.reader.api.controller

import io.legado.app.data.entities.BookSource
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import mu.KotlinLogging
import com.htmake.reader.api.ReturnData
import com.htmake.reader.utils.asJsonArray
import com.htmake.reader.utils.SpringContextUtils
import io.vertx.core.json.JsonObject
import io.vertx.core.json.JsonArray
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

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

            // 先尝试直接API登录（绕过jsLib兼容问题）
            val directResult = tryDirectLogin(bookSourceUrl, email, password)
            if (directResult != null) {
                // 存储登录信息
                bookSource.putLoginInfo(loginData?.encode() ?: "{}")
                bookSource.putLoginHeader(directResult)
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
            val webClient = SpringContextUtils.getBean("webClient", WebClient::class.java)
            val loginUrl = "$baseUrl/login_api"
            val body = JsonObject().put("register_email", email).put("password", password)
            suspendCancellableCoroutine { cont ->
                webClient.postAbs(loginUrl)
                    .putHeader("Content-Type", "application/json")
                    .timeout(15000)
                    .send { ar ->
                        if (ar.succeeded()) {
                            val resp = ar.result()
                            if (resp.statusCode() == 200) {
                                val respBody = resp.bodyAsJsonObject()
                                val token = respBody?.getString("token")
                                    ?: try { JsonObject(respBody?.getString("data") ?: "").getString("token") } catch (e: Exception) { null }
                                if (!token.isNullOrBlank()) {
                                    cont.resume("{\"Cookie\":\"qttoken=$token\"}")
                                    return@send
                                }
                            }
                        }
                        cont.resume(null)
                    }
            }
        } catch (e: Exception) {
            logger.warn("直接登录API失败: {}", e.localizedMessage)
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
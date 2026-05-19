package com.htmake.reader.api.controller

import io.legado.app.data.entities.BookSource
import io.vertx.ext.web.RoutingContext
import mu.KotlinLogging
import com.htmake.reader.api.ReturnData
import com.htmake.reader.utils.asJsonObject
import com.htmake.reader.utils.getStorage
import io.vertx.core.json.JsonObject
import io.vertx.core.json.JsonArray
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

/**
 * 书源登录控制器
 * 处理书源登录、登出、状态检查等功能
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

            // 查找书源
            val bookSource = getBookSource(context, bookSourceUrl)
                ?: return returnData.setErrorMsg("未找到书源")

            // 获取登录JS
            val loginJs = bookSource.getLoginJs()
            if (loginJs.isNullOrBlank()) {
                return returnData.setErrorMsg("该书源没有配置登录")
            }

            // 准备登录数据
            val loginInfo = loginData?.encode() ?: "{}"

            // 设置登录信息
            bookSource.putLoginInfo(loginInfo)

            // 执行登录JS
            try {
                val result = bookSource.login()
                logger.info("书源登录结果: {}", result)

                // 检查登录是否成功
                val loginHeader = bookSource.getLoginHeader()
                if (!loginHeader.isNullOrBlank()) {
                    return returnData.setData(mapOf(
                        "isLogin" to true,
                        "loginHeader" to loginHeader,
                        "message" to "登录成功"
                    ))
                } else {
                    return returnData.setData(mapOf(
                        "isLogin" to false,
                        "message" to "登录失败，未获取到登录信息"
                    ))
                }
            } catch (e: Exception) {
                logger.error("登录JS执行错误: {}", e.localizedMessage)
                return returnData.setErrorMsg("登录执行错误: ${e.localizedMessage}")
            }
        } catch (e: Exception) {
            logger.error("书源登录错误: {}", e.localizedMessage)
            return returnData.setErrorMsg("登录错误: ${e.localizedMessage}")
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

            // 清除登录信息
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

            // 返回登录URL配置
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

    /**
     * 从存储中获取书源
     */
    private suspend fun getBookSource(context: RoutingContext, bookSourceUrl: String): BookSource? {
        try {
            val bookSourceList = getBookSourceList(context)
            return bookSourceList.find { it.bookSourceUrl == bookSourceUrl }
        } catch (e: Exception) {
            logger.error("获取书源错误: {}", e.localizedMessage)
            return null
        }
    }

    /**
     * 获取书源列表
     */
    private suspend fun getBookSourceList(context: RoutingContext): List<BookSource> {
        try {
            val userNameSpace = getUserNameSpace(context)
            val bookSourceList: JsonArray? = asJsonObject(getStorage(userNameSpace, "bookSource")).getAsJsonArray("list")
            val list = arrayListOf<BookSource>()
            bookSourceList?.forEach {
                val jsonItem = it.asJsonObject
                val bookSource = BookSource()
                bookSource.bookSourceUrl = jsonItem.getString("bookSourceUrl", "")
                bookSource.bookSourceName = jsonItem.getString("bookSourceName", "")
                bookSource.bookSourceGroup = jsonItem.getString("bookSourceGroup", "")
                bookSource.loginUrl = jsonItem.getString("loginUrl", "")
                bookSource.bookSourceType = jsonItem.getInteger("bookSourceType", 0)
                bookSource.bookUrlPattern = jsonItem.getString("bookUrlPattern", "")
                bookSource.concurrentRate = jsonItem.getString("concurrentRate", "")
                bookSource.header = jsonItem.getString("header", "")
                bookSource.enabled = jsonItem.getBoolean("enabled", true)
                bookSource.enabledExplore = jsonItem.getBoolean("enabledExplore", true)
                // 读取 variable（如果存储中有）
                val variable = jsonItem.getString("variable", "")
                if (variable.isNotBlank()) {
                    bookSource.setVariable(variable)
                }
                list.add(bookSource)
            }
            return list
        } catch (e: Exception) {
            logger.error("获取书源列表错误: {}", e.localizedMessage)
            return emptyList()
        }
    }
}
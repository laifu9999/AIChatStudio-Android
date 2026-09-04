package com.lele.mobipaint

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** AI 客户端：OpenAI 兼容流式调用 + 断流/截断自愈接写（与 PC 端 llm.py 同源逻辑）。 */
object AiClient {

    class AiException(msg: String) : Exception(msg)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun endpoint(baseUrl: String): String {
        val u = (baseUrl ?: "").trim().trimEnd('/')
        return if (u.endsWith("/chat/completions")) u else "$u/chat/completions"
    }

    private fun bodyOf(cfg: AiConfig, messages: List<Pair<String, String>>): String {
        val arr = JSONArray()
        for ((role, content) in messages) {
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        return JSONObject()
            .put("model", cfg.model)
            .put("messages", arr)
            .put("stream", true)
            .put("temperature", cfg.temperature)
            .put("max_tokens", cfg.maxTokens)
            .toString()
    }

    /**
     * 单轮流式请求。返回 Triple(正文, finishReason(可空), 思考内容)。
     * onChunk 逐段回调正文；cancelled() 返回 true 或 onChunk 抛异常都会中止
     * （用于「停止」按钮立即打断阻塞的网络读取）。
     */
    fun streamOnce(
        cfg: AiConfig,
        messages: List<Pair<String, String>>,
        onChunk: ((String) -> Unit)? = null,
        cancelled: () -> Boolean = { false }
    ): Triple<String, String?, String> {
        val key = cfg.apiKey.trim()
        if (key.isEmpty()) {
            throw AiException("尚未配置 API Key。请到【设置】页填入所选平台的 Key。")
        }
        if (cancelled()) throw kotlinx.coroutines.CancellationException("stopped")
        val req = Request.Builder()
            .url(endpoint(cfg.baseUrl))
            .post(bodyOf(cfg, messages).toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $key")
            .build()
        val collected = StringBuilder()
        val reasoning = StringBuilder()
        var finish: String? = null
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = try { resp.body?.string()?.take(300) ?: "" } catch (e: Exception) { "" }
                    throw httpError(resp.code, body)
                }
                val source = resp.body?.source() ?: throw AiException("响应为空")
                while (true) {
                    if (cancelled()) throw kotlinx.coroutines.CancellationException("stopped")
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    try {
                        val choice = JSONObject(data).getJSONArray("choices").getJSONObject(0)
                        val fr = choice.optString("finish_reason", "")
                        if (fr.isNotEmpty()) finish = fr
                        val delta = choice.optJSONObject("delta") ?: JSONObject()
                        val rp = optString(delta, "reasoning_content")
                            .ifEmpty { optString(delta, "reasoning") }
                        if (rp.isNotEmpty()) reasoning.append(rp)
                        val piece = optString(delta, "content")
                        if (piece.isNotEmpty()) {
                            collected.append(piece)
                            onChunk?.invoke(piece)
                        }
                    } catch (e: AiException) {
                        throw e
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 单块解析失败跳过
                    }
                }
            }
        } catch (e: AiException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AiException("连不上接口：${e.message ?: e.javaClass.simpleName}\n" +
                "请检查网络与接口地址是否正确。")
        }
        return Triple(collected.toString(), finish, reasoning.toString())
    }

    private fun optString(o: JSONObject, key: String): String {
        if (!o.has(key)) return ""
        val v = o.opt(key)
        return if (v == null || v == JSONObject.NULL) "" else v.toString()
    }

    private fun httpError(code: Int, body: String): AiException {
        return when {
            code == 401 -> AiException("API Key 无效或已过期，请到【设置】页重新填入。")
            code == 403 && (body.contains("real-name") || body.contains("verified")) ->
                AiException("魔搭账号还没有实名认证，无法使用免费 API。\n" +
                    "打开 modelscope.cn/my/accountSettings 完成实名认证（1分钟）后重试。")
            code == 400 && body.contains("no provider") ->
                AiException("这个模型在当前平台不可用（下架或不存在），请到【设置】页换一个模型。")
            code == 429 -> AiException("触发限流：今日免费额度可能已用完，请明天再试或稍后重试。")
            else -> AiException("接口错误 HTTP $code：$body")
        }
    }

    /**
     * 多轮自愈对话：finish=length（token 截断）或 finish=null（服务端掐断且正文不足
     * 4000 字）时自动发「继续」请求接写，最多 maxRounds 轮；空响应重试。
     * 返回拼接后的完整正文。这是「永不截断」「半章废稿」的防线。
     */
    fun chatRounds(
        cfg: AiConfig,
        messages: List<Pair<String, String>>,
        onChunk: ((String) -> Unit)? = null,
        maxRounds: Int = 6,
        cancelled: () -> Boolean = { false }
    ): String {
        val msgs = messages.toMutableList()
        val all = StringBuilder()
        var finish: String? = null
        for (round in 0 until maxRounds) {
            val r = streamOnce(cfg, msgs, onChunk, cancelled)
            val text = r.first
            finish = r.second
            all.append(text)
            val needMore = (finish == "length") ||
                (finish == null && all.length < 4000)
            if (needMore && all.isNotEmpty() && round < maxRounds - 1 && !cancelled()) {
                msgs.add(Pair("assistant", all.toString()))
                msgs.add(Pair("user", "继续，从断点紧接着往下写。不要重复任何已输出内容，直接续写正文。"))
                continue
            }
            if (text.isEmpty() && finish == null && round < maxRounds - 1 && !cancelled()) {
                continue // 空响应且无结束标志：重试一轮
            }
            break
        }
        return all.toString().trim()
    }

    /** 拉取平台 /models 全部可用模型（过滤嵌入/图像类）。 */
    fun listModels(cfg: AiConfig): List<String> {
        val base = cfg.baseUrl.let { u ->
            val t = u.trim().trimEnd('/')
            if (t.contains("/chat/completions")) t.substringBefore("/chat/completions") else t
        }
        val rb = Request.Builder().url("$base/models")
        val key = cfg.apiKey.trim()
        if (key.isNotEmpty()) rb.header("Authorization", "Bearer $key")
        client.newCall(rb.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw AiException("获取模型列表失败：HTTP ${resp.code}")
            val data = JSONObject(resp.body?.string() ?: "{}").optJSONArray("data")
                ?: JSONArray()
            val exclude = listOf("embedding", "image-edit", "antangelmed", "compassjudger")
            val out = ArrayList<String>()
            for (i in 0 until data.length()) {
                val id = optString(data.getJSONObject(i), "id")
                if (id.isEmpty()) continue
                val low = id.lowercase()
                if (exclude.any { low.contains(it) }) continue
                out.add(id)
            }
            return out.sorted()
        }
    }
}

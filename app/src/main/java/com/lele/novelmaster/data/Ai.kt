package com.lele.novelmaster.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ChatMsg(val role: String, val content: String)

/** 一次 AI 调用的结果；finishReason="length" 表示被 token 上限截断 */
data class AiResult(
    val text: String,
    val finishReason: String = "stop"
) {
    val truncated: Boolean get() = finishReason == "length"
}

data class ProviderPreset(
    val name: String,
    val provider: String,
    val baseUrl: String,
    val suggestedModel: String = "",
    val freeNote: String = ""
)

object AiProviders {
    val presets = listOf(
        ProviderPreset(
            "智谱GLM（有免费模型）", "openai", "https://open.bigmodel.cn/api/paas/v4",
            "glm-4-flash", "glm-4-flash 完全免费，推荐首选"
        ),
        ProviderPreset(
            "硅基流动 SiliconFlow（多款免费模型）", "openai", "https://api.siliconflow.cn/v1",
            "Qwen/Qwen2.5-7B-Instruct", "平台内有多款免费模型"
        ),
        ProviderPreset(
            "Google Gemini（免费额度）", "gemini", "https://generativelanguage.googleapis.com",
            "gemini-2.0-flash", "有免费调用额度"
        ),
        ProviderPreset(
            "OpenRouter（:free 免费模型）", "openai", "https://openrouter.ai/api/v1",
            "meta-llama/llama-3.3-70b-instruct:free", "带 :free 后缀的模型免费"
        ),
        ProviderPreset("DeepSeek 深度求索", "openai", "https://api.deepseek.com/v1", "deepseek-chat"),
        ProviderPreset("通义千问（阿里）", "openai", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        ProviderPreset("月之暗面 Kimi", "openai", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        ProviderPreset("百度文心（兼容接口）", "openai", "https://qianfan.baidubce.com/v2", "ernie-4.0-8k"),
        ProviderPreset("Ollama 本地模型（本地免费）", "openai", "http://127.0.0.1:11434/v1", "qwen2.5:7b"),
        ProviderPreset("自定义 OpenAI 兼容接口", "openai", "https://")
    )
}

object AiClient {

    /**
     * v5.7：连接 20s / 单次读 60s（流式下=两个数据包之间的最长间隔）。
     * 老版本 readTimeout=600s 会让界面"假死"十几分钟——用户感觉就是"卡住、回复不了"。
     */
    /**
     * v6.1：两个客户端分开 ——
     *  流式：60s 读超时即可（只要 60s 内有新字到达就算活着）；
     *  非流式：整段生成往往要 1~3 分钟，60s 会把"写章/大纲/摘要"全部超时掉
     *  （正是「已注入上下文却没有正文」的根因），必须放宽到 600s。
     */
    private val streamingClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val plainClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /** v5.7：把每个 AI 的允许输出长度开到最大（默认 16384），被平台拒绝时自动降级重试 */
    const val MAX_TOKENS_HUGE = 16384
    private val TOKEN_LADDER = listOf(16384, 8192, 4096, 2048, 1024)

    /** v5.9：记住每个模型实测可用的 max_tokens，下次直接命中，不再从头降级重试（省 1~2 次往返延迟） */
    private val okMaxTokens = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * v6.9：全局「AI 活动」时间戳——任何流式字节到达就刷新。
     * ChatScreen 的兜底看门狗改为检查它：只要 AI 还在出字就不超时，
     * 只有真正卡死（5 分钟无任何字节）才强制结束。修复分章大纲等长任务被看门狗误杀。
     */
    @Volatile
    var lastActivityMs: Long = System.currentTimeMillis()

    private fun ladderFor(cfg: ApiConfig, maxTokens: Int): List<Int> {
        val cached = okMaxTokens[cfg.model]
        return (listOfNotNull(cached?.takeIf { it <= maxTokens }, maxTokens) + TOKEN_LADDER)
            .distinct().filter { it >= 512 }
    }

    /**
     * 流式对话：拿到第一个字就回调，界面立刻能看到内容在增长。
     * 流式不被支持时自动回退到普通对话。
     */
    suspend fun chatStream(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double = 0.85,
        maxTokens: Int = MAX_TOKENS_HUGE,
        onDelta: suspend (String) -> Unit
    ): AiResult {
        val ladder = ladderFor(cfg, maxTokens)
        var lastErr: Exception? = null
        // v6.9：包装 onDelta——每个字节到达都刷新全局活动时间戳（喂看门狗）
        val fed: suspend (String) -> Unit = { d ->
            lastActivityMs = System.currentTimeMillis()
            onDelta(d)
        }
        for (mt in ladder) {
            try {
                val r = if (cfg.provider == "gemini")
                    streamGemini(cfg, messages, temperature, mt, fed)
                else
                    streamOpenai(cfg, messages, temperature, mt, fed)
                if (r.text.isNotBlank()) {
                    okMaxTokens[cfg.model] = mt
                    return r
                }
                // 流式拿到空内容（部分老模型 stream 支持不全）→ 回退普通对话
                val plain = chatPlain(cfg, messages, temperature, mt)
                if (plain.isNotBlank()) {
                    onDelta(plain)
                    return AiResult(plain, "stop")
                }
                return r
            } catch (e: Exception) {
                if (e is CancellationException) throw e   // 用户停止：立刻中断，不重试
                lastErr = e
                if (!isMaxTokensError(e.message)) break
            }
        }
        throw lastErr ?: RuntimeException("AI 调用失败")
    }

    /**
     * v6.2：对话补全统一走流式管道（可取消、无 60s 超时问题）。
     * 写章/大纲/摘要/专家功能等所有旧调用点因此全部变成流式，无需逐个改造。
     * 底层 chatPlain 仅在流式完全不可用时兜底。
     */
    suspend fun chat(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double = 0.85,
        maxTokens: Int = MAX_TOKENS_HUGE
    ): String = withContext(Dispatchers.IO) {
        val r = try {
            chatStream(cfg, messages, temperature, maxTokens) { }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val plain = chatPlain(cfg, messages, temperature, maxTokens).trim()
            if (plain.isNotBlank()) return@withContext plain
            throw e
        }
        val t = r.text.trim()
        if (t.isNotBlank()) t else throw RuntimeException("AI返回为空")
    }

    /** 非流式兜底（max_tokens 过大时自动降级重试） */
    private suspend fun chatPlain(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double,
        maxTokens: Int
    ): String = withContext(Dispatchers.IO) {
        val ladder = ladderFor(cfg, maxTokens)
        var lastErr: Exception? = null
        for (mt in ladder) {
            try {
                val r = if (cfg.provider == "gemini")
                    chatGemini(cfg, messages, temperature, mt)
                else
                    chatOpenai(cfg, messages, temperature, mt)
                if (r.isNotBlank()) {
                    okMaxTokens[cfg.model] = mt
                    return@withContext r
                }
                lastErr = RuntimeException("AI返回为空")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastErr = e
                if (!isMaxTokensError(e.message)) break
            }
        }
        throw lastErr ?: RuntimeException("AI 调用失败")
    }

    /** 测试连接：发一句极短的请求 */
    suspend fun testConnection(cfg: ApiConfig): String {
        val reply = chat(
            cfg,
            listOf(ChatMsg("user", "请只回复四个字：连接成功")),
            temperature = 0.1,
            maxTokens = 100
        )
        return reply.trim().take(50)
    }

    /** 自动获取该服务下所有可用模型 */
    suspend fun listModels(cfg: ApiConfig): List<String> = withContext(Dispatchers.IO) {
        if (cfg.provider == "gemini") {
            val body = httpGet("https://generativelanguage.googleapis.com/v1beta/models?key=${cfg.apiKey}", null)
            val arr = JSONObject(body).optJSONArray("models") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("name")
                    ?.removePrefix("models/")
                    ?.takeIf { it.contains("gemini") }
            }
        } else {
            val body = httpGet(cfg.baseUrl.trimEnd('/') + "/models", "Bearer ${cfg.apiKey}")
            val arr = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
            }.sorted()
        }
    }

    /** 平台拒绝"太大 max_tokens"时的特征：自动降一档重试 */
    private fun isMaxTokensError(msg: String?): Boolean {
        val m = msg?.lowercase().orEmpty()
        return m.contains("max_tokens") || m.contains("maxtokens") ||
            m.contains("maxoutputtokens") || m.contains("max_output") ||
            m.contains("too large") || m.contains("too long") ||
            m.contains("exceed") || m.contains("超出") || m.contains("上限") ||
            m.contains("maximum context") || m.contains("greater than")
    }


    /** 安全取字符串：org.json 的 optString 遇到 JSON null 会返回字面量 "null"，必须拦截 */
    private fun jstr(o: JSONObject?, key: String): String {
        if (o == null || !o.has(key)) return ""
        return when (val v = o.opt(key)) {
            null, JSONObject.NULL -> ""
            is String -> v
            else -> v.toString()
        }
    }

    // ---------- OpenAI 兼容 ----------

    private fun openaiBody(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean = false
    ): String {
        val arr = JSONArray()
        messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val o = JSONObject()
            .put("model", cfg.model)
            .put("messages", arr)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
        if (stream) o.put("stream", true)
        return o.toString()
    }

    private suspend fun streamOpenai(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double,
        maxTokens: Int,
        onDelta: suspend (String) -> Unit
    ): AiResult = withContext(Dispatchers.IO) {
        if (cfg.model.isBlank()) throw RuntimeException("未选择模型，请先在模型列表中选择")
        val req = Request.Builder()
            .url(cfg.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Accept", "text/event-stream")
            .post(openaiBody(cfg, messages, temperature, maxTokens, true).toRequestBody(JSON_TYPE))
            .build()

        executeCall(req, useStreaming = true).use { resp ->
            if (!resp.isSuccessful) {
                val b = runCatching { resp.peekBody(4096).string() }.getOrDefault("")
                throw RuntimeException("HTTP ${resp.code}: ${b.take(300)}")
            }
            val reader = java.io.BufferedReader(resp.body?.charStream() ?: return@use AiResult("", "stop"))
            val sb = StringBuilder()
            val think = StringBuilder()
            var finish = "stop"
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    val t = line.trim()
                    if (t.isEmpty() || !t.startsWith("data:")) continue
                    val payload = t.removePrefix("data:").trim()
                    if (payload == "[DONE]" || payload.isEmpty()) break
                    runCatching {
                        val obj = JSONObject(payload)
                        val ch = obj.optJSONArray("choices")?.optJSONObject(0)
                        jstr(ch, "finish_reason").takeIf { it.isNotBlank() }?.let { finish = it }
                        val d = ch?.optJSONObject("delta")
                        val piece = jstr(d, "content")
                        if (piece.isNotEmpty()) {
                            sb.append(piece)
                            onDelta(piece)
                        } else {
                            // v6.0：思考过程(reasoning_content/reasoning)只收集不显示
                            val tk = jstr(d, "reasoning_content").ifEmpty { jstr(d, "reasoning") }
                            if (tk.isNotEmpty()) think.append(tk)
                        }
                    }
                }
            } catch (io: java.io.IOException) {
                // 用户点「停止」时 Call 被 cancel，读流会抛 IOException —— 转成正常取消，别报成失败
                if (currentCoroutineContext()[Job]?.isActive == false) throw CancellationException("用户停止")
                throw io
            }
            // 个别推理模型只输出思考段：作为兜底内容返回（界面侧还会再剥一层 <think>）
            AiResult(if (sb.isBlank()) think.toString() else sb.toString(), finish)
        }
    }

    private suspend fun chatOpenai(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double,
        maxTokens: Int
    ): String = withContext(Dispatchers.IO) {
        if (cfg.model.isBlank()) throw RuntimeException("未选择模型，请先在模型列表中选择")
        val req = Request.Builder()
            .url(cfg.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .post(openaiBody(cfg, messages, temperature, maxTokens).toRequestBody(JSON_TYPE))
            .build()
        executeCall(req).use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${body.take(300)}")
            val msg = JSONObject(body)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")
            // content 为空时回退 reasoning_content（DeepSeek-R1 等推理模型把正文放这里）
            var content = jstr(msg, "content")
            if (content.isBlank()) content = jstr(msg, "reasoning_content")
            if (content.isBlank()) content = jstr(msg, "reasoning")
            if (content.isBlank()) throw RuntimeException("AI返回为空: ${body.take(300)}")
            content
        }
    }

    // ---------- Gemini 原生 ----------

    private fun geminiBody(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double,
        maxTokens: Int
    ): String {
        val sys = messages.firstOrNull { it.role == "system" }?.content
        val contents = JSONArray()
        messages.filter { it.role != "system" }.forEach { m ->
            contents.put(
                JSONObject()
                    .put("role", if (m.role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", m.content)))
            )
        }
        val json = JSONObject().put("contents", contents)
        if (!sys.isNullOrBlank()) {
            json.put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
        }
        json.put(
            "generationConfig",
            JSONObject().put("temperature", temperature).put("maxOutputTokens", maxTokens)
        )
        return json.toString()
    }

    private suspend fun streamGemini(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double,
        maxTokens: Int,
        onDelta: suspend (String) -> Unit
    ): AiResult = withContext(Dispatchers.IO) {
        if (cfg.model.isBlank()) throw RuntimeException("未选择模型，请先在模型列表中选择")
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/${cfg.model}:streamGenerateContent?alt=sse&key=${cfg.apiKey}"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .post(geminiBody(cfg, messages, temperature, maxTokens).toRequestBody(JSON_TYPE))
            .build()
        executeCall(req, useStreaming = true).use { resp ->
            if (!resp.isSuccessful) {
                val b = runCatching { resp.peekBody(4096).string() }.getOrDefault("")
                throw RuntimeException("HTTP ${resp.code}: ${b.take(300)}")
            }
            val reader = java.io.BufferedReader(resp.body?.charStream() ?: return@use AiResult("", "stop"))
            val sb = StringBuilder()
            var finish = "stop"
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    val t = line.trim()
                    if (t.isEmpty() || !t.startsWith("data:")) continue
                    val payload = t.removePrefix("data:").trim()
                    if (payload == "[DONE]" || payload.isEmpty()) break
                    runCatching {
                        val obj = JSONObject(payload)
                        val cand = obj.optJSONArray("candidates")?.optJSONObject(0)
                        jstr(cand, "finishReason").takeIf { it.isNotBlank() }?.let { finish = it }
                        val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val piece = jstr(parts.optJSONObject(i), "text")
                                if (piece.isNotEmpty()) {
                                    sb.append(piece)
                                    onDelta(piece)
                                }
                            }
                        }
                    }
                }
            } catch (io: java.io.IOException) {
                if (currentCoroutineContext()[Job]?.isActive == false) throw CancellationException("用户停止")
                throw io
            }
            AiResult(sb.toString(), if (finish == "MAX_TOKENS") "length" else finish)
        }
    }

    private suspend fun chatGemini(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double,
        maxTokens: Int
    ): String = withContext(Dispatchers.IO) {
        if (cfg.model.isBlank()) throw RuntimeException("未选择模型，请先在模型列表中选择")
        val url =
            "https://generativelanguage.googleapis.com/v1beta/models/${cfg.model}:generateContent?key=${cfg.apiKey}"
        val req = Request.Builder()
            .url(url)
            .post(geminiBody(cfg, messages, temperature, maxTokens).toRequestBody(JSON_TYPE))
            .build()
        executeCall(req).use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${body.take(300)}")
            val cand = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)
            val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    sb.append(jstr(parts.optJSONObject(i), "text"))
                }
            }
            val content = sb.toString()
            if (content.isBlank()) throw RuntimeException("Gemini返回为空: ${body.take(300)}")
            content
        }
    }

    // ---------- HTTP 基础 ----------

    /** v5.7：可取消的 execute —— 用户点「停止」时真正断开连接，不再"卡住没反应" */
    private suspend fun executeCall(req: Request, useStreaming: Boolean = false): Response =
        suspendCancellableCoroutine { cont ->
            val call = (if (useStreaming) streamingClient else plainClient).newCall(req)
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            try {
                val r = call.execute()
                if (cont.isActive) cont.resume(r) else r.close()
            } catch (e: Throwable) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    private fun httpGet(url: String, auth: String?): String {
        val b = Request.Builder().url(url)
        if (auth != null) b.header("Authorization", auth)
        streamingClient.newCall(b.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${body.take(300)}")
            return body
        }
    }
}

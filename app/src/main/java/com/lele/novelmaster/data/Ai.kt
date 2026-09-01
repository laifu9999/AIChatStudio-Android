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

    /**
     * v6.9.42：剥掉混进正文的 <think>…</think> 思考段。
     * 用户踩坑：有的模型把英文思考过程直接写进 content（或只输出思考段），
     * 体检报告里出现一大段英文——正文里严禁出现 <think> 块。
     */
    fun stripThink(s: String): String = s
        .replace(Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<(?:think|thinking|thought)>[\\s\\S]*?$", RegexOption.IGNORE_CASE), "")
        .trim()

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

    /**
     * v6.9.46：进行中的非流式调用计数。chatPlain 全程没有字节回流，喂不了看门狗——
     * 体检/自检等长任务走普通对话时，看门狗只看 lastActivityMs 会误判「5 分钟无响应」。
     * 计数 > 0 期间看门狗不得杀任务（任务真卡死由各自的闸门/超时兜底）。
     */
    @Volatile
    var plainInflight: Int = 0

    private fun ladderFor(cfg: ApiConfig, maxTokens: Int): List<Int> {
        val cached = okMaxTokens[cfg.model]
        return (listOfNotNull(cached?.takeIf { it <= maxTokens }, maxTokens) + TOKEN_LADDER)
            .distinct().filter { it >= 512 }
    }

    /**
     * 流式对话：拿到第一个字就回调，界面立刻能看到内容在增长。
     * 流式不被支持时自动回退到普通对话。
     * v6.9.52：思考耗尽输出额度（只出思考没出正文）→ 自动关思考重试一次，任务不中断。
     */
    suspend fun chatStream(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double = 0.85,
        maxTokens: Int = MAX_TOKENS_HUGE,
        onDelta: suspend (String) -> Unit
    ): AiResult {
        return try {
            chatStreamLadder(cfg, messages, temperature, maxTokens, onDelta)
        } catch (e: Exception) {
            if (e is CancellationException || cfg.thinkMode == "none" || !isThinkExhaustedError(e.message)) throw e
            lastActivityMs = System.currentTimeMillis()
            chatStreamLadder(cfg.copy(thinkMode = "none"), messages, temperature, maxTokens, onDelta)
        }
    }

    private suspend fun chatStreamLadder(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double,
        maxTokens: Int,
        onDelta: suspend (String) -> Unit
    ): AiResult {
        // v6.9.46：任务开始即重置活动时间戳——上一轮 AI 活动可能是几分钟前，
        // 不重置的话新任务开局就带着旧 idle，几分钟无首字节就被看门狗误杀（用户实测：明明没到5分钟就报超时）
        lastActivityMs = System.currentTimeMillis()
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
     * v6.9.52：思考耗尽输出额度 → 自动关思考重试一次（流式和非流式兜底都失败时在这里兜）。
     */
    suspend fun chat(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double = 0.85,
        maxTokens: Int = MAX_TOKENS_HUGE
    ): String = withContext(Dispatchers.IO) {
        try {
            chatOnce(cfg, messages, temperature, maxTokens)
        } catch (e: Exception) {
            if (e is CancellationException || cfg.thinkMode == "none" || !isThinkExhaustedError(e.message)) throw e
            chatOnce(cfg.copy(thinkMode = "none"), messages, temperature, maxTokens)
        }
    }

    private suspend fun chatOnce(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double,
        maxTokens: Int
    ): String = withContext(Dispatchers.IO) {
        val r = try {
            chatStream(cfg, messages, temperature, maxTokens) { }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val plain = chatPlain(cfg, messages, temperature, maxTokens).trim()
            if (plain.isNotBlank()) return@withContext plain
            throw e
        }
        // v6.9.42：统一剥 <think> 思考段
        val t = stripThink(r.text.trim())
        if (t.isNotBlank()) t else throw RuntimeException("AI返回为空")
    }

    /** 非流式兜底（max_tokens 过大时自动降级重试）。v6.9.46：全程喂不了看门狗，用 plainInflight 豁免 */
    private suspend fun chatPlain(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double,
        maxTokens: Int
    ): String {
        lastActivityMs = System.currentTimeMillis()
        plainInflight++
        try {
            return plainInner(cfg, messages, temperature, maxTokens)
        } finally {
            plainInflight--
            lastActivityMs = System.currentTimeMillis()
        }
    }

    private suspend fun plainInner(
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

    /**
     * v6.9.47：思考模式测试——发一个需要想一想的小问题，观察模型是否真的进入/退出思考状态。
     * 返回给用户看的状态文案（不抛异常，失败也在文案里说明）。
     */
    suspend fun testThinking(cfg: ApiConfig): String = withContext(Dispatchers.IO) {
        val mode = cfg.thinkMode
        val probe = listOf(ChatMsg("user", "9.11 和 9.8 哪个更大？只回答结论。"))
        try {
            if (cfg.provider == "gemini") {
                // Gemini：思考内容默认不回传，能接受配置不报错即视为生效
                geminiCall(cfg, probe, 0.1, 2048, stream = false).use { resp ->
                    val b = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val lb = b.lowercase()
                        if (lb.contains("thinking")) {
                            thinkingUnsupported[cfg.model] = true
                            "⚠️ 该 Gemini 模型不支持思考配置，已按模型默认运行"
                        } else "❌ 测试失败：HTTP ${resp.code}: ${b.take(200)}"
                    } else "✅ Gemini 已接受${when (mode) { "none" -> "关闭思考"; "low" -> "低强度思考"; else -> "高强度思考" }}配置"
                }
            } else {
                openaiCall(cfg, probe, 0.1, 2048, stream = false).use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        when {
                            // v6.9.54：「始终思考」模型（智谱 glm-5 系等）——自动切 reasoning_effort 并缓存
                            isAlwaysThinkError("HTTP ${resp.code}: $body") -> {
                                alwaysThink[cfg.model] = true
                                if (mode == "none")
                                    "⚠️ 该模型始终思考、无法关闭（如智谱glm-5系列）。已自动改用最低思考量（effort=low）并记住，正常出内容，正文生成不受影响"
                                else
                                    "✅ 已按${if (mode == "high") "高" else "低"}强度控制思考量（该模型无法完全关闭思考，已用 effort=${if (mode == "high") "high" else "low"}）"
                            }
                            isThinkingParamError("HTTP ${resp.code}: $body") -> {
                                thinkingUnsupported[cfg.model] = true
                                "⚠️ 该模型/服务商不认识思考参数，已自动忽略（按模型默认运行，不影响使用）"
                            }
                            else -> "❌ 测试失败：HTTP ${resp.code}: ${body.take(200)}"
                        }
                    } else {
                        val msg = JSONObject(body)
                            .optJSONArray("choices")?.optJSONObject(0)
                            ?.optJSONObject("message")
                        val hasThink = jstr(msg, "reasoning_content").isNotBlank() ||
                            jstr(msg, "reasoning").isNotBlank() ||
                            jstr(msg, "content").contains("<think")
                        when (mode) {
                            "none" -> if (hasThink)
                                "⚠️ 模型仍在输出思考过程——该服务商可能不支持关闭思考（或此模型是纯推理模型）"
                            else
                                "✅ 无思考模式生效：本次回复没有思考过程"
                            else -> if (hasThink)
                                "✅ ${if (mode == "low") "低强度" else "高强度"}思考模式已生效：模型返回了思考过程"
                            else
                                "⚠️ 未检测到思考过程——该模型可能不支持思考强度参数，已按模型默认运行（普通对话不受影响）"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "❌ 测试失败：${e.message?.take(200)}"
        }
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

    // ---------- 思考强度（v6.9.47） ----------

    /**
     * v6.9.47：记住实测不认思考参数的模型——400 报错指向思考参数后置位，
     * 之后所有请求直接跳过注入（走模型默认），不再反复撞墙浪费时间。
     */
    private val thinkingUnsupported = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * v6.9.47：把思考强度注入 OpenAI 兼容请求体。
     * 各家参数不一样，按 地址/模型名 启发式适配（deepseek-v4 官方支持 thinking.type 开关 + reasoning_effort 强度）：
     *  - DeepSeek：{"thinking":{"type":"enabled/disabled"}} + reasoning_effort low/high
     *  - 智谱 GLM：{"thinking":{"type":"enabled/disabled"}}（无强度档，开关即用）
     *  - 通义 Qwen：enable_thinking true/false
     *  - 其他：同时给 thinking.type 与 enable_thinking / reasoning_effort——
     *    若服务商报 400 不认识参数，由 openaiCall 自动去参重试兜底，保证任何模型都能用。
     */
    private fun applyThinkingOpenai(o: JSONObject, cfg: ApiConfig) {
        val mode = cfg.thinkMode
        if (mode.isBlank()) return
        if (thinkingUnsupported[cfg.model] == true) return
        // v6.9.54：「始终思考」模型——关不掉思考，用 reasoning_effort 控制思考量
        // （无思考/低强度→low 把思考压到最低，高强度→high），实测 glm-5.3-flash effort=low 合法生效
        if (alwaysThink[cfg.model] == true) {
            o.put("reasoning_effort", if (mode == "high") "high" else "low")
            return
        }
        val host = cfg.baseUrl.lowercase()
        val model = cfg.model.lowercase()
        val deepseek = host.contains("deepseek") || model.contains("deepseek")
        val zhipu = host.contains("bigmodel.cn") || model.startsWith("glm")
        val qwen = host.contains("dashscope") || model.contains("qwen")
        when {
            deepseek -> when (mode) {
                "none" -> o.put("thinking", JSONObject().put("type", "disabled"))
                else -> {
                    o.put("thinking", JSONObject().put("type", "enabled"))
                    o.put("reasoning_effort", mode)
                }
            }
            zhipu -> o.put("thinking", JSONObject().put("type", if (mode == "none") "disabled" else "enabled"))
            qwen -> o.put("enable_thinking", mode != "none")
            else -> when (mode) {
                "none" -> {
                    o.put("thinking", JSONObject().put("type", "disabled"))
                    o.put("enable_thinking", false)
                }
                else -> o.put("reasoning_effort", mode)
            }
        }
    }

    /** v6.9.47：400 报错文本指向思考参数 → 判定该模型不认识，去参重试并缓存 */
    private fun isThinkingParamError(msg: String?): Boolean {
        val m = msg?.lowercase().orEmpty()
        if (!m.contains("http 400") && !m.contains("http 422")) return false
        // v6.9.54：补中文「思考」——智谱等中文报错（如「不支持关闭思考」）此前匹配不到，导致去参重试不触发
        val kw = m.contains("thinking") || m.contains("reasoning") || m.contains("思考")
        val err = m.contains("unrecognized") || m.contains("unknown") || m.contains("invalid") ||
            m.contains("not support") || m.contains("unexpected") || m.contains("extra") ||
            m.contains("unsupported") || m.contains("不支持") || m.contains("未知") || m.contains("无效")
        return kw && err
    }

    /**
     * v6.9.54：「始终思考」模型（智谱 glm-5 系实测报 1210「该模型始终思考，不支持关闭思考；请使用 low、high 或 max」）。
     * 这类模型关不掉思考，改用 reasoning_effort 控制思考量（实测 effort=low 时思考量骤降且合法）。
     */
    private val alwaysThink = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun isAlwaysThinkError(msg: String?): Boolean {
        val m = msg ?: return false
        return m.contains("始终思考") || m.contains("不支持关闭思考")
    }

    /**
     * v6.9.52：思考耗尽输出额度——开了思考档位的模型把 max_tokens 全花在思考上，
     * content 为空（报错文案含「思考内容已过滤」或「只输出了思考内容」）。
     * 这种失败重试大概率还会复发，自动关掉思考重打一次才能救回来。
     */
    private fun isThinkExhaustedError(msg: String?): Boolean {
        val m = msg ?: return false
        return m.contains("思考内容已过滤") || m.contains("只输出了思考内容")
    }

    /**
     * v6.9.47：OpenAI 兼容请求统一入口——先带思考参数发送；
     * 若服务商报 400 且报错指向思考参数，自动去参重试（保证「适配所有 AI」：
     * 不认识的模型永远回退到模型默认行为，绝不因新参数把请求打死）。
     */
    private suspend fun openaiCall(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean
    ): Response {
        val useThink = cfg.thinkMode.isNotBlank() && thinkingUnsupported[cfg.model] != true
        val url = cfg.baseUrl.trimEnd('/') + "/chat/completions"
        fun build(withThink: Boolean): Request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .let { if (stream) it.header("Accept", "text/event-stream") else it }
            .post(openaiBody(cfg, messages, temperature, maxTokens, stream, withThink).toRequestBody(JSON_TYPE))
            .build()
        var resp = executeCall(build(useThink), stream)
        if (!resp.isSuccessful && useThink) {
            val b = runCatching { resp.peekBody(4096).string() }.getOrDefault("")
            when {
                // v6.9.54：「始终思考」模型（如智谱 glm-5 系拒绝 disabled）——记缓存后原样重发，
                // applyThinkingOpenai 会转走 reasoning_effort 分支（无思考/低→low，高→high）
                isAlwaysThinkError("HTTP ${resp.code}: $b") -> {
                    alwaysThink[cfg.model] = true
                    resp.close()
                    resp = executeCall(build(useThink), stream)
                }
                isThinkingParamError("HTTP ${resp.code}: $b") -> {
                    thinkingUnsupported[cfg.model] = true
                    resp.close()
                    resp = executeCall(build(false), stream)
                }
            }
        }
        return resp
    }

    // ---------- OpenAI 兼容 ----------

    private fun openaiBody(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean = false,
        withThinking: Boolean = true
    ): String {
        val arr = JSONArray()
        messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val o = JSONObject()
            .put("model", cfg.model)
            .put("messages", arr)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
        if (stream) o.put("stream", true)
        // v6.9.47：思考强度注入（选「模型默认」或不认识参数的模型不注入）
        if (withThinking) applyThinkingOpenai(o, cfg)
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
        // v6.9.47：统一走 openaiCall（思考参数注入 + 400 自动去参重试）
        openaiCall(cfg, messages, temperature, maxTokens, stream = true).use { resp ->
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
                            // v6.9.54：思考增量也刷新活动时间戳（喂看门狗）——glm-5 系等「始终思考」模型
                            // 长任务思考可达数分钟（实测 138 章建书思考 5000+ 字），不喂狗会被看门狗误杀
                            val tk = jstr(d, "reasoning_content").ifEmpty { jstr(d, "reasoning") }
                            if (tk.isNotEmpty()) {
                                think.append(tk)
                                lastActivityMs = System.currentTimeMillis()
                            }
                        }
                    }
                }
            } catch (io: java.io.IOException) {
                // 用户点「停止」时 Call 被 cancel，读流会抛 IOException —— 转成正常取消，别报成失败
                if (currentCoroutineContext()[Job]?.isActive == false) throw CancellationException("用户停止")
                throw io
            }
            // v6.9.48：思考内容绝不回退当正文——开了思考强度的模型思考耗尽输出额度时 content 为空，
            // 旧逻辑把整段 reasoning_content 当正文返回，导致「好的，用户要求写…」整段思考被存进章节/显示在聊天。
            if (sb.isBlank() && think.isNotBlank())
                throw RuntimeException("AI 只输出了思考内容，没有产出正文（思考可能耗尽了输出额度）——请重试一次，或在AI后台把思考强度调低/改为无思考")
            // v6.9.42：返回前先剥 <think> 块，防止英文思考过程混进报告/正文
            AiResult(stripThink(sb.toString()), finish)
        }
    }

    private suspend fun chatOpenai(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double,
        maxTokens: Int
    ): String = withContext(Dispatchers.IO) {
        if (cfg.model.isBlank()) throw RuntimeException("未选择模型，请先在模型列表中选择")
        // v6.9.47：统一走 openaiCall（思考参数注入 + 400 自动去参重试）
        openaiCall(cfg, messages, temperature, maxTokens, stream = false).use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${body.take(300)}")
            val msg = JSONObject(body)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")
            // v6.9.48：不再回退 reasoning_content——那是思考链，回退会把整段思考当正文存进章节。
            // DeepSeek-R1 官方 API 的正文也在 content 里；content 为空说明只有思考没有正文，宁可报错也不污染正文。
            val content = stripThink(jstr(msg, "content"))
            if (content.isBlank()) throw RuntimeException("AI返回为空（思考内容已过滤，不会混入正文）: ${body.take(300)}")
            content
        }
    }

    // ---------- Gemini 原生 ----------

    private fun geminiBody(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double,
        maxTokens: Int,
        withThinking: Boolean = true
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
        // v6.9.47：Gemini 思考强度——thinkingBudget 0=关 / 2048=低 / -1=动态（高）；老模型不认时由 geminiCall 去参重试
        val gen = JSONObject().put("temperature", temperature).put("maxOutputTokens", maxTokens)
        if (withThinking && cfg.thinkMode.isNotBlank()) {
            val budget = when (cfg.thinkMode) { "none" -> 0; "low" -> 2048; else -> -1 }
            gen.put("thinkingConfig", JSONObject().put("thinkingBudget", budget))
        }
        json.put("generationConfig", gen)
        return json.toString()
    }

    /**
     * v6.9.47：Gemini 请求统一入口——先带思考配置发送；
     * 若报错指向 thinkingConfig/thinkingBudget（老模型不支持），自动去参重试。
     */
    private suspend fun geminiCall(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double,
        maxTokens: Int,
        stream: Boolean
    ): Response {
        val path = if (stream) "streamGenerateContent?alt=sse&key=${cfg.apiKey}"
        else "generateContent?key=${cfg.apiKey}"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${cfg.model}:$path"
        val useThink = cfg.thinkMode.isNotBlank()
        fun build(withThink: Boolean): Request = Request.Builder()
            .url(url)
            .let { if (stream) it.header("Accept", "text/event-stream") else it }
            .post(geminiBody(cfg, messages, temperature, maxTokens, withThink).toRequestBody(JSON_TYPE))
            .build()
        var resp = executeCall(build(useThink), stream)
        if (!resp.isSuccessful && useThink) {
            val b = runCatching { resp.peekBody(4096).string() }.getOrDefault("")
            val lb = b.lowercase()
            if (lb.contains("thinking")) {
                resp.close()
                resp = executeCall(build(false), stream)
            }
        }
        return resp
    }

    private suspend fun streamGemini(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double,
        maxTokens: Int,
        onDelta: suspend (String) -> Unit
    ): AiResult = withContext(Dispatchers.IO) {
        if (cfg.model.isBlank()) throw RuntimeException("未选择模型，请先在模型列表中选择")
        // v6.9.47：统一走 geminiCall（思考配置 + 报错自动去参重试）
        geminiCall(cfg, messages, temperature, maxTokens, stream = true).use { resp ->
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
                                val p = parts.optJSONObject(i) ?: continue
                                // v6.9.48：思考强度模式下思考段带 thought:true——绝不进正文、不回调显示
                                // v6.9.54：思考段同样喂看门狗（Gemini 长思考任务同理不被误杀）
                                if (p.optBoolean("thought")) {
                                    lastActivityMs = System.currentTimeMillis()
                                    continue
                                }
                                val piece = jstr(p, "text")
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
        // v6.9.47：统一走 geminiCall（思考配置 + 报错自动去参重试）
        geminiCall(cfg, messages, temperature, maxTokens, stream = false).use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${body.take(300)}")
            val cand = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)
            val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    // v6.9.48：跳过思考段（thought:true），思考内容不进正文
                    if (p.optBoolean("thought")) continue
                    sb.append(jstr(p, "text"))
                }
            }
            // v6.9.42：剥 <think> 思考段
            val content = stripThink(sb.toString())
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

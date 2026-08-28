package com.lele.novelmaster.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChatMsg(val role: String, val content: String)

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /** 对话补全（自动区分 OpenAI 兼容 / Gemini 原生） */
    suspend fun chat(
        cfg: ApiConfig,
        messages: List<ChatMsg>,
        temperature: Double = 0.85,
        maxTokens: Int = 4096
    ): String = withContext(Dispatchers.IO) {
        if (cfg.provider == "gemini") chatGemini(cfg, messages, temperature, maxTokens)
        else chatOpenai(cfg, messages, temperature, maxTokens)
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
        try {
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
        } catch (e: Exception) {
            throw e
        }
    }

    // ---------- OpenAI 兼容 ----------

    private fun openaiBody(cfg: ApiConfig, messages: List<ChatMsg>, temperature: Double, maxTokens: Int): String {
        val arr = JSONArray()
        messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        return JSONObject()
            .put("model", cfg.model)
            .put("messages", arr)
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)
            .toString()
    }

    private suspend fun chatOpenai(cfg: ApiConfig, messages: List<ChatMsg>, temperature: Double, maxTokens: Int): String =
        withContext(Dispatchers.IO) {
            if (cfg.model.isBlank()) throw RuntimeException("未选择模型，请先在模型列表中选择")
            val url = cfg.baseUrl.trimEnd('/') + "/chat/completions"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .post(openaiBody(cfg, messages, temperature, maxTokens).toRequestBody(JSON_TYPE))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${body.take(300)}")
                val msg = JSONObject(body)
                    .optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")
                // content 为空时回退 reasoning_content（DeepSeek-R1/V3-Flash 等推理模型把正文放这里）
                var content = msg?.optString("content").orEmpty()
                if (content.isBlank()) {
                    content = msg?.optString("reasoning_content").orEmpty()
                }
                if (content.isBlank()) {
                    // 兜底：拼接 reasoning_content（有的模型叫 reasoning）
                    content = msg?.optString("reasoning").orEmpty()
                }
                if (content.isBlank()) throw RuntimeException("AI返回为空: ${body.take(300)}")
                content
            }
        }

    // ---------- Gemini 原生 ----------

    private suspend fun chatGemini(cfg: ApiConfig, messages: List<ChatMsg>, temperature: Double, maxTokens: Int): String =
        withContext(Dispatchers.IO) {
            if (cfg.model.isBlank()) throw RuntimeException("未选择模型，请先在模型列表中选择")
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
            val url = "https://generativelanguage.googleapis.com/v1beta/models/${cfg.model}:generateContent?key=${cfg.apiKey}"
            val req = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(JSON_TYPE))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${body.take(300)}")
                val cand = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)
                val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
                val sb = StringBuilder()
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
                    }
                }
                val content = sb.toString()
                if (content.isBlank()) throw RuntimeException("Gemini返回为空: ${body.take(300)}")
                content
            }
        }

    // ---------- HTTP 基础 ----------

    private fun httpGet(url: String, auth: String?): String {
        val b = Request.Builder().url(url)
        if (auth != null) b.header("Authorization", auth)
        client.newCall(b.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${body.take(300)}")
            return body
        }
    }
}

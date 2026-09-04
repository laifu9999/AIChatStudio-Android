package com.lele.mobipaint

import android.content.Context

/** OpenAI 兼容平台预设：填 Key 即用。 */
data class Platform(
    val name: String,
    val url: String,
    val model: String,
    val hint: String
)

val PLATFORMS = listOf(
    Platform("魔搭社区（免费2000次/天）",
        "https://api-inference.modelscope.cn/v1/chat/completions",
        "deepseek-ai/DeepSeek-V4-Flash-0731",
        "modelscope.cn/my/myaccesstoken 免费获取"),
    Platform("硅基流动（聚合平台·有免费模型）",
        "https://api.siliconflow.cn/v1/chat/completions",
        "deepseek-ai/DeepSeek-V3.2",
        "cloud.siliconflow.cn 获取（部分模型免费）"),
    Platform("DeepSeek 官方",
        "https://api.deepseek.com/v1/chat/completions",
        "deepseek-chat",
        "platform.deepseek.com 获取（按量付费）"),
    Platform("Kimi 官方（月之暗面）",
        "https://api.moonshot.cn/v1/chat/completions",
        "kimi-k2-turbo-preview",
        "platform.moonshot.cn 获取（按量付费）"),
    Platform("智谱 GLM（有免费模型）",
        "https://open.bigmodel.cn/api/paas/v4/chat/completions",
        "glm-4-flash",
        "open.bigmodel.cn 获取（glm-4-flash 免费）"),
    Platform("通义千问（阿里百炼）",
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        "qwen-plus",
        "bailian.console.aliyun.com 获取（有免费额度）"),
    Platform("OpenAI",
        "https://api.openai.com/v1/chat/completions",
        "gpt-4o-mini",
        "platform.openai.com 获取（按量付费）")
)

/** 全局 AI 配置（SharedPreferences JSON 持久化）。 */
data class AiConfig(
    val platformIdx: Int = 0,
    val apiKey: String = "",
    val baseUrl: String = PLATFORMS[0].url,
    val model: String = PLATFORMS[0].model,
    val temperature: Double = 0.75,
    val maxTokens: Int = 16384
) {
    val isCustom: Boolean get() = platformIdx >= PLATFORMS.size
}

object Config {
    private const val FILE = "mobipaint_config"

    @Volatile private var cache: AiConfig? = null

    fun load(ctx: Context): AiConfig {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val cfg = AiConfig(
                platformIdx = sp.getInt("platformIdx", 0),
                apiKey = sp.getString("apiKey", "") ?: "",
                baseUrl = sp.getString("baseUrl", PLATFORMS[0].url) ?: PLATFORMS[0].url,
                model = sp.getString("model", PLATFORMS[0].model) ?: PLATFORMS[0].model,
                temperature = java.lang.Double.longBitsToDouble(sp.getLong("temperatureBits",
                    java.lang.Double.doubleToRawLongBits(0.75))),
                maxTokens = sp.getInt("maxTokens", 16384)
            )
            cache = cfg
            return cfg
        }
    }

    fun save(ctx: Context, cfg: AiConfig) {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp.edit()
            .putInt("platformIdx", cfg.platformIdx)
            .putString("apiKey", cfg.apiKey)
            .putString("baseUrl", cfg.baseUrl)
            .putString("model", cfg.model)
            .putLong("temperatureBits", java.lang.Double.doubleToRawLongBits(cfg.temperature))
            .putInt("maxTokens", cfg.maxTokens)
            .apply()
        cache = cfg
    }
}

package com.lele.novelmaster.data

import android.content.Context

/**
 * v6.9.41：分任务专用 AI 模型 + 注入偏好设置（SharedPreferences 存全局配置，不动数据库）。
 *
 * 分任务模型：大纲生成 / 设定体检 / 发布打磨 三类任务可各自指定专用 AI 接口，
 * 未指定（0）则回落到「本书绑定模型 → 全局启用模型」的老链路（见 Db.apiFor）。
 * 典型用法：大纲/体检用便宜的免费模型，打磨用高质量付费模型。
 */
object TaskModels {
    const val OUTLINE = "outline"   // 分章大纲生成
    const val CHECK = "check"       // 设定体检 / 设定瘦身
    const val POLISH = "polish"     // 发布打磨 / 润色 / 扩写 / 补写
    val ALL = listOf(OUTLINE, CHECK, POLISH)

    private const val FILE = "task_models"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun set(ctx: Context, task: String, apiId: Long) {
        sp(ctx).edit().putLong(task, apiId).apply()
    }

    /** 任务绑定的接口 id，0 = 跟随默认 */
    fun boundId(ctx: Context, task: String): Long = sp(ctx).getLong(task, 0L)

    /**
     * 解析某本书某个任务应使用的 AI 接口：
     * 任务专用绑定 → 本书绑定 → 全局启用（与 Db.apiFor 相同的回落链）。
     */
    suspend fun apiFor(ctx: Context?, pid: Long, task: String): ApiConfig? {
        if (ctx != null) {
            val tid = boundId(ctx, task)
            if (tid != 0L) {
                try {
                    Repo.dao.apiConfig(tid)?.let { return it }
                } catch (_: Exception) { }
            }
        }
        return Repo.apiFor(pid)
    }
}

/**
 * v6.9.41：写章注入偏好（全局）。
 *  - summaryCount：注入「前情摘要」的章数，0 = 不注入（默认——有相邻大纲+上一章结尾，摘要没必要）；
 *  - windowPrev：分章大纲窗口往前多注入几章（默认 2，即前两章+本章+后一章）。
 */
object InjectPrefs {
    private const val FILE = "inject_prefs"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun setSummaryCount(ctx: Context, n: Int) = sp(ctx).edit().putInt("summaryCount", n.coerceIn(0, 10)).apply()
    fun summaryCount(ctx: Context): Int = sp(ctx).getInt("summaryCount", 0)

    fun setWindowPrev(ctx: Context, n: Int) = sp(ctx).edit().putInt("windowPrev", n.coerceIn(0, 10)).apply()
    fun windowPrev(ctx: Context): Int = sp(ctx).getInt("windowPrev", 2)
}

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
    const val OUTLINE = "outline"       // 分章大纲生成
    const val CHECK = "check"           // 设定体检 / 设定瘦身 / 按报告修复
    const val POLISH = "polish"         // 发布打磨 / 润色 / 扩写 / 补写
    // v6.9.46：后台任务模型扩展——全部带「检/修复」性质的任务都可指定专用模型
    const val CHAPTER = "chapter"       // 正文生成 / 续写 / 重写 / 自动写作
    const val BOOKCHECK = "bookcheck"   // 全书体检（只诊断）
    const val SELFCHK = "selfcheck"     // 全书自检 / 单章自检 / 自检一键修复
    val ALL = listOf(OUTLINE, CHECK, POLISH, CHAPTER, BOOKCHECK, SELFCHK)

    /** 任务显示名（后台 AI 设置页用，与 ALL 顺序一致） */
    fun label(task: String): String = when (task) {
        OUTLINE -> "🗂 设定卡（含分章大纲）"
        CHECK -> "🧾 设定体检 / 瘦身"
        POLISH -> "🚀 发布打磨"
        CHAPTER -> "✍️ 正文生成 / 重写"
        BOOKCHECK -> "🔍 全书体检"
        SELFCHK -> "🔧 全书自检 / 单章自检"
        else -> task
    }

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
 *  - v6.9.75：summaryCount（前情摘要章数）已废弃——用户要求彻底移除摘要注入，衔接只靠结尾+大纲窗口；
 *  - windowPrev：分章大纲窗口往前多注入几章（默认 2，即前两章+本章+后一章）。
 *  - v6.9.75：writerSystemOverride——用户自定义写章系统提示词（功能面板🧠「系统提示词」查看/修改），
 *    空 = 使用 Prompts.writerRules() 内置默认；非空 = 整体替换内置规则。
 */
object InjectPrefs {
    private const val FILE = "inject_prefs"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun setWindowPrev(ctx: Context, n: Int) = sp(ctx).edit().putInt("windowPrev", n.coerceIn(0, 10)).apply()
    fun windowPrev(ctx: Context): Int = sp(ctx).getInt("windowPrev", 2)

    // v6.9.47：每章自动体检开关——写完/重写/润色替换正文后是否自动跑一致性自检（默认开）
    fun setAutoCheck(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("autoCheck", on).apply()
    fun autoCheck(ctx: Context): Boolean = sp(ctx).getBoolean("autoCheck", true)

    // v6.9.54：全书自检修时同步润色去AI味（默认开）——每章查完矛盾后顺手按发布级文风重写，一键直达可发布
    fun setPolishWithCheck(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("polishWithCheck", on).apply()
    fun polishWithCheck(ctx: Context): Boolean = sp(ctx).getBoolean("polishWithCheck", true)

    // v6.9.73：智能分层注入（默认开）——本章登场人物/相关世界观整卡注入，
    // 未登场角色与无关条目自动降为一行档案（省 token 且一致性不丢）；关=全部整卡注入（旧行为）
    fun setLeanInject(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("leanInject", on).apply()
    fun leanInject(ctx: Context): Boolean = sp(ctx).getBoolean("leanInject", true)

    // v6.9.75：自定义写章系统提示词（空=内置默认 Prompts.writerRules()；非空=整体替换）
    fun setWriterSystemOverride(ctx: Context, s: String) = sp(ctx).edit().putString("writerSystemOverride", s).apply()
    fun writerSystemOverride(ctx: Context): String = sp(ctx).getString("writerSystemOverride", "") ?: ""
}

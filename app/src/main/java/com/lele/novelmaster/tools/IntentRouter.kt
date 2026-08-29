package com.lele.novelmaster.tools

import android.content.Context
import com.lele.novelmaster.data.CardCategories
import com.lele.novelmaster.data.Repo
import kotlinx.coroutines.flow.first

/**
 * 聊天意图路由器。
 *
 * 设计目标：把"和 AI 聊天"与"实际操作"无缝衔接。
 *
 * - 本地可确定性识别的指令（"写下一章"/"列出设定卡"/"开始自动写作"等）→ 直接调 Tools，不浪费 token
 * - 含义不明的对话（"你觉得这本书怎么样"/"我想让主角更强"）→ 返回 null，由 ChatScreen 发给 AI 自由对话
 *
 * 原则：宁可放过（让 AI 处理），不错杀（误执行本地工具）。
 */
object IntentRouter {

    /** 命中本地工具则返回 ToolResult；未命中返回 null（由调用方交给 AI） */
    suspend fun handle(
        input: String,
        currentPid: Long?,
        context: Context?
    ): ToolResult? {
        val raw = input.trim()
        if (raw.isBlank()) return null

        // 项目上下文必须是 long 才允许触发依赖项目的工具
        suspend fun needPid(): Long? {
            if (currentPid != null && currentPid > 0L) return currentPid
            val ps = Repo.dao.projectsFlow().first()
            if (ps.isEmpty()) return null
            return ps.first().id
        }

        val t = raw.lowercase()
        val noAi = t.replace(Regex("\\s"), "")

        // ------- 项目管理 -------
        if (Regex("^(开新书|新建小说|创建项目|新写一本|新开一本)").containsMatchIn(raw)) {
            // 让 AI 处理（更智能地抽取书名/类型）
            return null
        }
        Regex("切换?到?第?([0-9一二三四五六七八九十百千]+)本|切到项目([0-9]+)|打开第([0-9一二三四五六七八九十百千]+)本").find(raw)?.let { m ->
            val n = m.groupValues.filter { it.isNotEmpty() && it != m.value }.firstOrNull()?.let { parseChineseNum(it) }
                ?: m.groupValues[1].toIntOrNull() ?: 0
            if (n > 0L) {
                val ps = Repo.dao.projectsFlow().first()
                val pid = ps.getOrNull(n - 1)?.id
                return if (pid != null) Tools.switchProject(pid) else ToolResult(false, "找不到第 $n 本")
            }
        }

        // v5.5：本地快速改名 / 改目标章数（不用等 AI）
        Regex("(?:会话名|书名|小说名|改名叫)\\s*(?:改为|改成|为|叫)?\\s*[:：]?\\s*[《]?([^《》\\n]{1,20})[》]?").find(raw)?.let { m ->
            val pid = needPid() ?: return@let
            val name = m.groupValues[1].trim()
            if (name.isNotBlank() && name.length < 25) {
                return Tools.updateProject(pid, title = name)
            }
        }
        Regex("(?:目标|共|改成|改为)\\s*([0-9零一二三四五六七八九十百千]{1,4})\\s*章").find(raw)?.let { m ->
            val pid = needPid() ?: return@let
            val n = parseChineseNum(m.groupValues[1])
            if (n in 1..600) return Tools.updateProject(pid, totalCh = n)
        }

        // ------- 章节操作 -------
        if (Regex("^(写下?一?章|继续写|接着写|写吧|开写)").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先告诉我你要写哪本书，先创建或选一本。")
            return Tools.writeNextChapter(pid, context)
        }

        Regex("(?:写|写好|生成|产?出|第)\\s*([0-9零一二三四五六七八九十百千]{1,4})\\s*章(?!.*重写)").find(raw)?.let { m ->
            val idx = parseChineseNum(m.groupValues[1])
            if (idx in 1..100000) {
                val pid = needPid() ?: return ToolResult(false, "请先告诉我写哪本书")
                // 已存在该章则跳转查看，否则要求先创建分章骨架
                return Tools.readChapter(pid, idx).takeIf { it.ok } ?: ToolResult(true, "目标章节不在骨架中，请先创建项目并初始化大纲。")
            }
        }

        Regex("重写第?([0-9零一二三四五六七八九十百千]{1,4})章|重写这一?章").find(raw)?.let { m ->
            val idx = parseChineseNum(m.groupValues[1])
            val pid = needPid() ?: return ToolResult(false, "请先告诉我写哪本书")
            return Tools.rewriteChapter(pid, idx)
        }

        Regex("查看第?([0-9零一二三四五六七八九十百千]{1,4})章|阅读第?([0-9零一二三四五六七八九十百千]{1,4})章|第([0-9零一二三四五六七八九十百千]{1,4})章(是啥|是?什么|啥|写了什么|看看)").find(raw)?.let { m ->
            val idx = parseChineseNum(m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "0")
            val pid = needPid() ?: return ToolResult(false, "请先告诉我看哪本书")
            return Tools.readChapter(pid, idx)
        }

        if (Regex("(全部章节|章节列表|看看所有章节|所有章节|列章节)").containsMatchIn(raw)) {
            val onlyMissing = raw.contains("未写") || raw.contains("空") || raw.contains("待写")
            val pid = needPid() ?: return ToolResult(false, "请先告诉我看哪本书")
            return Tools.listChapters(pid, onlyMissing)
        }

        // ------- 设定卡 -------
        if (Regex("(所有)?设定卡(列表)?$|列出?(所有)?设定|列出?(所有)?卡片|我的设定").containsMatchIn(raw) ||
            Regex("^设定").containsMatchIn(raw)
        ) {
            val pid = needPid() ?: return ToolResult(false, "请先告诉我看哪本书")
            return Tools.listCards(pid)
        }
        val catMatch = findCategory(raw)
        if (Regex("^(列出|看看|显示|我要看|给我看|调出|查看)").containsMatchIn(raw) && catMatch != null) {
            val pid = needPid() ?: return ToolResult(false, "请先告诉我看哪本书")
            return Tools.listCards(pid, catMatch)
        }

        // 存入设定卡，例如：「把主角林墨的设定存到人物设定卡：性格高冷，...」
        if (raw.startsWith("把") && (raw.contains("存到") || raw.contains("归类") || raw.contains("归入") || raw.contains("放到"))) {
            // 交给 AI 更稳
            return null
        }
        if (raw.startsWith("记一下") || raw.startsWith("记住") || raw.startsWith("保存：") || raw.startsWith("保存:")) {
            return null
        }

        // ------- 自动写作 -------
        if (Regex("自动写作|一键写完|自动写完|自动写下去|全部?自动|开始自动").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先告诉我写哪本书")
            val rangePair = Regex("(从第)?([0-9零一二三四五六七八九十百千]{1,4})(章?)?(到|~|至|\\-)?第?([0-9零一二三四五六七八九十百千]{1,4})章?").find(noAi)
            val (from, to) = if (rangePair != null) {
                val a = parseChineseNum(rangePair.groupValues[2])
                val b = parseChineseNum(rangePair.groupValues[5])
                Pair(if (a in 1..100000) a else 1, if (b in 1..100000) b else 300)
            } else Pair(1, 300)
            return Tools.startAutoWrite(pid, from, to, context)
        }
        if (Regex("^(停止|暂停|中止|停).*(写|自动)|停止写作|别写了|暂停写作").containsMatchIn(raw)) {
            return Tools.stopAutoWrite()
        }

        // ------- 灵感 / 大纲 -------
        if (raw.startsWith("灵感") || raw.startsWith("我想写") || raw.startsWith("我的灵感") || raw.startsWith("帮我构思") || raw.startsWith("根据以下灵感")) {
            val text = raw.removePrefix("灵感：").removePrefix("灵感:").removePrefix("我的灵感：").removePrefix("我的灵感:")
                .removePrefix("我想写：").removePrefix("我想写:").removePrefix("帮我构思：").removePrefix("帮我构思:")
                .removePrefix("根据以下灵感：").removePrefix("根据以下灵感:")
            val pid = needPid() ?: return ToolResult(false, "请先创建一本书或告诉我写哪一本")
            return Tools.inspireFromText(pid, text, context)
        }

        if (Regex("(补|写|生成|生)(全|所有)?大纲|大纲生成|自动大纲").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先告诉我看哪本书")
            return Tools.generateOutlines(pid, context)
        }

        // ------- 模型 -------
        if (Regex("我的(AI|模型|接口|接入)|列出.*模型|我的接口|我接了?什么").containsMatchIn(raw)) {
            return Tools.listApis()
        }
        Regex("测试(连接)?(所有|全部|接口|AI)|测试一下连接").find(raw)?.let { _ ->
            val all = Repo.dao.apiConfigsFlow().first()
            if (all.isEmpty()) return ToolResult(false, "还没添加 AI 接口")
            return if (all.size == 1) Tools.testApi(all[0].id) else ToolResult(true, "请指定要测试哪一条接口的 id")
        }

        // ------- 导出 -------
        if (Regex("^(导出|打包|下载|生成txt|导出txt|导出文档|一键导出)").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先告诉我导哪一本")
            return Tools.exportTxt(pid, context)
        }

        // ------- 上下文注入预览 -------
        if (Regex("注入预览|预览上下文|看注入|注入什么|会注入|上下文预览").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.contextPreview(pid)
        }

        // ------- 专家级写作功能 -------
        val chapterNum = Regex("(?:第\\s*([0-9零一二三四五六七八九十百千]{1,4})\\s*章)").find(raw)?.let { parseChineseNum(it.groupValues[1]) } ?: -1
        if (Regex("润色").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.polishChapter(pid, chapterNum)
        }
        if (Regex("对话扩写|扩写对话|把.*改成对话|扩写").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.expandDialogue(pid, chapterNum)
        }
        Regex("(?:模仿|按|用)\\s*([^，。,]{2,15}?)\\s*(?:的)?风格|风格改写").find(raw)?.let { m ->
            val style = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() && !it.contains("风格") } ?: ""
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.styleRewrite(pid, chapterNum, style)
        }
        if (Regex("章末钩子|强化钩子|结尾钩子|优化钩子|钩子").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.hookChapter(pid, chapterNum)
        }
        if (Regex("金句|名场面台词").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.goldenLines(pid, chapterNum)
        }
        if (Regex("推演|剧情走向|后续剧情|接下来怎么写|接下来剧情").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.plotBrainstorm(pid)
        }
        Regex("人物?\\s*(体检|一致性)|检查(一下)?人物\\s*([^，。,\\s]{1,10})").find(raw)?.let { m ->
            val name = m.groupValues.drop(1).lastOrNull { it.isNotEmpty() && !it.contains("体检") && !it.contains("一致性") }?.trim() ?: ""
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            if (name.isBlank()) return ToolResult(false, "请说明检查哪个人物，如：体检林墨")
            return Tools.characterCheck(pid, name)
        }
        if (Regex("全书体检|一致性体检|一致性检查|体检|找矛盾|查矛盾").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.consistencyCheck(pid)
        }
        Regex("(起|取|来)\\s*([0-9]{0,3})\\s*[个组]?\\s*(人物名|人名|地名|功法|门派|法宝|势力|名字|名称)").find(raw)?.let { m ->
            val kind = m.groupValues[3].removeSuffix("名").ifBlank { "人物" }
            val count = m.groupValues[2].toIntOrNull() ?: 8
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.nameGen(pid, kind, count)
        }
        if (Regex("生成简介|写简介|发布简介|书名|简介").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.genBlurb(pid, context)
        }
        if (Regex("列出?文件|看看文件|打开文件|文件列表|项目文件").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            val fr = context?.let { com.lele.novelmaster.tools.FileTools.dispatch(it, pid, "listFiles", org.json.JSONObject()) }
            return fr ?: ToolResult(false, "文件系统不可用")
        }

        // ------- 自然语言文件操作：保存/读取/删除/修改 -------
        Regex("^保存到\\s*([^\\s:：]+)\\s*[:：]\\s*([\\s\\S]+)").find(raw)?.let { m ->
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            val path = m.groupValues[1]
            val content = m.groupValues[2]
            val args = org.json.JSONObject().put("path", path).put("content", content)
            return context?.let { com.lele.novelmaster.tools.FileTools.dispatch(it, pid, "writeFile", args) }
                ?: ToolResult(false, "文件系统不可用")
        }
        Regex("^保存[:：]\\s*([\\s\\S]+)").find(raw)?.let { m ->
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            val content = m.groupValues[1]
            val name = java.text.SimpleDateFormat("MMdd_HHmm", java.util.Locale.CHINA).format(java.util.Date())
            val args = org.json.JSONObject().put("path", "手动保存/保存_$name.md").put("content", content)
            return context?.let { com.lele.novelmaster.tools.FileTools.dispatch(it, pid, "createFile", args) }
                ?: ToolResult(false, "文件系统不可用")
        }
        Regex("^(读取|查看|打开)\\s*(文件\\s*)?([^\\s:：]+\\.(md|txt|json)|设定卡/.+|大纲/.+|正文/.+)").find(raw)?.let { m ->
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            val path = m.groupValues[3]
            val args = org.json.JSONObject().put("path", path)
            return context?.let { com.lele.novelmaster.tools.FileTools.dispatch(it, pid, "readFile", args) }
                ?: ToolResult(false, "文件系统不可用")
        }
        Regex("^删除文件\\s*([^\\s:：]+)").find(raw)?.let { m ->
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            val args = org.json.JSONObject().put("path", m.groupValues[1])
            return context?.let { com.lele.novelmaster.tools.FileTools.dispatch(it, pid, "deleteFile", args) }
                ?: ToolResult(false, "文件系统不可用")
        }
        Regex("^(新建文件夹|创建文件夹)\\s*([^\\s:：]+)").find(raw)?.let { m ->
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            val args = org.json.JSONObject().put("path", m.groupValues[2])
            return context?.let { com.lele.novelmaster.tools.FileTools.dispatch(it, pid, "createFolder", args) }
                ?: ToolResult(false, "文件系统不可用")
        }
        Regex("^(新建文件|创建文件)\\s*([^\\s:：]+)\\s*[:：]?\\s*([\\s\\S]*)").find(raw)?.let { m ->
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            val args = org.json.JSONObject().put("path", m.groupValues[2]).put("content", m.groupValues[3])
            return context?.let { com.lele.novelmaster.tools.FileTools.dispatch(it, pid, "createFile", args) }
                ?: ToolResult(false, "文件系统不可用")
        }

        // ------- 模糊匹配：删除 / 查卡号 -------
        Regex("删除(设定|卡|人物|世界观|伏笔)\\s*id\\s*([0-9]+)").find(raw)?.let { m ->
            val id = m.groupValues[2].toLong()
            val pid = needPid() ?: return ToolResult(false, "请先告诉我操作哪本书")
            return Tools.deleteCard(pid, id)
        }

        return null
    }

    /** 找输入里出现的分类（全字匹配） */
    private fun findCategory(raw: String): String? {
        var best: String? = null
        for (c in CardCategories.all) {
            if (raw.contains(c)) best = c
        }
        if (best != null) return best
        // 别名映射
        return when {
            raw.contains("人物") || raw.contains("角色") || raw.contains("主角") || raw.contains("配角") -> "人物设定"
            raw.contains("世界观") || raw.contains("世界设定") -> "世界观"
            raw.contains("主线") -> "主线剧情"
            raw.contains("支线") -> "支线任务"
            raw.contains("伏笔") || raw.contains("钩子") -> "伏笔钩子"
            raw.contains("冲突") || raw.contains("矛盾") -> "核心冲突"
            raw.contains("全书大纲") || raw.contains("总纲") -> "全书大纲"
            raw.contains("圣经") -> "设定圣经"
            raw.contains("进度") -> "剧情进度"
            raw.contains("辅助") -> "辅助设定"
            else -> null
        }
    }

    private fun parseChineseNum(s: String): Int {
        val map = mapOf(
            "零" to 0, "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
            "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10
        )
        if (s.isBlank()) return 0
        if (s.all { it.isDigit() }) return s.toIntOrNull() ?: 0
        var total = 0; var cur = 0; var lastUnit = 0
        for (ch in s) {
            val v = map[ch.toString()]
            when {
                v == null -> {}
                v == 10 -> { if (cur == 0) cur = 1; total += cur * 10; cur = 0; lastUnit = 10 }
                v < 10 -> if (lastUnit == 10) { cur = v; lastUnit = 0; total += v } else cur = v
            }
        }
        return total + cur
    }
}

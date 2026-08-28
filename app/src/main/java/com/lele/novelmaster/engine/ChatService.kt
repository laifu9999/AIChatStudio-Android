package com.lele.novelmaster.engine

import android.content.Context
import com.lele.novelmaster.data.AiClient
import com.lele.novelmaster.data.CardCategories
import com.lele.novelmaster.data.ChatMsg
import com.lele.novelmaster.data.Message
import com.lele.novelmaster.data.Prompts
import com.lele.novelmaster.data.Repo
import com.lele.novelmaster.tools.IntentRouter
import com.lele.novelmaster.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 聊天调度中枢。负责编排：
 *  1) 持久化用户消息（会话隔离：每本小说=一个会话，消息按 pid 分库存）
 *  2) 先走 IntentRouter（本地快速路由 → 不浪费 token）
 *  3) 路由未命中 → AI 自然对话；AI 可携带工具块（新旧模型兼容解析）由本地执行
 *  4) 结果与回复写回数据库
 */
object ChatService {

    /** 全部可被 AI 触发的工具名（用于校验与协议说明同步） */
    val KNOWN_TOOLS = setOf(
        "createProject", "addCard", "deleteCard", "writeNextChapter", "rewriteChapter",
        "startAutoWrite", "stopAutoWrite", "generateOutlines", "inspireFromText",
        "readChapter", "listCards", "listChapters", "exportTxt", "deleteProject",
        "contextPreview", "moveChapter", "copyChapter"
    )

    const val Welcome = "你好，我是乐乐 —— 你的小说写作 AI 助理 🪶\n\n" +
        "点右上「＋」新建会话即新建一本小说；每个会话的设定、章节、聊天记录完全独立。\n\n" +
        "你可以直接说：\n" +
        "  • 「我想写一本玄幻小说，主角叫林墨…」→ 我自动建书+生成整套设定卡+分章大纲\n" +
        "  • 「写下一章」「自动写作 1 到 600」→ 自动逐章写作并保存\n" +
        "  • 「把这段存到人物设定卡」→ 分类保存\n" +
        "  • 「给我看下一章会注入什么」→ 透明查看上下文\n\n" +
        "先到右上「AI 模型」添加一个（推荐智谱 glm-4-flash，免费）✨"

    /** 对外入口 */
    suspend fun handle(
        context: Context,
        currentPid: Long,
        input: String,
        onProjectChange: (Long) -> Unit
    ) {
        // 1. 持久化用户消息
        Repo.dao.insertMessage(Message(projectId = currentPid, role = "user", content = input))

        // 2. 本地快速路由（确定性指令，不浪费 token）
        val tool = IntentRouter.handle(input, currentPid, context)
        if (tool != null) {
            appendToolResult(currentPid, tool)
            tool.newProjectId?.let { onProjectChange(it) }
            if (tool.ok && tool.summary.startsWith("已删除会话")) {
                val ps = Repo.dao.projectsFlow().first()
                onProjectChange(ps.firstOrNull()?.id ?: 0L)
            }
            return
        }

        // 3. AI 对话（可携带工具块，由本地执行）
        val cfg = Repo.dao.activeApi()
        if (cfg == null) {
            val msg = "你还没启用任何 AI 模型。\n点右上「AI 模型」→ 添加一个（推荐智谱 glm-4-flash 免费）→ 选好模型 → 设为启用。"
            Repo.dao.insertMessage(Message(projectId = currentPid, role = "system", content = msg, kind = "text"))
            return
        }
        try {
            val reply = aiChat(cfg, currentPid, input)
            var pid = currentPid
            var deletedCurrent = false
            // 兼容两种格式：<tool>{...}</tool>（新模型）与 ```json {...} ```（老模型）
            val strict = Regex("<tool>\\s*(\\{.*?\\})\\s*</tool>", RegexOption.DOT_MATCHES_ALL)
            val fence = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```")
            val candidates = strict.findAll(reply).map { it.groupValues[1] } +
                fence.findAll(reply).map { it.groupValues[1] }.filter { it.contains("\"name\"") }
            var executed = 0
            val seen = mutableSetOf<String>()
            candidates.forEach { jsonText ->
                try {
                    if (!seen.add(jsonText)) return@forEach
                    val obj = org.json.JSONObject(jsonText)
                    val name = obj.optString("name")
                    if (name.isBlank() || name !in KNOWN_TOOLS) return@forEach
                    val args = obj.optJSONObject("args") ?: org.json.JSONObject()
                    val r = com.lele.novelmaster.tools.Tools.dispatch(pid, name, args, context)
                    if (r != null) {
                        appendToolResult(pid, r)
                        r.newProjectId?.let { np -> pid = np; onProjectChange(np) }
                        if (name == "deleteProject" && r.ok) deletedCurrent = true
                        executed++
                    }
                } catch (_: Exception) { /* 单块失败不影响整体 */ }
            }
            if (deletedCurrent) {
                val ps = Repo.dao.projectsFlow().first()
                pid = ps.firstOrNull()?.id ?: 0L
                onProjectChange(pid)
            }
            val text = reply.replace(strict, "").replace(fence) { m ->
                if (m.groupValues[1].contains("\"name\"")) "" else m.value
            }.trim()
            if (text.isNotBlank()) {
                Repo.dao.insertMessage(Message(projectId = pid, role = "assistant", content = text, kind = "text"))
            } else if (executed == 0) {
                Repo.dao.insertMessage(Message(projectId = pid, role = "assistant", content = reply.take(2000), kind = "text"))
            }
        } catch (e: Exception) {
            Repo.dao.insertMessage(
                Message(
                    projectId = currentPid,
                    role = "system",
                    content = "⚠️ AI 调用失败：${e.message?.take(300)}",
                    kind = "error"
                )
            )
        }
    }

    private suspend fun appendToolResult(pid: Long, r: ToolResult) {
        val text = if (r.detail.isBlank()) r.summary else r.summary + "\n\n" + r.detail
        val kind = if (r.ok) "tool" else "error"
        Repo.dao.insertMessage(Message(projectId = pid, role = "tool", content = text, kind = kind))
    }

    /** AI 自然对话：注入项目核心上下文（token 恒定）+ 工具协议 */
    private suspend fun aiChat(cfg: com.lele.novelmaster.data.ApiConfig, pid: Long, input: String): String {
        return withContext(Dispatchers.IO) {
            val sysText = buildString {
                appendLine(
                    "你是「乐乐」，一个专业网文写作 AI 助理，与作者协作创作长篇小说。请遵循：\n" +
                        "1) 用简洁自然的中文回复；不重复作者的话；讨论剧情/人物时给出具体、可执行的建议。\n" +
                        "2) 始终保持人物、世界观、伏笔的一致性（参考下方核心设定）。\n"
                )
                appendLine(
                    "【工具协议】当作者要求执行实际操作时（创建新书、保存设定、写章节、删除会话、导出等），" +
                        "在回复中输出工具调用块，格式：\n" +
                        "<tool>{\"name\":\"工具名\",\"args\":{\"参数名\":\"值\"}}</tool>\n" +
                        "可用工具（args 一律 JSON 对象）：\n" +
                        "- createProject: {title,genre,desc,totalCh,chWords}（作者描述完新书想法就建，totalCh 默认300）\n" +
                        "- addCard: {category,name,content}（category 必须是：" + CardCategories.all.joinToString("/") + "；作者让你记住/保存任何设定时用）\n" +
                        "- deleteCard: {cardId}\n" +
                        "- writeNextChapter: {}\n" +
                        "- rewriteChapter: {index}\n" +
                        "- startAutoWrite: {from,to}（1~600，自动写到实际最后一章自动停）\n" +
                        "- stopAutoWrite: {}\n" +
                        "- generateOutlines: {}\n" +
                        "- inspireFromText: {inspiration}\n" +
                        "- readChapter: {index}\n" +
                        "- listCards: {category(可空)}\n" +
                        "- listChapters: {onlyMissing}\n" +
                        "- moveChapter: {from,to}\n" +
                        "- copyChapter: {index}\n" +
                        "- deleteProject: {}（删除当前会话/小说，需作者明确说删除时才用）\n" +
                        "- contextPreview: {}（展示下一章将注入的上下文）\n" +
                        "- exportTxt: {}\n" +
                        "规则：可一次输出多个工具块；工具块后用一两句自然语言告诉作者已完成什么；" +
                        "纯聊天/讨论剧情时不要输出工具块。"
                )
                appendLine()
                if (pid > 0L) {
                    val project = Repo.dao.project(pid)
                    if (project != null) {
                        appendLine("【当前会话/小说】《${project.title}》 类型：${project.genre} 目标 ${project.targetChapters} 章 / 每章 ${project.chapterWordTarget} 字")
                        if (project.description.isNotBlank()) appendLine("简介：${project.description}")
                        val cards = Repo.dao.cards(pid)
                        val coreCards = cards.filter { it.priority == 2 || it.category in CardCategories.KEY_CATS }
                        if (coreCards.isNotEmpty()) {
                            appendLine()
                            appendLine("【核心设定（必保持一致）】")
                            appendLine(Prompts.cardBlock(coreCards))
                        }
                    }
                }
            }
            // 上下文：最近 6 条聊天消息（会话隔离：只取当前 pid 的记录）
            val recent = Repo.dao.messagesFlow(pid).first().takeLast(6)
            val chatMessages = mutableListOf<ChatMsg>()
            chatMessages.add(ChatMsg("system", sysText))
            recent.forEach { m ->
                if (m.kind == "text") chatMessages.add(ChatMsg(when (m.role) { "user" -> "user"; else -> "assistant" }, m.content))
            }
            chatMessages.add(ChatMsg("user", input))
            AiClient.chat(cfg, chatMessages, temperature = 0.85, maxTokens = 2000)
        }
    }
}

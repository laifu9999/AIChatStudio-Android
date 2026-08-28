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
 *  1) 持久化用户消息
 *  2) 先走 IntentRouter（本地快速路由 → 不浪费 token）
 *  3) 路由未命中 → 把上下文发给 AI 自然对话（与项目设定挂钩）
 *  4) 把回复写回数据库
 */
object ChatService {

    const val Welcome = "你好，我是乐乐 —— 你的小说写作 AI 助理 🪶\n\n" +
        "我可以直接帮你完成这些事：\n" +
        "  • 写下一章 / 写第 N 章 / 重写某章\n" +
        "  • 自动写作（自动补大纲 + 逐章生成 + 自动保存）\n" +
        "  • 帮你把任何灵感、人物、伏笔、世界观存到「设定卡」分类中\n" +
        "  • 列出设定卡 / 列出所有章节 / 查看任一章节\n" +
        "  • 一键导出整本书\n\n" +
        "先在右上「AI 模型」添加一个（推荐智谱 glm-4-flash，免费），然后告诉我：\n" +
        "  「我想写一本玄幻小说，主角叫林墨……」\n\n" +
        "或者直接发一个灵感给我，我自动帮你生成整套设定卡 ✨"

    /** 对外入口 */
    suspend fun handle(
        context: Context,
        currentPid: Long,
        input: String,
        onProjectChange: (Long) -> Unit
    ) {
        // 1. 持久化用户消息
        Repo.dao.insertMessage(Message(projectId = currentPid, role = "user", content = input))

        // 2. 先尝试本地路由（确定性指令，不浪费 token）
        val tool = IntentRouter.handle(input, currentPid, context)
        if (tool != null) {
            appendToolResult(currentPid, tool)
            tool.newProjectId?.let { onProjectChange(it) }
            return
        }

        // 3. 没命中 — AI 自然对话（AI 可在回复中携带工具调用块，由本地执行）
        val cfg = Repo.dao.activeApi()
        if (cfg == null) {
            val msg = "你还没启用任何 AI 模型。\n点击右上「AI 模型」→ 添加一个（推荐智谱 glm-4-flash 免费）→ 选好模型 → 设为启用。"
            Repo.dao.insertMessage(Message(projectId = currentPid, role = "system", content = msg, kind = "text"))
            return
        }
        try {
            val reply = aiChat(cfg, currentPid, input)
            // 解析并执行 AI 发起的工具调用
            var pid = currentPid
            val toolRegex = Regex("<tool>\\s*(\\{.*?\\})\\s*</tool>", RegexOption.DOT_MATCHES_ALL)
            var executed = 0
            toolRegex.findAll(reply).forEach { m ->
                try {
                    val obj = org.json.JSONObject(m.groupValues[1])
                    val name = obj.optString("name")
                    val args = obj.optJSONObject("args") ?: org.json.JSONObject()
                    val r = com.lele.novelmaster.tools.Tools.dispatch(pid, name, args, context)
                    if (r != null) {
                        appendToolResult(pid, r)
                        r.newProjectId?.let { np -> pid = np; onProjectChange(np) }
                        executed++
                    }
                } catch (_: Exception) { /* 单个工具块解析失败不影响整体 */ }
            }
            // 剩余文字部分（去掉工具块）作为 AI 回复
            val text = reply.replace(toolRegex, "").trim()
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

    /** AI 自然对话：注入项目核心上下文（不浪费 token）与最近的聊天记忆 + 工具协议 */
    private suspend fun aiChat(cfg: com.lele.novelmaster.data.ApiConfig, pid: Long, input: String): String {
        return withContext(Dispatchers.IO) {
            val sysText = buildString {
                appendLine(
                    "你是「乐乐」，一个专业网文写作 AI 助理，与作者协作创作长篇小说。请遵循：\n" +
                        "1) 用简洁自然的中文回复；不重复作者的话；讨论剧情/人物时给出具体建议。\n" +
                        "2) 始终保持人物、世界观、伏笔的一致性（参考下方核心设定）。\n"
                )
                appendLine(
                    "【工具协议】当作者要求执行实际操作时（如创建新书、保存设定、写章节、导出等），" +
                        "你必须同时输出工具调用块，格式严格为：\n" +
                        "<tool>{\"name\":\"工具名\",\"args\":{\"参数名\":\"值\"}}</tool>\n" +
                        "可用工具与参数（args 一律用 JSON）：\n" +
                        "- createProject: {title,genre,desc,totalCh,chWords}（作者描述完新书想法就建）\n" +
                        "- addCard: {category,name,content}（category 必须是：" + CardCategories.all.joinToString("/") + "；作者让你记住/保存任何设定时用）\n" +
                        "- writeNextChapter: {}（写下一章）\n" +
                        "- rewriteChapter: {index}\n" +
                        "- startAutoWrite: {from,to}（自动写完一段章节）\n" +
                        "- stopAutoWrite: {}\n" +
                        "- generateOutlines: {}（补全缺失大纲）\n" +
                        "- inspireFromText: {inspiration}（根据灵感生成整套设定卡）\n" +
                        "- readChapter: {index}\n" +
                        "- listCards: {category(可空)}\n" +
                        "- listChapters: {onlyMissing}\n" +
                        "- moveChapter: {from,to}\n" +
                        "- copyChapter: {index}\n" +
                        "- exportTxt: {}\n" +
                        "规则：可以一次输出多个工具块；工具块之后再用一两句自然语言告诉作者已完成什么；" +
                        "纯聊天/讨论剧情时不要输出任何工具块。"
                )
                appendLine()
                if (pid > 0L) {
                    val project = Repo.dao.project(pid)
                    if (project != null) {
                        appendLine("【当前项目】《${project.title}》 类型：${project.genre} 目标 ${project.targetChapters} 章 / 每章 ${project.chapterWordTarget} 字")
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
            // 上下文：最近的 6 条聊天消息
            val recent = if (pid > 0L) Repo.dao.messagesFlow(pid).first().takeLast(6)
                else Repo.dao.messagesFlow(-1).first().takeLast(6)
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

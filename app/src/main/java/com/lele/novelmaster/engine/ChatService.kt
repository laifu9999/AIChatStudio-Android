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

        // 2. 先尝试本地路由
        val tool = IntentRouter.handle(input, currentPid, context)
        if (tool != null) {
            // 创建项目这种特殊指令：返回值里没有摘要文本，但项目已经写入；onProjectChange 由 chat 调用方读最新项目
            appendToolResult(currentPid, tool)
            // 如果 IntentRouter 帮忙新建或切换了项目，主屏需要刷新当前选中
            // 重新拉项目列表，更新 PID
            val ps = Repo.dao.projectsFlow().first()
            if (ps.isNotEmpty() && (currentPid == 0L || ps.none { it.id == currentPid })) {
                onProjectChange(ps.first().id)
            }
            return
        }

        // 3. 没命中 — AI 自然对话
        val cfg = Repo.dao.activeApi()
        if (cfg == null) {
            val msg = "你还没启用任何 AI 模型。\n点击右上「AI 模型」→ 添加一个（推荐智谱 glm-4-flash 免费）→ 选好模型 → 设为启用。"
            Repo.dao.insertMessage(Message(projectId = currentPid, role = "system", content = msg, kind = "text"))
            return
        }
        try {
            val reply = aiChat(cfg, currentPid, input)
            Repo.dao.insertMessage(Message(projectId = currentPid, role = "assistant", content = reply, kind = "text"))
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

    /** AI 自然对话：注入项目核心上下文（不浪费 token）与最近的聊天记忆 */
    private suspend fun aiChat(cfg: com.lele.novelmaster.data.ApiConfig, pid: Long, input: String): String {
        return withContext(Dispatchers.IO) {
            val sysText = buildString {
                appendLine(
                    "你是「乐乐」，一个专业网文写作 AI 助理。当前正与作者协作创作长篇小说。请遵循：\n" +
                        "1) 用简洁、自然的中文回复，作者说的是中文你也用中文。\n" +
                        "2) 不要重复作者说过的话；若作者正在讨论剧情/人物/情节，鼓励并给出具体建议。\n" +
                        "3) 作者可能要求你：写下一章、修改某章、增删设定卡、查看设定卡、导出等；如果能直接执行会直接完成，无需你重复具体步骤。\n" +
                        "4) 始终保持人物、世界观、伏笔的一致性。\n"
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
            AiClient.chat(cfg, chatMessages, temperature = 0.85, maxTokens = 1500)
        }
    }
}

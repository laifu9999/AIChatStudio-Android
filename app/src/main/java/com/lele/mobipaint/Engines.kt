package com.lele.mobipaint

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 进程级协程宿主：所有 AI 长任务都跑在这里，与界面生命周期解耦（切页/旋转不打断）。 */
object AppScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

data class UiMsg(val role: String, val text: String)

data class ChatUi(
    val msgs: List<UiMsg> = emptyList(),
    val streaming: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null
)

/**
 * 聊天创作引擎：一书一任务，按 pid 并行互不影响。
 * · 发送消息 → 流式回复 → 设定沉淀/题材回写 → 落库
 * · 更新剧情记忆：通读设定/章节/对话，产出 600~900 字主线记忆
 */
object ChatEngine {

    private val states = ConcurrentHashMap<Long, MutableStateFlow<ChatUi>>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val stopFlags = ConcurrentHashMap<Long, AtomicBoolean>()

    fun state(pid: Long): StateFlow<ChatUi> =
        states.getOrPut(pid) { MutableStateFlow(loadFromDb(pid)) }

    private fun loadFromDb(pid: Long): ChatUi {
        val rows = Db.listChat(pid, 200)
        val msgs = rows.map { UiMsg(it.role, it.content) }.toMutableList()
        if (msgs.isEmpty()) {
            msgs.add(UiMsg("assistant",
                "👋 我是你的创作伙伴，咱们像聊天一样把这本书写出来！\n\n" +
                "你可以直接丢给我一个灵感，比如：\n" +
                "· 「开局被退婚，觉醒吞噬系统」——帮我写第一章\n" +
                "· 「帮我设计一套修仙力量体系」\n" +
                "· 「把目前的剧情整理成记忆，继续往下写」\n\n" +
                "想自动写整本？点上方「🤖 连写」，从第1章写到第999章都行。"))
        }
        return ChatUi(msgs = msgs)
    }

    /** 从数据库重载（连写回执等后台写入的内容回来后可见）。 */
    fun reload(pid: Long) {
        val f = states.getOrPut(pid) { MutableStateFlow(loadFromDb(pid)) }
        val busy = jobs.containsKey(pid)
        val cur = f.value
        f.value = loadFromDb(pid).copy(
            busy = busy && cur.busy,
            streaming = if (busy) cur.streaming else "",
            info = cur.info)
    }

    fun busy(pid: Long): Boolean = jobs.containsKey(pid) &&
        (states[pid]?.value?.busy ?: false)

    fun stop(pid: Long) {
        stopFlags.getOrPut(pid) { AtomicBoolean(false) }.set(true)
        jobs.remove(pid)?.cancel()
        val f = states[pid] ?: return
        val v = f.value
        if (v.streaming.isNotEmpty()) {
            // 手动停止：保留已生成部分到对话
            Db.addChat(pid, "assistant", v.streaming)
        }
        f.value = v.copy(busy = false, streaming = v.streaming,
            info = "⏹ 已停止，已生成部分已保留。")
    }

    fun send(pid: Long, userText: String, cfg: AiConfig) {
        val f = states.getOrPut(pid) { MutableStateFlow(loadFromDb(pid)) }
        if (f.value.busy) return
        val text = userText.trim()
        if (text.isEmpty()) return
        Db.addChat(pid, "user", text)
        val msgs = f.value.msgs + UiMsg("user", text)
        f.value = f.value.copy(msgs = msgs, busy = true, streaming = "",
            error = null, info = null)
        val stopFlag = AtomicBoolean(false)
        stopFlags[pid] = stopFlag
        val job = AppScope.scope.launch {
            try {
                val context = Prompts.chatMessages(pid, text)
                val buf = StringBuilder()
                var lastEmit = 0L
                val reply = AiClient.chatRounds(cfg, context, onChunk = { piece ->
                    buf.append(piece)
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 150) {
                        lastEmit = now
                        f.value = f.value.copy(streaming = buf.toString())
                    }
                }, cancelled = { stopFlag.get() })
                if (stopFlag.get()) {
                    // stop() 已负责收尾落库
                    return@launch
                }
                val visible = Protocols.visibleOf(reply).ifBlank { reply }
                val applied = Protocols.apply(null, pid, reply)
                val suffix = if (applied.isEmpty()) ""
                    else "\n\n" + applied.joinToString("\n") { "✅ $it" }
                Db.addChat(pid, "assistant", visible + suffix)
                f.value = f.value.copy(
                    msgs = f.value.msgs + UiMsg("assistant", visible + suffix),
                    streaming = "", busy = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // stop() 已处理收尾
            } catch (e: Exception) {
                val msg = "❌ 出错了：${e.message ?: e.toString()}"
                Db.addChat(pid, "assistant", msg)
                f.value = f.value.copy(
                    msgs = f.value.msgs + UiMsg("assistant", msg),
                    streaming = "", busy = false)
            } finally {
                jobs.remove(pid)
                stopFlags.remove(pid)
            }
        }
        jobs[pid] = job
    }

    /** 更新主线剧情记忆（对话页「🧠 记忆」按钮）。 */
    fun refreshMemory(pid: Long, cfg: AiConfig) {
        val f = states.getOrPut(pid) { MutableStateFlow(loadFromDb(pid)) }
        if (f.value.busy) return
        f.value = f.value.copy(busy = true, streaming = "", error = null,
            info = "🧠 正在通读设定、章节与对话，更新主线剧情记忆……")
        val stopFlag = AtomicBoolean(false)
        stopFlags[pid] = stopFlag
        val job = AppScope.scope.launch {
            try {
                val len = refreshMemoryCore(pid, cfg, { stopFlag.get() })
                if (stopFlag.get()) return@launch
                val receipt = "（🤖 主线剧情记忆已更新（${len}字），" +
                    "之后每一章写作都会自动注入，写到999章也记得住前情。）"
                Db.addChat(pid, "assistant", receipt)
                f.value = f.value.copy(
                    msgs = f.value.msgs + UiMsg("assistant", receipt),
                    busy = false, info = null)
            } catch (e: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) {
                f.value = f.value.copy(busy = false, info = null,
                    error = "记忆更新失败：${e.message ?: e.toString()}")
            } finally {
                jobs.remove(pid)
                stopFlags.remove(pid)
            }
        }
        jobs[pid] = job
    }

    /** 记忆生成核心（连写每 20 章也会调用）；返回记忆字数。 */
    suspend fun refreshMemoryCore(
        pid: Long,
        cfg: AiConfig,
        cancelled: () -> Boolean = { false }
    ): Int {
        val prompt = Prompts.memoryPrompt(pid)
        if (prompt.isEmpty()) return 0
        val reply = AiClient.chatRounds(cfg, listOf(
            Pair("system", Prompts.CHAT_SYSTEM),
            Pair("user", prompt)), cancelled = cancelled)
        val memory = reply.trim()
        if (memory.isNotEmpty()) Db.updateProject(pid, memory = memory)
        return memory.length
    }
}

data class BatchUi(
    val running: Boolean = false,
    val progress: String = "",
    val done: String? = null
)

/**
 * 自动连写引擎：从第 X 章写到第 Y 章，每章全量上下文（记忆+设定+前情），
 * 每章入库并写聊天回执，每 20 章自动刷新剧情记忆，成稿过短自动重写。
 */
object BatchEngine {

    private val states = ConcurrentHashMap<Long, MutableStateFlow<BatchUi>>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val stopFlags = ConcurrentHashMap<Long, AtomicBoolean>()

    fun state(pid: Long): StateFlow<BatchUi> =
        states.getOrPut(pid) { MutableStateFlow(BatchUi()) }

    fun running(pid: Long): Boolean = jobs.containsKey(pid)

    fun stop(pid: Long) {
        stopFlags.getOrPut(pid) { AtomicBoolean(false) }.set(true)
        jobs.remove(pid)?.cancel()
        val f = states[pid] ?: return
        f.value = f.value.copy(running = false,
            progress = f.value.progress + "\n⏹ 已停止")
    }

    fun start(pid: Long, from: Int, to: Int, cfg: AiConfig) {
        val f = states.getOrPut(pid) { MutableStateFlow(BatchUi()) }
        if (f.value.running) return
        if (to < from) return
        f.value = BatchUi(running = true, progress = "开始连写：第${from}~${to}章",
            done = null)
        val stopFlag = AtomicBoolean(false)
        stopFlags[pid] = stopFlag
        val job = AppScope.scope.launch {
            try {
                for (no in from..to) {
                    currentCoroutineContext().ensureActive()
                    f.value = f.value.copy(progress = "正在写第${no}章（共${to}章）……")
                    writeOne(pid, no, cfg, f, stopFlag)
                    if (stopFlag.get()) break
                    // 每 20 章自动刷新剧情记忆（含最后一章）
                    if (no % 20 == 0 || no == to) {
                        f.value = f.value.copy(progress =
                            f.value.progress + "\n🧠 正在刷新主线剧情记忆……")
                        try {
                            ChatEngine.refreshMemoryCore(pid, cfg, { stopFlag.get() })
                        } catch (e: Exception) {
                            // 记忆刷新失败不中断连写
                        }
                    }
                    withContext(Dispatchers.Main) { ChatEngine.reload(pid) }
                }
                f.value = f.value.copy(running = false,
                    progress = f.value.progress,
                    done = "✅ 连写完成：第${from}~${to}章已全部入库。")
            } catch (e: kotlinx.coroutines.CancellationException) {
                f.value = f.value.copy(running = false)
            } catch (e: Exception) {
                f.value = f.value.copy(running = false,
                    progress = f.value.progress,
                    done = "❌ 连写出错：${e.message ?: e.toString()}（已完成章节均已保存）")
            } finally {
                jobs.remove(pid)
                stopFlags.remove(pid)
                withContext(Dispatchers.Main) { ChatEngine.reload(pid) }
            }
        }
        jobs[pid] = job
    }

    private suspend fun writeOne(
        pid: Long,
        no: Int,
        cfg: AiConfig,
        f: MutableStateFlow<BatchUi>,
        stopFlag: AtomicBoolean
    ) {
        val prompt = Prompts.batchChapterPrompt(pid, no)
        var best = ""
        // 成稿校验：不足 1200 字自动重写，最多 3 次，取最长
        for (attempt in 0 until 3) {
            val buf = StringBuilder()
            val text = AiClient.chatRounds(cfg, listOf(
                Pair("system", Prompts.CHAT_SYSTEM),
                Pair("user", prompt)), onChunk = { piece ->
                buf.append(piece)
                f.value = f.value.copy(progress =
                    "正在写第${no}章（第${attempt + 1}次）……已生成${buf.length}字")
            }, cancelled = { stopFlag.get() })
            if (text.length > best.length) best = text
            if (best.length >= 1200) break
        }
        if (best.isEmpty()) {
            throw AiClient.AiException("第${no}章连续多次生成失败")
        }
        val visible = Protocols.visibleOf(best).ifBlank { best }
        val (title, body) = Protocols.splitTitle(no, visible)
        Db.upsertChapter(pid, no, title, body)
        Db.addChat(pid, "assistant",
            "（🤖 自动连写：第${no}章《${title}》已写入（${body.length}字））")
    }
}

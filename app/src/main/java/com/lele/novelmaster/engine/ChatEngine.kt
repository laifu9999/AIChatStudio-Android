package com.lele.novelmaster.engine

import android.content.Context
import com.lele.novelmaster.data.AiClient
import com.lele.novelmaster.data.Message
import com.lele.novelmaster.data.Repo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * v6.9.35：聊天生成引擎——进程级单例，生成流的宿主不再挂在界面组合上。
 * 此前 chatJob 跑在 rememberCoroutineScope 里，点功能面板跳转页面 / 切到章节列表等任何离开
 * 聊天界面的操作都会销毁组合、取消协程，导致"AI 生成到一半会话就断了"。
 * 现在生成在单例 scope 里：除非用户主动点停止（发送键）或看门狗判定真卡死（5 分钟无字节），
 * 按任何按钮、离开会话、切别的书，生成都照常进行，回来还能看到实时进度。
 *
 * v6.9.44：按书并行——此前全局单流，A 书生成中切到 B 书连消息都发不出（send 静默忽略），
 * 且在 B 书点发送键会误停 A 书的生成。现在每本书独立 job / 独立状态 / 独立计时，
 * 多本书同时各聊各的互不影响；看门狗仍用全局活动时间戳（任一 AI 出字节都算活着）。
 */
object ChatEngine {

    /** 聊天界面观察这份状态即可：忙闲 / 流式文本 / 本次生成所属会话 / 已用秒数 */
    data class Ui(
        val busy: Boolean = false,
        val streamingText: String? = null,
        val pid: Long = 0L,
        val elapsedSec: Int = 0
    )

    /** 每本书一份生成状态；界面按 currentPid 取自己的那份 */
    val state = MutableStateFlow<Map<Long, Ui>>(emptyMap())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    /** 任意一本书正在生成（旧全局语义，供兜底判断用） */
    fun busy() = state.value.values.any { it.busy }

    /** 指定这本书是否正在生成 */
    fun busy(pid: Long) = state.value[pid]?.busy == true

    /**
     * 发送一条消息并启动本书的生成流。本书已在生成中时忽略（界面会给提示）；
     * 其他书在生成不影响——v6.9.44 起多本书可同时各聊各的。
     * onNewPid：工具新建/切换了项目时通知界面跟随。
     */
    fun send(ctx: Context, pid: Long, text: String, onNewPid: (Long) -> Unit = {}) {
        val t = text.trim()
        if (t.isEmpty() || busy(pid)) return
        state.update { it + (pid to Ui(busy = true, streamingText = "", pid = pid)) }
        var lastStream = ""
        // v6.9.46：发送即重置全局活动时间戳——不重置的话上一轮活动的旧 idle 会连累新任务开局被误杀
        AiClient.lastActivityMs = System.currentTimeMillis()
        jobs[pid] = scope.launch {
            // 看门狗：活动感知，5 分钟无任何流式字节才判定卡死强制结束（每出一个字都算活着）
            // v6.9.46：非流式调用（plainInflight>0，如体检/自检排队中）全程没有字节回流，豁免不杀
            val guard = launch {
                while (true) {
                    delay(15_000)
                    if (!busy(pid)) break
                    val idle = System.currentTimeMillis() - AiClient.lastActivityMs
                    if (idle > 300_000 && AiClient.plainInflight == 0) {
                        runCatching {
                            Repo.dao.insertMessage(
                                Message(projectId = pid, role = "system", kind = "error",
                                    content = "⚠️ 本次请求超过 5 分钟没有任何响应，已自动停止。请再发一次，或换一个模型试试。")
                            )
                        }
                        jobs[pid]?.cancel()
                        break
                    }
                }
            }
            // 已用秒数计时（离开界面也继续走，回来数字还是连续的）
            val ticker = launch {
                while (true) {
                    delay(1000)
                    val cur = state.value[pid] ?: break
                    if (!cur.busy) break
                    state.update { m -> m[pid]?.let { m + (pid to it.copy(elapsedSec = it.elapsedSec + 1)) } ?: m }
                }
            }
            try {
                ChatService.handle(ctx, pid, t, { newPid -> if (newPid != 0L) onNewPid(newPid) }) { s ->
                    lastStream = s
                    state.update { m -> m[pid]?.let { m + (pid to it.copy(streamingText = s)) } ?: m }
                }
            } catch (e: CancellationException) {
                // 被停止（手动停止或看门狗）：落库已生成的部分，不再白花已产出的 token
                if (lastStream.isNotBlank()) {
                    runCatching {
                        Repo.dao.insertMessage(
                            Message(projectId = pid, role = "assistant", content = lastStream, kind = "text")
                        )
                    }
                }
                throw e
            } finally {
                guard.cancel()
                ticker.cancel()
                state.update { it - pid }
                jobs.remove(pid)
            }
        }
    }

    /** 用户主动停止当前这本书：取消生成（已产出的部分文字由 catch 落库），自动写作按书停止由界面负责 */
    fun stop(pid: Long) {
        jobs[pid]?.cancel()
    }

    /** 兼容旧调用：停掉所有正在进行的生成 */
    fun stopAll() {
        jobs.values.forEach { it.cancel() }
    }
}

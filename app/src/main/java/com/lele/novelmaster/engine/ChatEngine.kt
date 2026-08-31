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
 */
object ChatEngine {

    /** 聊天界面观察这份状态即可：忙闲 / 流式文本 / 本次生成所属会话 / 已用秒数 */
    data class Ui(
        val busy: Boolean = false,
        val streamingText: String? = null,
        val pid: Long = 0L,
        val elapsedSec: Int = 0
    )

    val state = MutableStateFlow(Ui())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun busy() = state.value.busy

    /**
     * 发送一条消息并启动生成。busy 时直接忽略（不打断进行中的生成，也不排队），
     * 由界面给出提示。onNewPid：工具新建/切换了项目时通知界面跟随。
     */
    fun send(ctx: Context, pid: Long, text: String, onNewPid: (Long) -> Unit = {}) {
        val t = text.trim()
        if (t.isEmpty() || state.value.busy) return
        state.value = Ui(busy = true, streamingText = "", pid = pid)
        var lastStream = ""
        job = scope.launch {
            // 看门狗：活动感知，5 分钟无任何流式字节才判定卡死强制结束（每出一个字都算活着）
            val guard = launch {
                while (true) {
                    delay(15_000)
                    if (!state.value.busy) break
                    val idle = System.currentTimeMillis() - AiClient.lastActivityMs
                    if (idle > 300_000) {
                        runCatching {
                            Repo.dao.insertMessage(
                                Message(projectId = pid, role = "system", kind = "error",
                                    content = "⚠️ 本次请求超过 5 分钟没有任何响应，已自动停止。请再发一次，或换一个模型试试。")
                            )
                        }
                        job?.cancel()
                        break
                    }
                }
            }
            // 已用秒数计时（离开界面也继续走，回来数字还是连续的）
            val ticker = launch {
                while (true) {
                    delay(1000)
                    if (!state.value.busy) break
                    state.update { it.copy(elapsedSec = it.elapsedSec + 1) }
                }
            }
            try {
                ChatService.handle(ctx, pid, t, { newPid -> if (newPid != 0L) onNewPid(newPid) }) { s ->
                    lastStream = s
                    state.update { it.copy(streamingText = s) }
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
                state.update { it.copy(busy = false, streamingText = null) }
            }
        }
    }

    /** 用户主动停止：取消生成（已产出的部分文字由 catch 落库），自动写作按书停止由界面负责 */
    fun stop() {
        job?.cancel()
        job = null
    }
}

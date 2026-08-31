package com.lele.novelmaster.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * v6.9.36：界面级长任务（设定体检/补全大纲等）的进程级宿主。
 * 此前 CardsScreen 把体检/补大纲跑在 rememberCoroutineScope 里，用户切走页面任务就被取消，
 * 白花 token 还以为修好了。现在任务跑在这里：离开页面照常完成，改卡/建卡效果照常落库。
 * 界面用 running 集合驱动按钮忙闲（key 自定义，如 "cardsCheck:<pid>"）。
 */
object AppTasks {

    data class St(val running: Set<String> = emptySet())

    val state = MutableStateFlow(St())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun isRunning(key: String): Boolean = key in state.value.running

    /**
     * 启动一个长任务；同名任务已在跑则返回 null（由界面提示，不重复启动）。
     * 任务完成/失败都会自动从 running 移除。
     */
    fun launch(key: String, block: suspend CoroutineScope.() -> Unit): Job? {
        while (true) {
            val cur = state.value
            if (key in cur.running) return null
            if (state.compareAndSet(cur, cur.copy(running = cur.running + key))) break
        }
        return scope.launch {
            try {
                block()
            } finally {
                state.update { it.copy(running = it.running - key) }
            }
        }
    }
}

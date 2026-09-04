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

    data class St(
        val running: Set<String> = emptySet(),
        // v6.9.41：任务实时进度文案（key → 进度描述），界面随任务展示「正在生成 x/y…」
        val progress: Map<String, String> = emptyMap()
    )

    val state = MutableStateFlow(St())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // v6.9.40：登记任务句柄，支持删除书时按 pid 取消关联任务
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    fun isRunning(key: String): Boolean = key in state.value.running

    /** v6.9.41：任务内实时上报进度（如「已生成 30/300 章」「正在体检 第3/23张卡」） */
    fun setProgress(key: String, msg: String) {
        state.update { it.copy(progress = it.progress + (key to msg)) }
    }

    /**
     * 启动一个长任务；同名任务已在跑则返回 null（由界面提示，不重复启动）。
     * 任务完成/失败都会自动从 running 移除。
     */
    fun launch(key: String, block: suspend CoroutineScope.() -> Unit): Job? {
        while (true) {
            val cur = state.value
            if (key in cur.running) return null
            if (state.compareAndSet(cur, cur.copy(running = cur.running + key, progress = cur.progress - key))) break
        }
        val job = scope.launch {
            try {
                block()
            } finally {
                jobs.remove(key)
                state.update { it.copy(running = it.running - key, progress = it.progress - key) }
            }
        }
        jobs[key] = job
        return job
    }

    /**
     * v6.9.40：取消该书关联的全部页面长任务（key 以 ":pid" 结尾的，如 inspire/outline/cardsCheck）。
     * 删除书时调用——书都没了，任务继续跑只会白烧 token。按章 id 为 key 的任务（编辑器续写/重写）
     * 在章节被级联删除后会自然快速失败，无需专门取消。
     */
    fun cancelProject(pid: Long) {
        jobs.keys.filter { it.endsWith(":$pid") }.forEach { jobs[it]?.cancel() }
    }
}

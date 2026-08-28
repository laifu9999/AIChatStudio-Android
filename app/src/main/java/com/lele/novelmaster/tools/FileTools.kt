package com.lele.novelmaster.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * AI 自由文件系统 —— 每个会话（小说）一个独立空间：
 *   {filesDir}/novels/{pid}/files/...
 * AI 可自主：建文件夹/建文件/写入/追加/更新/读取/删除/重命名（文件与文件夹）/列目录。
 * 所有路径限制在会话目录内（防穿越），用户未要求的重要资料 AI 可自行建文件归类保存。
 */
object FileTools {

    val NAMES = setOf(
        "createFolder", "deleteFolder", "renameFolder",
        "createFile", "writeFile", "appendFile", "readFile",
        "deleteFile", "renameFile", "listFiles"
    )

    fun baseDir(context: Context, pid: Long): File =
        File(File(context.filesDir, "novels/$pid"), "files").apply { mkdirs() }

    private fun resolve(context: Context, pid: Long, rawPath: String): Pair<File, File> {
        val base = baseDir(context, pid)
        val cleaned = rawPath.trim().trimStart('/').ifBlank { "." }
        val f = File(base, cleaned)
        // 防路径穿越
        if (!f.canonicalPath.startsWith(base.canonicalPath)) {
            throw SecurityException("路径越界：$rawPath")
        }
        return base to f
    }

    /** 分发器；返回 null 表示不是文件工具 */
    suspend fun dispatch(context: Context, pid: Long, name: String, args: JSONObject): ToolResult? {
        if (name !in NAMES) return null
        val path = args.optString("path", args.optString("folder", args.optString("file", "")))
        return try {
            withContext(Dispatchers.IO) {
                when (name) {
                    "createFolder" -> {
                        val (base, f) = resolve(context, pid, path)
                        f.mkdirs()
                        ToolResult(true, "已创建文件夹", rel(base, f))
                    }
                    "deleteFolder" -> {
                        val (base, f) = resolve(context, pid, path)
                        if (!f.exists() || !f.isDirectory) return@withContext ToolResult(false, "文件夹不存在：$path")
                        val n = f.walkBottomUp().count { it.isFile }
                        f.deleteRecursively()
                        ToolResult(true, "已删除文件夹（含 $n 个文件）", rel(base, f))
                    }
                    "renameFolder" -> rename(context, pid, path, args)
                    "renameFile" -> rename(context, pid, path, args)
                    "createFile" -> {
                        val (base, f) = resolve(context, pid, path)
                        if (f.exists()) return@withContext ToolResult(false, "文件已存在（可用 writeFile 覆盖）：$path")
                        f.parentFile?.mkdirs()
                        f.writeText(args.optString("content"), Charsets.UTF_8)
                        ToolResult(true, "已创建文件并写入 ${f.length()} 字", rel(base, f))
                    }
                    "writeFile" -> {
                        val (base, f) = resolve(context, pid, path)
                        f.parentFile?.mkdirs()
                        f.writeText(args.optString("content"), Charsets.UTF_8)
                        ToolResult(true, "已写入文件（${f.length()} 字，覆盖模式）", rel(base, f))
                    }
                    "appendFile" -> {
                        val (base, f) = resolve(context, pid, path)
                        f.parentFile?.mkdirs()
                        f.appendText(args.optString("content"), Charsets.UTF_8)
                        ToolResult(true, "已追加写入（现共 ${f.length()} 字）", rel(base, f))
                    }
                    "readFile" -> {
                        val (_, f) = resolve(context, pid, path)
                        if (!f.exists() || !f.isFile) return@withContext ToolResult(false, "文件不存在：$path")
                        val text = f.readText(Charsets.UTF_8)
                        ToolResult(true, "《${f.name}》内容（${text.length} 字）：", text.take(3000) + if (text.length > 3000) "\n…（过长截断，可用 listFiles+readFile 分段看）" else "")
                    }
                    "deleteFile" -> {
                        val (base, f) = resolve(context, pid, path)
                        if (!f.exists() || !f.isFile) return@withContext ToolResult(false, "文件不存在：$path")
                        f.delete()
                        ToolResult(true, "已删除文件", rel(base, f))
                    }
                    "listFiles" -> {
                        val (base, f) = resolve(context, pid, path)
                        if (!f.exists()) return@withContext ToolResult(false, "路径不存在：$path")
                        val items = if (f.isDirectory) {
                            f.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
                        } else listOf(f)
                        if (items.isEmpty()) return@withContext ToolResult(true, "目录为空", rel(base, f))
                        val text = items.joinToString("\n") { c ->
                            val relPath = rel(base, c)
                            if (c.isDirectory) "📁 $relPath/" else "📄 $relPath（${c.length()}字）"
                        }
                        ToolResult(true, "共 ${items.size} 项：", text)
                    }
                    else -> null
                }
            }
        } catch (e: Exception) {
            ToolResult(false, "文件操作失败：${e.message?.take(200)}")
        }
    }

    private fun rename(context: Context, pid: Long, path: String, args: JSONObject): ToolResult {
        val (base, f) = resolve(context, pid, path)
        if (!f.exists()) return ToolResult(false, "不存在：$path")
        val newName = args.optString("newName").trim().trimStart('/')
        if (newName.isBlank()) return ToolResult(false, "newName 不能为空")
        val target = File(f.parentFile, newName)
        if (target.exists()) return ToolResult(false, "目标已存在：$newName")
        if (!f.renameTo(target)) return ToolResult(false, "重命名失败")
        return ToolResult(true, "已重命名", rel(base, f) + " → " + rel(base, target))
    }

    private fun rel(base: File, f: File): String {
        val b = base.canonicalPath
        val c = f.canonicalPath
        return if (c.startsWith(b)) c.removePrefix(b).trimStart('/').ifBlank { "." } else c
    }
}

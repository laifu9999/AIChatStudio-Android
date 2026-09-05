package com.lele.lelenote

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条笔记：文字 + 若干截图 */
data class Note(
    val id: Long,
    val text: String,
    val images: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long
)

/** 笔记 JSON 存储 + 截图文件管理 + 导出/导入（图片转 base64 内嵌） */
object NoteStore {

    private fun file(ctx: Context): File = File(ctx.filesDir, "notes.json")
    private fun screenDir(ctx: Context): File =
        File(ctx.filesDir, "screens").apply { if (!exists()) mkdirs() }

    fun load(ctx: Context): MutableList<Note> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val root = JSONObject(f.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("notes") ?: JSONArray()
            val out = mutableListOf<Note>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val imgs = mutableListOf<String>()
                val ia = o.optJSONArray("images")
                if (ia != null) for (j in 0 until ia.length()) imgs.add(ia.getString(j))
                out.add(
                    Note(
                        id = o.getLong("id"),
                        text = o.optString("text"),
                        images = imgs,
                        createdAt = o.optLong("createdAt"),
                        updatedAt = o.optLong("updatedAt")
                    )
                )
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(ctx: Context, notes: List<Note>) {
        val arr = JSONArray()
        notes.forEach { n ->
            val o = JSONObject()
            o.put("id", n.id)
            o.put("text", n.text)
            o.put("createdAt", n.createdAt)
            o.put("updatedAt", n.updatedAt)
            val ia = JSONArray()
            n.images.forEach { ia.put(it) }
            o.put("images", ia)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("app", "LeleNote")
        root.put("version", 1)
        root.put("notes", arr)
        val tmp = File(ctx.filesDir, "notes.json.tmp")
        tmp.writeText(root.toString(2), Charsets.UTF_8)
        if (file(ctx).exists()) file(ctx).delete()
        tmp.renameTo(file(ctx))
    }

    fun upsert(ctx: Context, note: Note) {
        val list = load(ctx)
        val idx = list.indexOfFirst { it.id == note.id }
        if (idx >= 0) list[idx] = note else list.add(0, note)
        save(ctx, list)
    }

    fun delete(ctx: Context, id: Long) {
        val list = load(ctx)
        list.removeAll { it.id == id }
        save(ctx, list)
    }

    fun newScreenshotFile(ctx: Context): File =
        File(screenDir(ctx), "s_${System.currentTimeMillis()}.jpg")

    /** 导出全部笔记（含截图 base64），返回 JSON 文本 */
    fun exportJson(ctx: Context, notes: List<Note>): String {
        val arr = JSONArray()
        notes.forEach { n ->
            val o = JSONObject()
            o.put("id", n.id)
            o.put("text", n.text)
            o.put("createdAt", n.createdAt)
            o.put("updatedAt", n.updatedAt)
            val ia = JSONArray()
            n.images.forEach { p ->
                val f = File(p)
                if (f.exists()) {
                    val img = JSONObject()
                    img.put("name", f.name)
                    img.put("b64", android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
                    ia.put(img)
                }
            }
            o.put("images", ia)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("app", "LeleNote")
        root.put("version", 1)
        root.put("notes", arr)
        return root.toString(2)
    }

    /** 导入备份 JSON：截图落盘、id 顺延避冲突。返回导入条数 */
    fun importJson(ctx: Context, text: String): Int {
        val root = JSONObject(text)
        val arr = root.optJSONArray("notes") ?: return 0
        val list = load(ctx)
        var nextId = (list.maxOfOrNull { it.id } ?: 0L) + 1L
        var count = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val imgs = mutableListOf<String>()
            val ia = o.optJSONArray("images")
            if (ia != null) {
                for (j in 0 until ia.length()) {
                    try {
                        val img = ia.getJSONObject(j)
                        val name = img.optString("name")
                        val b64 = img.optString("b64")
                        if (b64.isNotBlank()) {
                            var f = File(screenDir(ctx), if (name.isNotBlank()) name else "i_${nextId}_$j.jpg")
                            if (f.exists()) f = File(screenDir(ctx), "i_${nextId}_$j.jpg")
                            f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP))
                            imgs.add(f.absolutePath)
                        }
                    } catch (_: Exception) { }
                }
            }
            list.add(
                0,
                Note(
                    id = nextId++,
                    text = o.optString("text"),
                    images = imgs,
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                )
            )
            count++
        }
        if (count > 0) save(ctx, list)
        return count
    }

    /** 缩略图解码（省内存） */
    fun decodeThumb(path: String, req: Int): Bitmap? {
        return try {
            val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bo)
            var sample = 1
            while (bo.outWidth / sample > req * 2 || bo.outHeight / sample > req * 2) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        }
    }
}

/** 媒体键：让大多数视频 App 暂停 / 续播 */
object MediaCtl {
    private fun send(ctx: Context, code: Int) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } catch (_: Exception) { }
    }

    fun pause(ctx: Context) = send(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
    fun play(ctx: Context) = send(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
}

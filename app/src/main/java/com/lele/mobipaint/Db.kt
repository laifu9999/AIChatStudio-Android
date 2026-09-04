package com.lele.mobipaint

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** 轻量数据层：书档案 / 设定卡 / 章节 / 聊天记录（SQLite）。 */
object Db {
    data class Project(
        val id: Long,
        val title: String,
        val genre: String,
        val brief: String,
        val memory: String
    )

    data class SettingRow(val id: Long, val pid: Long, val cat: String,
                          val title: String, val content: String)

    data class Chapter(val id: Long, val pid: Long, val no: Int,
                       val title: String, val content: String, val words: Int)

    data class ChatMsg(val id: Long, val pid: Long, val role: String,
                       val content: String, val ts: Long)

    private lateinit var helper: DbHelper

    fun init(ctx: Context) {
        if (!::helper.isInitialized) helper = DbHelper(ctx.applicationContext)
    }

    private class DbHelper(ctx: Context) :
        SQLiteOpenHelper(ctx, "mobipaint.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE projects(id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, genre TEXT DEFAULT '', brief TEXT DEFAULT '', " +
                "memory TEXT DEFAULT '', created_at INTEGER)")
            db.execSQL("CREATE UNIQUE INDEX idx_projects_title ON projects(title)")
            db.execSQL("CREATE TABLE settings(id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "pid INTEGER NOT NULL, cat TEXT NOT NULL, title TEXT NOT NULL, " +
                "content TEXT DEFAULT '')")
            db.execSQL("CREATE UNIQUE INDEX idx_settings ON settings(pid, cat, title)")
            db.execSQL("CREATE TABLE chapters(id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "pid INTEGER NOT NULL, no INTEGER NOT NULL, title TEXT DEFAULT '', " +
                "content TEXT DEFAULT '', words INTEGER DEFAULT 0)")
            db.execSQL("CREATE UNIQUE INDEX idx_chapters ON chapters(pid, no)")
            db.execSQL("CREATE TABLE chat(id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "pid INTEGER NOT NULL, role TEXT NOT NULL, content TEXT DEFAULT '', " +
                "ts INTEGER)")
        }

        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }

    // ---------- projects ----------
    fun createProject(title: String, genre: String, brief: String): Long {
        val cv = ContentValues().apply {
            put("title", title); put("genre", genre); put("brief", brief)
            put("memory", ""); put("created_at", System.currentTimeMillis())
        }
        return helper.writableDatabase.insertWithOnConflict(
            "projects", null, cv, SQLiteDatabase.CONFLICT_FAIL)
    }

    fun listProjects(): List<Project> {
        val out = ArrayList<Project>()
        helper.readableDatabase.rawQuery(
            "SELECT id, title, genre, brief, memory FROM projects ORDER BY id DESC", null)
            .use { c ->
                while (c.moveToNext()) out.add(Project(
                    c.getLong(0), c.getString(1), c.getString(2) ?: "",
                    c.getString(3) ?: "", c.getString(4) ?: ""))
            }
        return out
    }

    fun project(id: Long): Project? {
        helper.readableDatabase.rawQuery(
            "SELECT id, title, genre, brief, memory FROM projects WHERE id=?",
            arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) return Project(
                c.getLong(0), c.getString(1), c.getString(2) ?: "",
                c.getString(3) ?: "", c.getString(4) ?: "")
        }
        return null
    }

    fun updateProject(id: Long, title: String? = null, genre: String? = null,
                      brief: String? = null, memory: String? = null) {
        val cv = ContentValues()
        if (title != null) cv.put("title", title)
        if (genre != null) cv.put("genre", genre)
        if (brief != null) cv.put("brief", brief)
        if (memory != null) cv.put("memory", memory)
        if (cv.size() > 0) helper.writableDatabase.update(
            "projects", cv, "id=?", arrayOf(id.toString()))
    }

    fun deleteProject(id: Long) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("projects", "id=?", arrayOf(id.toString()))
            db.delete("settings", "pid=?", arrayOf(id.toString()))
            db.delete("chapters", "pid=?", arrayOf(id.toString()))
            db.delete("chat", "pid=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- settings ----------
    fun upsertSetting(pid: Long, cat: String, title: String, content: String) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("settings", "pid=? AND cat=? AND title=?",
                arrayOf(pid.toString(), cat, title))
            val cv = ContentValues().apply {
                put("pid", pid); put("cat", cat); put("title", title)
                put("content", content)
            }
            db.insert("settings", null, cv)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listSettings(pid: Long): List<SettingRow> {
        val out = ArrayList<SettingRow>()
        helper.readableDatabase.rawQuery(
            "SELECT id, pid, cat, title, content FROM settings WHERE pid=? ORDER BY id",
            arrayOf(pid.toString())).use { c ->
            while (c.moveToNext()) out.add(SettingRow(
                c.getLong(0), c.getLong(1), c.getString(2) ?: "",
                c.getString(3) ?: "", c.getString(4) ?: ""))
        }
        return out
    }

    // ---------- chapters ----------
    fun upsertChapter(pid: Long, no: Int, title: String, content: String) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("chapters", "pid=? AND no=?",
                arrayOf(pid.toString(), no.toString()))
            val cv = ContentValues().apply {
                put("pid", pid); put("no", no); put("title", title)
                put("content", content); put("words", content.length)
            }
            db.insert("chapters", null, cv)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listChapters(pid: Long): List<Chapter> {
        val out = ArrayList<Chapter>()
        helper.readableDatabase.rawQuery(
            "SELECT id, pid, no, title, content, words FROM chapters " +
                "WHERE pid=? ORDER BY no", arrayOf(pid.toString())).use { c ->
            while (c.moveToNext()) out.add(Chapter(
                c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3) ?: "",
                c.getString(4) ?: "", c.getInt(5)))
        }
        return out
    }

    fun maxChapterNo(pid: Long): Int {
        helper.readableDatabase.rawQuery(
            "SELECT MAX(no) FROM chapters WHERE pid=?", arrayOf(pid.toString()))
            .use { c -> if (c.moveToFirst()) return c.getInt(0) }
        return 0
    }

    fun chapter(pid: Long, no: Int): Chapter? {
        helper.readableDatabase.rawQuery(
            "SELECT id, pid, no, title, content, words FROM chapters WHERE pid=? AND no=?",
            arrayOf(pid.toString(), no.toString())).use { c ->
            if (c.moveToFirst()) return Chapter(
                c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3) ?: "",
                c.getString(4) ?: "", c.getInt(5))
        }
        return null
    }

    fun totalWords(pid: Long): Int {
        helper.readableDatabase.rawQuery(
            "SELECT SUM(words) FROM chapters WHERE pid=?", arrayOf(pid.toString()))
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getInt(0) }
        return 0
    }

    // ---------- chat ----------
    fun addChat(pid: Long, role: String, content: String) {
        val cv = ContentValues().apply {
            put("pid", pid); put("role", role); put("content", content)
            put("ts", System.currentTimeMillis())
        }
        helper.writableDatabase.insert("chat", null, cv)
    }

    fun listChat(pid: Long, limit: Int): List<ChatMsg> {
        val out = ArrayList<ChatMsg>()
        helper.readableDatabase.rawQuery(
            "SELECT id, pid, role, content, ts FROM chat WHERE pid=? " +
                "ORDER BY id DESC LIMIT ?", arrayOf(pid.toString(), limit.toString()))
            .use { c ->
                while (c.moveToNext()) out.add(ChatMsg(
                    c.getLong(0), c.getLong(1), c.getString(2) ?: "",
                    c.getString(3) ?: "", c.getLong(4)))
            }
        out.reverse()
        return out
    }

    fun clearChat(pid: Long) {
        helper.writableDatabase.delete("chat", "pid=?", arrayOf(pid.toString()))
    }
}

package com.lele.novelmaster.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 小说项目 */
@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val genre: String = "",
    val description: String = "",
    val targetChapters: Int = 300,
    val chapterWordTarget: Int = 2500,
    // v6.9.34：本书绑定的独立 AI 接口（0=跟随全局「已启用」接口；多书并行时各书可用不同模型）
    val apiConfigId: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/** 章节 */
@Entity(tableName = "chapters", indices = [Index("projectId")])
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val chapterIndex: Int,
    val title: String = "",
    val outline: String = "",
    val content: String = "",
    val summary: String = "",
    val status: Int = 0, // 0待写 1 AI稿 2已编辑
    val wordCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 设定卡：小说所有设定内容的分类保存单元
 * category: 全书大纲/世界观/人物设定/主线剧情/支线任务/伏笔钩子/核心冲突/设定圣经/剧情进度/辅助设定
 * priority: 0低频(几乎不发) 1常规(智能匹配) 2每章必发
 * status:   伏笔钩子专用：埋设中 / 已回收
 */
@Entity(tableName = "setting_cards", indices = [Index("projectId")])
data class SettingCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val category: String,
    val name: String,
    val content: String,
    val priority: Int = 1,
    val status: String = "",
    // v6.9.41：是否参与写章注入——设定卡页可按卡开关（关掉的卡只存档不注入，省 token）
    val inject: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

/** AI 接口配置 */
@Entity(tableName = "api_configs")
data class ApiConfig(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val provider: String = "openai", // openai兼容 | gemini
    val baseUrl: String,
    val apiKey: String,
    val model: String = "",
    val isActive: Boolean = false
)

/**
 * 聊天消息（像豆包/元宝一样持久化所有对话）
 * role: user | assistant | system | tool
 * projectId: 0 = 通用对话（无当前项目），其余对应小说项目
 * kind: text | action（操作摘要卡片） | divider | error
 */
@Entity(tableName = "messages", indices = [Index("projectId"), Index("createdAt")])
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val role: String,
    val content: String,
    val kind: String = "text",
    val createdAt: Long = System.currentTimeMillis()
)

object CardCategories {
    val all = listOf(
        "全书大纲", "世界观", "人物设定", "主线剧情", "支线任务",
        "伏笔钩子", "核心冲突", "设定圣经", "剧情进度", "时间线", "辅助设定"
    )
    val KEY_CATS = setOf("全书大纲", "世界观", "主线剧情", "核心冲突", "设定圣经", "剧情进度")
}

@Dao
interface NovelDao {
    // 项目
    @Query("SELECT * FROM projects ORDER BY id DESC")
    fun projectsFlow(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun project(id: Long): Project?

    @Insert
    suspend fun insertProject(p: Project): Long

    @Update
    suspend fun updateProject(p: Project)

    @Delete
    suspend fun deleteProject(p: Project)

    // 章节
    @Query("SELECT * FROM chapters WHERE projectId = :pid ORDER BY chapterIndex")
    fun chaptersFlow(pid: Long): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE projectId = :pid ORDER BY chapterIndex")
    suspend fun chapters(pid: Long): List<Chapter>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun chapter(id: Long): Chapter?

    @Insert
    suspend fun insertChapter(c: Chapter): Long

    @Insert
    suspend fun insertChapters(cs: List<Chapter>)

    @Update
    suspend fun updateChapter(c: Chapter)

    @Delete
    suspend fun deleteChapter(c: Chapter)

    @Query("DELETE FROM chapters WHERE projectId = :pid")
    suspend fun deleteChaptersOf(pid: Long)

    // 设定卡
    @Query("SELECT * FROM setting_cards WHERE projectId = :pid ORDER BY category, id")
    fun cardsFlow(pid: Long): Flow<List<SettingCard>>

    @Query("SELECT * FROM setting_cards WHERE projectId = :pid")
    suspend fun cards(pid: Long): List<SettingCard>

    @Query("SELECT * FROM setting_cards WHERE projectId = :pid AND category = :cat AND name = :name LIMIT 1")
    suspend fun findCard(pid: Long, cat: String, name: String): SettingCard?

    @Insert
    suspend fun insertCard(c: SettingCard): Long

    @Update
    suspend fun updateCard(c: SettingCard)

    @Delete
    suspend fun deleteCard(c: SettingCard)

    @Query("DELETE FROM setting_cards WHERE projectId = :pid")
    suspend fun deleteCardsOf(pid: Long)

    // AI 配置
    @Query("SELECT * FROM api_configs ORDER BY id")
    fun apiConfigsFlow(): Flow<List<ApiConfig>>

    @Query("SELECT * FROM api_configs WHERE isActive = 1 LIMIT 1")
    suspend fun activeApi(): ApiConfig?

    /** v6.9.34：按 id 取接口（本书独立模型绑定用） */
    @Query("SELECT * FROM api_configs WHERE id = :id")
    suspend fun apiConfig(id: Long): ApiConfig?

    @Insert
    suspend fun insertApi(c: ApiConfig): Long

    @Update
    suspend fun updateApi(c: ApiConfig)

    @Delete
    suspend fun deleteApi(c: ApiConfig)

    @Query("UPDATE api_configs SET isActive = 0")
    suspend fun clearActiveApi()

    // 聊天消息
    @Query("SELECT * FROM messages WHERE projectId = :pid ORDER BY createdAt ASC, id ASC")
    fun messagesFlow(pid: Long): Flow<List<Message>>

    @Insert
    suspend fun insertMessage(m: Message): Long

    /** v6.1：流式写章时实时更新同一条消息（正文边生成边显示在聊天里） */
    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: Long, content: String)

    @Query("DELETE FROM messages WHERE projectId = :pid")
    suspend fun clearMessages(pid: Long)
}

@Database(
    entities = [Project::class, Chapter::class, SettingCard::class, ApiConfig::class, Message::class],
    version = 4,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): NovelDao
}

/** v6.9.34：projects 新增本书独立 AI 模型绑定列——非破坏迁移，老用户数据（章节/设定卡/配置）全部保留 */
private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE projects ADD COLUMN apiConfigId INTEGER NOT NULL DEFAULT 0")
    }
}

/** v6.9.41：setting_cards 新增 inject 列（是否参与写章注入），非破坏迁移 */
private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE setting_cards ADD COLUMN inject INTEGER NOT NULL DEFAULT 1")
    }
}

object Repo {
    lateinit var dao: NovelDao
        private set

    // v6.8.1：全局 Context，供 rewriteChapter/chapterTask 等无 context 参数的路径同步本地文件用
    var app: Context? = null
        private set

    fun init(context: Context) {
        app = context.applicationContext
        if (!::dao.isInitialized) {
            dao = Room.databaseBuilder(context, AppDb::class.java, "novel_master.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
                .dao()
        }
    }

    /**
     * v6.9.34：解析一本书应使用的 AI 接口——本书绑定了独立模型就用它（绑定失效自动回落），
     * 否则用全局「已启用」接口。多书并行时各书各用各的模型，互不影响。
     */
    suspend fun apiFor(pid: Long): ApiConfig? {
        if (pid > 0L) {
            try {
                val p = dao.project(pid)
                if (p != null && p.apiConfigId != 0L) {
                    dao.apiConfig(p.apiConfigId)?.let { return it }
                }
            } catch (_: Exception) { }
        }
        return dao.activeApi()
    }
}

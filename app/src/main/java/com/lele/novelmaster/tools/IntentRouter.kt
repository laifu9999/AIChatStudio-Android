package com.lele.novelmaster.tools

import android.content.Context
import com.lele.novelmaster.data.CardCategories
import com.lele.novelmaster.data.Repo
import kotlinx.coroutines.flow.first

/**
 * 聊天意图路由器。
 *
 * 设计目标：把"和 AI 聊天"与"实际操作"无缝衔接。
 *
 * - 本地可确定性识别的指令（"写下一章"/"列出设定卡"/"开始自动写作"等）→ 直接调 Tools，不浪费 token
 * - 含义不明的对话（"你觉得这本书怎么样"/"我想让主角更强"）→ 返回 null，由 ChatScreen 发给 AI 自由对话
 *
 * 原则：宁可放过（让 AI 处理），不错杀（误执行本地工具）。
 */
object IntentRouter {

    /** 命中本地工具则返回 ToolResult；未命中返回 null（由调用方交给 AI） */
    suspend fun handle(
        input: String,
        currentPid: Long?,
        context: Context?
    ): ToolResult? {
        val raw = input.trim()
        if (raw.isBlank()) return null

        // 项目上下文必须是 long 才允许触发依赖项目的工具
        suspend fun needPid(): Long? {
            if (currentPid != null && currentPid > 0L) return currentPid
            val ps = Repo.dao.projectsFlow().first()
            if (ps.isEmpty()) return null
            return ps.first().id
        }

        val t = raw.lowercase()
        val noAi = t.replace(Regex("\\s"), "")

        // v5.6：疑问句一律交给 AI 按上一轮信息回答，绝不本地执行"写章/读章"等动作
        // 例："都好了吗，可以开始写第一章了没有" → AI 回答准备状态，而不是真的开写
        val asking = Regex("[??？]|吗\\s*$|好了没有|好了吗|可以了吗|准备好了|是不是|能不能|可不可以|什么时候").containsMatchIn(raw) ||
            raw.trimEnd().endsWith("没有")

        // v5.7：AI 上一轮明显没写完（结尾没收束）时，作者说「继续/继续写/接着写/往下」= 让它接着输出。
        // 这类指令绝不能被本地路由截去"写下一章"——那正是"叫继续没反应 / 反而直接开写下一章"的根因。
        val cont = Regex("^(继续|接着|往下|然后呢|还有呢|没写完|写下去|continue)", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        var resuming = false
        if (cont && currentPid != null && currentPid > 0L) {
            val last = Repo.dao.messagesFlow(currentPid).first()
                .lastOrNull { (it.role == "assistant" || it.role == "tool") &&
                    !it.content.startsWith("📖") && !it.content.startsWith("✍️") }
            val s = last?.content?.trimEnd().orEmpty()
            resuming = s.isNotEmpty() && s.last() !in "。！？…」』”\"!?~）)】"
        }

        // ------- 项目管理 -------
        if (Regex("^(开新书|新建小说|创建项目|新写一本|新开一本)").containsMatchIn(raw)) {
            // 让 AI 处理（更智能地抽取书名/类型）
            return null
        }
        Regex("切换?到?第?([0-9一二三四五六七八九十百千]+)本|切到项目([0-9]+)|打开第([0-9一二三四五六七八九十百千]+)本").find(raw)?.let { m ->
            val n = m.groupValues.filter { it.isNotEmpty() && it != m.value }.firstOrNull()?.let { parseChineseNum(it) }
                ?: m.groupValues[1].toIntOrNull() ?: 0
            if (n > 0L) {
                val ps = Repo.dao.projectsFlow().first()
                val pid = ps.getOrNull(n - 1)?.id
                return if (pid != null) Tools.switchProject(pid) else ToolResult(false, "找不到第 $n 本")
            }
        }

        // v5.5：本地快速改名 / 改目标章数（不用等 AI）
        // v6.0：创作请求（帮写…共36章）绝不能被这里的"改章数/改名"小路由截胡——
        //       之前「帮写一个家庭幸福的小说，共36章」只改了个章数就返回，AI 根本没开始写书
        val creativeReq = Regex("帮写|帮我写|写一[本个部]|来一[本个部]|创作|写个|开一[本个部]").containsMatchIn(raw)
        if (!creativeReq) {
            Regex("(?:会话名|书名|小说名|改名叫)\\s*(?:改为|改成|为|叫)?\\s*[:：]?\\s*[《]?([^《》\\n]{1,20})[》]?").find(raw)?.let { m ->
                val pid = needPid() ?: return@let
                val name = m.groupValues[1].trim()
                if (name.isNotBlank() && name.length < 25) {
                    return Tools.updateProject(pid, title = name)
                }
            }
            Regex("(?:目标|共|改成|改为)\\s*([0-9零一二三四五六七八九十百千]{1,4})\\s*章").find(raw)?.let { m ->
                val pid = needPid() ?: return@let
                val n = parseChineseNum(m.groupValues[1])
                if (n in 1..600) return Tools.updateProject(pid, totalCh = n)
            }
        } // v6.0: !creativeReq

        // ------- 章节操作 -------
        // v5.7：正在续写上一轮未写完的内容时，一切交给 AI，本地不再抢着写章
        if (resuming) return null

        // v6.0：设定卡和分章大纲已就绪时，「继续」= 写下一章（完整正文），不问不绕直接干
        val nextStep = Regex("^(继续|接着|往下写|写下去|continue)", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        if (nextStep) {
            val pid = needPid()
            if (pid != null) {
                val cards = Repo.dao.cards(pid)
                val chapters = Repo.dao.chapters(pid)
                val nextCh = chapters.firstOrNull { it.content.isBlank() }
                val ready = cards.isNotEmpty() && nextCh != null && nextCh.outline.isNotBlank()
                if (ready) return Tools.writeNextChapter(pid, context)
            }
            return null   // 资料没就绪 → 交给 AI 按流程把剩下的步骤做完
        }

        // v6.0：「开始写第一章 / 开写 / 写下一章」= 真的写完整一章，绝不只给个梗概
        if (Regex("^(开始写|开写|写下?一?章|继续写|接着写|写吧|动笔)").containsMatchIn(raw) ||
            Regex("(开写|开始写)[吧吧]?第一?章").containsMatchIn(raw)
        ) {
            val pid = needPid() ?: return ToolResult(false, "请先告诉我你要写哪本书，先创建或选一本。")
            return Tools.writeNextChapter(pid, context)
        }

        Regex("(?:写|写好|生成|产?出|第)\\s*([0-9零一二三四五六七八九十百千]{1,4})\\s*章(?!.*重写)").find(raw)?.let { m ->
            if (asking) return null   // 疑问句：让 AI 回答，不执行
            // v6.9.19：纯「第N章」命中（前面没有写/生成等动词）且输入含专家动作词时，
            // 这是「自检第5章/撤销第5章/重写第5章/补写第5章」这类指令的一部分，必须放行给后面的专家路由，
            // 否则会在这里被当成「查看/写第N章」截胡（回归模拟确认 v6.9.8~v6.9.18 的 14 条用例全部中招）。
            // v6.9.20：动作词补「改成对话/对话体/对话」（覆盖扩写路由的「把第N章改成对话体/对话改生动」语序）
            if (m.value.startsWith("第") &&
                Regex("自检|检查|体检|撤销|还原|回滚|恢复|重写|补写|润色|扩写|风格|钩子|金句|伏笔|支线|推演|一致性|矛盾|改成对话|对话体|对话|大纲").containsMatchIn(raw)
            ) return@let
            val idx = parseChineseNum(m.groupValues[1])
            if (idx in 1..100000) {
                val pid = needPid() ?: return ToolResult(false, "请先告诉我写哪本书")
                // v6.0：这一章还没写（内容为空）→ 写它；已写过 → 才是"查看"
                val chapters = Repo.dao.chaptersFlow(pid).first()
                val target = chapters.firstOrNull { it.chapterIndex == idx }
                if (target != null && target.content.isBlank()) {
                    return Tools.writeNextChapter(pid, context)
                }
                return Tools.readChapter(pid, idx).takeIf { it.ok } ?: ToolResult(true, "目标章节不在骨架中，请先创建项目并初始化大纲。")
            }
        }

        // v6.9.19：章节号提取上移 + 风格改写路由上移——「用金庸的风格重写第5章」应走风格改写而非被重写截胡
        val chapterNum = Regex("(?:第\\s*([0-9零一二三四五六七八九十百千]{1,4})\\s*章)").find(raw)?.let { parseChineseNum(it.groupValues[1]) } ?: -1
        Regex("(?:模仿|按|用)\\s*([^，。,]{2,15}?)\\s*(?:的)?风格|风格改写").find(raw)?.let { m ->
            val style = m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() && !it.contains("风格") } ?: ""
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.styleRewrite(pid, chapterNum, style)
        }
        Regex("重写第?([0-9零一二三四五六七八九十百千]{1,4})章|重写这一?章").find(raw)?.let { m ->
            val idx = parseChineseNum(m.groupValues[1])
            val pid = needPid() ?: return ToolResult(false, "请先告诉我写哪本书")
            return Tools.rewriteChapter(pid, idx)
        }

        Regex("查看第?([0-9零一二三四五六七八九十百千]{1,4})章|阅读第?([0-9零一二三四五六七八九十百千]{1,4})章|第([0-9零一二三四五六七八九十百千]{1,4})章(是啥|是?什么|啥|写了什么|看看)").find(raw)?.let { m ->
            val idx = parseChineseNum(m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "0")
            val pid = needPid() ?: return ToolResult(false, "请先告诉我看哪本书")
            return Tools.readChapter(pid, idx)
        }

        if (Regex("(全部章节|章节列表|看看所有章节|所有章节|列章节)").containsMatchIn(raw)) {
            val onlyMissing = raw.contains("未写") || raw.contains("空") || raw.contains("待写")
            val pid = needPid() ?: return ToolResult(false, "请先告诉我看哪本书")
            return Tools.listChapters(pid, onlyMissing)
        }

        // v6.9.22：设定体检——必须放在设定卡列表路由之前（「设定体检」以「设定」开头、「检查设定卡」以「设定卡」结尾，都会被下面两条截胡）
        if (Regex("设定体检|卡片体检|检查设定|设定一致性|(体检|检查|校对).{0,2}设定卡").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先告诉我体检哪本书")
            return Tools.cardsCheck(pid)
        }

        // v6.9.29：查看体检报告——放在设定体检之后（「体检报告」不含"设定"不会撞上面的路由），只读最近一份报告不跑AI
        if (Regex("(查看|看|打开|调出).{0,4}体检报告|体检报告|体检结果|上(一次|次|回).{0,3}体检").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先告诉我要看哪本书的报告")
            return Tools.cardsCheckReport(pid)
        }

        // ------- 设定卡 -------
        if (Regex("(所有)?设定卡(列表)?$|列出?(所有)?设定|列出?(所有)?卡片|我的设定").containsMatchIn(raw) ||
            Regex("^设定").containsMatchIn(raw)
        ) {
            val pid = needPid() ?: return ToolResult(false, "请先告诉我看哪本书")
            return Tools.listCards(pid)
        }
        val catMatch = findCategory(raw)
        if (Regex("^(列出|看看|显示|我要看|给我看|调出|查看)").containsMatchIn(raw) && catMatch != null) {
            val pid = needPid() ?: return ToolResult(false, "请先告诉我看哪本书")
            return Tools.listCards(pid, catMatch)
        }

        // 存入设定卡，例如：「把主角林墨的设定存到人物设定卡：性格高冷，...」
        if (raw.startsWith("把") && (raw.contains("存到") || raw.contains("归类") || raw.contains("归入") || raw.contains("放到"))) {
            // 交给 AI 更稳
            return null
        }
        if (raw.startsWith("记一下") || raw.startsWith("记住") || raw.startsWith("保存：") || raw.startsWith("保存:")) {
            return null
        }

        // ------- 自动写作 -------
        if (!asking && Regex("自动写作|一键写完|自动写完|自动写下去|全部?自动|开始自动").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先告诉我写哪本书")
            val rangePair = Regex("(从第)?([0-9零一二三四五六七八九十百千]{1,4})(章?)?(到|~|至|\\-)?第?([0-9零一二三四五六七八九十百千]{1,4})章?").find(noAi)
            val (from, to) = if (rangePair != null) {
                val a = parseChineseNum(rangePair.groupValues[2])
                val b = parseChineseNum(rangePair.groupValues[5])
                Pair(if (a in 1..100000) a else 1, if (b in 1..100000) b else 300)
            } else Pair(1, 300)
            return Tools.startAutoWrite(pid, from, to, context)
        }
        if (Regex("^(停止|暂停|中止|停).*(写|自动)|停止写作|别写了|暂停写作").containsMatchIn(raw)) {
            // pid 为空时 stopAutoWrite 会按「有任务在跑则停全部」处理
            val pid = needPid()
            return Tools.stopAutoWrite(pid)
        }

        // ------- 灵感 / 大纲 -------
        if (raw.startsWith("灵感") || raw.startsWith("我想写") || raw.startsWith("我的灵感") || raw.startsWith("帮我构思") || raw.startsWith("根据以下灵感")) {
            val text = raw.removePrefix("灵感：").removePrefix("灵感:").removePrefix("我的灵感：").removePrefix("我的灵感:")
                .removePrefix("我想写：").removePrefix("我想写:").removePrefix("帮我构思：").removePrefix("帮我构思:")
                .removePrefix("根据以下灵感：").removePrefix("根据以下灵感:")
            val pid = needPid() ?: return ToolResult(false, "请先创建一本书或告诉我写哪一本")
            return Tools.inspireFromText(pid, text, context)
        }

        // v6.9.31：手动修改第N章大纲——唯一的大纲人工修改入口，改完自动同步分章大纲镜像卡；
        // 放在生成大纲路由之前（「修改第3章大纲」不被「写大纲」类截胡）
        Regex("(?:修改|改成?|更新|换掉?|重设)\\s*(?:一下)?\\s*第\\s*([0-9零一二三四五六七八九十百千]{1,4})\\s*章\\s*(?:的)?大纲|第\\s*([0-9零一二三四五六七八九十百千]{1,4})\\s*章\\s*的?\\s*大纲\\s*(?:改成|改为|换成|修改为)").find(raw)?.let { m ->
            val idx = parseChineseNum(m.groupValues[1].ifBlank { m.groupValues[2] })
            val pid = needPid() ?: return ToolResult(false, "请先告诉我改哪本书")
            // 新大纲 = 命中词之后的内容（去掉引导冒号），为空时工具内部返回用法提示
            val rest = raw.substring(m.range.last + 1).trim().trimStart('：', ':', ' ', '，', ',')
            return Tools.setChapterOutline(pid, idx, rest)
        }

        if (Regex("(补|写|生成|生)(全|所有)?大纲|大纲生成|自动大纲").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先告诉我看哪本书")
            return Tools.generateOutlines(pid, context)
        }

        // ------- 模型 -------
        if (Regex("我的(AI|模型|接口|接入)|列出.*模型|我的接口|我接了?什么").containsMatchIn(raw)) {
            return Tools.listApis()
        }
        Regex("测试(连接)?(所有|全部|接口|AI)|测试一下连接").find(raw)?.let { _ ->
            val all = Repo.dao.apiConfigsFlow().first()
            if (all.isEmpty()) return ToolResult(false, "还没添加 AI 接口")
            return if (all.size == 1) Tools.testApi(all[0].id) else ToolResult(true, "请指定要测试哪一条接口的 id")
        }

        // ------- 导出 -------
        if (Regex("^(导出|打包|下载|生成txt|导出txt|导出文档|一键导出)").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先告诉我导哪一本")
            return Tools.exportTxt(pid, context)
        }

        // ------- 上下文注入预览 -------
        if (Regex("注入预览|预览上下文|看注入|注入什么|会注入|上下文预览").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.contextPreview(pid)
        }

        // ------- 专家级写作功能 -------
        if (Regex("润色").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.polishChapter(pid, chapterNum)
        }
        // v6.9.20：扩写补「对话改生动/对话改丰富」语序
        if (Regex("对话扩写|扩写对话|把.*改成对话|扩写|对话[^，。]{0,8}(改|丰富|生动|自然)").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.expandDialogue(pid, chapterNum)
        }
        // v6.9.14：补写缺失剧情——自检报「重大偏离/关键事件遗漏」后的修复入口
        if (Regex("补写|补全.*剧情|剧情补全").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.supplementChapter(pid, chapterNum)
        }
        if (Regex("章末钩子|强化钩子|结尾钩子|优化钩子|钩子").containsMatchIn(raw) && !raw.contains("伏笔")) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.hookChapter(pid, chapterNum)
        }
        if (Regex("金句|名场面台词").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.goldenLines(pid, chapterNum)
        }
        if (Regex("推演|剧情走向|后续剧情|接下来怎么写|接下来剧情").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.plotBrainstorm(pid)
        }
        // v6.9.19：人物体检重写——旧正则 `人物?\s*(体检|一致性)` 里「人」必选，导致「体检林墨」
        // 根本进不了本路由（漏到全书体检）；现在裸「体检+人名」也命中，但名字含「第/章/数字」
        // 或是「进度/记录」等垃圾词时放行给后面的路由，避免误拦「体检进度」「体检第3章」「全书体检」。
        Regex("人物\\s*(体检|一致性)|体检\\s*([^，。,\\s]{1,10})|检查(一下)?人物\\s*([^，。,\\s]{1,10})").find(raw)?.let { m ->
            fun cleanName(s: String?): String? {
                val n = s?.trim()?.removePrefix("一下")?.trim().orEmpty()
                if (n.isEmpty() || n in setOf("怎么样", "如何", "进度", "记录", "结果", "吧", "啊", "呢", "吗", "了")) return null
                if (n.any { it == '第' || it == '章' || it.isDigit() }) return null
                return n
            }
            val name = cleanName(m.groupValues.getOrNull(4)) ?: cleanName(m.groupValues.getOrNull(2)) ?: ""
            val hasRenwu = m.groupValues[1].isNotEmpty() || m.groupValues[3].isNotEmpty()
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            if (name.isBlank()) {
                if (hasRenwu) return ToolResult(false, "请说明检查哪个人物，如：体检林墨")
                return@let   // 裸「体检X」但 X 不是人名 → 放行给自检进度/全书体检等路由
            }
            return Tools.characterCheck(pid, name)
        }
        // v6.9.13：自检进度——必须放在撤销/自检各路由之前（「自检进度」含「自检」「进度」会被误拦截）
        if (Regex("自检进度|体检进度|自检记录|检了哪些|哪些章已?经?(自检|体检)").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.selfCheckProgress(pid)
        }
        // v6.9.10：撤销自检修改——必须放在单章自检路由之前（「撤销第N章自检修改」含「自检」会被误拦截）
        if (Regex("撤销|还原|回滚|恢复第").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            if (chapterNum <= 0) return ToolResult(false, "请说明要撤销哪一章，如：撤销第5章自检修改")
            return Tools.undoSelfCheck(pid, chapterNum)
        }
        // v6.9.9：全书逐章自检修复——必须放在单章自检路由之前（「全书自检」含「自检」会被误拦截）
        if (Regex("全书自检|自检全书|逐章自检|自检所有|所有章.{0,4}自检").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.fullSelfCheck(pid)
        }
        // v6.9.8：单章自检——「自检第N章 / 检查第N章 / 自检最新章」，手动触发写后自检（发现矛盾自动修正）
        // 注意必须放在「全书体检」路由之前，且正则不与 人物体检/全书体检 冲突
        if (Regex("自检|本章体检|检查第|体检第|[0-9零一二三四五六七八九十百千]+章.{0,6}(检查|体检|自检)").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.chapterSelfCheck(pid, chapterNum)
        }
        // v6.9.18：支线任务体检——必须放在伏笔体检路由之前（两者均宽匹配，互不包含则无冲突；置于全书体检之前防裸「体检」误拦截）
        if (raw.contains("支线")) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.subplotCheck(pid)
        }
        // v6.9.16：标记伏笔已回收——必须放在伏笔体检路由之前（后者匹配一切含「伏笔」的输入）
        // v6.9.19：新增「把伏笔X标记回收 / 伏笔X已回收」语序识别
        // v6.9.20：排除查询语序（「伏笔已回收的有哪些/已回收伏笔列表」→ 交给伏笔体检）
        if (Regex("标记.{0,4}伏笔.{0,6}(已回收|回收)|伏笔[「'\"]?[^」'\"]{1,20}[」'\"]?.{0,6}(标记|已回收|回收)|(已回收|回收).{0,4}标记").containsMatchIn(raw) &&
            !Regex("有哪些|哪些|列表|清单").containsMatchIn(raw)
        ) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            val hookName = (Regex("标记.{0,4}伏笔[「'\"]?([^」'\"]{1,20})[」'\"]?").find(raw)?.groupValues?.getOrNull(1)
                ?: Regex("伏笔[「'\"]?([^」'\"]{1,20})[」'\"]?.{0,6}(标记|已回收|回收)").find(raw)?.groupValues?.getOrNull(1)
                )?.trim()?.takeIf { it.isNotEmpty() && !it.contains("回收") && !it.contains("标记") } ?: ""
            return Tools.markHookRecovered(pid, hookName)
        }
        // v6.9.12：伏笔体检——必须放在「全书体检」路由之前（后者含裸「体检」正则会误拦截）
        if (raw.contains("伏笔")) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.foreshadowCheck(pid)
        }
        if (Regex("全书体检|一致性体检|一致性检查|体检|找矛盾|查矛盾|矛盾").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.consistencyCheck(pid)
        }
        Regex("(起|取|来)\\s*([0-9]{0,3})\\s*[个组]?\\s*(人物名|人名|地名|功法|门派|法宝|势力|名字|名称)").find(raw)?.let { m ->
            val kind = m.groupValues[3].removeSuffix("名").ifBlank { "人物" }
            val count = m.groupValues[2].toIntOrNull() ?: 8
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.nameGen(pid, kind, count)
        }
        if (Regex("生成简介|写简介|发布简介|书名|简介").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            return Tools.genBlurb(pid, context)
        }
        if (Regex("列出?文件|看看文件|打开文件|文件列表|项目文件").containsMatchIn(raw)) {
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            val fr = context?.let { com.lele.novelmaster.tools.FileTools.dispatch(it, pid, "listFiles", org.json.JSONObject()) }
            return fr ?: ToolResult(false, "文件系统不可用")
        }

        // ------- 自然语言文件操作：保存/读取/删除/修改 -------
        Regex("^保存到\\s*([^\\s:：]+)\\s*[:：]\\s*([\\s\\S]+)").find(raw)?.let { m ->
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            val path = m.groupValues[1]
            val content = m.groupValues[2]
            val args = org.json.JSONObject().put("path", path).put("content", content)
            return context?.let { com.lele.novelmaster.tools.FileTools.dispatch(it, pid, "writeFile", args) }
                ?: ToolResult(false, "文件系统不可用")
        }
        Regex("^保存[:：]\\s*([\\s\\S]+)").find(raw)?.let { m ->
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            val content = m.groupValues[1]
            val name = java.text.SimpleDateFormat("MMdd_HHmm", java.util.Locale.CHINA).format(java.util.Date())
            val args = org.json.JSONObject().put("path", "手动保存/保存_$name.md").put("content", content)
            return context?.let { com.lele.novelmaster.tools.FileTools.dispatch(it, pid, "createFile", args) }
                ?: ToolResult(false, "文件系统不可用")
        }
        Regex("^(读取|查看|打开)\\s*(文件\\s*)?([^\\s:：]+\\.(md|txt|json)|设定卡/.+|大纲/.+|正文/.+)").find(raw)?.let { m ->
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            val path = m.groupValues[3]
            val args = org.json.JSONObject().put("path", path)
            return context?.let { com.lele.novelmaster.tools.FileTools.dispatch(it, pid, "readFile", args) }
                ?: ToolResult(false, "文件系统不可用")
        }
        Regex("^删除文件\\s*([^\\s:：]+)").find(raw)?.let { m ->
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            val args = org.json.JSONObject().put("path", m.groupValues[1])
            return context?.let { com.lele.novelmaster.tools.FileTools.dispatch(it, pid, "deleteFile", args) }
                ?: ToolResult(false, "文件系统不可用")
        }
        Regex("^(新建文件夹|创建文件夹)\\s*([^\\s:：]+)").find(raw)?.let { m ->
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            val args = org.json.JSONObject().put("path", m.groupValues[2])
            return context?.let { com.lele.novelmaster.tools.FileTools.dispatch(it, pid, "createFolder", args) }
                ?: ToolResult(false, "文件系统不可用")
        }
        Regex("^(新建文件|创建文件)\\s*([^\\s:：]+)\\s*[:：]?\\s*([\\s\\S]*)").find(raw)?.let { m ->
            val pid = needPid() ?: return ToolResult(false, "请先选择一本书")
            val args = org.json.JSONObject().put("path", m.groupValues[2]).put("content", m.groupValues[3])
            return context?.let { com.lele.novelmaster.tools.FileTools.dispatch(it, pid, "createFile", args) }
                ?: ToolResult(false, "文件系统不可用")
        }

        // ------- 模糊匹配：删除 / 查卡号 -------
        Regex("删除(设定|卡|人物|世界观|伏笔)\\s*id\\s*([0-9]+)").find(raw)?.let { m ->
            val id = m.groupValues[2].toLong()
            val pid = needPid() ?: return ToolResult(false, "请先告诉我操作哪本书")
            return Tools.deleteCard(pid, id)
        }

        return null
    }

    /** 找输入里出现的分类（全字匹配） */
    private fun findCategory(raw: String): String? {
        var best: String? = null
        for (c in CardCategories.all) {
            if (raw.contains(c)) best = c
        }
        if (best != null) return best
        // 别名映射
        return when {
            raw.contains("人物") || raw.contains("角色") || raw.contains("主角") || raw.contains("配角") -> "人物设定"
            raw.contains("世界观") || raw.contains("世界设定") -> "世界观"
            raw.contains("主线") -> "主线剧情"
            raw.contains("支线") -> "支线任务"
            raw.contains("伏笔") || raw.contains("钩子") -> "伏笔钩子"
            raw.contains("冲突") || raw.contains("矛盾") -> "核心冲突"
            raw.contains("全书大纲") || raw.contains("总纲") -> "全书大纲"
            raw.contains("圣经") -> "设定圣经"
            raw.contains("进度") -> "剧情进度"
            raw.contains("辅助") -> "辅助设定"
            else -> null
        }
    }

    private fun parseChineseNum(s: String): Int {
        val map = mapOf(
            "零" to 0, "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
            "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10
        )
        if (s.isBlank()) return 0
        if (s.all { it.isDigit() }) return s.toIntOrNull() ?: 0
        var total = 0; var cur = 0; var lastUnit = 0
        for (ch in s) {
            when (ch) {
                // v6.9.21：支持「百/千」——此前「第一百零五章」「六百章」中的百/千被直接跳过（解析成 5/6）
                '百' -> { if (cur == 0) cur = 1; total += cur * 100; cur = 0; lastUnit = 100 }
                '千' -> { if (cur == 0) cur = 1; total += cur * 1000; cur = 0; lastUnit = 1000 }
                else -> {
                    val v = map[ch.toString()]
                    when {
                        v == null -> {}
                        v == 10 -> { if (cur == 0) cur = 1; total += cur * 10; cur = 0; lastUnit = 10 }
                        // v6.9.21：修复双重计数——旧代码 cur=v 后又 total+=v，而结尾 return total+cur
                        // 会把 v 再加一次，「十五」算成 20、「九十九」算成 108
                        v < 10 -> if (lastUnit == 10) { cur = v; lastUnit = 0 } else cur = v
                    }
                }
            }
        }
        return total + cur
    }
}

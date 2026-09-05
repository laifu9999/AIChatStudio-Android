# 乐乐速记 LeleNote

边看视频边记笔记的 Android 记事本。

## 功能

- **悬浮球**：授权「显示在其他应用上层」后，小球常驻任何应用最上层，可拖动换位
- **点球速记**：点击悬浮球 → 视频自动暂停（媒体键）→ 半透明速记页弹出
- **截图**：速记页内点「📷 截图」，屏幕录制授权后自动抓当前画面存入笔记（截图时速记页自动隐藏，拍到的是底下的视频）
- **自动续播**：保存或关闭速记页后，视频自动继续播放（媒体键）
- **笔记管理**：列表/详情/编辑/删除/分享
- **导出**：全部笔记（文字+截图内嵌）导出为 JSON，可存到任意网盘 App（百度网盘、阿里云盘等）
- **导入**：从网盘/文件管理器选 JSON 备份恢复

## 说明

- 暂停/续播走媒体键（AudioManager 派发 KEYCODE_MEDIA_PAUSE/PLAY），对绝大多数视频 App（YouTube、B站、抖音、浏览器等）有效；个别不响应媒体键的播放器除外
- 截图基于 MediaProjection，每次首次截图需系统授权；Android 14+ 由前台服务承载
- 笔记与截图存储在应用私有目录，卸载即清空，重要内容请导出备份

## 构建

GitHub Actions 自动构建：push 即跑 `gradle assembleDebug`，产物在 Artifacts（LeleNote-APK）。

技术栈：Kotlin + Jetpack Compose（Material3），minSdk 26 / targetSdk 35，AGP 8.7.3 / Kotlin 2.0.21。

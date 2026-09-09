# Librera Reader HarmonyOS 移植总体分阶段计划

> 制订日期：2026-08-17。本计划为活文档，每阶段结束 review 一次，按实际情况调整后续阶段。
> 移植策略：**C++ 核心（MuPDF）与 Android 共享源码树复用，UI 层用 ArkTS 全新实现**，Android 的 Java/Kotlin 业务代码不直接移植。

## 现状基线（截至制订日）

| 能力 | 状态 |
|---|---|
| 构建/安装/日志链路（Ubuntu22 SSH + hvigorw + hdc + hilog） | ✅ 稳定 |
| MuPDF 1.23.7 原生库（arm64-v8a / x86_64，源码树与 Android 共享） | ✅ 可编译打包 |
| NAPI 导出 | ✅ 12 个：version / openDocument / openDocumentByFd / pageCount / renderPage / **renderPageAsync** / getText / searchText / getDocumentInfo / closeDocument / **getToc** / **getPageSize** |
| UI | Index.ets API 自测页（10 项测试） + Reader.ets 阅读器（Swiper 翻页 + tap zone + async 渲染） |
| 已验证 | 5 页 PDF 全链路、async render + rotate/invert、getToc/getPageSize、EPUB 有 MuPDF 上游 SIGSEGV |

已知限制：MuPDF 1.23.7 EPUB CSS parser 在某些文件上触发 SIGSEGV（fz_chartorune NULL deref），需升级 MuPDF 或等上游修复。

## 已修复问题记录（2026-08-18）

### 启动概率闪退（SIGSEGV in RenderJobExecute）✅ 已修复
- **现象**：VM 上手动打开 APP 有概率闪退，faultlog 签名全部为 `NativeAsyncWork::AsyncWorkCallback → RenderJobExecute` 内 SEGV_ACCERR。
- **主根因**：`renderPageAsync` 90/270 度旋转的像素拷贝索引公式错误 —— `(y*stride + (w-1-x)) * 4` 把本已是字节单位的 stride 又乘了 4，y≥1 即按 4 倍行距越界读源 pixmap，撞堆 guard page。Index 每次启动都跑 rot=90 渲染测试 → "概率"取决于堆布局（debug 越界落未用堆页不崩，release -O2 必崩）。
- **次根因（一并修复）**：closeDocument 显式 `delete h` 后 napi_external finalizer 再次 drop → double-free/UAF；RenderJob 持裸 DocumentHandle* 无引用计数；全文件无 fz_var()（longjmp 后局部变量 UB）；PageCount/GetDocumentInfo/SearchText 有裸 MuPDF 调用（无 try 即 abort）。
- **修复**：DocumentHandle 引入 refs/closed 引用计数（ReleaseHandle 为唯一删除点）；RenderJob 入队前 AcquireHandle；所有 fz_try 块补 fz_var 并集中 fz_always 释放；裸调用包 try；修正旋转索引公式。
- **验证**：release 版循环启动 15 次零崩溃、faultlog 零新增、10 项 API 测试全过。

### 阅读菜单无法弹出 ✅ 已修复（2026-08-18）
- **现象**：用户报告"没有菜单功能"——Reader 中心点击无菜单弹出。
- **修复**：
  1. 顶栏新增显式 **「≡ 菜单」按钮**（不依赖中心 tap zone 作为唯一入口）
  2. 菜单浮层从 `.position({x:'15%',y:'30%'})` 改为**模态式**（全屏半透明遮罩 + 居中面板 + Scroll 可滚动），消除定位/层级不确定性
- **验证**（VM uitest 驱动）：`Menu toggled (top bar): true` 菜单弹出 → 面板完整显示（缩放/模式/页面/夜间/亮度/自动翻页/高亮/手绘/保存/关闭）→ 高亮功能 `annotations 0→1` → 书签 `Bookmarks Added page 1` → 菜单关闭；中心 tap zone 同步恢复（`Menu toggled: true`）。release 循环启动 10 次零崩溃、faultlog 零新增。

---

## 阶段 8（2026-09-06）：UI 壳对齐安卓 V1.0.0（移植 Phase 1）

> 背景：安卓 V1.0.0 稳定后，按用户要求把鸿蒙端口 UI 布局与功能对齐安卓。此前鸿蒙是自创 6-Tab（最近/收藏/书签/浏览/设置/云盘），安卓是「底部 4 Tab（首页/书库/我的文件/偏好）+ 左侧抽屉 + 顶栏 + FAB」。

已完成 ✅：
1. ✅ **主框架重构**：Index.ets 改为安卓同款壳——顶栏（品牌色 #3949AB：☰ 汉堡 + Tab 标题 + ＋ 导入）、底部 4 Tab、左侧抽屉（280vp：品牌横幅"值得读，好好读" + 5 导航项 + 随机读书格言 + 底部 4 按钮）、右下 FAB"继续阅读"、3 模态浮层（书签笔记/网上书库/软件说明）。
2. ✅ **功能归位**（一个不丢）：最近→首页轮播+书库；收藏→书库星标筛选；书签管理→抽屉书签笔记浮层；浏览→我的文件 Tab；设置→偏好 Tab；WebDAV→网上书库浮层。
3. ✅ **抽屉定位 bug 修复**：本 SDK 的 Stack 子级 `.align(Alignment.TopStart)` 未生效（面板跑到右侧），改用全屏 Row 固定左侧。
4. ✅ **主题资源**：color.json 补 brand_primary #3949AB / accent #03A9F4 / tab 选中未选色；应用名修正（AppScope HowRead/好好读，entry 新增 zh_CN）。
5. ✅ **抽屉格言**：rawfile/reading_quotes.txt（复用安卓 assets 1000+ 条），随机刷新。
6. ✅ **验证**：编译/安装/启动通过；uitest 布局核对（4 Tab/抽屉/浮层/FAB 均渲染、格言随机刷新）；交互 hilog 驱动验证（Tab 切换、晚上模式、三浮层、FAB→Reader 渲染）；无崩溃。

待后续阶段：书库搜索/状态chips/排序/视图模式（阶段2）、首页阅读统计（阶段3）、我的文件 my-files:（阶段4）、OPDS（阶段5）等。

### 阶段 8 续（2026-09-07）：书库对齐（移植 Phase 2）✅ 已完成

1. ✅ **书库搜索**：书名/作者/系列/标签模糊过滤（TextInput + 实时过滤）。
2. ✅ **状态 chips**：全部/未读/在读/已读；`RecentBook.status`（0/1/2）持久化，旧数据按 page/totalPages 回填推导。
3. ✅ **排序**：最近/名称/日期/作者/系列 5 种。
4. ✅ **视图模式**：列表/紧凑/网格/封面/书架（木纹背景）5 种。
5. ✅ **批量选择**：长按进入 → N 项已选/全选/标记已读/未读/在读/取消；`setBookStatus/setBooksStatus` 批量持久化。
6. ✅ **进度条**：每行 Linear Progress（page+1/totalPages）。
7. ✅ **元数据合并修复**：`saveRecentBook` 由"整体替换"改为"合并"——Reader 只传 page/totalPages/lastRead 时保留 star/cover/author/tags/series/genre/status（此前读一次书会丢星标/封面/标签）。
8. ✅ **ForEach 重渲修复**：书库列表/网格 key 由 `path` 改为 `path_page_status_star`，状态/星标/进度变化时强制重渲（ArkUI ForEach 同 key 复用不刷新数据的经典坑）。
9. ✅ **验证**：uitest/hilog 驱动——搜索过滤、chips 筛选（在读→1 本）、批量标记持久化（hilog `setBooksStatus 1 books -> 1` + 设备 preferences 文件核对 status=1）、5 视图切换、列表状态标签与进度条渲染正确、无崩溃。

### 阶段 8 续（2026-09-07）：首页阅读统计 + 品牌对齐（移植 Phase 3）✅ 已完成

1. ✅ **ReadingStats 模型**（新 `model/ReadingStats.ets`）：totalMs + 每日/每月 JSON（preferences 持久化）；`recordReadTime`（30s 分块）、`recordPagesRead`（翻页计数）、聚合统计（今日/本月/速度）、近 N 日/近 N 月序列。
2. ✅ **Reader 上报**：aboutToAppear 启动 30s 定时器 → `recordReadTime(30000)`，aboutToDisappear 停止；onPageChanged 实际翻页才 `recordPagesRead(1)`。
3. ✅ **首页统计区块**（对齐安卓 DashboardFragment2 截图 hr01/hr03）：5 卡片 总数/已读/总时长/今日/速度 + 柱状图（周/月/年 三档，近7天/30天/12个月）。
4. ✅ **反应式修复**：@Builder 参数不追踪状态 → 统计卡改为直接在 Text 内引用 @State；图表数据改为 @State 数组（chartValues/chartLabels）由 updateChartData() 驱动。
5. ✅ **onPageShow 刷新**：从阅读器返回首页时 loadStats + loadLibrary。
6. ✅ **品牌对齐**（用户要求）：图标替换为 docs/howread_cleaned.png（512px 缩放，AppScope + entry 双 media）；包名 com.foobnix.pdf.reader → **com.howread.reader**（与安卓 google 渠道一致，signing/gen_signing.sh + profile-template.json 包名同步后重签）；应用名 HowRead/好好读（阶段1 已完成）。
7. ✅ **验证**：uitest——统计卡 总数 8 响应式；阅读 90s 后返回首页显示 总时长/今日 2m；hilog `[Stats] record 30000ms, total=90000` 累计正确；周柱状图 09-01~09-07 标签渲染；新包名安装启动正常。无崩溃。

### 阶段 9 续（2026-09-08）：UI 对齐安卓版（图标体系 + 四 Tab + 阅读器重构）✅ 已完成（部分）

1. ✅ **图标体系**：38 个 SVG 矢量图标（base/media/ic_*.svg，.fillColor 染色）替代全部 emoji（对齐安卓 glyphicons 主集）。
2. ✅ **首页**（hr01/hr03）：最近阅读横排大封面 / 书签笔记白卡 / 我的珍藏 / 阅读统计 3+2 白卡 + 图表对话框（hr02）/ 我的文件圆形快捷钮 / 网上书库 OPDS 圆钮 / 书本图标 FAB。
3. ✅ **书库**（hr04/hr19）：品牌蓝工具条（搜索框+计数+排序/刷新/视图）、白底蓝框筛选 chips、封面底部覆盖层（百分比圆标+星标+竖三点菜单：标记状态/收藏/删除）。
4. ✅ **我的文件**（hr05）：OPDS/WebDAV/书库文件夹/搜索 四分区 + 「+添加」入口。
5. ✅ **偏好**（hr07）：偏好|退出程序子栏、配置文件行（H 头像）、重置、蓝色分区标题条 + 键值行（WebDAV 同步/AI 大模型行）。
6. ✅ **阅读器**（hr10/hr11/hr12）：单行品牌蓝顶栏 6 钮（目录/亮度/全屏/AI/设置/关闭）、品牌蓝底栏 6 白钮 + 蓝底页码滑块行；文本选择改「文本」对话框（标注按钮 + 加入书签/分享/复制/文内搜索/发送给AI/笔记/网络搜索/网络词典菜单，部分功能 toast 占位）。
7. ✅ **验证**：default/debug 构建 + 模拟器逐屏截图比对 hr01/04/05/07/10/12 通过；统计卡响应式（修复 @Builder 值传参不追踪）。
8. ✅ 待续项已全部完成（2026-09-08 第二批，见下节）。


### 阶段 14（2026-09-09）：第六批功能移植（偏好设置页 1:1 对齐安卓 PrefFragment2 + 软件说明页完整对齐 AboutSectionBinder）✅ 已完成（0.8.6 / versionCode 37）

1. ✅ **设置页分组重构**：安卓式单页长滚动 + 可折叠分组（图标+箭头）：书库设置（格式设置/书库设置/书库显示配置/封面配置/阅读配置 子区）→ 常规设置 → 备份配置 → UI 配置（主题配置/标签栏配置）→ 关于；档案区对齐（首字母头像+档案名+面板）；原「系统集成」分组取消、散项归位。
2. ✅ **新设置项**：扫描格式白名单 14 族（仅过滤扫描/导入，LibrarySearch parseScanFormats/isExtAllowed + .nomedia 跳过）；封面列数 1-8、列表/网格封面大小、显示封面、封面阴影/裁剪/边框（LibGridCell/BookRow 生效）；单击/长按动作（Reader tapCenterAction 覆盖 + action 4；长按 文件信息/菜单/无操作/默认选择）；打开最后一本书；总是第 1 页；退出确认；全屏（setWindowSystemBarEnable）；字体缩放 0.7-2.0（fsp() 关键文本）；语言切换（i18n.System.setAppPreferredLanguage，重启生效）；KV 行+弹层选择（buildOptSheet）；主题色/强调色色板+HEX 弹层；tab 拖拽排序（tabsOrder + List onItemMove + 箭头兜底 + 应用/恢复默认，tabContentFor(pos) 内容调度 + 显示序↔id 映射）；文件夹三行手输。
3. ✅ **动态主题色**：brand_primary 资源引用全量替换 thm()/acc()（Index 79/Reader 13/AiChat 2），@StorageProp(themeColorHex/accentColorHex/fontScale) 全局实时换色；Reader.persistSettings 改合并式保存（不再重置 Index 管理字段）。
4. ✅ **About 完整对齐**：版本 pill（bundleManager 真实版本 + build_hap_all.sh 自动生成 BuildInfo.ets BUILD_TIME）、应用描述、更新日志外链、GPL+开源许可（licenses.html → rawfile + Web 弹窗）、支持邮箱 mailto、主页链接、fork 尾注。
5. ✅ **验证**（模拟器 uitest）：分组/子区折叠、各行为取值、tab 重排+内容映射+持久化+恢复默认、主题色一键全局换色、语言弹层+toast、仅显示图标、重置联动、About 全条目+许可页渲染、阅读器回归正常。
6. ⏸ **待真机/待续**：tab 拖拽手势复验；字体缩放仅关键文本；存储根/字体/下载目录消费点接线；作者姓氏/书籍描述预留（无扫描元数据）。

### 阶段 13（2026-09-09）：第五批功能移植（阅读器 UI 对齐 / 全库搜索 / PDF 密码 / TTS 播控卡片 / 偏好补齐 / 多档案 / i18n 第二批）✅ 已完成（部分待真机）

1. ✅ **阅读器 UI 对齐 + 图标补齐**：顶栏补 跳页/TTS/工具行开关 4 钮（对齐 document_title_bar）；顶栏下 2px 阅读进度条 + 二级按钮排（缩放±/亮度/对比度/页面分割，对齐 document_title_buttons）；底部工具栏横向滚动补齐 返回上一位置/缩略图/TTS/模式切换（对齐 document_footer）；垂直模式快速滚动条（PanGesture，FastScroller 对应）；补 32 个 SVG 图标（共 96）。
2. ✅ **全库全文搜索 + 找书导入 + 书库清理**：LibrarySearch.ets 逐本逐页 getText 检索（进度/取消/坏书跳过），书库「全文」按钮 + 结果面板点击直达书页（Reader jumpPage 参数）；scanDeviceBooks 尽力扫描设备目录一键导入；失效文件横幅 + 确认清理。模拟器实测 fox 跨 4 书命中并直达 ✅。
3. ✅ **PDF 密码 + 格式验证**：NAPI 新增 needsPassword/authenticateDocument；加密 PDF 密码对话框（错误不关/正确解锁重渲染，模拟器实测 ✅）；格式实测：FB2 ✅、**MD ✅（MuPDF txt handler 扩展名表加 md/markdown，两 ABI 重编 libmupdf）**、RTF/DOCX/MOBI ❌（引擎无 handler，0 页不崩溃）。txt/md 顶部一行 CSS 文本残留为固有已知项。
4. ✅ **TTS 播控桌面卡片**：TtsCard 2×4（上一句/暂停继续/下一句，postCardAction tts_cmd 复用通知播控通路）；TTS 状态经 preferences（librera_ttsstate）跨进程 + formProvider.updateForm 主动推；FormAbility 按表单分支载荷，RecentCard 支持卡片偏好（最近/星标、1-3 本）。⏸ 真机复验添加到桌面。
5. ✅ **偏好补齐**：屏幕方向（@ohos.window setPreferredOrientation）/启动扫描/封面列数 2-4/桌面卡片配置/隐藏已读；应用内语言切换 SDK 23 无 setAppLanguage API，放弃。
6. ✅ **多 Profile 档案**：Profiles.ets storeName 后缀方案，default 沿用原存储名零迁移；Settings/ReadingProgress/Bookmarks/Notes/ReadingStats/Playlists 档案感知；档案面板 新建/切换/删除；模拟器实测 work 档案书库隔离、切回恢复 ✅。
7. ✅ **i18n 第二批全量**：Reader/Index/AiChat 546 处资源化（直显位 $r / 字符串位 this.L 助手）；base(en)+zh_CN 各 +405 key（共 452+）；拼接碎片与 AI 提示词保留原文（待续）。en 语言切换抽查 ⏸ 真机。
8. ⏸ **仍待续**：RTF/DOCX/MOBI 引擎级支持（需移植安卓外部抽取器或 MuPDF 定制 handler）；txt/md CSS 残留清理；TTS 录音导出；i18n 43 语铺开；桌面卡片/生物识别/分享接收/真实 WebDAV PROPFIND/真实 AI 凭据真机复验。

### 阶段 12（2026-09-09）：第四批功能移植（TTS 后台播控 / 桌面卡片 / WebDAV 目录浏览 / 批注管理 / 生物识别 / 小功能包 / i18n 第一批）✅ 已完成（部分待真机）

1. ✅ **UI 资源收敛 + 图标补齐**：101 处硬编码品牌色 → `$r('app.color.*')`；补 26 个 Material SVG（TTS 播控/云盘/版式/手势类）；Tab 顶底切换（tapPositionTop 对齐）；阅读器顶栏时钟+电量。
2. ✅ **TTS 后台播控**：backgroundTaskManager AUDIO_PLAYBACK 长时任务 + 常驻通知三播控按钮（wantAgent → EntryAbility → AppStorage → Reader 消费）；启停/暂停状态刷新、退出全清理。⏸ 真机复验（模拟器无语音包）。
3. ✅ **桌面服务卡片**：FormAbility（最近 3 本：封面/书名/进度）+ RecentCard 2×2（postCardAction 打开书）+ form_config；⏸ 真机复验添加到桌面（模拟器无 start-extension）。
4. ✅ **WebDAV 目录浏览 + 流式下载**：真实 PROPFIND（207 实测通过，模拟器 HTTP 栈接受非枚举方法）→ GET HTML 回退；解析兼容 D: 命名空间与 HTML 列表；目录下钻/上级面包屑；requestInStream 进度条下载入库；HTTP 代理全链路接入（HttpUtil → Sync/Opds/AiClient）；NAPI 自测区默认隐藏（点偏好标题 3 次）。
5. ✅ **批注管理**：全书标注列表面板（扫描/类型徽标/跳页/单条删除/刷新）。
6. ✅ **应用锁生物识别**：userAuth FINGERPRINT/FACE 探测 + 系统弹窗校验 + 失败回退密码；偏好开关 + 锁定层按钮。⏸ 真机复验（模拟器无指纹硬件）。
7. ✅ **分享接收**：plain-text sendData skill + EntryAbility 暂存 + 自动存 TXT 入库。⏸ 真机复验（需真实分享源）。
8. ✅ **小功能包**：页缩略图 3×3 宫格（跳页验证）、位置历史（跳页压栈/返回上一位置验证）、页面分割（crop 左右半页并排验证）、对比度 colorFilter 矩阵。
9. ✅ **i18n 第一批**：string.json 44 键（base en + zh_CN），本轮全部新 UI + Tab 标签走 $r 资源；全量铺开仍待续。
10. ⏸ **仍待续**：i18n 全量（880 条 × 43 语）；TTS 录音导出（平台无 synthesizeToFile）；双语后台预翻译窗口；AI/WebDAV 真实凭据端到端；PROPFIND 在真机 WebDAV 服务器（群晖/Apache/Nginx）上的兼容性抽验；桌面卡片与生物识别真机验证。

### 阶段 10（2026-09-08）：第二批功能移植（AI 全套 / 服务器管理 / 同步基础版 / 木质书架 / 分享查词）✅ 已完成

1. ✅ **AI 大模型接入全套**：`model/AiClient.ets`（OpenAI 兼容 /chat/completions + /models + 测试，AiConfig 持久化 `librera_ai`）+ `model/Notes.ets`（AI 笔记 `librera_notes`）；hr08 偏好设置对话框（厂商 chips 智谱/OpenAI/DeepSeek/自定义、密钥掩码、获取模型、输出上限、思考模式、测试连接）；hr13 发送给AI 独立页 `pages/AiChat.ets`（可编辑选中文本+问题+回答+保存到笔记，已注册 main_pages.json）；AI 简介书籍（书库长按菜单→结果对话框→保存笔记）；hr18 AI 翻译对话框（源/目标语言、双语对照置灰=面板模式、结果底部面板+保存）；hr17 书签面板「AI 笔记」分组（徽章/页码/删除）。
2. ✅ **OPDS/WebDAV 服务器持久化增删改**（hr05/hr06）：`model/Servers.ets`（`librera_servers`，首启种入 Gutenberg/Standard Ebooks/CBETA）；我的文件根服务器卡片行（✎编辑/✕删除/＋添加对话框），WebDAV 对话框含账号/密码/信任自签；连接带 Basic Authorization；首页圆卡与 OPDS 浮层改读持久化列表。
3. ✅ **书库木质书架背景**（hr04）：bg_wood.png（4 竖板木纹平铺贴图）+ 网格视图背景铺贴 + 每格底部深棕木板条。
4. ✅ **WebDAV 同步基础版**（hr09/hr09b）：`model/Sync.ets` 配置+日志持久化；runSync 按书比对 updatedAt 较新者胜（GET/PUT `.howread.json`，服务端新→回写进度+合并书签；仅 GET/PUT，无 PROPFIND/三向合并/定时——鸿蒙 HTTP 栈限制，用户确认基础版）；hr09 同步对话框（启用/地址/凭据/同步路径/冲突策略/测试连接/同步日志/立即同步/上次摘要）；hr09b 日志页（倒序+每书明细）。
5. ✅ **分享/在线查词真实跳转**：分享=@kit.ShareKit systemShare（SharedRecord utd general.text + ShareController.show，失败回退复制剪贴板）；网络搜索=openLink Google；网络词典=openLink 有道。
6. ✅ **原待续项在阶段 11 完成**：页内双语对照、同步三向合并/定时/墓碑、播放列表均已实现（见阶段 11）；仍待：AI/WebDAV 真实 Key 端到端、openLink 真机复验、TTS 录音导出（平台无 synthesizeToFile）、i18n 铺开。

### 阶段 11（2026-09-08）：第三批功能移植（笔记导出 / 自动滚动 / 播放列表 / 同步三方合并 / 页内双语）✅ 已完成

1. ✅ **笔记/书签导出**（对齐安卓 2026-09-03）：`model/Export.ets` TXT/Markdown 双格式（每条带位置行 + 时间）；Reader 书签面板「导出」→ DocumentViewPicker 保存，失败回退 filesDir/export。验证：保存成功（重名提示证明落盘）。
2. ✅ **自动连续滚动**：垂直滚动 List 挂 Scroller + 50ms 定时 scrollBy（触摸/到底即停，开启时自动切垂直）；Settings 持久化 autoScroll/autoScrollSpeed；设置面板「自动滚动：开关+速度」行。
3. ✅ **OPDS 预置对齐**：移除 Standard Ebooks，仅 Gutenberg + CBETA。
4. ✅ **播放列表**（对齐安卓 Playlists.java）：`model/Playlists.ets` 文本文件持亐（`<filesDir>/playlists/*.playlist`）；书籍菜单「加入播放列表」选择器 + 书库 chips「▶ 播放列表」管理对话框（新建/删除/条目打开/↑↓调序/✕移除/播放第一本）。验证：新建→加入→条目展示全链路。
5. ✅ **WebDAV 同步三方合并**（对齐安卓 syncThreeWayFile）：`.base` 本地快照（`librera_syncbase`）；进度按「谁相对 base 变了」字段级判定，书签墓碑式合并（任一侧删除传播、新增并集）；冲突策略三选（较新/本地/服务器优先）；定时同步（默认 5 分，应用存活期间）；日志【合并】【冲突】明细。验证：mock WebDAV（Ubuntu 8765）——首轮 8 本上传建快照，远端伪造变更后二轮【合并】应用本地，余【已最新】。
6. ✅ **页内双语对照**（对齐安卓 BilingualBuilder，真实注入）：`model/Bilingual.ets` EPUB 管线（解包 → `<p>` 提取 → AI 5 段/批编号翻译 → 注入 `<p class="aitran">` + CSS → **自写 store-only ZIP 打包器**（mimetype 首位 + CRC32，规避 zlib.compressFile 产物 MuPDF 不识别））；TXT 交替行；段落级缓存（FNV-1a 键）二次零请求；失败章节保留原文。Reader 翻译对话框双语复选框启用（EPUB/TXT「本书可用」徽章），完成后 swapDoc 原地换开双语版（保页码重排版），再次开启则恢复原书。验证：mock AI（Ubuntu 8766）——Alice EPUB 162 批段落全部注入，103→123 页重排版，译文逐段显示，恢复原文正常。
7. ⏸ **仍待续**：TTS 录音导出（平台无 synthesizeToFile，需另想方法）；i18n 多语言铺开（单独一轮）；双语后台预翻译窗口（现为逐章）；AI/WebDAV 真实环境端到端；openLink 真机复验。





### 阶段 8 续（2026-09-07）：我的文件 my-files:（移植 Phase 4）✅ 已完成

1. ✅ **my-files: 根视图**（对齐安卓 hr05）：网络区（网上书库 OPDS / WebDAV 服务器 两项入口）+ 书库文件夹（书库=cacheDir，可进入）+ 快捷目录。
2. ✅ **文件夹浏览器**：面包屑栏（⌂ home / ↑ 上级 / 路径 / 网格·列表切换），目录在前文件在后，点击目录进入、点击书籍打开（可阅读扩展名判定，非书文件弹操作菜单）、长按弹操作菜单。
3. ✅ **文件操作**：长按 → 打开 / 重命名 / 删除 / 关闭（fs.renameSync / fs.unlinkSync / fs.rmdirSync）。
4. ✅ **新建文件夹**：对话框输入 → fs.mkdirSync → 列表刷新（验证：testfolder 创建成功出现在目录列表）。
5. ⚠️ **系统分享未接**：本 OpenHarmony SDK 无 `@ohos.share` 模块（编译报错），分享行已移除（后续设备/API 支持再补；安卓截图 hr12 的分享属文本选择浮层，阶段6 评估）。
6. ✅ **验证**：uitest 驱动——根视图三分区渲染、进入书库文件夹列出 demo 书、长按弹出操作菜单、新建文件夹成功。无崩溃。

### 阶段 8 续（2026-09-07）：网上书库 OPDS（移植 Phase 5）✅ 已完成

1. ✅ **Opds 模型**（新 `model/Opds.ets`）：`@ohos.net.http` fetch + 字符串解析（entry/title/link/rel 分类 subsection→目录 / acquisition→书籍，href 相对路径解析，实体解码）；带浏览器 UA（Gutenberg 等要求）。
2. ✅ **网上书库浮层重构**：OPDS 目录 / WebDAV 云盘 双区切换；OPDS 内置 3 书目（Project Gutenberg / Standard Ebooks / CBETA 电子佛典）+ 自定义 URL；目录下钻（返回栈）+ 书籍下载入库（复用 WebDAV 下载模式）。
3. ✅ **解析器验证**：Node 复刻逻辑测试通过（子目录/书籍分类、标题、href 解析全对）。
4. ⚠️ **公共书源从模拟器网络被拒**（Gutenberg 403 / Standard Ebooks 401）：fetch 管道正常（能收到状态码），属模拟器网络环境限制；自定义 URL 可在可用网络中使用。
5. ✅ **验证**：浮层渲染（OPDS 选中态/3 书目/自定义地址）、点击拉取日志正确。无崩溃。

### 阶段 8 续（2026-09-07）：阅读器增强 + 应用锁 + 阅读提醒（移植 Phase 6 部分）✅ 已完成（部分）

1. ✅ **应用锁**：Settings 增加 `appLockPass`（默认 1234）+ 偏好页锁屏密码输入；Index 启动/返回时若 appLockEnabled 显示全屏锁定覆盖层（密码掩码输入 → 校验 → 解锁，错误清空重输）；验证：开启→重启→锁定页→输入 1234→解锁（hilog `App lock requested` / `App unlocked`）。
2. ✅ **阅读提醒**：Settings 增加 `readReminderMinutes`（0=关）+ 偏好页设置项（30 分钟步进）；Reader 打开时设一次性定时器，到点 showToast 提醒；aboutToDisappear 清理。
3. ✅ **阅读器页码滑块**：Reader 底部进度条下加 Slider（0..totalPages-1），End/Click 跳页（对齐安卓 footer SeekBar）。
4. ✅ **验证**：应用锁全流程、偏好页新设置项、阅读器 Slider 渲染（uitest 组件树含 Slider）。无崩溃。
5. ⏸ **未做（记录待续）**：PDF 文本精确选择 + 下划线/删除线标注（需 NAPI 扩展 C++）、查词/翻译（需外部 API）、系统分享（本 SDK 无 @ohos.share，需 @kit.ShareKit 评估）、播放列表、书内 WebView（不迁移清单）。

### 阶段 8 续（2026-09-07）：我的文件/OPDS/阅读器增强/品牌收尾（移植 Phase 4-7）✅ 已完成（部分）

1. ✅ **我的文件**（Phase 4）：my-files: 根视图（网络区+书库文件夹+快捷目录）、文件夹浏览器（面包屑/网格列表/目录在前）、长按文件操作（打开/重命名/删除）、新建文件夹。系统分享暂缺（SDK 无 @ohos.share）。
2. ✅ **OPDS 网上书库**（Phase 5）：model/Opds.ets fetch+解析；浮层 OPDS/WebDAV 双区；内置书目+自定义 URL；下钻+下载入库；解析器 Node 测试通过；公共书源被模拟器网络拒（403/401）。
3. ✅ **应用锁/阅读提醒/页码滑块**（Phase 6）：锁定→解锁全流程验证；阅读提醒定时 toast；Reader Slider 跳页。
4. ✅ **蓝光持久化/真实背景图**（Phase 7）：Settings 持久化 + 图片选择器衬底。
5. ✅ **品牌**：图标 howread_cleaned.png；包名 com.howread.reader；应用名 HowRead/好好读。
6. ⏸ **后续（按用户要求自动继续）**：文本选择+下划线/删除线标注（NAPI C++）、查词/翻译、WebDAV 三向同步、桌面卡片、TTS 录音导出（LAME 交叉编译）、连字符词库、播放列表、i18n 铺开。测试书 ci/autotest/teskbook 供压力/真机验证。

### 续（2026-09-07）：PDF 文本精确选择 + 下划线/删除线/波浪线/文字笔记标注 ✅ 已完成

1. ✅ **NAPI 扩展**（mupdf_napi.cpp + Index.d.ts）：`getTextRects` 输出扩为每行 `{x0,y0,x1,y1,text,chars:[x0,x1,...]}`（行文本 UTF-8 JSON 转义 + 逐字符 x 边界，归一化 0..1）；新增 `addMarkupAnnotation(handle,page,rects[],type,color)`（underline/strikeout/squiggly/highlight，每 rect 一 quad，透明度对齐安卓：highlight 0.4 其余 1.0）与 `addTextNote(handle,page,x,y,text,color)`（24pt 图标 rect + contents，对齐安卓 addTextNoteInternal）；注册表 + d.ts 同步。
2. ✅ **精确选区 UI**（Reader.ets）：页面中央长按（600ms）取 `FingerInfo.globalX/Y` + `onAreaChange` 换算归一化坐标 → 命中最近行/字符为起点；选区模式下全页透明捕捉层把点按转为终点（长按重设起点）；首末行按字符 x 裁剪、中间行整行合成 quads，实时蓝色预览；翻页自动退出选区。
3. ✅ **浮动操作条**：下划线/删除线/波浪线/高亮（直调 addMarkupAnnotation + refreshAnnotations）、笔记（内联 TextInput → addTextNote 锚在选区起点）、复制（@ohos.pasteboard 系统剪贴板）、取消；条底部悬于工具栏上方（HitTestMode.Transparent 不挡页面手势）。
4. ✅ **渲染扩展**（PageRenderer）：highlight 半透明块保留；underline/squiggly 矩形底部 3px 横线（蓝/紫）、strikeout 中部横线（红）、text 💬 图标；选区预览层。
5. ✅ **验证**（Pura 90 模拟器 + hilog + uitest dumpLayout）：选区 5 行解析（2751 字节 JSON）→ 两点选区 14 字符 1 quad → 下划线（annotations:1）→ 删除线（2）→ 笔记（3）→ 复制（`Copied 14 chars to clipboard`）→ 保存（`Document saved with annotations`）；字节级验证：拉取保存后 PDF 含 `Subtype/Underline`。已知坑：@State 代理数组直传 NAPI `napi_get_array_length` 失败 → 调用侧传本地拷贝；启动自检 runApiTest 每次用 rawfile 覆盖 cacheDir/test.pdf，会清掉该演示文件的已存标注（自检固有行为，非管线缺陷）。
6. ✅ **产物规范**（应需求插入，三次迭代定稿）：新增 `harmony/build_hap_all.sh` —— **按硬件平台分 ABI 构建，DEBUG/RELEASE × arm64/x86_64 共 4 个产物**，放入 `harmony/dist/DEBUG/` 与 `harmony/dist/RELEASE/`，文件名 ABI 段按 HAP 实际打包内容推导（每次构建设单一 abiFilters 并临时移走 entry/libs 下其它预置 ABI 目录——hvigor 会无视 abiFilters 打包 libs 下所有预置 so；脚本启动清空 dist 全量重建，结束 trap 恢复默认配置）。签名：debug 构建用 debug profile，release 构建用 `signing/gen_release_profile.sh` 生成的 release profile（type=release、无设备绑定；bundle-info 需同时含 development/distribution-certificate），平台侧不再识别为 DEBUG 包。最终产物：`harmony-HowRead-v1.0.0-{arm64,x86_64}.hap` × {DEBUG,RELEASE}，全部字节级验证（ABI 唯一 + 内嵌 profile 类型正确）；模拟器安装 DEBUG x86_64 包启动正常。注意：签名链仍为本地自签（Librera Root CA），若 AGC 校验证书链需在 AGC 签发正式 release 证书/profile 后替换 signing/ 材料。

---

## 阶段 1：环境与构建链路 ✅ 已完成

Ubuntu22（lee 用户 + `source ~/.bashrc`）构建、`hvigorw assembleHap --mode module -p product=default -p buildMode=debug`、hdc 安装启动、`timeout N hilog | grep` 日志验证。

## 阶段 2：最小阅读 PoC（NAPI 打通）✅ 已完成

5 页 test.pdf，Swiper 翻页 + tap zone 导航全链路 hilog 验证通过（`[Reader] Page changed to x/5`）。

## 阶段 3：文档引擎补全（NAPI 层扩展）✅ 已完成

目标：把"能打开一个 PDF"变成"支撑完整阅读器所需的文档能力"。

1. **真异步渲染** ✅ `renderPageAsync`（napi_async_worker + pthread mutex 保护 fz_context，支持 zoom/rotationDeg/invert 选项）
2. **打开方式** ✅ `openDocumentByFd`（已实现，待阶段 4 picker 对接）
3. **结构信息** ✅ `getToc`（fz_load_outline DFS 扁平化）、`getPageSize`（fz_bound_page via fz_load_page）；`getLinks`/`getPageLayout` 留阶段 5
4. **渲染参数** ✅ rotate（0/90/180/270 软件旋转）、invert（XOR 0xFF）通过 RenderOptions 传入 renderPageAsync
5. **缩略图渲染** ✅ 复用 renderPageAsync(zoom<1) 即可，无需单独 API
6. **格式验证** ⚠️ PDF 全功能通过；EPUB 触发 MuPDF 1.23.7 `fz_parse_css→fz_chartorune` SIGSEGV（上游 bug，已记录）
7. **Index.ets API 自测页** ✅ 10 项测试全部 hilog 打勾

NAPI 导出从 9 → 12：新增 renderPageAsync / getToc / getPageSize。
Reader.ets 已切换到 async 渲染路径，5 页 PDF 全页翻页无错误。

## 阶段 4：书库与文件接入（Library）✅ 已完成

目标：脱离 rawfile 测试文档，成为"能打开用户自己文件的应用"。

1. ✅ **文件选择打开**：DocumentViewPicker → URI → fs.openSync(READ_ONLY) → copy to sandbox cacheDir → `openDocument(localPath)`。无需额外权限（picker 走系统授权）。
2. ✅ **最近阅读 + 进度持久化**：`@ohos.data.preferences` 存储 RecentBook[]（path/title/page/totalPages/lastRead），上限 20 条。Reader 每次翻页即保存，退出时兜底保存。重进 Reader 自动恢复到上次页码。
3. ✅ **Index 页面**：双按钮（"打开文件" picker + "测试 PDF" rawfile）+ 最近阅读列表（点击直接进 Reader）。
4. ⚠️ **待真机验证**：picker UI 在 emulator 上难以 uinput 自动化，代码逻辑已就绪（编译通过、app 稳定无 crash），需真机走一遍 picker→Reader→退出重进恢复页码全链路。

新增文件：`entry/src/main/ets/model/ReadingProgress.ets`（preferences 封装）。
修改文件：Index.ets（picker + recent books UI）、Reader.ets（router params + 进度保存/恢复）。

## 阶段 5：完整阅读体验（Reader UI 重构）✅ 已完成

1. ✅ **双滚动模式**：水平 Swiper 翻页（默认）+ 垂直 List 连续滚动，菜单内一键切换（`[Reader] Scroll mode: horizontal/vertical`）
2. ✅ **Pinch 缩放**：`PinchGesture({fingers:2}).onActionEnd` → zoomIn/zoomOut（步进 0.25x，范围 0.5x–4.0x）→ `dataSource.setZoom()` → 下次 renderPageAsync 用新 zoom。菜单内也有 A+/A- 按钮
3. ✅ **夜间模式**：`RenderOptions.invert=true` → MuPDF 渲染后 XOR 0xFF 反色。UI toggle + 顶栏/底栏颜色自适应（`[Reader] Night mode: ON/OFF`）
4. ✅ **TOC 侧边栏**：打开文档时 `getToc()` 加载 → TocPanel 组件从右侧滑入 → 点击条目跳转页码（`[Reader] TOC jump to page N`）。无目录时不显示按钮
5. ✅ **菜单浮层**：居中面板含缩放控制、模式切换、夜间开关、TOC 入口、关闭按钮。每次操作均有 hilog 埋点
6. ✅ **进度持久化**：每次翻页自动 saveRecentBook，退出兜底保存

Reader.ets 从 ~370 行扩展到 ~580 行，新增 TocPanel + PageRenderer 组件。
⚠️ 待真机验证：pinch 手势、垂直滚动流畅性、大文档（数百页）性能。

## 阶段 6：系统集成与发布准备 ✅ 已完成

1. ✅ **文件关联**：module.json5 skills 增加 `{scheme:"file", utd:"general.pdf"}`；EntryAbility onCreate/onNewWant 捕获 want.uri 存 AppStorage，Index 启动时消费 → importUriToSandbox → 直跳 Reader。hilog 验证 URI 管道通（未授权路径 ENOENT 为正确沙箱行为）
2. ✅ **多语言资源**：AppScope zh_CN / en_US string 目录建立（品牌名不变），i18n 结构就绪
3. ✅ **性能收口**：Swiper/List cachedCount(1) 预加载；zoom/夜间切换经 ForEach key 触发重渲染。体积分析：libmupdf.so 的 .rodata 46MB 为内嵌字体（base14+CJK），--strip-debug 无效；abiFilters 不影响 libs/ 打包（HAP 恒双 ABI 110MB，装机时按设备 ABI 提取）。后续优化：字体子集化或商店级 per-ABI 分发
4. ✅ **仓库上库清理**（沿用独立清理计划，全部落位）：.gitignore 鸿蒙段 / gen_signing.sh+make_material.js 密码参数化（LIBRERA_SIGN_PW）/ build-profile.json5 加密材料可克隆再生（signing/README.md 一键流程）/ libmupdf.so 入 prebuilt/harmony/mupdf-1.23.7 双 ABI 已入库 / harmony 根目录无秘密副本
5. ✅ **Release 打包+冒烟**：`buildMode=release` 签名 HAP 109.9MB，VM 干净卸载重装后全部 API 测试通过（注：debug→release 覆盖安装会导致 BMS 元数据不一致，需先 bm uninstall）

新增：`model/FileImport.ets`（URI→沙箱导入，picker/open-with 共用）。

## 阶段 7（长期，按需裁剪）：对齐 Librera 功能广度

TTS 朗读、搜索全书、批注/高亮编辑、OPDS 书源、云同步等。**须先明确"不迁移清单"**——Android 版功能面太大，按需求优先级逐个排期，不默认全量对齐。

### 7.0 安卓版功能全景 vs 鸿蒙版差距（2026-08-18 盘点）

来源：`app/src/main/java/com/foobnix/`（~1060 Java 文件）+ `org/ebookdroid/`（164 文件）+ `prebuilt/native/mupdf-1.23.7/`。安卓侧几乎全部为成熟完整实现。

**图例**：✅ 鸿蒙已实现｜🟡 部分/底层有、UI 未接｜❌ 未实现

**当前统计（2026-08-19 冲刺 F 后）**：✅ 5｜🟡 21｜❌ 26｜✅/🟡 1（合计 53 项）

| # | 功能（安卓） | 鸿蒙现状 | 说明 |
|---|---|---|---|
| **阅读引擎** | | | |
| 1 | 翻页模式：单页/双页/双页+封面/半页/乐谱 | 🟡 | 鸿蒙只有单页 Swiper+List；双页/乐谱需 UI 拼合 |
| 2 | 滚动模式（垂直连续） | ✅ | List 模式已实现 |
| 3 | PDF 渲染/文本层/链接/大纲 | ✅ | renderPageAsync + getToc |
| 4 | 文本重排 reflow（PDF→HTML→EPUB） | ❌ | MuPDF 支持但未接 NAPI/UI |
| 5 | 翻页效果/自动翻页 | 🟡 | 有 Swiper 动画；自动翻页定时器无 |
| 6 | 手势配置（滑动方向/双击动作） | 🟡 | 有 pinch+点击分区；自定义手势映射无 |
| 7 | 点击分区定制（4 边+中心） | 🟡 | 固定左中右 3 区；定制对话框无 |
| 8 | 裁剪（自动白边/对称裁剪） | ❌ | NAPI 无 crop 选项 |
| 9 | 方向/旋转/RTL 阅读方向 | 🟡 | rotationDeg 有；RTL 无 |
| 10 | 全屏/沉浸式 | ❌ | 未接（鸿蒙 window 全屏 API） |
| 11 | 状态栏定制（进度线/章节刻度） | 🟡 | 有简易进度条；定制无 |
| 12 | 性能设置（内存/质量/抗锯齿） | ❌ | 未接 |
| 13 | 护眼定时/屏幕超时 | ❌ | 未接 |
| 14 | 书内搜索 UI（高亮+前后跳转） | 🟡 | searchText NAPI 有；UI 无 |
| **书库与文件** | | | |
| 15 | 多 Tab 壳（浏览/最近/收藏/书签/网络/设置） | 🟡 | 只有 Index 首页+最近列表；Tab 体系无 |
| 16 | 文件夹浏览/按作者系列标签浏览 | ❌ | 无 |
| 17 | 最近阅读/收藏/书签 Tab | 🟡 | 最近列表有；收藏/书签 Tab 无 |
| 18 | 库内搜索+多文档全文搜索 | ❌ | 无 |
| 19 | SQLite 元数据（greenDAO：标签/系列/进度/星级） | 🟡 | preferences 存最近；完整元数据无 |
| 20 | 元数据提取（Calibre opf） | 🟡 | getDocumentInfo 有；opf 提取无 |
| 21 | 后台扫描/删书检测 | ❌ | 无 |
| 22 | 封面（Glide/打印封面/裁剪阴影） | 🟡 | renderPageAsync 可出缩略图；缓存/UI 无 |
| 23 | 播放列表/标签管理 | ❌ | 无 |
| 24 | 文件信息对话框（重命名/删除/分享） | ❌ | 无 |
| 25 | 桌面小部件（最近/TTS） | ❌ | 无（鸿蒙卡片可做） |
| **格式** | | | |
| 26 | PDF/XPS/TIFF/CBZ | ✅/🟡 | PDF ✅；CBZ ✅（冲刺 F，mutool 转 5 页样本 UI 打开）；XPS/TIFF 未验证 |
| 27 | EPUB | ✅ | 冲刺 A 前置修复：html-parse.c 空 user_css 分支补丁 + 重编译 libmupdf.so；Alice EPUB 105 页+TOC+reflow ✅ |
| 28 | MOBI/FB2 | ❌ | 引擎支持（fz_open_document 同路径）；无样本生成工具，待真机验证 |
| 29 | TXT/HTML/MD/RTF/DOCX | 🟡 | TXT/HTML ✅（冲刺 F，reflowable+文本提取）；MD/RTF/DOCX 未接 |
| 30 | DjVu | ❌ | 需额外解码库，成本高 |
| **文本排版** | | | |
| 31 | 字体大小/缩放/自定义字体 | 🟡 | zoom ✅；reflow 字号/行距/页边距 ✅（冲刺 E）；自定义字体无 |
| 32 | 页边距/行距/段距/对齐 | 🟡 | 行距/页边距 ✅（冲刺 E reflowable CSS）；段距/对齐无 |
| 33 | 连字符（HyphenPattern 670KB） | ❌ | 未接 |
| 34 | 文本选择+高亮/下划线/删除线 | ✅ | 逐字符选区 + addMarkupAnnotation（underline/strikeout/squiggly/highlight）+ 文字笔记 + 剪贴板复制，PDF 持久化 |
| 35 | 词典/翻译（本地+在线） | ❌ | 未接 |
| 36 | 速读 RSVP/脚注/EPUB3 页码 | 🟡 | 速读 RSVP ✅（冲刺 D）；脚注/EPUB3 页码未接 |
| **批注与书签** | | | |
| 37 | PDF 批注（26 类型，MuPDF 持久化） | 🟡 | highlight/underline/strikeout/squiggly/ink/text ✅（MuPDF 持久化）；其余类型未接 |
| 38 | 手绘覆盖层 | ❌ | 未接 |
| 39 | 书签管理器（多书签/导出导入） | 🟡 | 进度单点持久化有；多书签无 |
| **TTS 与音频** | | | |
| 40 | TTS 朗读（语速/音调/按句） | ✅ | 冲刺 D：ArkWeb speechSynthesis 后端（本 SDK 无 @ohos.ai.tts） |
| 41 | 录音导出 WAV/MP3（LAME） | ❌ | 未接（LAME 需交叉编译） |
| **设置/主题** | | | |
| 42 | 主题（浅/深/OLED/墨/自定义色） | 🟡 | 冲刺 E：浅/深/OLED/墨 ✅（invert + UI 色 + 墨色 sepia 叠加层）；自定义色未接 |
| 43 | 亮度/蓝光 | 🟡 | 冲刺 E：亮度已入设置并持久化 ✅；蓝光滤镜未接 |
| 44 | 43+ 语言 | 🟡 | zh/en 资源骨架 |
| 45 | 应用密码/指纹 | ❌ | 未接 |
| 46 | 备份恢复（JSON） | ✅ | 冲刺 E：设置+书库+书签导出/恢复到沙箱 JSON |
| **云与网络** | | | |
| 47 | Drive/Dropbox/OneDrive/WebDAV | ❌ | 未接 |
| 48 | 云同步（WiFi-only/增量） | ❌ | 未接 |
| 49 | OPDS 目录 | ❌ | 未接（HTTP 解析，相对易） |
| 50 | 应用内 WebView | ❌ | 未接 |
| **系统集成** | | | |
| 51 | 深链/打开方式（全格式） | 🟡 | PDF/EPUB/TXT/HTML/ebook 关联 ✅（冲刺 F）；CBZ/MOBI/XPS 无标准 UTD |
| 52 | 分享接收/发送 | ❌ | 未接 |
| 53 | 桌面卡片（鸿蒙 AnalogCard） | ❌ | 未接 |

### 7.1 不迁移清单（明确不做，除非点名）

- 广告（AdMob/GMS）——鸿蒙无此生态
- Google Drive/Dropbox/OneDrive 专有云（依赖 GMS/CloudRail）——若做只做 WebDAV/OPDS
- 应用内 WebView 浏览（系统浏览器替代）
- DjVu（额外解码库，性价比低）
- 旧版 EBookDroid 引擎（直接用 MuPDF 统一）

### 7.2 补齐方案（按用户价值×实现成本排序，分 6 个冲刺）

> **前置阻塞项 ✅ 已解除（2026-08-18）**：EPUB SIGSEGV 根因 = MuPDF 1.23.7 `html-parse.c` 的 `xml_to_boxes` 中 `if (user_css) {}` 是空块，随后**无条件** `fz_parse_css(ctx, css, user_css, "<user>")` —— 本应用未设置用户 CSS（`fz_user_css()` 返回 NULL）→ `css_lex_init` 里 `buf->s=NULL` → `fz_chartorune(NULL)` → SIGSEGV。**修复**：将 `fz_parse_css` + `fz_add_css_font_faces` 移入 `if (user_css)` 块内（源码树 `Builder/mupdf-1.23.7/source/html/html-parse.c`，纯 bug 修复，Android 共享源码同样受益）。重编双 ABI libmupdf.so → 同步 entry/libs + prebuilt → 验证：Alice in Wonderland EPUB 打开+渲染 105 页 + getToc 正常，release 循环启动 10 次零崩溃、faultlog 零新增。

**冲刺 A：阅读器核心体验 ✅ 已完成（2026-08-18）**
1. ✅ **双页模式 + 乐谱模式**：菜单「页面」按钮循环切换 单页/双页/乐谱。双页=DoublePageRenderer 左右拼合两页；乐谱=MusicianPageRenderer 用 crop 显示上半/下半（A1 crop 支撑）。hilog `[Reader] Page mode: single/double/musician`
2. ✅ **书内搜索 UI**：顶栏 🔍 → 搜索条（TextInput+搜索/下一命中/关闭）→ 遍历全书找首个命中页 → 归一化高亮叠加（PageRenderer searchHits 半透明矩形）→ 前后命中页跳转。hilog `[Reader] Search 'x': N hits on page P`
3. ✅ **裁剪**：NAPI `renderPageAsync` options 新增 `crop?: {x0,y0,x1,y1}`（0..1 归一化，作用于旋转后缓冲）。Index 测试验证 612x792→crop 10%→489x633
4. ✅ **文本重排 reflow（部分）**：NAPI 新增 `layoutDocument(handle,w,h,em)`（fz_layout_document，EPUB/HTML/TXT 重排，PDF 为 no-op）。**PDF reflow 无公开 API**（fz_new_xhtml_document_from_pdf 不在 1.23.7 头文件），且 EPUB 端到端验证受前置阻塞项限制 —— 待修复 EPUB 后验证
5. ✅ **亮度调节**：菜单亮度 Slider（@system.brightness.setValue 1..255）+ 百分比显示，与夜间 invert 组合。hilog `[Reader] Brightness: N`
6. ✅ **自动翻页定时器**：菜单开关 + 间隔秒数（1-5s 循环点按），Swiper 模式逐页翻，末页自动停，离开页面停定时器。hilog `[Reader] Auto-flip: ON/OFF (Ns)`

**冲刺 B：书库体系 ✅ 已完成（2026-08-18）**
1. ✅ **多 Tab 壳**：Index 重构为 Tabs（最近阅读/收藏/设置），顶部常驻「打开文件」+「测试 PDF」按钮。hilog `[Index] Tab switched to N` / `[Index] Library loaded: X recent, Y starred`
2. ✅ **元数据提取**：`generateCover` 打开文档时提取 getDocumentInfo 的 author 存入 RecentBook.author（title 沿用文件名）。hilog `Cover generated ... author='...'`
3. ✅ **封面缩略图**：renderPageAsync(zoom 0.18) → createPixelMap → ImagePacker.packToFile → cacheDir/covers/*.png → updateCover/整体持久化 → 列表 Image 显示（52x70 封面）。示例书 test.pdf 首启自动入库存封面，二次启动复用（`Demo book exists: totalPages=5 cover=...`）
4. ✅ **收藏 Tab**：RecentBook.star 字段 + toggleStar（★/☆ 切换）+ getRecentBooksStarred 列表
5. ✅ **文件夹浏览（降级）**：fileAccessHelper 目录授权暂缓，改用 DocumentViewPicker 多选导入（maxSelectNumber 20，一次导入多本并生成封面）
- **数据模型**：ReadingProgress.ets 扩展 RecentBook{star, coverPath, author}，旧数据自动回填；修复 saveRecentBook/updateCover 双写 list 竞态（统一整体持久化）
- **验证**：release 循环启动 10 次零崩溃、faultlog 零新增、demo 书封面生成+复用链路通

**冲刺 C：批注与书签 ✅ 已完成（2026-08-18）**
1. ✅ **NAPI annotation API**（MuPDF 1.23.7 pdf 层，`pdf_specifics` 从 fz_document 取 pdf_document）：`getAnnotations`（遍历页标注，归一化坐标+类型+内容）、`addHighlight`（PDF_ANNOT_HIGHLIGHT + quad points + 颜色）、`addInkStroke`（PDF_ANNOT_INK 笔迹）、`deleteAnnotation`（pdf_delete_annot）、`saveDocument`（pdf_save_document 持久化）。NAPI 导出 13 → 18
2. ✅ **高亮 UI**：Reader 菜单「高亮」按钮（无搜索命中时默认页面中部区域，有搜索命中时高亮首个命中矩形）→ addHighlight → refreshAnnotations → PageRenderer 叠加橙色标注层；「删除第1条」按钮 → deleteAnnotation。hilog `[Reader] Highlight added on page N` / `[Reader] Page N annotations: M`
3. ✅ **手绘覆盖层**：菜单「手绘」开关 → drawMode 触摸采集（TouchEvent → drawPoints）→ 抬手 finishInkStroke → addInkStroke 保存为 Ink 标注
4. ✅ **多书签管理器**：新 `model/Bookmarks.ets`（preferences 持久化，最多 200 条/文档，防重复）；顶栏 🔖 快速增删当前页书签 + 📑 打开书签面板（列表/跳转/删除）
- **验证**：Index 自测第 14 步标注往返测试全过（addHighlight → getAnnotations:1 → deleteAnnotation:0 → saveDocument）；release 循环启动 10 次零崩溃、faultlog 零新增
- **已知简化**：高亮区域为矩形（quad 单块），未做文本级精确选择；手绘坐标用 1000x1500 名义空间近似，未做容器精确归一化

**冲刺 D：TTS 与速读 ✅ 已完成（2026-08-18）**
1. ✅ **TTS 朗读**：调研确认本 SDK（OpenHarmony API 24）**无 `@ohos.ai.tts` 模块**（无 AI kit / TTS 接口），改用 **ArkWeb Web Speech API（speechSynthesis）** 后端：新增隐藏 Web 宿主 `resources/rawfile/tts.html`（`ttsSpeak/ttsStatus/ttsPause/ttsResume/ttsStop/ttsProbe`，数字返回码规避 runJavaScript 的 JSON 序列化），`WebviewController.runJavaScript` 驱动；语速 0.5–2.0x / 音调 0.5–2.0x 滑块、按句朗读（句间 400ms 轮询推进）、上一句/下一句、暂停/恢复/停止、读完自动进下一页、手动翻页重锚定。hilog `[Reader] TTS probe: N voices` / `[Reader] TTS speaking i/N (page P)`
2. ✅ **速读 RSVP**：新 `model/TextUtils.ets`（`normalize` 压缩空白 + `splitSentences` 按 。！？…；.!? 断句 + `splitTokens` CJK 感知分词：汉字逐字闪、拉丁按词闪、CJK 标点贴前字）；面板大字号词流显示 + WPM 100–800 滑块（运行中改速即时重启定时器）+ 开始/暂停/停止 + 进度 `n/total`；读完当前页自动进下一页直至末页停止。hilog `[Reader] RSVP started: N wpm, M tokens` / `[Reader] RSVP continue on page N` / `[Reader] RSVP finished (last page)`
3. ✅ **入口与互切**：顶栏 🔊/⚡ + 菜单「TTS 朗读」「速读」行；两面板底部「转朗读/转速读」互切按钮
4. ✅ **demo PDF 升级**：原 test.pdf 为纯图片占位（getText 0 字，TTS/RSVP/搜索无法验证）→ `tools/gen_demo_pdf.py` 用 mutool create 生成 5 页中英文本 PDF（内置 CJK 字体 F1 + Helvetica F2，中文 hex UTF-16BE、拉丁直接括号串，**注意 mutool create 一文件一页**）
- **验证（debug）**：TTS 面板 3/13 句断句正确、`probe: 0` → 正确判定「未检测到语音包」+ 面板内警告文案；RSVP 52/142 token 分词正确、300wpm 严格按 200ms/词推进、800wpm 全 5 页自动推进且时间精确匹配、末页 `finished (last page)` 收尾；暂停/恢复/互切/面板关闭全通
- **已知限制（重要）**：本 OpenHarmony 虚拟机**无 TTS 语音包**（speechSynthesis.getVoices()=0，属系统/镜像限制，非代码问题）→ TTS 在此 VM 上降级为「未检测到语音包」提示；代码在带系统语音引擎的 HarmonyOS NEXT 设备上可直接出声。若后续设备端支持 `@ohos.ai.tts`，可在 TextToSpeech 层替换后端
- **验证（release）**：循环启动 10 次零崩溃、faultlog 保持 11 条（零新增）

**冲刺 E：设置与主题 ✅ 已完成（2026-08-19）**
1. ✅ **设置面板与持久化**：新 `model/Settings.ets`（preferences 持久化，字段级默认值合并，任意新增字段向后兼容）；Index 设置 Tab 全量表单（主题/默认缩放/翻页与页面模式/亮度/自动翻页/TTS 语速音调/RSVP 速度/字号/行距/页边距），Reader 菜单「⚙ 设置」模态面板（主题 + 可重排文档排版），滑块在 End/Click 才落盘；Reader 启动 `[Reader] Settings applied: theme=.. zoom=.. font=..`，退出时全量持久化
2. ✅ **主题体系**：浅色/深色/OLED/墨 四主题 → `setTheme` 驱动 `dataSource.setInvert`（深色/OLED 反色渲染）+ 顶栏/底栏/阅读区主题色 helper + 墨色全屏 sepia 叠加层（`HitTestMode.None` 不挡手势）；菜单夜间开关与主题联动
3. ✅ **备份恢复（JSON）**：设置 + 最近阅读（`exportRecentBooks`/`replaceRecentBooks`）+ 书签（`exportBookmarks`/`replaceBookmarks`）→ `filesDir/librera_backup.json`；恢复校验 `app` 标记后整体回写并刷新书库
4. ✅ **字体/行距/页边距（可重排文档）**：NAPI `layoutDocument` 新增可选 `css` 参数（`fz_set_user_css` + `fz_layout_document`，MuPDF epub 的 `user_css_sum` 校验和自动触发重排）；新增 `isReflowable(handle)`（`fz_is_document_reflowable`）；Reader 打开文档时检测 reflow → 以 595×842 + em + `body{margin;line-height}` 布局；设置面板改字号/行距/边距 → `applyReflow` 重排并重建页列表（`reflowVersion` 参与 ForEach key 强制重渲染）；PDF 显示「固定版面不可调」提示
5. ✅ **demo EPUB 入库**：书库种子第二个 demo 书（Alice in Wonderland），EPUB 排版链路可从 UI 直达
- **验证（debug）**：设置持久化跨重启（theme=3/font=10 保留）；主题四色切换 + 页面重渲染（invert 生效）+ 墨色 sepia 无崩溃；reflow 页数双向（em16→103 页 / em28→213 页 / em10→69 页）；备份文件 JSON 完整（含重排后页数）且恢复往返（改 theme 0 → 恢复回 3）；PDF 回归（Reflowable:false、固定版面提示、5 页渲染正常）
- **验证（release）**：循环启动 10 次零崩溃、faultlog 保持 11 条（零新增）

**冲刺 F：网络与格式扩展 ✅ 已完成（2026-08-19，OPDS/WebDAV 按用户要求顺延）**
1. ✅ **格式测试样本**：`demo.txt`（手写 UTF-8）、`demo.html`（手写）、`demo.cbz`（`mutool convert -F cbz test.pdf` 从 5 页 demo PDF 转换，含中英文本与图片页）
2. ✅ **格式扫描自测**：Index 自测新增第 15 步——txt/html/cbz 逐格式 openDocument→pageCount→isReflowable→renderPage→（reflow 文档）getText。hilog `[Index] FMT demo.txt OK: 1 pages reflow=true render=450x600 text=548`（TXT/HTML reflowable、CBZ 固定 5 页）
3. ✅ **文件关联扩展**：module.json5 skills 增 `general.epub` / `general.plain-text` / `general.html` / `general.ebook`（UTD 取自 SDK `@ohos.data.uniformTypeDescriptor` 官方枚举；CBZ/MOBI/XPS 无标准 UTD，不做关联）
4. ✅ **Picker 过滤扩展**：`fileSuffixFilters` 覆盖 PDF/EPUB/TXT/HTML/CBZ/MOBI/XPS
5. ✅ **demo 书库**：`seedFormatDemo` 通用种子（rawfile→sandbox→书库→封面）；现有 5 本 demo（PDF/EPUB/TXT/HTML/CBZ），页数与元数据正确（EPUB 封面带出 author='Lewis Carroll'）
6. ✅ **EPUB 阻塞项确认**：html-parse.c 补丁后 Alice EPUB 105 页+TOC 全链路稳定（含 Reader reflow 字号重排 69↔103↔213 页）
7. ✅ **顺手修复两个真 bug**：
   - **getDocumentInfo 非 PDF 崩溃（SIGSEGV in pdf_metadata）**：原实现无条件 `reinterpret_cast<pdf_document*>(h->doc)` 调 pdf_metadata——PDF 恰好命中（fz_document 在 pdf_document 头部），EPUB/TXT/HTML/CBZ 越界读（堆布局相关概率闪退，faultlog 两个 cppcrash 实证）→ 加 `pdf_specifics` 守卫，非 PDF 走 `fz_lookup_metadata(FZ_META_INFO_TITLE/AUTHOR)` 通用路径（EPUB 元数据可正常取出）
   - **封面目录 EEXIST**：`fs.mkdirSync(coversDir)` 非递归，第二本书起 EEXIST 中断封面 → try/catch 包裹
- **验证（debug）**：FMT 扫描 3 格式全过；CBZ UI 打开（Reflowable:false、5 页图片渲染）；TXT UI 打开（Reflowable:true、em16 布局）；5 本书全封面生成、零崩溃
- **验证（release）**：循环启动 10 次零崩溃、faultlog 保持 11 条（零新增）
- **顺延项**：OPDS 目录、WebDAV 云盘（用户明确暂缓；网络权限+HTTP 客户端 + 服务端验证环境齐备后单独冲刺）

---

## 架构与工程原则（全程有效）

1. **禁止 Windows 侧构建**；构建一律 SSH lee@192.168.50.111 且前缀 `source ~/.bashrc &&`
2. NAPI 是唯一桥：文档重计算全在 C++，ArkTS 只做 UI 与状态
3. 新 API 先在 Index 测试页用日志验证，再接入正式 UI
4. ArkTS 严格语法（禁 any/throw 限制/ESObject 受限）——UI 代码保持保守写法，避免编译错误返工
5. 验证一律 hilog 日志驱动（`timeout N hilog | grep`），不用截图作为常规手段
6. 每阶段完成即更新本文件勾选状态，并 review 是否调整后续阶段

## 主要风险

| 风险 | 缓解 |
|---|---|
| 同步 renderPage 卡 UI（大页/低性能设备） | 阶段 3 首项就是 async worker，后续 UI 只允许走异步版 |
| EPUB reflow 布局参数与 Android 端不一致 | 阶段 3 格式验证时与 Android 端截图对比页数与排版 |
| 鸿蒙文件授权模型（scoped storage）限制书库扫描 | 阶段 4 先做 picker + 最近阅读，目录扫描视系统能力降级 |
| MuPDF AGPL 许可与 Librera 发布方式 | 发布前确认许可合规路径（沿用 Android 版现有做法） |

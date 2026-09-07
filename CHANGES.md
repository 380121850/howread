# CHANGES(修改说明)

本文件用于记录每次对代码与构建配置的修改内容,随代码一起入库,便于回溯每次改动的目的与范围。
每次新修改完成后,请在本段下方追加一条带日期的条目。

---

## [2026-09-08] Android 包名体系迁移（vendor=leestudio）+ APK 命名规范 + AdMob 首启动死锁修复；鸿蒙侧 vendor/bundleName 与双变体构建脚本

**背景**：按用户要求统一 leestudio 品牌包名体系：Android 三渠道改名改包名、APK 按渠道/构建类型分目录命名；鸿蒙改 vendor/bundleName 并支持 howread / howread pro 双变体。

**① Android 包名与 flavor 迁移**（`android/`）：
- flavor 改名与包名（`app/build.gradle`）：`google` → **howread**（`com.leestudio.howread.reader`，保留 AdMob，即当前 GOOGLE 渠道）；**pro** → `com.leestudio.howread.pro.reader`；**fdroid** → `com.leestudio.howread.fd.reader`（不再与 pro 共用包名，两渠道可同机共存）。flavor 源目录 `src/google/` 重命名为 `src/howread/`（LibreraBuildConfig.FLAVOR="howread"，代码中无 "google" 字符串比较，安全），sourceSets/googleImplementation 同步改名。
- APK 命名规范：文件名加版本号 v 前缀，输出本就按 `apk/<flavor>/<debug|release>/` 分目录 → `HowRead-v1.0.0-arm64.apk` / `HowRead-Pro-v1.0.0-arm64.apk` / `HowRead-Fdroid-v1.0.0-arm64.apk`（uni 同规则）。
- 代码内包名常量：`AppsConfig` 的 `LIBRERA_READER`/`PRO_LIBRERA_READER` 更新并新增 `FDROID_LIBRERA_READER`；`Urls.openPdfPro` 商店链接由硬编码改为常量拼接；`AppState`/`WebDavSyncer` 的 "What's New" 包名白名单补入 fdroid 新包名（否则 fdroid 渠道弹窗被误关）。
- fdroid 渠道显示名：HowRead FD → **HowRead Fd / 好好读 Fd**（values/-zh-rCN/-zh-rTW 三份；曾一度改为 "FDroid"，因过长按用户要求定为 "Fd"）。AdMob 属性键优先 `howread_*`，回落旧 `google_*`（服务器 `~/.gradle/gradle.properties` 旧键继续生效）。
- **注意**：包名变更 = 全新应用，设备上为新安装、旧包数据不迁移；商店侧（Google Play）等同上架新 App。

**② AdMob 首启动死锁（新装冷启动 ANR，严重）**：MI9 全新安装冷启动必现 ANR——ANR trace 显示主线程在 `AdsFragmentActivity.onResume → AdMobAdsProvider.loadInterstitial`（`MobileAds.setAppVolume`，:228）阻塞等待广告 SDK 初始化锁，而持锁的初始化线程又在等本机 WebView 首次加载（WebView 83 冷启动极慢）→ 主线程死锁、窗口永无焦点、黑屏（am start -W Status: timeout）。旧包因 WebView/SDK 已暖启动不触发，但**任何新装用户都可能命中**。修复（`src/admobAds/AdMobAdsProvider.java`）：`setAppVolume` 移入 `AppsConfig.executorService` 后台执行；`initialize()` 里的 `MobileAds.setRequestConfiguration` 一并移入后台线程。
**验证**：卸载重装真首启动 `am start -W` → Status: ok / COLD / TotalTime 1239ms（修复前 timeout+ANR）；主页正常渲染、测试横幅广告成功加载（初始化与广告链路恢复）、logcat 0 FATAL / 0 ANR。

**③ 鸿蒙侧（与用户并行的 AGC 签名材料工作交叉进行）**：`AppScope/app.json5` bundleName → `com.leestudio.howread.reader.hmos`、vendor → `leestudio`；`signing/gen_signing.sh`、`gen_release_profile.sh` 改为一次生成 reader/pro 两份 profile（`librera-{debug,release}.p7b` + `librera-pro-{debug,release}.p7b`，profile 内嵌 bundle-name，四份 verify 全通过）；密码回填收窄为只作用于 storeFile=librera-sign.p12 的签名配置（不触碰用户的 howread AGC 材料）；`build-profile.json5` 增加 pro 产品与 liberaprodebug/liberaprorelease 签名配置、entry 增加 pro target；`build_hap_all.sh` 支持变体参数（`bash build_hap_all.sh [default|pro]`，pro 变体临时替换 app.json5 bundleName 与 app_name 字符串、退出自动恢复），产物命名 `HowRead[-Pro]-v<ver>-<abi>-hmos.hap`。default 变体 4 个 HAP 构建成功；**pro 变体尚未打通**（hvigor 报 no executable target in module: 'entry'，产物为陈旧复制件）——已按用户要求暂停鸿蒙构建，待另行任务完成后恢复。

**④ 首启动主题默认改为浅色 + 清理旧产物**：用户反馈新装 App 打开界面很暗。`AppState.defaults()`（仅首启动）原跟随系统深色模式（`Dips.isDarkThemeOn() ? DARK : LIGHT`），系统深色模式的手机上新装首次打开即暗色；改为品牌默认 **THEME_LIGHT**（用户可在偏好设置切换，e-ink 默认不变），`WebDavSyncer.defaultAppState()` 镜像同步。另：删除输出目录中改名前的旧批次 APK（`HowRead[-Pro|-Fdroid]-1.0.0-*.apk` 无 v 前缀批次，旧 fdroid 包名与旧 pro 相同且显示名仍是 "好好读 FD"，已引起误装混淆——经 aapt2 验证新 APK 内 zh-CN label 已是 "好好读 Fd"）。三渠道 release 重编译 BUILD SUCCESSFUL，fdroid 新包已升级安装到 MI9。

**⑤ MI9 三应用界面发暗的定位与处理（设备侧数据修复，无代码改动）**：截图像素采样确认应用背景 141,141,141（系统设置 255,255,255）——应用内有一层约 44% 的均匀黑罩。读取共享配置 `/sdcard/HowRead/profile.HowRead/device.MI_9/app-State.json` 实锤：`isEnableBlueFilter=true + blueLightAlpha=93 + 滤镜色默认纯黑`（黑色 44% 遮罩，255×(1-111/255)≈144 与实测吻合）+ `appBrightness=0 / appBrightnessNight=11`（应用把屏幕背光压至 2%）。根因：**HowRead 系所有包名（旧 com.howread.reader 与新 com.leestudio.*）共享同一份 /sdcard/HowRead 外部配置**（0.9.0 起的设计），旧应用里的夜间护眼+低亮度设置被三个新应用继承。处理：force-stop 三个应用后把共享 app-State.json 修正（isEnableBlueFilter=false、appBrightness/appBrightnessNight=-1000 自动）并回写，fdroid/howread 重启后背景恢复 250,250,250，界面明亮。注意：该配置为全渠道共享，任一渠道里调 亮度/护眼滤镜 会影响其它渠道；旧应用 com.howread.reader 若再被使用且重设暗色值，新应用会再次继承。

**⑥ 切换白天/夜晚模式时重置亮度与护眼滤镜到默认**：用户反馈切换主题后残留的暗色值仍会生效且找不到调亮入口。`AppState` 新增 `resetBrightnessAndFilterToDefaults()`（日/夜亮度→自动 AUTO、护眼滤镜→关、滤镜强度→默认 30、isAllowMinBrigthness→false）；偏好 → 主题色的 4 个模式分支（系统/浅色/深色/深色 OLED）在切换时调用，保证每次白天/夜晚模式切换都从干净的显示值开始。三渠道 release 重编译 BUILD SUCCESSFUL，fdroid/howread 更新包已安装到 MI9 并验证启动正常、背景明亮。

**Android 编译验证**：Ubuntu 服务器 `assembleHowreadRelease assembleProRelease assembleFdroidRelease` 三渠道 BUILD SUCCESSFUL，15 个 APK 文件名/目录全部符合新规范；MI9 安装新包（versionName 1.0.0）冷启动/渲染/广告冒烟通过（见②）。MIUI 限制备注：卸载后重装新包需在手机上手动点一次"继续安装"（INSTALL_FAILED_USER_RESTRICTED）。未执行任何 git 命令。

---

## [2026-09-07] 鸿蒙移植·阶段4-7：我的文件 / OPDS 网上书库 / 阅读器增强与品牌对齐收尾

**背景**：按安卓 V1.0.0 功能与 UI（参考安卓 CHANGES、store/manual/img 截图、用户要求品牌/图标/包名/测试书）继续对齐鸿蒙端口。

**改动**（`harmony/` 内）：
- **阶段4 我的文件**：my-files: 根视图（网络区 OPDS/WebDAV 入口 + 书库文件夹 + 快捷目录）；文件夹浏览器（⌂/↑/路径/网格列表切换、目录在前、长按操作菜单 打开/重命名/删除）；新建文件夹（对话框+fs.mkdirSync，验证 testfolder 创建成功）。系统分享暂缺（本 SDK 无 @ohos.share）。
- **阶段5 OPDS**：新增 `model/Opds.ets`（fetch+字符串解析，UA 头，subsection/acquisition 分类，href 相对解析）；网上书库浮层 OPDS/WebDAV 双区；内置 Gutenberg/Standard Ebooks/CBETA 书目 + 自定义 URL；下钻返回栈 + 书籍下载入库；解析器 Node 测试通过。公共书源从模拟器网络被拒（403/401，环境限制）。
- **阶段6**：应用锁（Settings.appLockPass + 全屏锁定覆盖层，验证 锁定→1234→解锁）；阅读提醒（readReminderMinutes，Reader 定时 toast）；阅读器页码滑块（Slider 跳页）。
- **阶段7**：蓝光滤镜持久化（Settings.blueLightFilter）；阅读器背景图改为真实图片（DocumentViewPicker 选图 → importUriToSandbox → Image 衬底 + 透明度持久化）。
- **品牌（阶段3 延续）**：图标=docs/howread_cleaned.png 512px；包名 com.foobnix.pdf.reader→com.howread.reader（重签）；应用名 HowRead/好好读。
- 参考素材：安卓 CHANGES（Z:\opt\librera\CHANGES.md）、截图 store/manual/img（hr01-19）、图标 docs/howread_cleaned.png、测试书 ci/autotest/teskbook（用于后续真机/压力验证）。

**验证**（模拟器 uitest/hilog）：我的文件根视图/文件夹浏览/新建文件夹、OPDS 浮层与拉取日志、应用锁全流程、阅读器 Slider、蓝光/背景图设置项；各阶段编译 BUILD SUCCESSFUL、无崩溃。未执行任何 git 命令。

---

## [2026-09-07] 鸿蒙移植·阶段3：首页阅读统计 + 品牌对齐（图标/包名）

**背景**：对齐安卓首页仪表盘（截图 hr01/hr03：5 统计卡 + 周/月/年柱状图弹层）；按用户要求统一品牌——图标、包名。

**改动**（`harmony/` 内）：
- 新增 `model/ReadingStats.ets`：阅读时长/每日/每月持久化（preferences）；`recordReadTime`（30s 分块）、`recordPagesRead`、聚合统计、近 N 日/月序列。
- `Reader.ets`：aboutToAppear 启动 30s 计时上报 → `recordReadTime`；aboutToDisappear 停止；onPageChanged 实际翻页 `recordPagesRead(1)`。
- `pages/Index.ets` 首页新增「阅读统计」5 卡片（总数/已读/总时长/今日/速度）+ 柱状图（周/月/年）；修复 @Builder 参数不追踪状态的坑（统计卡内联引用 @State、图表数据改 @State 数组驱动）；新增 `onPageShow` 从阅读器返回时刷新统计与书库。
- **品牌对齐**：图标 = `docs/howread_cleaned.png` 缩放到 512px（AppScope app_icon.png + entry icon.png）；**包名 com.foobnix.pdf.reader → com.howread.reader**（与安卓 google 渠道一致），signing/gen_signing.sh + profile-template.json 包名同步并重新生成签名（verify bundle=com.howread.reader）；应用名 HowRead/好好读（阶段1已设）。

**验证**（模拟器，uitest + hilog）：统计卡 总数=8 响应式；阅读 90s 后返回首页显示 总时长/今日 2m（hilog `[Stats] record 30000ms, total=90000`）；周柱状图 09-01~09-07 渲染；新包名 com.howread.reader 安装/启动正常，全部 NAPI 自测通过、无崩溃。未执行任何 git 命令。

---

## [2026-09-07] 鸿蒙移植·阶段2：书库功能对齐安卓（搜索/状态chips/排序/5视图/批量选择）

**背景**：对齐安卓 SearchFragment2 的书库体验——搜索、阅读状态筛选、排序、多视图、批量标记。

**改动**（`harmony/` 内）：
- `model/ReadingProgress.ets`：`RecentBook` 增加 `status`（0未读/1在读/2已读），旧数据按 page/totalPages 回填推导；`saveRecentBook` 由整体替换改为**元数据合并**（保留 star/cover/author/tags/series/genre/status，修复"读一次书丢星标/封面/标签"既有隐患）；新增 `setBookStatus`/`setBooksStatus` 批量状态设置。
- `pages/Index.ets` 书库 Tab 重构：搜索框（书名/作者/系列/标签）、状态 chips（全部/未读/在读/已读）、排序（最近/名称/日期/作者/系列）、5 视图（列表/紧凑/网格/封面/书架·木纹背景）、行内进度条、长按进入批量选择（N 项已选/全选/标记已读·未读·在读/取消）；列表/网格 ForEach key 改为 `path_page_status_star` 强制状态变化时重渲（ArkUI 同 key 复用不刷新的坑）。
- 全部 RecentBook 字面量补 `status` 字段（Index 9 处 + Reader 2 处）。

**验证**（模拟器 Pura 90，uitest + hilog + 设备 preferences 文件）：搜索"alice"→仅剩 Alice EPUB（共 1 本）；在读 chips→1 本；长按批量标记在读→hilog `setBooksStatus 1 books -> 1`、设备 prefs 文件 status=1、列表显示"在读"；5 视图切换正常；全部 NAPI 自测通过、无崩溃。未执行任何 git 命令。

---

## [2026-09-06] 鸿蒙移植·阶段1：主框架 UI 重构对齐安卓（4 Tab + 左侧抽屉 + 顶栏 + FAB）

**背景**：安卓 V1.0.0 稳定，按既定方案将鸿蒙端口 UI 布局对齐安卓（底部 4 Tab：首页/书库/我的文件/偏好 + 左侧抽屉：横幅/导航/格言/底部按钮 + 顶栏汉堡+标题+导入 + 右下"继续阅读"FAB + 品牌色 #3949AB）。此前鸿蒙是自创的 6-Tab（最近/收藏/书签/浏览/设置/云盘）结构，与安卓不一致。

**改动**（全部在 `harmony/` 内）：
- `entry/src/main/ets/pages/Index.ets`：重构 build() 为安卓同款壳——顶栏（品牌色 48dp：☰ 汉堡 + 当前 Tab 标题 + ＋ 导入）；底部 4 Tab（首页/书库/我的文件/偏好，选中白/未选 #ddffffff，barBackgroundColor #3949AB）；左侧抽屉（280vp：品牌渐变横幅"值得读，好好读" + 5 导航项 最近阅读/书库/我的文件/网上书库/书签笔记 + 每次打开刷新随机格言 + 底部 4 按钮 设置选项/软件说明/晚上模式/退出）；右下 FAB"继续阅读"（仅首页/书库显示）；3 个模态浮层（书签笔记/网上书库[WebDAV，OPDS 待阶段5]/软件说明）。原 6-Tab 功能全部归位：最近→首页轮播+书库；收藏→书库星标筛选+首页珍藏；书签管理→抽屉书签笔记浮层；浏览→我的文件 Tab；设置→偏好 Tab；云盘 WebDAV→网上书库浮层。
- 抽屉定位 bug 修复：`Stack` 子级 `.align(Alignment.TopStart)` 在本 SDK 上未生效（面板被对齐到右侧），改用全屏 `Row`（面板 + weight-1 点击关闭区）固定左侧。
- `resources/base/element/color.json`：新增 brand_primary #3949AB、brand_accent #03A9F4、tab_selected #FFFFFF、tab_unselected #DDFFFFFF。
- 应用名修正：AppScope app_name = HowRead（base/en_US）/ 好好读（zh_CN）；entry 新增 zh_CN 资源（EntryAbility_label=好好读 + 4 个 tab 文案），base 补 4 个 tab 文案（Home/Library/My Files/Preferences）。
- `resources/rawfile/reading_quotes.txt`：从安卓 assets/reading_quotes.txt 复制格言库（1000+ 条，逐条 `文 —— 出处` 格式），Index 用 `util.TextDecoder` UTF-8 解码，抽屉随机取一条。

**验证**（鸿蒙模拟器 Pura 90，hdc + uitest + hilog 驱动）：
- 编译 BUILD SUCCESSFUL；安装启动正常，全部 NAPI 自测通过（仅 demo.mobi 样本本身不支持，与本次无关）。
- uitest dumpLayout 核对：4 Tab、顶栏（☰ 首页 ＋）、首页继续阅读卡片/最近封面轮播/快捷宫格（我的文件·网上书库·书签笔记·偏好）、FAB 均渲染；抽屉在左侧且含横幅/5 导航/格言/4 底部按钮；格言每次打开随机刷新（两次打开分别为"读书当读全书…"与"奇文共欣赏…"）。
- 交互验证：4 Tab 切换（hilog `Tab switched to N`）、晚上模式切换（theme 0→1 持久化）、软件说明/书签笔记/网上书库三浮层开合居中、FAB→Reader 打开并渲染（theme=1 生效）。全程无崩溃。未执行任何 git 命令。

---

## [2026-09-06] P1 缺陷修复五项：页面缓存并发竞态、OOM 清理 NPE、warm 态打开文件失效、TTS 重复朗读与暂停后翻页、TTS 空白页假死

**背景**：Android 主干代码三轮走查（品牌迁移/阅读核心/周边功能）确认的 P1 级缺陷，本轮只修 P1（P0 迁移类与 P2 JNI 泄漏类另行处理）。

**① 页面缓存并发竞态（错页渲染 / 渲染中页面被回收 → #error 占位页）**：`AbstractCodecDocument.getPage` 的单槽缓存（pageNuberCache/pageCache）原为无同步读写，而取页调用方分布在解码线程、搜索线程、TTS 线程与 Glide 线程池——并发换页可返回错页；更严重的是 `TTSService.playPage`、`DecodeServiceBase.searchText/processTextForPages`、`HorizontalModeController.getTextForPage/getPageText` 等"取页后即 recycle"的调用方回收的是**共享缓存页**，TTS 朗读回收页面时解码线程可能正持有同一对象渲染 → `render()` 抛 "page has been recycled" → 用户看到 #error 页。修复：①`getPage` 加 `synchronized`；②`CodecDocument` 接口新增 `getOwnedPage(int)`（AbstractCodecDocument 实现为直接 `getPageInner`，返回调用方独享的一次性页对象，MuPdfPage.createPage 自带 TempHolder.lock 线程安全），上述 5 处"取页后 recycle"的调用点全部改用 `getOwnedPage`，回收不再波及共享实例；③`HorizontalModeController.recyclePage` 与 `VerticalModeController.recyclePage` 原实现"按页号重新取共享缓存页再回收"本身就是危险操作（会回收其它线程正在使用的页），改为显式 no-op（文本提取路径已各自回收独享页，缓存内存由单槽 + CodecPageHolder LRU 约束）；`HorizontalModeController.getPageText` 改为独享页取文本后自回收（原依赖 recyclePage 二次回收共享页）。

**② DecodeServiceBase OOM 清理并发 NPE**：`performDecode` 的 OutOfMemoryError 分支向页面 map `put(null)` 后 `clear()`，全程不持锁，而 UI 线程可经 `synchronized getPageHolder` 并发迭代该 map（value 未判空）→ OOM 处理升级为崩溃。修复：清理段包进 `synchronized(this)` 与 getPageHolder 互斥；`getPageHolder` 迭代对 null value 跳过（防御）。

**③ OpenerActivity warm 态"用 HowRead 打开"失效**：manifest 为 `singleTask` 但未重写 `onNewIntent`——应用存活时（OpenerActivity 实例 finish 尚未销毁/复用实例）从文件管理器再次点书，intent 走 onNewIntent 默认空实现被静默忽略（自动测试 FN 已固化为"仅冷态生效"）。修复：onCreate 打开逻辑抽为 `handleIntent()`，重写 `onNewIntent` → `setIntent(intent)` + `handleIntent()`，冷/热两态共用同一处理。

**④ TTSEngine 新建引擎时整页朗读两遍 / 跳两页**：`speek()` 在 `ttsEngine == null`（stopDestroy 后首次播放、进程重启恢复等）时先创建引擎并**同步继续入队**当前页文本，随后 `onInit(SUCCESS)` 又递归 `speek(text)` 再入队一次（TextToSpeech 连接建立前的请求会排队执行）→ 整页读两遍；分页朗读分支的页结束信号也触发两次 → 连跳两页。修复：`speek` 捕获 `engineWasNull`，新建引擎路径设置好 pitch/rate 后直接返回（不入队），首次朗读由 onInit 成功回调驱动（synchronized 保证在外层返回后执行，只入队一次）；匿名 OnInitListener 补 ERROR 分支 Toast（与默认 listener 行为一致）。

**⑤ TTSService 两处状态机缺陷**：①连续 3 个空白页（扫描版 PDF/空白章节）后原逻辑直接 return——不停止服务、不释放 wakeLock、状态仍显示"播放中"假死至 wakeLock 超时；现该分支调用 `stopMediaSesstionAndReleaweWakeLock()` + `TTSNotification.showLast()` 干净收尾。②暂停竞态：`TTS_PAUSE` 走 `ttsEngine.stop()` 清队列，但 stop 前已派发的 `onDone(UTTERANCE_ID_DONE)` 回调仍会执行 `playPage(下一页)` → 按暂停后又自动翻页续读。修复：TTSService 新增代际令牌 `playPageGen`（volatile int）——每次 `playPage` 入口与每次 `stopMediaSesstionAndReleaweWakeLock`（暂停/停止/销毁）递增，新旧两套 UtteranceProgressListener 的 `onDone/onError/onUtteranceCompleted` 回调入口校验令牌不匹配即丢弃，暂停后仍在途的旧回调不再误触发翻页；正常翻页链 onDone→playPage 递增令牌后，同队列残留的重复回调亦被自然抑制。

**验证**：Ubuntu 服务器 `assembleGoogleDebug` / `assembleFdroidDebug` / `assembleProDebug` 三渠道 BUILD SUCCESSFUL（fdroid/pro 编译确认 noAds flavor 无回归）；MI9（48fee174，google arm64）安装冒烟——应用启动无崩溃、冷态 VIEW 打开 PDF 正常、**warm 态**（HOME 后应用存活）VIEW 打开 EPUB 成功切书（修复前被忽略）、EPUB 封面渲染清晰、连续 3 次快速翻页 0 FATAL。TTS 重复朗读/暂停续读与扫描版空白页场景建议人工试听复核。未执行任何 git 命令。

---

## [2026-09-06] 冒烟回归 24/24 全过 + 修复 run_all.py 离线设备导致整轮崩溃的问题


**改动**:`ci/autotest/run_all.py` 的 worker 增加设备连接异常捕获——此前若 devices.json 中的设备不在线(u2.connect 抛 ConnectError),整个运行在收尾阶段崩溃且 report.md 不生成;现改为该设备用例记为 SKIP("device not online"),其余设备照常执行并正常产出报告。

**验证**:三台真机(MI9/P20/KSA)L0 冒烟全集 24/24 PASS、0 FAIL、0 SKIP(结果目录 `ci/autotest/results/20260906-185109_L0_ui-device`);复现场景(仅一台在线)下首跑虽崩但 7/7 用例全过,修复后报告正常生成。未执行任何 git 命令。

## [2026-09-06] 清理旧测试目录 + 隔离测试过程文件与测试书目（不入代码仓）

**改动**：①删除旧测试目录 `Z:\opt\librera\autotest`（约 458MB，其中 99% 为 artifacts/ 历史运行截图证据；其源码 driver/cases/run_all 均已复制并演进到 `ci/autotest/`，文档由 `ci/autotest/docs/` 取代，全仓无任何代码引用旧路径，经确认后整体删除）；②新增 `ci/autotest/.gitignore`，将测试过程产物与本地测试书目挡在代码仓外：`teskbook/`（约 97MB 测试书，big25 四格式 ~100MB 压力书等）、`results/`（约 29MB 每次运行的截图/日志/报告）、`__pycache__/`、`*.pyc`——`ci/` 为新增目录且从未提交，ignore 即生效；③`ci/autotest/docs/ENV_DEPENDENCIES.md` 第 5 节补充"teskbook/ 不入库"说明及各测试书目的来源/再获取方式（big25 系列由 `bench/genbooks.py` 现场生成、Alice EPUB 来自 Project Gutenberg、小样本手造），新环境部署先放书目再跑 UI 层用例。

**验证**：`Z:\opt\librera\autotest` 已不存在；.gitignore 条目覆盖 teskbook/results/__pycache__ 全部路径；ENV_DEPENDENCIES 中 big25 来源经核实为 bench/genbooks.py 生成器（bench 下无现成 big25 拷贝）。未执行任何 git 命令，提交由用户完成。

## [2026-09-06] 新增四层自动测试体系（ci/autotest）：JVM 单元 / Robolectric 集成 / AVD UI / 真机 UI

**改动**：①新增 `ci/autotest/` 作为工程内自动测试目录——`docs/`（TEST_PLAN 分层矩阵/ARCHITECTURE 架构/ENV_DEPENDENCIES 环境依赖）、`config/`（devices.json 三真机+AVD 档案、cases.yaml 用例注册表含 layer/P0-P2 优先级/单用例超时/重试次数）、`lib/driver.py`（uiautomator2 驱动：进度显示+30s 心跳+单用例强制超时+失败自动重试+crash 守护+截图/dump/logcat 证据留存）、`cases/ui/`（L0 冒烟 SM-01~07、L1 功能回归 FN-01~08、L2 专项 PF-01/PF-03/ST-01）、`results/<时间戳>_<层级>/`（每次运行独立结果目录：report.md+run.log+证据）、`tools/ai_mock.py`（OpenAI 兼容 AI mock 服务 :8770）、`run_all.py`（UI 层入口，按 ABI 自动选包，--avd/--serial/--flavor）、`run_unit.sh`（服务器 JVM 层入口）、`teskbook/`（测试书目：big25.pdf/test.pdf/test.epub 等，新增 alicesadventures.epub）；②新增真实 JVM 测试 `android/app/src/test/java/com/foobnix/autotest/` 6 个类 53 条断言——单元层 MyMathTest/StringUtilsTest/TxtUtilsTest/AppBookmarkTest（LOG 框架依赖 Build.*，以 Robolectric runner 运行）、集成层 AppStatePersistTest（AppState JSON 持久化往返）/BookmarksDataTest（书签增查，走 getAllFiles 的 device.* 目录扫描语义）；③`libs.versions.toml`+`app/build.gradle` 新增 robolectric 4.16 testImplementation；④归档 8 个僵尸/坏断言旧测试（TestDB/TestGFile/TestSync/TestYearFormat/TestPage/TestSVG/ExampleUnitTest/LibreraBuildConfig）至 `ci/autotest/archive/stale_tests/`。

**验证**：服务器 `./gradlew :app:testGoogleDebugUnitTest` BUILD SUCCESSFUL（53 tests, 0 failed）；真机层三台（MI9/P20/KSA）L0 冒烟 24/24 PASS、L1 功能回归 22 PASS/0 FAIL/5 环境性 SKIP、google/fdroid/pro 三 flavor 冒烟各自全绿；ST-01 受控 monkey 5min 零 crash；PF-03 内存无泄漏（开书后 PSS 增长 12%）；框架具备每用例进度/心跳/超时/重试与时间戳结果目录（results/<时间戳>_<层级>/）。已知发现：Debug 包冷启动 3.3s 超阈值（待 release 复测）、KSA/P20 书库首扫不收录 Download（FN-02 SKIP）、TTS 入口未定位（FN-07 SKIP）、VIEW intent 仅冷态生效（warm 态 onNewIntent 被忽略，driver 已固化冷态投递）、MedicineAVD 镜像损坏致 UI-AVD 层暂挂（框架就绪，待 wipe data）。未执行任何 git 命令。

## [2026-09-06] 官网版本号同步：APK 升级 v1.0.0 后更新站点展示版本

**改动**：随 `android/app/gradle.properties` 升级为 1.0.0（appCodeNumber 7200），同步更新官网展示——①中英首页下载区副标题"当前版本 0.9.0/current version 0.9.0"→1.0.0，Google Play/F-Droid/GitHub Releases 三张下载卡版本号→1.0.0；②中英更新日志页"当前版本 0.9.x/Current version: 0.9.x"→1.0.x。首页更新日志手风琴中"品牌焕新与架构升级（v0.9.0）"为历史条目，保留不动。

**验证**：本地 jekyll 重建，中英首页与更新日志页版本字样全部为 1.0.0/1.0.x，无 0.9 遗留（历史条目除外）。未执行任何 git 命令。

## [2026-09-06] 官网带宽优化：删除 pdf.js 源码映射与 Librera 历史版本截图（保留最新 8.9 系列），全站 399 张图片转 WebP，站点体积 105MB→33MB（-69%）

**改动**：①删除 `docs/online-book-reader/pdf/` 下 3 个 `.map` 源码映射（8.6MB，线上永不加载）；②`docs/what-is-new/` 删除 8.9 以前的历史版本发布截图（根目录 48 张 + 7.10~8.3 版本子目录 65 张），保留最新 8.9 系列 12 张，并清理 60 个中英文 md 里对已删截图的引用（含空表格残留）；③其余全部站点图片（399 张：首页截图/图标页/css 配图/369 张 FAQ 截图/手册图）批量转 WebP（质量 80，58.4MB→14.4MB，-75%），原 PNG/JPG 删除，477 个 md/css/布局文件内的引用同步改写；favicon（`web/256.png`）与 og:image 保持 PNG，页面内 `<img>` 图标改用新增的 `web/256.webp`（94KB→7KB）；④顺手清理 what-is-new 页内 60 处指向不存在目录 `manual/` 的死图片引用（上游遗留）；⑤首页图片转 WebP 后 109/116/315KB→约 47/44/50KB。

**修复过程**（批量改写引发的三处回归，均已修复并验证）：引用改写映射曾误带目录前缀导致 427 个文件引用损坏（反向替换修复）；空表格清理误删 front matter `---` 分隔线（120 处恢复）；跨主题图片引用丢失 `../` 前缀（1448 处按站点根相对解析修复）。

**验证**：jekyll 重建后 chrome 链接审计 8509 条全过、构建产物 webp 引用零缺失、md 内损坏图片引用零残留；浏览器实测首页（webp 图标+三张截图）、FAQ 页（9 张跨主题 webp 全载）、更新日志页（仅剩 8.9.54/8.9.50 最新系列 3 张）。docs 目录 105MB→33MB。未执行任何 git 命令。

## [2026-09-06] 用户手册同步刷新 v1.0.0:定位语对齐官网、版本号 1.0.0、WebDAV 截图匿名化

**改动**:①手册定位语与官网首页统一——封面副标题与 1.1 首句由「高度可定制的电子书阅读器」改为「一个专注于个人阅读体验的开源电子书阅读软件」;②版本号随 v1.0.0 升级刷新——封面「适用版本」与 1.3「当前版本」改为 HowRead 1.0.0(versionCode 7200);手册文件名不带版本号,定名 `store/manual/HowRead用户手册.docx`,旧 `-0.9.docx` 删除;③真机截图 WebDAV 信息匿名化——`img/hr09.png`(WebDAV 同步配置)服务器地址 leestation.ddns.net:55005→dav.example.com:5005、账号 Lee→demo、同步路径 /home/Drive/Books/howread→/dav/Books,`img/hr05.png` 服务器名 LeeStation→MyNAS(PIL 擦除原文字后按原图字色/字号重绘,输入框下划线完整重绘,尺寸保持 1080x2221,原图备份于 bench/hr_backup/)。

**验证**:重新生成 DOCX+TOC 占位(36 条)+页脚域修补,postcheck 0 错误;Word 导出 PDF(27 页)渲染后 judge 验收受影响 5 页(封面/1.1/1.3/图11 同步配置/图13 我的文件)全部通过——新定位语与 1.0.0 版本号生效、截图中无真实域名/账号残留、下划线与版式无回归。未执行任何 git 命令。

## [2026-09-06] 版本升级 v1.0.0 + 软件说明措辞微调

**改动**:①版本号由 0.9.0 升级为 1.0.0(`android/app/gradle.properties` 的 appVersionNumberBase 0.9→1.0,appCodeNumber 7198→7200 保持单调递增,三渠道 google/pro/fdroid 统一生效,`app/build.gradle` 注释同步);②软件说明页描述"一个专注于个人阅读体验的开源电子书阅读器"改为"一个专注于个人阅读体验的开源电子书阅读软件"(values-zh-rCN/strings.xml 的 app_description)。

**验证**:Ubuntu 服务器构建三渠道 Release APK 全部 BUILD SUCCESSFUL,产物 HowRead-1.0.0-arm64.apk / HowRead-Pro-1.0.0-arm64.apk / HowRead-Fdroid-1.0.0-arm64.apk。未执行任何 git 命令。

## [2026-09-06] 官网素材升级：应用图标与首页截图替换为 HowRead 真实素材

**改动**：①`docs/web/256.png` 由旧 Librera 绿色图标替换为 HowRead 新图标（书+「书」字金色设计，取自 `bench/howread_cleaned.png` 1536px 原图缩至 256px，站点导航栏/Hero/页脚/favicon/og:image 全部同步生效）；②首页三张截图 `docs/1.png/2.png/3.png` 由旧 Librera 英文占位图替换为 HowRead 真机截图（`store/manual/img/` 的 hr01 首页书架+阅读统计、hr04 书库网格、hr12 阅读器划词菜单），统一缩放至 720px 宽并做 256 色量化压缩（453/325/160KB→109/116/315KB，照顾国内带宽）；③旧素材备份于 `bench/tmp/backup_site_assets/`。

**验证**：本地 jekyll 重建 + 浏览器实测，新图标（导航栏/Hero）与三张真机截图渲染清晰、点击放大正常；中英首页与旧版 zh.md 页面引用同一组文件，全部自动更新。未执行任何 git 命令。

## [2026-09-06] 用户使用手册刷新:基于 CHANGES.md 全量梳理,附录重写为「与原版 Librera Reader 对比」(A.1 功能表 / A.2 界面优化 / A.3 懒加载性能优化+提速数据 / A.4 渠道工程),正文补书架视图与性能条目

**改动**:①通读 CHANGES.md 全部条目(2026-08-12~09-06)汇总 HowRead 相对原版的全部差异;②附录由 4 条要点扩为四节——A.1 全新功能一览(表7:AI 交互/页内双语翻译/笔记/WebDAV 同步/阅读统计/首页仪表盘/书架视图/在线阅读器)、A.2 界面与交互优化(图标与 MIUI 绿边修复、阅读底栏四行精简两行、触控区 25→40dp、软件说明改版、OPDS 源精简、书库滚动位置记忆)、A.3 性能优化(引擎懒加载、标签页懒创建、按章惰性排版、首屏门闩、同步零冗余,附表8 提速实测:TXT 热 27~30s→0.09s、EPUB 冷首屏 17.2→7.1s、TXT 直转 28.5→12s、MOBI 二开页数坍缩修复)、A.4 渠道工程与多平台(三渠道统一版本、广告 SDK 抽象、去 GMS、官网+在线阅读器、HarmonyOS);③第 3 章补书架视图描述(木纹搁板/进度角标/收藏星标/四视图)、1.1 增加性能懒加载条目;④1.2 节段前分页修复表 1 跨页孤行。

**验证**:postcheck 0 错误;Word 导出 PDF(27 页)渲染后 judge 逐页验收,首轮仅表 1 跨页孤行一处 fail,分页修复后复验 27/27 通过。未执行任何 git 命令。

## [2026-09-06] 新增用户使用手册:store/manual/HowRead用户手册-0.9.docx(中文 DOCX,封面/目录/10 章+附录/16 张 MI9 真机截图/6 张表格)

**内容**:按"软件概述→安装配置→书库→阅读→AI 大模型交互→笔记→WebDAV 同步→阅读统计→备份迁移→常见问题→基于 Librera 的修改说明"大纲撰写,重点突出 HowRead 新增的 AI(问答/翻译双语对照/书籍简介)、笔记(按书分组/TXT·Markdown·JSON 导出)、WebDAV 同步(冲突策略/定时同步/同步日志/文件浏览)与阅读统计(5 卡片+周月柱状图)四大特色功能;所有界面入口、菜单文案、默认值均从源码(strings.xml/AiConfigDialog/WebDavSyncDialog/DashboardFragment2 等)核实。**配图**:MI9 真机(adb + uiautomator2)逐屏截取 19 张——首页仪表盘、统计柱状图、书架、我的文件、添加 WebDAV、偏好、AI 配置(密钥为掩码未泄露)、WebDAV 同步、同步日志、阅读界面、选中文本浮层、AI 问答(真实 glm-4.5-air 回答)、笔记编辑器、书签笔记列表、AI 笔记全文、AI 翻译、书籍操作菜单等,截图统一裁去系统状态栏/手势条(bench/crop_img.py)。

**验证**:docx-js 生成(封面 R1/三节页码 罗马+阿拉伯/TOC 域)+ add_toc_placeholders 注入 32 条目录 + fix_footer_fields 修补页脚域;postcheck 0 错误;Word 导出 PDF(26 页)渲染 PNG 后逐页视觉验收 26/26 通过(封面铺满、目录页码、表格无跨页断裂、图注同页、无乱码)。生成脚本与中间产物在 bench/docxgen(不入库)。未执行任何 git 命令。

## [2026-09-06] 官网全站改版：套用 App-Showcase-Template 展示模板（GPL-3.0），首页改为 Hero+功能卡片+更新日志手风琴+下载区，全站 606 页换新导航栏/页脚

**改动**：①新增 `docs/showcase/style.css` + `script.js`（基于 yxs2003/App-Showcase-Template 改编，GPL-3.0 署名保留于文件头与页脚；顺手补上模板缺失的 ripple 关键帧动画；去掉模板的 SyncPro 下载弹窗，改为真实链接）；②新增 `_layouts/home.html`（首页展示布局：导航/Hero/6 功能卡/真机截图/关于+数字/更新日志手风琴/4 下载卡/页脚）与 `_layouts/page.html`（子页通用布局，保留旧版的返回链接与 versions 引用逻辑、图片点击放大 modal）；③新增 `_includes/navbar.md`（全站导航 + 中英切换）与 `_data/changelog.yml`（首页手风琴最近 3 条中英数据）；④`index.md`/`en.md` 改为数据化 front matter 驱动的 home 布局；⑤其余 606 个 md（download/faq/what-is-new/PrivacyPolicy/wiki 等全部语言）由 `layout: main` 批量换为 `layout: page`，正文零改动；⑥旧 `main.html`/`wiki.css` 保留不再引用；在线阅读器页面为独立应用界面，不套官网外壳；⑦语言切换规则：优先当前目录的语言变体（Liquid 存在性校验），目录无对应语言文件时回落到目标语言首页；导航/页脚链接全部使用显式 `.html` 后缀，不依赖 GH Pages 的无扩展名解析。

**验证**：Ubuntu 服务器 apt 安装 ruby3.0 + gem 安装 jekyll4.4.1（清华镜像），按 GH Pages 同构方式构建（baseurl /howread）并 :8767 起本地服务；browser-use 黑盒遍历截图——中/英首页（Hero、6 功能卡、截图区、手风琴展开、下载 4 卡、页脚 GPL 署名）、FAQ 图片点击放大、窄屏 390px 汉堡菜单、导航锚点滚动偏移、语言切换 6 个场景（含深层 FAQ 子页与 wiki 页）全部正确；navbar/footer 链接审计 8509 条全部命中磁盘文件；旧内容里历史遗留的无扩展名链接（GH Pages 可解析）未改动。未执行任何 git 命令。

## [2026-09-05] 全量 BUG 修复：两轮独立代码审查合并清单（除两处存疑项外全修），约 40 项，覆盖同步引擎/持久化/AI/阅读引擎/UI 五个板块

**同步/持久化（数据安全主线）**：①WebDAV 列目录失败时跳过书籍上传与服务器删除阶段（此前网络抖动会把本地全部书籍信息以旧进度全量重传覆盖服务器）；②`syncThreeWayFile` 稳态下远端 404/空文件不再被当作"远端全字段删除"——原逻辑会清空本地配置并广播到所有设备（历史配置丢失事故的完整根源），现按首同步处理为"本地上传播种"；③`buildInfoWithHash` 的 `t=now` 不再混入变更比较（新增 `sameIgnoringT`）：修掉了每台设备每轮同步对每本书无条件 PUT 的风暴，`booksSynced` 统计改为真实上传数；④doSync 回写改为 `mergeSnapshotsAtEnd` 增量合并（进度按键比较 t 新者胜、书签并集、写回前按最新墓碑删除已删键）——同步期间翻页/加删书签不再被陈旧快照吞掉；⑤墓碑生命周期三重修复：新增 `DeletedBooks.expireOlderThan`（30 天过期）、列目录成功后清除服务器上已不存在的陈旧墓碑（防止日后误删同名新书的服务器信息）、发布信息携带 `dk`(键→删除时间) 供其它设备应用/越过删除（多设备删除不再复活）；⑥`IO.java` 加固：`getLock` 改 ConcurrentHashMap（修复多线程 HashMap 竞态导致锁失效）、`writeString` 临时文件+rename 原子替换+显式 UTF-8（进程死亡不再产生截断 JSON）、`readJsonObject` 解析失败先把损坏内容备份为 `*.corrupt`（不再静默清空）、`readString` 缓存键加入分隔符标志（修 .txt 编辑器换行丢失）、`copyFile` 后失效缓存；⑦`merge3` 双端新增嵌套对象改 firstSyncMerge 结构合并（不再丢远端整对象）、`unionArrays` 保留元素类型与标量侧（不再把对象压成字符串）；⑧404 判定去掉消息子串匹配（URL 含 404 的哈希不再被误判）；⑨`SyncChangeLog` 加 begun 守卫（异常路径不再重复提交上一轮日志）；⑩Sardine/OkHttp 客户端按凭据缓存复用（不再每轮新建连接池）；⑪备份恢复/异机配置采纳：`unZipFolder` 解压后删除外来 `.base/.tmp/.corrupt`、`adoptForeignDeviceConfigs` 采纳后同步写 base=采纳内容（不再把恢复配置误判为本地改动强推服务器）；⑫`JsonDB.get` 不再每次排序（保持用户顺序）、解析失败返回可变列表（修 add/remove 崩溃）；⑬书签键同毫秒碰撞时 t+1ms 挪位（不再互相覆盖）。

**AI（配置与 KEY 的存取保持现状，只修功能性缺陷）**：①OpenAI 兼容分支仅对非 api.openai.com 端点发送 `chat_template_kwargs`/`enable_thinking`（官方 OpenAI 之前必 400）；②Anthropic 回复解析遍历 content 拼接 text 块（思考模式下不再"空回复"）；③OkHttpClient 按超时档缓存复用；④旧翻译面板关闭"保存翻译结果"时改用 `inMemory` 缓存（会话内仍命中，不再重复计费请求）；⑤`TranslationCache.flush` 与 `BilingualBuilder` 双语 epub 构建改临时文件+原子改名（截断缓存不再被永久信任，ensure 增加 ZipFile 可读校验）；⑥`BilingualSession` 的 attempts 改 ConcurrentHashMap、空段枚举判空、`suppressExitOnDestroy` 失败复位。

**阅读引擎/UI**：①解码执行器 catch 移入任务级（一次异常不再白屏到重启）；`saveAnnotations` 回调放 finally（"正在保存…"对话框不再永久卡死）；`searchText` 整体 try/finally+判空+同步回收页面（搜索状态不再卡死）；`shutdownInner` 与 `getPageHolder` 同锁+判空（native 泄漏）；`nextTask`/`addAny` 以服务实例为锁（shutdown/restore 竞态不再双执行任务）；②退出"另存新文件"的整本拷贝移到后台执行器（修 ANR+流泄漏）；`onScrollYPercent` 强转优先级修复；`getScrollValue` 判空；③`MuPdfDocument.isHasChanges` 加 volatile、`getPageCountSafe` 缓存成功后才提交且键改 `w*31+h`（旋转不再取错页数）、保存后验证输出文件才清脏标记；④`AdvGuestureDetector.destroy()` 注销 EventBus 并在切换控制器时调用（修 Activity 视图树泄漏与幽灵事件）；⑤TTS：通知 PendingIntent 加 `FLAG_UPDATE_CURRENT`（回书不再停旧页）、封面失败也发完整控制通知、`START_STICKY` 重启不再常驻"请稍候"通知；⑥拒授"所有文件访问"不再触发删库重扫（先 `isExternalStorageManager()` 确认）；`navigateToTab` 先关悬浮页（抽屉导航失效修复）；FAB 取最近书目不再在主线程解析整本书；`SearchAllBooksWorker` 的 `List.of`（minSdk24 崩溃）改 singletonList、实验扫描 `JsonDB.set`→`add`（不再清空书库文件夹）；`BookCSS.load1` 存储未挂载时跳过过滤回写（SD 卡未挂载不再清掉书库文件夹配置）；`AlertDialogs` 取消不再双跑 onDismiss、文本编辑保存不再 trim 换行；`ClickUtils` 点击区整数除法精度修复；小组件 null action 判空。

**过程说明**：`BilingualSession.java` 在编辑中被一次 PowerShell 编码操作损坏（GBK/UTF-8 混写），已通过字节级逆向还原+从 21:58 旧构建 APK 的 dex 字符串池提取原始中文提示词/正则逐一恢复（翻译提示词、【编号】解析正则、底部提示语均与原文一致），修复后全文件扫描无残留损坏字符。

**验证**：三渠道 Release `BUILD SUCCESSFUL`；MI9（google arm64）与华为机（pro arm）安装启动正常、书库/统计完好、AI 配置测试连接"连接成功"；A机（12S）USB 掉线待重连后补装（pro arm64 包已备好）。**同步实测**（MI9 app-SyncLog + BENCH 日志）：新代码首轮收敛上传 429 本（历史各设备书签路径互相不同的一次性合法收敛）后，**后续两轮静默周期同步均为 synced=0**——旧算法每轮无条件 429 个 PUT 的风暴彻底消除；顺带把 `searchPathsHiddenJson`（隐藏书库文件夹记忆，设备相关路径）加入 CSS_DEVICE_FIELDS 保持本机，不再跨设备同步。鸿蒙零改动，未执行任何 git 命令。

---

## [2026-09-05] 在线阅读器四项体验升级：①鼠标滚轮/点击翻页 ②刷新保持阅读（IndexedDB 会话+位置恢复）③非内嵌字体 PDF 兼容模式 ④小数进度显示；用《白鹿原》真实三格式实测

**背景**：用户以《白鹿原》(epub/pdf/mobi，X 盘真实藏书) 实测反馈：PDF 打开无文字、刷新退出阅读界面、页面偏暗。诊断：白鹿原.pdf 为 2009 年版本，**0 个内嵌字体**（BaseFont 为 GBK 十六进制名的宋体/华文行楷，Encoding=GBK-EUC-H），pdf.js 在 Chrome 内核下因 local() 系统字体限制无法绘制非内嵌中文字形（仅标点/西文可见）——Chrome 平台硬限制。

**① 滚轮/点击翻页**：`reader/foliate/paginator.js` 注入 wheel+click 处理（分页模式）——滚轮下/上=后/前一页（400ms 节流），点击左 1/3=上一页、右 2/3=下一页；书内 a/button 等元素点击不触发翻页；拖选文字（位移>6px）不误翻；scrolled 流保持原生滚动。坑：初版引用了 `#iframe` 私有字段，但该字段属于文件内另一个类，运行时 SyntaxError"Private field must be declared in an enclosing class"——改用元素自身 clientWidth + pointerdown/up 位移判定。

**② 刷新保持阅读**：新增 `reader/session.js`（IndexedDB 存最近阅读：文件 Blob/URL + 名称 + 位置{cfi,fraction,page}）；index.html 启动时无 `?file=` 参数则自动恢复最近会话（本地文件从 IndexedDB 取 Blob 重建，PDF 带 `&page=N`），有 `?file=` 时若与所存 URL 相同则恢复到保存位置；EPUB/TXT 的 `relocate` 事件回传 `{cfi,fraction}` 节流 800ms 落库；PDF 的 `pagechanging` 经 postMessage 通知父页落库；`pdf/viewer.html` 支持 `?page=N` 初始页。坑：挂载容器在 `display:none` 时 foliate 的 `goTo` 永不完成（无排版无 relocate）——容器必须先可见再挂载/跳转。

**③ PDF 兼容模式**：`pdf/viewer.html` 工具栏新增 🖥 按钮——用浏览器原生 PDFium 查看器（embed）打开同一文件，系统字体渲染，非内嵌中文 PDF 完整显示；带"← 返回"切回 pdf.js 视图。

**④ 进度小数显示**：fraction<10% 时显示一位小数（如 0.5%），避免长书开头进度恒显 0%。

**验证**（LAN + 浏览器逐项）：白鹿原 EPUB 自动跳过空白分隔节直达第一章、35 章目录、滚轮/点击翻页生效、刷新后 CFI 恢复原位；白鹿原 MOBI 打开渲染；白鹿原 PDF 兼容模式可切换（pdf.js 视图内汉字缺失为 Chrome 平台限制，EPUB/MOBI 版本与兼容模式为推荐读法）；中文 TXT/EPUB 回归正常。未执行任何 git 命令。

---

## [2026-09-05] 在线阅读器重构：弃用 MuPDF WASM，改用浏览器原生引擎（pdf.js + foliate-js）——彻底解决中文乱码，体积从 7.7MB 降到 <2MB

**背景**：上一轮已确认乱码根因是 wasm 编译参数 `-DTOFU_CJK` 剔除了 CJK 回退字体，修复需要 emscripten 重编译内嵌中文字体。因服务器到 GitHub 仅 ~20KB/s 无法拉取 emsdk，评估后选定**浏览器原生方案**（用户确认）：PDF 用 Mozilla pdf.js（Firefox 内置同款引擎），EPUB/MOBI/AZW3/FB2/CBZ/TXT 用 foliate-js（渲染为 HTML 由浏览器排版）——中文直接用浏览器系统字体回退，天然无乱码；首次加载从 7.7MB（1~3 分钟）降到 <2MB（普通网络秒开）。

**重构 `docs/online-book-reader/`**：
- `index.html` 全新入口页：打开文件/拖放/URL 输入，按扩展名路由——PDF 全屏 iframe 嵌入 `pdf/viewer.html`，其余格式动态挂载 foliate 阅读组件；保留 `?file=` 直连参数；错误显示在加载横幅上（修复了此前错误提示被隐藏的问题）。
- `pdf/viewer.html`（新写）：基于 pdfjs-dist 6.3.289 legacy 构建的 `pdf_viewer.mjs` 组件组装——工具栏（翻页/页码跳转/缩放档位/自适应）、目录侧栏（goToDestination）、搜索栏（PDFFindController 高亮+计数）、下载；`cMapUrl`/`standardFontDataUrl`/`wasmUrl` 指向本地目录保证非内嵌 CJK PDF 正常；加载进度百分比显示。
- `reader/reader-page.js`（新写）：`<foliate-view>` 组件挂载 + 工具栏（返回/目录/翻页/进度滑条/A± 字号/relocate 百分比），`book.toc` + ui/tree.js 生成目录。**关键坑：foliate `view.open()` 不会渲染首屏，需显式 `goToFraction(0)` 触发**。默认亮色书页（白底黑字，修复此前 iframe 透明叠加暗色底"像蒙了一层"的观感），工具栏 🌙/☀️ 一键昼夜切换（注入 `color-scheme` 与背景/前景色，localStorage 记忆选择）。
- `reader/txt-to-epub.js`（新写）：foliate 1.0.1 不支持 TXT，内存中将 TXT 转为最小 EPUB 再挂载——按"第N章/节/回/序章/楔子"等标题智能分章，zip 写入使用与 foliate 读取端同源的 zip.js ZipWriter。**两个关键坑**：①章节 zip 条目必须带 `OEBPS/` 前缀（否则 foliate 按 OPF 目录解析 404→size 0→空白页）；②add 必须用 `TextReader` 而非流式输入（否则 zip.js 不记录 `uncompressedSize`→section size 0→空白页）。
- 删除：`lib/`（mupdf wasm 14.4MB + gz 7.7MB + 绑定层）、`mupdf-view*.js`、`mupdf.c`、`build.sh`、`.gitignore`（mupdf-* 规则已无用）。
- 库来源均走 npmmirror 国内镜像（pdfjs-dist 8.5MB tgz、foliate-js 104KB tgz、@zip.js/zip.js dist/zip-core.js 320KB）。

**局域网端到端验证**（python http.server + 浏览器逐格式截图）：中文 PDF 简繁英全部清晰、目录/搜索/缩放工具齐全；中文 EPUB 正文/TOC 跳转/进度/字号正常；中文 TXT 分章正确、渲染清晰；demo.mobi 打开正常。未执行任何 git 命令。

---

## [2026-09-05] 官网在线阅读器增加 wasm 下载进度显示：worker 流式读取统计已接收字节并 postMessage PROGRESS，页面 Loading 区显示百分比+进度条+已下载/总量

**实现**：`mupdf-view-worker.js` 新增 `fetchWithProgress()`——用 `response.body.getReader()` 流式读取 wasm（.gz 优先，回退 .wasm），按 Content-Length 统计进度，每收到一个分块向页面 `postMessage(["PROGRESS", {received, total}])`，读取完拼接为 ArrayBuffer（替代直接 arrayBuffer()）。`mupdf-view.js` 初始消息处理（READY 之前）放行 PROGRESS 类型并转发给 `mupdfView.onwasmprogress` 回调（原先非 READY/ERROR 首消息会直接 reject）。`index.html` 注册回调：在 #placeholder 渲染「正在加载阅读器组件 Loading reader components… xx%」+ 进度条 + 已下载/总量 MB + "首次加载需下载渲染引擎，10 分钟内缓存" 提示；READY 后 openEmpty()/openURL() 自动替换该区域。

**验证**：双 JS 文件过 node --check；局域网端到端回归（python3 http.server + 浏览器）：READY 正常、`?file=` 打开 PDF 渲染 5 页 canvas、无错误。线上效果（进度条动态）推送后在 github.io 慢网络下实测可见。未执行任何 git 命令。

---

## [2026-09-05] 优化：软件说明页面改版——①官网改为 https://380121850.github.io/howread/ 并显示为"HowRead 好好读" ②更新日志指向 https://github.com/380121850/howread/blob/master/CHANGES.md ③删除"带图标的 HowRead Pro：无广告的应用程序"行，替换为"一个专注于个人阅读体验的开源电子书阅读器" ④整页统一左对齐并在末尾新增"This is a fork of Librera Reader"

**改动**：①`config.xml` 的 `my_site` 换为新官网（官网行为空时隐藏的逻辑不变），`about_section.xml` 官网行显示文本由硬编码 "howread.git" 改为 "HowRead 好好读"；②`AndroidWhatsNew.WHATSNEW_URL` 换为 CHANGES.md 地址，`getLangUrl` 不再追加语言后缀（单一 markdown 文件无语言变体）；③删除图标+Pro 推广行（`downloadPRO`），原位替换为引用新资源 `app_description` 的左对齐 TextView，`AboutSectionBinder` 同步移除该行的点击绑定（Pro 跳转方法 `Urls.openPdfPro` 保留未删）；④各可见行统一左对齐（唯一居中的就是被删除的 Pro 行），布局末尾新增 `fork_of_librera`（"This is a fork of Librera Reader"，仅英文）左对齐一行。

**验证**（MI9 真机，google arm64 包）：软件说明弹窗自上而下——版本胶囊（好好读 v0.9.0 build…）、描述行、更新日志、许可、支持邮箱、官网（显示 HowRead 好好读）、fork 声明，全部左对齐；点"更新日志"在浏览器打开 380121850/howread 的 CHANGES.md ✓；官网目标 https://380121850.github.io/howread/ 实测可访问 ✓。pro+google Release 重新编译 `BUILD SUCCESSFUL`。鸿蒙零改动，未执行任何 git 命令。

---

## [2026-09-05] 官网在线阅读器补充优化：新增预压缩 mupdf-wasm.wasm.gz（14.4MB→7.7MB），worker 优先下载 gz 并用浏览器原生 DecompressionStream 解压；实测确认 github.io 国内访问带宽是主要瓶颈

**实测数据**（本机 curl 直连 github.io）：CDN 对 .wasm 请求带 Accept-Encoding: gzip 时返回 gzip 传输（Content-Encoding: gzip）——7.69MB 耗时 145.7s；无压缩 14.4MB 耗时 258.9s，即到 github.io 带宽仅约 53KB/s。浏览器 fetch 默认携带 gzip 头（等价 7.7MB），此前 worker 的同步 XHR 25 秒即被掐断是雪上加霜。

**改动**：`docs/online-book-reader/lib/mupdf-wasm.wasm.gz`（gzip -9 预压缩，7.69MB）；`mupdf-view-worker.js` 的 loadWasmBinary() 优先 fetch `mupdf-wasm.wasm.gz` 并用 `DecompressionStream("gzip")` 解压（Chrome 80+/Edge/Firefox 113+/Safari 16.4+ 均支持），不支持时回退原始 `.wasm`。等价体积、双保险。

**局域网端到端验证**（服务器 python3 http.server + 浏览器实测）：worker 正常 READY（20 个方法全部注册），`?file=` 打开 PDF 成功渲染多页 canvas、文本层与工具栏正常、无任何报错。**结论：worker 修复本身已验证正确；线上 github.io 首次打开预计需 2~3 分钟（7.7MB@50KB/s），之后走 HTTP 缓存（max-age 600s）。国内要快只能换 CDN/自定义域名（如 Cloudflare 前置）或境内托管，属后续可选方案。** 未执行任何 git 命令。

---

## [2026-09-05] 官网上线：docs/ Jekyll 站全面品牌化改造为 HowRead（好好读），适配 GitHub Pages 项目页路径，新增 Pages 自动部署 workflow，在线阅读器一并发布

**背景**：docs/ 原为 Librera 老官网（Jekyll、发布于 librera.mobi、全站绝对路径）。本次将其改造为 HowRead 官网并发布到 GitHub Pages 项目页 `https://380121850.github.io/howread/`，同时上线自带的 MuPDF-WASM 在线阅读器 `online-book-reader/`（全静态、相对路径、14.4MB wasm 本地引用，无需改动路径即可在 /howread/ 子路径工作）。

**路径适配（防断链）**：`docs/_config.yml` 新增 `baseurl: /howread` 与站点元信息；服务器端 sed 批量把全站 103 个 md 文件的绝对链接 `](/xxx)` 改为 `]({{ site.baseurl }}/xxx)`；`_layouts/main.html` 的 css/在线阅读器/站点标题链接与 FAQ 返回链接全部加 baseurl 前缀，grep 审计绝对路径清零。

**品牌与内容**：布局标题/侧栏/页脚改为 HowRead · 好好读（联系渠道改为 GitHub Issues，favicon 改用 web/256.png，移除失效的 favicon.ico 与 RSS 引用）；首页重写为中文为主（根路径 / 即中文页，`_includes/codes.md` 新增根页与 /en 语言判定、语言选择器相应调整），英文版移至 en.md；主要功能列表更新为现状——**WebDAV 同步（替代已移除的 Google Drive）**、HarmonyOS 版本、在线阅读器入口等；其余 8 个语言首页与下载页改为指向中英文版的占位。下载页重写：Google Play（com.howread.reader）、F-Droid（占位）、GitHub Releases（releases/latest）。隐私政策新增 `PrivacyPolicy/com.howread.reader.md`（英文）与 `com.howread.reader.zh.md`（中文全文）：AdMob 广告仅 Google Play 渠道、无 GMS/Drive、WebDAV 直连用户自建服务器、本地文档不上传，索引页 HowRead 置顶、旧 Librera 各包政策归档保留。

**清理**：删除 `CNAME`（librera.mobi）、`ads.txt`/`app-ads.txt`（旧 AdSense ID）、`google6872a6801fea9f0a.html`（旧 Search Console 验证）、`epub-reader/`（与 online-book-reader 字节级相同的 14.9MB 副本，可由其 build.sh 再生成）；在线阅读器标题品牌化为"在线阅读器"；根 `README.md` 的下载/链接区块更新为新官网地址。

**CI**：新增 `.github/workflows/deploy.yml` —— push 到 master（paths 限定 docs/** 与本文件）或手动触发时，checkout → configure-pages（enablement 自动开启 Pages）→ **jekyll-build-pages（source: ./docs，layout/baseurl 依赖 Jekyll 构建）** → upload-pages-artifact → deploy-pages，带 Pages 权限与并发锁。

**遗留说明**：首页截图 1/2/3.png 仍为旧版 UI 截图，后续建议真机截图替换；FAQ/更新日志小语种翻译仅修链接不改内容。首次部署后需在仓库 Settings → Pages 确认 Source 为 GitHub Actions（workflow 的 enablement 会自动处理）。未执行任何 git 命令。

---

## [2026-09-05] 官网首批反馈修复：①在线阅读器加载 PDF 报 TypeError（GitHub Pages 掐断 emscripten 同步 XHR，worker 永不 READY）②中文界面点"关于App"变英文（根页 URL 判定失配）③FAQ/更新日志区分当前版与旧版存档 ④侧栏阅读器入口中文化

**① 在线阅读器**（浏览器实测定位）：`mupdf-view-worker.js` 里 emscripten 胶水用**同步 XMLHttpRequest** 加载 14.4MB 的 `mupdf-wasm.wasm`，在 GitHub Pages CDN 上约 25 秒后抛 `NetworkError: Failed to execute 'send' on 'XMLHttpRequest'`（curl 直取同一文件 200 正常，问题仅出在同步 XHR 路径）；worker 因此永远发不出 READY，主线程 `mupdfView` 只有 3 个硬编码方法，`openDocumentFromBuffer` 等全部缺失，打开 PDF 即报 `TypeError: this.mupdfWorker.openDocumentFromBuffer is not a function`。修复：worker 先 importScripts 胶水（仅定义工厂），再 `fetch("lib/mupdf-wasm.wasm")` 异步预载二进制，包装工厂注入标准 `wasmBinary` 参数后 importScripts 绑定库完成实例化，全部 workerMethods/onmessage/READY 逻辑移入 start()，结构不变；fetch 失败时向页面 postMessage ERROR（页面 UI 显示真实错误而非永久 Loading）。无需重新编译 wasm。

**② 中文界面**：GitHub Pages 构建下根页 `page.url` 不是 `/index.html`（实测英文侧栏、语言选择器显示 English），`codes.md` 根页判定失配 → 根页被当英文页渲染界面（正文仍是中文），从 /zh 点"关于App"跳根页即"变成英文"。修复：根页判定兼容 `/`、`/index.html`、`''` 等形式（`bare_url` 归一化），根页侧栏恢复中文。

**③ FAQ/更新日志分区**：仿隐私政策页结构——`faq/index.md`、`faq/zh.md` 页首新增「HowRead 好好读（当前版本）」小节（在线阅读器/下载/隐私/GitHub 反馈入口），45 篇旧文章整体归入「旧版 Librera FAQ（存档）」并注明"功能概念仍适用、界面可能不同"；`what-is-new/index.md`、`zh.md` 新增「HowRead 更新日志（当前版本）」（当前版本 0.9.x，指向 GitHub Releases），7.10–8.9.x 旧日志归入存档小节。

**④ 侧栏入口**：`_layouts/main.html` 侧栏阅读器链接在中文界面显示「HowRead在线阅读」，其他语言保持 "Online Book Reader"。

**验证**：worker 文件过 node --check 语法检查；推送后 Run #3 由 GitHub Actions 验证，线上用浏览器实测阅读器打开 PDF、根页中文界面、FAQ/日志分区与侧栏中文入口。未执行任何 git 命令。

---

## [2026-09-05] 定位并修复：A机「阅读配置→单击」被改回左右翻页——隐藏的"按格式指定阅读模式"开关（isPrefFormatMode）在打开书籍时按扩展名静默覆盖单击设置

**根因**（A机真机定位）：「单击」的值是 `AppSP.readingMode`（1=上下翻页，2=左右翻页）。A机与 MI9 的 `app-State.json` 里 `isPrefFormatMode=true`——这是"按扩展名决定打开模式"的隐藏功能（其唯一入口"更多模式设置"齿轮按钮在布局里 visibility=gone，界面找不到也关不掉）。`ExtUtils.showDocumentWithoutDialog2` 在该开关开启时按扩展名静默改写 readingMode：`prefScrollMode="pdf, djvu"→上下翻页`、`prefBookMode="epub, mobi, fb2, azw, azw3"→左右翻页`。A机主要在读 (官场小说).mobi——每打开一次 mobi/epub，单击就被改回左右翻页；同步日志中 readingMode 1↔2 的多次翻转与 lastBookPath 换书记录完全吻合。readingMode 又随 app-Misc.json 同步，翻转会被广播到所有设备。同步合并逻辑本身无责。

**处理**：①A机（12S）：改 app-State.json 关掉 isPrefFormatMode + 通过应用 UI 把"单击"设为上下翻页（经应用自身写入才能持久，直接改 app-Misc.json 会被每轮同步的 exportMisc 用 SharedPreferences 重新覆盖）；②MI9 同样处理（关开关 + 单击=上下翻页；误关的"平面封面"勾选已恢复）；③华为机关掉 isPrefFormatMode（其单击本就是上下翻页）。④代码加固：PrefFragment2 的"单击"设置回调中显式 `isPrefFormatMode = false`——用户明确选择单击模式时该选择必须生效，杜绝隐藏开关再次静默改写。三渠道 Release 重新编译 `BUILD SUCCESSFUL`。鸿蒙零改动，未执行任何 git 命令。

---

## [2026-09-05] 修复：「我的文件→书库文件夹」删除条目"没反应"——删除其实生效了，但空列表兜底与启动默认回填立即把存储根目录等标准目录重新显示出来；新增已移除目录的隐藏记忆

**根因**（华为畅享20 Plus 真机复现）：根页书库文件夹列表来自 `BookCSS.searchPathsJson`；当它为空时有两处兜底会**立即重新显示**标准目录（存储根 `/storage/emulated/0` + Download）：①`BrowseFragment2.prepareDataInBackground` 根页兜底（删掉唯一条目后 populate 当场复活）；②`BookCSS.load1` 启动时用全部外部存储回填（重启后复活）。于是"长按→删除→确认"虽然真实移除了条目，用户看到的却是"还在"，即"删除后没有反应"。之前的 deleteRecursive 修复解决的是"磁盘文件夹删不掉"，本条解决"列表条目删不掉"，是两个独立问题。

**修复**：`BookCSS` 新增 `searchPathsHiddenJson`（随 app-CSS.json 持久化/同步）记录用户明确移除过的目录——①根页长按删除（BrowseFragment2）与旧偏好设置删除（PrefDialogs）时同步写入隐藏列表；②根页空列表兜底与 `load1` 默认回填（含异常分支）都跳过隐藏项，删了就不再复活（可以删空，"+"添加即可恢复）；③所有"添加书库文件夹/文件"入口在添加时把该路径移出隐藏列表。磁盘文件照旧不删（提示文案不变）。

**验证**（华为畅享20 Plus，Pro arm 包）：重启后书库文件夹=[0]→长按"0"→删除→"0"立即消失（剩 Download）；force-stop 重启后"0"不再复活。三渠道 Release 重新编译 `BUILD SUCCESSFUL`。鸿蒙零改动，未执行任何 git 命令。

---

## [2026-09-05] 优化：①侧边栏标语改为"值得读，好好读" ②修复"我的文件"删除文件夹静默假成功（华为真机删不掉还弹"成功"）③OPDS 默认源 5 条精简为 2 条（Project Gutenberg + CBETA 电子佛典）

**① 标语**：`main_tabs.xml` 的 drawerTagline 文本"值得读的，好好读"→"值得读，好好读"（硬编码文本，无 flavor 覆盖）。

**② 删除文件夹修复**：删除走 `ExtUtils.deleteRecursive()`，原实现吞掉全部失败——目录 `listFiles()==null`（权限/IO 错误）直接返回 true、子项递归结果与 `File.delete()` 返回值全部忽略，几乎永远返回 true，UI 永远弹"成功"。现改为逐项累积真实结果并返回 `ok && delete()`，`listFiles()==null` 时以真实 `delete()` 结果为准——任何一步失败都会如实弹"失败"（AppProfile.deleteProfiles 等忽略返回值的调用方行为不变）。华为畅享20 Plus 真机验证：Download 下新建含子目录+文件的"删除测试"文件夹→长按删除→确认→磁盘与列表均清除；根目录长按书库条目仍为"从书库列表移除（磁盘不动）"设计并有明确提示。

**③ OPDS 默认源**：`AppState.OPDS_DEFAULT` 由 5 条精简为 2 条——Project Gutenberg（海外）+ CBETA 电子佛典（中国，URL 从 `http://www.cbeta.org/opds/` 改为 301 后的 `https://archive2.cbeta.org/opds/`，应用内 http→https 跳转不可靠；原"文渊阁"wenyuange.org 实测已无法连接故移除）。存量设备已保存的列表不受影响，在"网上书库→重置"即可拿到新默认。华为真机验证：重置后仅剩 Gutenberg + CBETA 两条，CBETA 目录可正常浏览（大正藏/卍續藏等）。三渠道 Release 重新编译 `BUILD SUCCESSFUL`；MI9 与华为机均已装入新包回归通过。鸿蒙零改动，未执行任何 git 命令。

---

## [2026-09-05] 修复：WebDAV 配置再次丢失——A 机（旧整文件同步包）把昨天"被冲掉的陈旧快照"重新上传覆盖服务器，MI9 按规则正确下拉后被清空；同步修复 `isEmptyValue` 把字符串形式空容器（`"[]"`/`"{}"`）判为"未配置"，防止陈旧设备的空列表在首同步时盖掉有效配置；MI9 数据已全部恢复

**日志定位**（MI9 `app-SyncLog.json`，30 轮全量回查）：本机从未上行过空值——16:06:06 一轮出现成批 **down**（服务器→本地）：`allWebDavLinks → (空)`、`app-NetworkSources.webdav → []`、`webdavSyncIntervalMin 5→15`、OPDS 回退旧列表等，且该服务器快照携带**旧版专有字段** `isSaveAiTranslation:true`（今日新包已删除该字段）、无 `aiConfigs/aiConfigName`——即昨天事故时 A 机被冲掉的"事故态快照"。结论：16:01–16:06 之间 **A 机用旧包（整文件 mtime 新者胜，先导出重写本地文件致 mtime 恒最新）做了一次同步，把自己的陈旧快照重新发布到服务器**；MI9 16:06 的周期同步按"本地未改、服务器已改→采纳服务器"的删除传播规则正确下拉，于是配置再次丢失（合并逻辑本身行为符合设计）。

**顺带修复真 bug**：首同步恢复时 `aiConfigs`（AppState 中为 JSON 字符串）被服务器上的 `"[]"` 空串覆盖——`isEmptyValue` 原本只对字符串判空串，`"[]"` 被当非空。现把 `"[]"`/`"{}"` 判为"未配置"：首同步"非空胜"规则下，本机有效列表胜出并回发布到服务器，陈旧空列表再也无法盖掉有效配置。

**MI9 恢复**（停应用→回写→删 base→重启触发首同步，同步日志可见上行）：`allWebDavLinks = http://leestation.ddns.net:55005,LeeStation;`、`app-NetworkSources.webdav = [LeeStation]`（17:03:56 up）、`aiConfigs = [智谱, Llama.cpp]`（17:11:57 up）、`webdavSyncIntervalMin = 5`；三渠道 Release 重新编译 `BUILD SUCCESSFUL`。

**必须处理（已完成）**：A 机（小米12S）16:57 前一直运行旧包——其自身同步日志还原了完整链条：16:03:00 下拉到同一份空值（服务器早于 16:03 已被污染）、16:05:38 以旧包签名字段 `isSaveAiTranslation=true` 回写服务器、16:02:51 结束的那次 **48 分钟后台同步**（休眠/网络断续中完成）是污染窗口（16:01–16:03）内唯一结束的同步；16:59 又把 `aiConfigs=[]`/`webdavSyncPolicy=server` 上传（引发 MI9 17:03 的 `aiConfigs→[]`，已被 isEmptyValue 修复兜住）。17:26 已通过 adb 给 A 机装上含该修复的最新包，17:12 起 A 机已下拉到恢复后的正确配置（LeeStation、每5分钟、智谱+Llama.cpp 厂商），两机当前收敛一致。鸿蒙零改动，未执行任何 git 命令。

---

## [2026-09-05] 优化：AI 配置对话框"配置方案"改版为"模型厂商"——与协议同行显示，下拉末项"添加模型厂商…"进入空白配置页、点保存即新增厂商

**改动**：①布局合并——`dialog_ai_config.xml` 原独立"配置方案"行取消，"模型厂商"选择器移到"协议"行右侧（协议 | 值 | 模型厂商 | 值，各占弹性宽度）；②文案——ai_profile_config 改为"模型厂商/Model vendor"，未选择时显示"未添加"，删除项改为"删除当前厂商"；③下拉菜单重排——已有厂商列表、"删除当前厂商"、**最后一项"添加模型厂商…"**：点选后先输入厂商名称，随即进入空白配置页（URL 重置为当前协议默认地址、Key/模型清空、输出上限 4096、思考模式关），填完配置项后点对话框"保存"即把该厂商写入 `aiConfigs` 并激活（未保存关闭则不落地）；选中已有厂商仍然自动回填全部参数。数据结构与同步方式不变（aiConfigs 随 app-State 三方合并跨设备同步）。

**验证**（Ubuntu 构建 assembleProRelease → MI9 真机）：新行内"协议 OpenAI 兼容 | 模型厂商 测试A"显示正常、升级后旧方案数据保留；下拉为 已有厂商…/删除当前厂商/添加模型厂商…（添加在最后）；输入"深度求索"→进入空白配置页（URL 重置 openai 默认、Key/模型为空）→填入模型名→点保存→厂商列表出现"深度求索"。三渠道 Release 重新编译 `BUILD SUCCESSFUL`。鸿蒙零改动，未执行任何 git 命令。

---

## [2026-09-05] 功能：①"AI翻译结果保存"移入 AI 翻译弹窗（仅双语可用时显示、默认勾选，并首次真正控制双语路径的落盘）②修复 PDF 打开后退出误报"保存更改"③AI 大模型支持多配置方案（下拉切换、选中自动回填全部参数）

**① AI翻译结果保存移位**：原"阅读配置"里的复选框（`isSaveAiTranslation`，默认关且双语路径无视它）整体移到 AI 翻译弹窗——新增 `AppState.aiSaveTranslation`（默认 true）替代旧字段；`ai_translate_dialog.xml` 在"页面内显示译文（双语对照）"正下方新增同款 `aiTranslateSave` 复选框，仅当 `isBilingualFormat()` 为真（EPUB/TXT/azw3）时显示，勾选变化立即持久化；`TranslationCache` 新增 `inMemory()` 会话临时模式（仍可命中已有缓存、save/flush 不落盘），`BilingualSession` 按开关选择真实/临时缓存——关闭后页内双语照常翻译但不写缓存文件，`AiTranslator` 列表路径沿用同一开关。移除 `fragment_preferences.xml` 与 `PrefFragment2` 中的旧复选框。

**② PDF 误报"保存更改"根除**：退出提示取决于原生 `pdf_has_unsaved_changes()`，它对"本次会话发生过任意内存对象写入"即返回 true（与用户是否真编辑注释无关），且 `MuPdfDocument.isHasChanges` 粘滞缓存在保存后从不清零——打开即退也会弹窗。改为 **Java 侧会话脏标记**：`hasChanges()` 直接返回 `isHasChanges`，只在真正改文档的包装方法里置位（`MuPdfDocument.setMeta/deleteAnnotation` + `MuPdfPage.addMarkupAnnotation/addTextNote/addAnnotation(墨迹)`，JNI 全部写入入口无遗漏），`saveAnnotations()` 保存成功后清零。效果：打开→退出不再弹窗；真加注释仍正常提示/自动保存；保存后再退出不再反复提示。

**③ AI 大模型多配置**：`AppState` 新增 `aiConfigs`（JSON 数组：name/protocol/baseUrl/apiKey/model/maxTokens/thinking）与 `aiConfigName`（当前激活方案名），随 app-State 参与字段级三方合并跨设备同步（key 随方案同步，沿用 app-AI.json 明文同步 key 的既有先例；激活方案的 key 继续镜像写入 AiCredentials 加密存储，`AiClient` 及全部现有调用点零改动）。AI 配置对话框顶部新增"配置方案"行：下拉列出已存方案（点选即把协议/URL/Key/模型/输出上限/思考模式全部回填）、"将当前配置保存为新方案…"（命名输入）、"删除当前配置"；保存按钮把当前控件值 upsert 回选中方案并标记激活；未选方案时行为与原单配置完全一致。新增字符串 ai_profile_*（en + zh-rCN）。

**验证**（Ubuntu 构建 assembleProRelease → MI9 真机）：EPUB 弹窗"AI翻译结果保存"默认勾选且开关切换即时生效无崩溃；MOBI 弹窗两个复选框正确隐藏；big25.pdf 打开→退出无"保存更改"提示，加文本笔记退出也无提示（笔记存数据库不改 PDF），文件信息改标题走 setMeta+保存后退出干净（标题落盘且标题已恢复）；AI 配置对话框保存"测试A/测试B"两方案、下拉切换后模型名正确回填（glm-4.5-air↔glm-4.6）、保存激活后偏好行同步刷新、**应用重启后方案与激活名持久保留**。三渠道 Release 重新编译 `BUILD SUCCESSFUL`。鸿蒙零改动，未执行任何 git 命令。

**遗留说明**：测试书 `Download/librera_bench/big25.pdf` 的标题已恢复为 Big25，但验证过程在其上留了一条"test note"（首页书签笔记可见，长按会打开书、本机书签列表为空无法就地删除），属无害测试痕迹，可整本删除或忽略。MI9 已装本次修复包（pro release），AI 当前激活方案为"测试A"。

---

## [2026-09-05] 优化：WebDAV 同步六项——进度冲突策略新增"本地优先/服务器优先"、同步后配置即时生效（防抖3s+页面重建刷新）、同步日志按"合入/更新到服务器"分组、同步日志与立即同步按钮互换、定时同步默认每5分钟、"我的文件"书库文件夹整体移到搜索之前、修复书库文件夹手动输入路径闪退

**需求与修复**：
- **进度冲突策略**：新增"本地优先"（本机进度永不覆盖）与"服务器优先"两档，默认仍为"较新优先"（`mergeProgressEntry` 按 `newer/farther/local/server` 四策略判定；对话框菜单与中英文案同步补充）。
- **同步后配置生效慢**：①配置变更触发的自动同步防抖 10s→3s；②doSync 结束后若本轮有变更，主线程发 `UpdateAllFragments` 立即重建各标签页（"我的文件"列表、偏好行等无需重进页面）；③同步对话框 `onFinish` 即时刷新自身行与偏好页 WebDAV 行。
- **同步日志分组**：每轮记录按"【本地合入了服务器的配置】/【本地更新了服务器的配置】"两组顺序展示，方向一目了然。
- **按钮互换**：同步对话框底部一行改为"测试连接 · 同步日志 · 立即同步"（原"立即同步"与"同步日志"位置互换）。
- **定时同步默认 5 分钟**：`webdavSyncIntervalMin` 默认 15→5，选项菜单新增"每 5 分钟"。
- **"我的文件"分区**：书库文件夹（标题+文件夹列表）整体前移到"搜索"之前——搜索块移入列表下方新增的 `searchSection` 容器（`fragment_browse2.xml`），随根视图一并显隐；并新增 `onResume` 重建网络分区，同步下发的列表切换页面即可见。
- **修复书库文件夹手动输入路径闪退**：真机复现 `BrowseFragment2.java:197` NPE——目录选择器"选择"按钮在 `TYPE_SELECT_FOLDER` 分支直接 `new File(BookCSS.get().dirLastPath)`，重装后 `dirLastPath` 为 null 必崩，且手动输入的路径完全未被使用。修复：手动输入的路径优先、为空回退当前浏览目录，空值/不可读/非目录一律 Toast 提示不再崩溃；`TYPE_SELECT_FILE/CREATE_FILE` 拼路径同样判空。

**验证**（Ubuntu 构建，MI9 真机）：策略菜单四项齐全（较新/更靠前/本地/服务器优先）；定时菜单含"每 5 分钟"且本机切换后 `webdavSyncIntervalMin: 15 → 5` 即时发布（同步日志可见）；按钮顺序为"测试连接·同步日志·立即同步"；同步日志按两组方向展示（含统计合并与进度下行条目）；"我的文件"顺序为 OPDS→WebDAV→书库文件夹（标题+列表一体）→搜索；书库文件夹"+添加→添加文件夹→手动输入路径→选择"不再闪退（崩溃缓冲无新记录）。三渠道 Release 重编译 `BUILD SUCCESSFUL`。鸿蒙零改动，未执行任何 git 命令。

---

## [2026-09-05] 修复：双机实测暴露"重装机首同步把陈旧本地值发布上服务器，冲掉其他设备的 AI 配置/WebDAV 列表/书库文件夹"——首同步规则改为"非空胜、同有则服务器优先、列表并集"，并用同步日志完成数据恢复

**现象**（A 机重装配置同步正常后，MI9 重装配置同步）：MI9 的 AI 大模型显示未配置、「我的文件」的 WebDAV 服务器列表与书库文件夹为空；随后 A 机的这些配置也被同步冲掉。

**根因**：三方合并改造中"首次同步无 base 时以服务器副本为祖先、冲突本地胜"的策略，遇到**带着陈旧个性化配置文件重装的设备**时失效——本地文件的每个字段都被判定为"本地修改"，全部本地胜并发布，把 A 机上传到服务器的 `aiBaseUrl/aiModel`（置空）、`allWebDavLinks`、书库文件夹等逐字段覆盖（MI9 的 `app-SyncLog.json` 逐条记录了这次覆盖，含被冲掉的旧值）。

**改动**：新增 `firstSyncMerge()` 替换首同步合并分支——逐字段"**有一方有值就以它为准，双方都有则服务器副本优先**（服务器是其他设备收敛后的真相），列表（数组）并集保留两侧条目"，嵌套对象递归同规则；设备绑定字段、本机刚填写的同步配置（SYNC_CONFIG_FIELDS）、AppSP 身份字段仍保留本机。效果：重装/新设备首同步只会**拉取**服务器配置（本地为空的字段全部采纳；本地非空而服务器为空的字段保留并发布，可为空服务器补种），永远不会再把陈旧值覆盖上服务器；真冲突（双方都非空且不同）收敛到服务器值。

**恢复**：借 `app-SyncLog.json` 记录的被覆盖旧值，回写 MI9 本地并删除 `.base` 触发首同步重新发布：`aiBaseUrl=https://open.bigmodel.cn/api/paas/v4/`、`aiModel=glm-4.5-air`、`aiMaxTokens=8192`、`allWebDavLinks=…LeeStation;`、"我的文件"WebDAV 服务器 LeeStation 与书库文件夹（并集）均已重新发布到服务器（同步日志 `[up] (空) -> …` 逐条可见）。AI key（app-AI.json 受 mergeAi 保护）全程未丢失。

**验证**：MI9 装修复版后「偏好」页 AI 大模型行显示 glm-4.5-air、「我的文件」显示 LeeStation 服务器与 Books 书库文件夹；三个渠道 Release 已用修复后代码重新编译（`BUILD SUCCESSFUL`）。**A 机（旧包）只需再同步一次即可自动拉回全部配置**（其 base 也是被冲后的值，服务器恢复值按"远端变更"采纳）；建议 A 机也更新为本次新包。鸿蒙零改动，未执行任何 git 命令。

---

## [2026-09-05] 重构：WebDAV 同步改字段级三方合并（base 快照，根除整文件覆盖）、修复"我的文件"WebDAV 服务器/书库文件夹同步不生效、移除 legacy 迁移、books/<hash>.json 只为打开过的书创建、新增同步变更日志

**需求**：单用户多设备场景下同步配置经常异常（此前修的字号+7、单击模式被改均源于此）；「我的文件」页的 WebDAV 服务器列表与书库文件夹每次都同步不下来；要求能看到同步到底合并/修改了哪些配置项。

**根因**：
1. app-State / app-CSS / app-Misc / app-NetworkSources / Recent / Favorite 均为**整文件 mtime 新者胜**——两台设备各改不同字段时后同步方覆盖整份；且 mtime 受导出重写、设备/服务器时钟偏差影响，本机文件 mtime 偏新时服务器新列表（WebDAV 服务器、书库文件夹）每轮都被本机旧内容覆盖（已核实 UI 与同步同源：`WebDavStore.load()` 读 `AppState.allWebDavLinks`、书库文件夹读 `BookCSS.searchPathsJson`，问题出在同步通道本身）。
2. mtime 无法表达"哪些字段被谁改过"，任何字段级冲突只能整文件二选一。

**改动**（`com/foobnix/webdav/WebDavSyncer.java` 为主）：- **字段级三方合并**：新增 `syncThreeWayFile()`——本地为每个同步的全局配置文件维护 base 快照 `app-*.json.base`（仅本机、永不上传，内容=上次合并结果）；同步时对 base/本地/服务器逐字段合并：单侧修改即采纳（含字段删除）、双侧修改同字段本地胜（数组并集）、嵌套对象（app-Misc 各节、AppSP）递归合并；变化检测全部比内容、完全不依赖时钟。首次同步无 base 以服务器副本为祖先（服务器变更采纳、本地变更保留、冲突本地胜），出厂默认态仍整体采纳服务器且保护刚填写的同步配置；设备绑定字段剥离/回贴逻辑不变；网络错误本轮不动、404 种子上传语义不变。应用到 app-State（SYNC_CONFIG_FIELDS 不再仅本机，WebDAV 同步设置随三方合并跨设备同步）、app-CSS、app-Misc、app-NetworkSources（条目级）。
- **Recent/Favorite 改条目级并集** `syncMetaUnion()`：按 path 为键、每条取新 time 胜，双向收敛，不再整文件覆盖；阅读统计 app-Stats.json 改 `mergeStats` 按键取最大值收敛。
- **移除 legacy 迁移**：删除 `importLegacyRemoteDir()`（旧 /Librera→/HowRead 首次拷贝）与 `copyRemoteTree()`、`migrateLegacy()`（服务器旧 progress.json/bookmarks.json 导入）及 `REMOTE_LEGACY_*` 常量、仅供它们调用的 `mergeProgress()/mergeBookmarks()` 死代码；`remoteDir()` 的陈旧 "Librera"→"HowRead" 兜底保留。
- **books/<hash>.json 只为打开过的书创建**：`buildLocalBooks()` 删除"本地无文件时按文件名兜底哈希"的上传路径（无真实本地文件的书不再上传）；远端书籍信息的发布改为仅 hash 确认本机确有该书时才执行（未打开的书由打开它的设备维护其服务器副本），进度/书签下行合并不受影响。
- **同步变更日志**：新增 `com/foobnix/webdav/SyncChangeLog.java`——doSync 期间收集每条配置变更（文件、字段/条目、方向 合入/发布、旧值→新值，API key/口令类值掩码，循环保留最近 30 轮）；出口①logcat `BENCH` 逐条 `syncChange` 行；②本地 `app-SyncLog.json`（`AppProfile.syncLog`，仅本机）；③WebDAV 同步设置对话框新增"同步日志"入口（`dialog_webdav_sync.xml` + `webdav_sync_log`/`webdav_sync_log_empty` 字符串，中英文），弹出最近 10 轮明细。
- **补充修复 `ProfileStateIO.importCss()`**（真机验证中发现）：CSS 与 app-State 不同，此前同步合并写入 app-CSS.json 后，`AppProfile.save()` 会把**陈旧的内存 BookCSS** 写回文件、并被同轮二次 CSS 同步发布到服务器（实测 `fontSizeSp 合入 20→24` 后紧跟 `发布 24→20` 回灌）。新增 `importCss()` 在网络源导入前把合并后的文件回读到活动 BookCSS（与 `importAppState()` 对称），回灌消除（复测仅 `合入 20→26`、无回灌）。

**验证**（Ubuntu 构建 `:app:assembleProDebug`，MI9 真机；本地 wsgidav 测试服务端 + 脚本模拟"另一台设备"改服务器文件，全程不碰生产同步服务器）：
- 种子上传：首轮同步 9 个 global 文件 + 52 本打开过的书；4 个三方文件 `.base` 生成；第二轮同步**服务器零写入**（内容比对生效，不依赖时钟）。
- **WebDAV 服务器列表/书库文件夹下行生效（本次主诉）**：服务器端 `app-NetworkSources.json` 加入"局域网NAS"服务器与 DCIM 文件夹 → 同步后「我的文件」页 WebDAV 区显示"局域网NAS"、书库文件夹显示"DCIM"，**重启应用后仍在**；`AppState.allWebDavLinks` 同步更新。
- 字段级合并：服务器改 `fontSizeSp 20→26` 本机不动 → 合入；同字段冲突（服务器 `appFontScale=1.4` vs 本机 `1.2`）→ **本地胜**并发布收敛；未冲突字段各自保留。
- books/<hash>.json：设备 62 条进度记录，仅 52 本有真实本地文件的书上传（10 本文件已丢失的书不再用兜底哈希创建服务器条目），未打开的书零文件。
- 同步日志：logcat `syncChange` 逐条 + 对话框"同步日志"显示每轮汇总（↑52本·关联52·2672ms）与逐字段"合入/发布：旧值→新值"明细。
- 测完恢复：设备 `webdavSyncEnabled=false`、服务器/路径/间隔恢复原生产配置（leestation.ddns.net:55005 / Temp/Xiaomi / 15分钟）、测试注入的 NAS 服务器与 DCIM 文件夹移除、冲突测试改动的 appFontScale 恢复 1、`.base` 快照清除、测试 WebDAV 服务端与临时文件清理；恢复后启动无同步、无崩溃。鸿蒙零改动，未执行任何 git 命令。

---

## [2026-09-05] 修复：双语"提示悬挂不消失/当前页永不翻译"（精确页内匹配+窗口漂移校准+队列确定性重建）、夜间亮度不再全黑（加下限）、主题切换字体不再跳"增大(+7)"、"单击"翻页模式保持上下翻页（设备侧关闭 WebDAV 同步并修正配置）

**需求**：①当前页已翻译仍显示"本页剩 X 段"提示且页面刷新跳闪；②当前页±(后5前2)缓冲全部译完就停止翻译，不要翻整本书；③手动切日夜模式主题配置里字体大小跳回"增大(+7)"，WebDAV 同步一直异常、暂时关闭且测试期间不同步；④夜间模式亮度到 -100% 全黑要限制；⑤阅读配置"单击"默认上下翻页经常被改成左右翻页。

**根因与修复**：
- **①②提示悬挂/当前页永不翻译（三个叠加根因）**：
  1. 页→段落用线性估算（pageStart），而双语文件每次合入译文页数都会增长，估算在书中部可漂移数十段——当前页真实段落完全落在 ±(5/2) 页的翻译窗口之外，永远入不了队（提示一直"剩 X 段"，无翻译无合入，期间偶发的窗口内合入又造成跳闪）；
  2. 队列簿记（pending/queued/lanes 增量维护）在跳页清队、暂停重加、重连后 setActive 等路径穿插时会漂移，个别段落丢失在账本外；
  3. 缓存里存在 22 字节空占位 epub，枚举出 0 段被永久缓存，会话瘫痪。
  **修复（`BilingualSession.java`）**：`onPageShown` 从当前页真实文本层匹配出本页全部源段（`currentPageMd5s`，norm 缓存加速），提示/待译判定/重建门控全部改用精确集合——本页译完提示即消失、不再误触发重建（跳闪消除）；用精确锚点的实际段落序号校准线性估算（`ordOffset`，随每次翻页自适应微调），翻译窗口整体平移到真实阅读位置；窗口重算改为**确定性重建**——每次从"已译缓存/在途/失败"三个事实源直接推导三条车道（清空重排），账本不可能再漂移；并把精确页段落兜底并入当前页车道；`ensureParas` 对空枚举不再缓存（打 `paras EMPTY` 日志，下次重试）。
- **④夜间全黑（`BrightnessHelper.java`）**：夜间亮度滑条 -100..0 区间被重定义为蓝灯滤镜强度，滑到底=滤镜100%+亮度0，"允许最低亮度"时 `BRIGHTNESS_OVERRIDE_OFF`（0.0=屏幕熄灭级）再叠近黑遮罩 alpha 200/255。修复：亮度一律取下限 0.02（永不熄灭级），遮罩 alpha 上限收紧为 150/120。
- **③字体跳+7 / ⑤单击被改左右（设备配置修正，非代码缺陷）**：`appFontScale` 存于 app-CSS.json、readingMode 存于 app-Misc.json，均被 WebDAV 整文件同步覆盖（另一设备的"增大(+7)"与翻页模式盖过来）；"单击被改"另一原因是本机残留 `isPrefFormatMode=true`（强制文字书按左右翻页打开）。按用户要求：设备关闭 WebDAV 同步（`webdavSyncEnabled=false`，该字段仅存本机）、`isPrefFormatMode=false`、应用内把"单击"重设为上下翻页、`appFontScale=1`，并恢复了被同步清空的 AI 大模型配置（aiBaseUrl/aiModel，key 在 app-AI.json 未丢）。注意：用 PowerShell 改这些 JSON 必须写**无 BOM UTF-8**（带 BOM 会导致应用解析失败回退默认值）。

**验证（MI9 真机 pro 包，BENCH 日志 + UI dump + 截图；全程 WebDAV 同步关闭）**：①停靠已译页：无提示、仅 `rebuild skipped` 日志（不重建不跳闪）；翻到未译页提示立即出现且计数与页面真实内容一致（"本页剩 6 段 · 队列 4 段"）；②缓冲全部译完即停（无新 AI 请求），翻页使缓冲出现未译页才恢复翻译；③关键复现——此前"卡住"的页 137（锚点 ord=533，旧窗口漂移完全覆盖不到）：新窗口精确对准 `queued=8` 全部入队，批量翻译完成后 `rebuild ensure` 原位合入、提示消失；ordOffset 每页自适应 1-2 段微调；④夜间模式暗色主题清晰可读、不再全黑；⑤主题切换后 appFontScale 保持 1，"单击"保持上下翻页且打开 epub 实际进入竖屏（VerticalViewActivity）；另修复空占位 epub 导致的会话瘫痪（paras EMPTY 不再缓存）。鸿蒙零改动，未执行任何 git 命令。



**需求**：①"正在翻译中..."字体加大，同时显示当前翻译段落队列；②优先级：当前页最优先 → 往后5页 → 往前2页，翻页后新的当前页优先翻译；③后台3条队列并发请求 AI 提效；④双语开启状态下点 AI 翻译按钮：勾选框没勾（状态显示错误），且此时应禁用"开始翻译"，只能取消或关闭页内双语；⑤书签笔记里已删除书籍仍会被服务器同步回来。

**改动**：
- `com/foobnix/ai/BilingualSession.java`：窗口改 `AHEAD_PAGES=5 / BACK_PAGES=2`；单队列拆为**三条并发车道** `lanes[0]=当前页 / [1]=往后页 / [2]=往前页`（统一 `queueLock`），worker 改为 3 个线程（BiTran-0/1/2）各消费本车道、空闲时按"当前→往后→往前"窃取，最多 3 个 AI 请求并发（批量5段/请求不变）；跳页时三条车道全部清空重排（翻页后新当前页最优先）；暂停按来源车道放回队首；失败重试进入最低优先的前方车道；新增 `currentHintText()` 计算"正在翻译中… 本页剩 X 段 · 队列 Y 段"（随翻页与每段落完成实时刷新）；BENCH 日志带各车道长度。
- `com/foobnix/ai/BilingualHintUi.java`：接口改为 `setBilingualHint(String text)`（null/空=隐藏）；两个阅读 Activity 同步适配（有文字才显示并 setText）。
- `res/layout/activity_horiziontal_view.xml` / `activity_vertical_view.xml`：提示字体 12sp→16sp。
- `com/foobnix/ai/AiTranslateDialog.java`：`isBilingualActive` 时勾选框如实 `setChecked(true)` 并禁用，"开始翻译" `setEnabled(false)+setAlpha(0.4)` 置灰——只能"取消"或红色"关闭页内双语模式"退出；未激活时维持默认不勾选、可开始。
- **已删书书签复活修复**（根因：删书只删 FileMeta 行，不删该书书签/进度、不打墓碑，而 `WebDavSyncer` 对书签逐条 union 且把无文件的书当 pending 持续维护服务器副本）：
  - `com/foobnix/pdf/info/BookmarksData.java` 新增 `removeByBook(path)`（删该书全部书签/AI笔记 + 逐 key "b" 墓碑 + 书级墓碑 + `deleteProgress` 清进度记 "p" 墓碑）与 `pruneDeletedBooks()`（清理历史遗留：文件已不存在、存储已挂载、书库中无同名条目——即确已删除而非移动——的书签批量删除并打墓碑，防误删移动中的书）；
  - `DefaultListeners.deleteFile()` 删除成功后与 `CheckDeletedBooksWorker` 发现文件丢失删除 FileMeta 行的同一处调用 `removeByBook`；
  - `WebDavSyncer.doSync()` 在构建本地书籍信息前先 `pruneDeletedBooks()`；书级/key 级墓碑使既有合并守卫（`delBookmarks`/`deletedKeys`）跳过并回、并删除服务器 `books/<hash>.json` 副本。

**验证（MI9 真机 pro 包，BENCH 日志 + UI dump + 截图）**：①跳到未译页后 `lanes=[5,22,9]` 三车道同时入队，**同一毫秒 3 个线程各发一个批量请求**（ords 互不相同、含当前页/往后/往前三组）；提示以 16sp 显示"正在翻译中… 本页剩 4 段 · 队列 13 段"（bounds [116,2058][963,2145]，底部居中），当前页译完合入后提示消失；停留期间仅 `rebuild skipped`、无周期性重载。②双语激活时打开 AI 对话框：勾选框 `checked=true enabled=false`（如实显示开启）、"开始翻译" `enabled=false` 置灰，仅"取消"与红色"关闭页内双语模式"可点（截图确认）；点关闭后回原书、`aiBilingual=false` 落盘。③删书书签测试：给《客户端AMR编解码库API参考.pdf》添加快速书签后经书库菜单删除——`app-Bookmarks.json` 该书条目即清零，`app-DeletedBooks.json` 出现完整墓碑（书级"b"+逐key+"p"），下一轮同步 `deleted=8、associated 53→52`（服务器副本被清理），同步后书签条目仍为 0（不再被合回）。鸿蒙零改动，未执行任何 git 命令。


## [2026-09-05] 修复与优化：双语跳页后当前页优先翻译、停留只提示不刷新、退出阅读页自动退出双语、AI对话框记住语言配置且双语默认不勾选、5段批量翻译提效

**需求**：①"正在翻译中..."提示只在单击时出现，应停靠未译页即提示；跳到书中间未译页一直显示翻译中、页面定时刷新但没有英文；②优先翻译当前页；③退出阅读页时若在双语模式则同时退出双语；④重新打开 AI 翻译对话框应回显上次配置（此前中文→英文再打开仍显示中文→中文）；⑤开启 AI 翻译时"双语对照"默认不选中；⑥逐段请求 AI 效率低，改为约5段一批（AI 按段编号翻译、不思考不分析仅译文），提高效率。

**根因**：跳页后旧位置已入队段落仍占队列前部（`enqueuePageRange` 只 `addLast` 从不重排），当前页段落被压后迟迟译不到（单线程串行），表现为"一直翻译中"；且每段落译完都触发 3s 防抖重建+原位重载，即"定时刷新页面"。提示只挂在翻页事件路径。对话框目标语言硬编码 `setSelection(1)`（中文），从不读已保存的 `aiBilingualSrc/aiBilingualTgt`。

**改动**：
- `com/foobnix/ai/BilingualSession.java`：`onView` 检测页码变化时在 queue 锁内**清空未开翻队列**（pending/queued 同步清理，inFlight 不动）再按"当前页 span → 后10页 → 前3页"重新入队，跳页后当前页最先翻译；`onParagraphDone` 的 3s 防抖重建触发时先检查 `needsRefreshForCurrentPage()`，当前页没有"已译未合入"段落就跳过重建（BENCH: `rebuild skipped`），只有当前页译文到位才原位合入一次，消除停留时的周期性刷新；`onView` 增加 500ms 延迟 `updateHint()` 重查，快速翻页停下后提示可靠出现（不拦翻页）；**5 段批量翻译**：workerLoop 一次从队首取最多 5 段（先剔除缓存命中）合并一次 `AiClient.ask`，提示词要求逐段编号（【1】…【2】…）、不思考/不分析/不解释、仅输出译文；`splitNumbered()` 按编号切分（兼容 1./1、/[N]/N) 变体），逐段按原 `h<md5>` 键写入 TranslationCache + 一次 flush + 逐段 onParagraphDone；请求失败/截断/切分段数不符时降级逐段翻译（保留原 attempts 有限重试→failed），不丢段；新增 `exitOnReaderDestroy()`（阅读页真实退出即 stop 会话并清 `aiBilingual/aiBilingualBook` + AppProfile.save）与一次性 `suppressExitOnDestroy`（程序化重启——开启双语的 restart、竖滑静默重载、原位重载兜底——不误关模式）；失败日志补充 `res.detail`（定位到 GLM 内容过滤 1301 拦截问题）。
- `com/foobnix/ai/AiTranslateDialog.java`：打开时若已保存过语言则回显 `aiBilingualSrc/aiBilingualTgt`（新增 `isValidCode` 校验），有已存源语言时跳过自动检测线程；"双语对照"勾选框**每次打开一律不选中**（双语改纯手动开启，不再读/写 `aiDefaultModeBilingual`）；列表模式启动也持久化语言对。
- `com/foobnix/model/AppState.java`：`aiDefaultModeBilingual` 默认改 false 并标注为遗留字段（仅兼容旧 app-State.json）。
- `HorizontalViewActivity.java` / `VerticalViewActivity.java`：`onDestroy` 调用 `BilingualSession.exitOnReaderDestroy(this)`；Horizontal 的 `fallbackRestart()` 与 Dialog 的 `startBilingual` 设置抑制标志。
- `res/layout/activity_horiziontal_view.xml` / `activity_vertical_view.xml`："正在翻译中..."提示改为 `alignParentBottom + marginBottom=55dp`（原 `layout_above bottomBar` 锚点在工具栏收起时失效，提示跑到页面顶部）。

**验证（MI9 真机 pro 包，BENCH 日志 + 截图）**：对话框打开回显 中文→英文、双语勾选默认不选中 ✓；跳页（118→199/208/399）后 `onView` 立即按新窗口入队且首批批量 ords 全在当前页 span 内（当前页优先）✓；停留期间只有 `rebuild skipped`、无周期性 BilingualReload（消除定时刷新）✓；《没有人给他写信的上校》开启后批量 `n=5` 请求 `ok=true` 无 parse mismatch、逐段落盘、原位重载页数 122→125 增长、页面截图确认中文段上/英文带背景译文下 ✓；停靠未译页 500ms 后提示出现在底部正确位置（bounds [2042,2145]），当前页全译合入后提示消失 ✓；退出阅读页出现 `reader exited -> mode off` 且 app-State.json `aiBilingual=false`，重进为原书 ✓；批量请求被上游内容过滤拦截（GLM 错误 1301，本书第二章文字敏感）时按批重试后降级逐段、仍失败标记 failed 不丢段、提示按 failed 规则隐藏 ✓。全程无 git、鸿蒙零改动。



**需求**：上一版每次译文落盘都走整 Activity 重启（finish+startActivity + 加载框 + 转场，位置仅按百分比恢复），坐在当前页时后台译文持续落盘导致页面反复闪现、位置漂移。目标：当前页翻译完成后，后台继续翻译并渲染后续页面，翻页平滑；翻到还没译完的页时正文照常显示、页面底部提示"正在翻译中..."；打开后优先翻译并渲染当前页，后台按"当前页往后+10页、往前3页"的窗口补齐。

**改动**：
- `com/foobnix/ai/BilingualSession.java`：翻译窗口由"段落数（后10段/前26段）"改为**按页（往后10页、往前3页）**，页→段用线性换算 pageStart(p)=floor(total*p/pageCount)；入队顺序改为**当前页 span 优先 → 往后 1..10 页逐页 → 往前 1..3 页（近→远）**，新增 queued 集合修掉 setActive 清 pending 造成的队内重复；新增当前页"顶部段落锚点"anchorMd5（getPageParagraphs 文本层归一化包含匹配，失败回退窗口中心段）、builtMd5s（当前打开的书已含译文 md5 快照）、isCurrentPagePending()（未译或已译未合入判定，驱动提示）；Host 接口新增 requestReload(page0, anchorMd5) 与 requestHintUpdate()，每批译文 3s 防抖重建后改走"原位静默合入"（不再 requestRestart），带 reloading/reloadPending 合并闸；新增 locateAnchorPage(dc, anchorMd5, approxPage0)（±10 页文本层定位）；枚举完成后补算锚点/提示/必要时刷新。
- `com/foobnix/ai/BilingualHintUi.java`（新增接口）：两个阅读 Activity 实现，切换底部"正在翻译中..."提示显隐。
- `pdf/info/wrapper/DocumentController.java`：新增 restartActivitySilently(page0, anchorMd5)——竖滑侧无原位重开，改为"Intent 打 silent+anchor 标记的静默重启"。
- `search/activity/HorizontalViewActivity.java`：匿名 controller 实例化抽成 createController(w,h) 复用；新增 reloadBilingualInPlace()（同 Activity 内 清 codeDocument+清 Glide 内存+清 LibreraAppGlideModule 静态位图缓存 → 后台重建 controller → UI 线程 nullAdapter/createAdapter/loadUI → 锚点定位 setCurrentItem，期间无加载框无转场，异常整体降级 restartActivity）；实现 BilingualHintUi；host 由 BilingualSession 分派到该方法。
- `sys/LibreraAppGlideModule.java`：新增 clearBitmapCache() 清静态"最后解码页"缓存（原 IMG.clearMemoryCache 只清 Glide 内存，同 URL key 会吐旧文档残图）。
- `org/ebookdroid/ui/viewer/ViewerActivityController.java`：竖滑静默重启——afterCreate 消费 silent/anchor 标记；BookLoadTask 静默时不弹加载框、不挂 FirstPaintGate；加载完成后延时 600ms 定位锚点页并 onGoToPage（文本层刚开书时未就绪，需延后）。
- `org/ebookdroid/ui/viewer/VerticalViewActivity.java`：实现 BilingualHintUi。
- 两个阅读布局（activity_horiziontal_view.xml / activity_vertical_view.xml）+ strings：底部新增"正在翻译中..."小提示 TextView（贴工具栏上方，修复 layout_above 与 alignParentBottom 冲突导致的压住页脚）。

**验证（MI9 真机，pro 包，BENCH 日志 + logcat 生命周期 + 文本层）**：
1) 窗口按页且当前页优先：`BilingualSession onView page=290/421 winPages=[287,300]`（=当前页-3/+10），首个 AI ask ord=320 恰为当前页 span 段落；
2) 水平模式原位静默合入：每次译文落盘仅 `BilingualReload start/done`（同 Activity、同一 pid，无 finish/start、无加载框、无 openTextDoc 重开日志），页数在屏内更新（456→457→461），锚点使当前页内容不漂移（295→296→297→301→304 随注入跟踪同一内容）；锚点修复前首次合入 anchor=null（枚举完成前页面已显示、锚点未算），修复后 anchor=050fdd3... 非空；
3) 竖滑模式静默重启：`load-begin silent=true`、无 FirstPaintGate/加载框、延时锚点定位；
4) "正在翻译中..."提示：翻到未译页底部出现（竖滑已实测），到位合入后消失；位置修复后贴工具栏上方不再压页脚；
5) 关闭回归：aiBilingual=false 后重开书回到原版（页数回基、无双语会话/打开日志）。

**说明/边界**：水平模式已做到"同 Activity 原位重排"（翻页真正无重建无转场）；竖滑模式因无原位重开入口，采用"无加载框、无转场、锚点复位"的静默重启（仍有一次窗口重建，已尽量压到无感）；.aitran 背景渲染沿用上一版（BookCSS 未动）；鸿蒙工程零改动，未执行任何 git 命令。

## [2026-09-04] 修复：页内双语模式下译文不显示（页面仍只显示中文原文、无上下段）

**问题**：开启「页内双语对照」后，页面仍只显示中文原文，看不到英文译文，也没有"原文在上、译文在下"的两段效果（背景色块在有译文的位置能看到，但块内没有文字）。

**排查过程（实证定位）**：注入内容正确——解包双语版本书确认 `<p class="aitran">…英文译文…</p>` 正确紧跟各中文段；但设备端 MuPDF 渲染页的文本层（stext）里完全没有译文段，仅剩中文。用 Ubuntu 侧 mupdf 1.23.7 的 mutool 复现：单章"原样打包"能完整导出译文，而整本 Java 生成的双语 epub 打开后译文全部缺失；把同一份 Java 产物用 python 重新打包（STORED 条目在本地头写真实 size/CRC）后 mutool 导出全部正常 → 定位为 **zip 容器编码差异**，与内容、class、CSS、字体均无关。

**根因**：`BilingualBuilder` 原来复用 `Fb2Extractor.writeToZipNoClose` 的流式写法（`ZipOutputStream` level0 + 不显式设置条目大小/CRC），生成的 STORED 条目本地头不含真实 size/CRC。MuPDF 的 epub zip 读取对这类条目产生错位，导致整本打开时 `.aitran` 段落文本在解析/布局阶段丢失（中文段仍在，页数却随空占位增长）。

**修复**：`BilingualBuilder.build()` 改为先把每个条目读成全量字节再写，用 `writeStoredEntry()`：显式 `setMethod(STORED)` + `setSize(len)` + `setCrc(CRC32)`，本地头携带真实大小与校验，MuPDF 读取无歧义（附注释说明原因）。

**验证**：1) mutool 全文导出修复后的双语 epub：4913 行，英文译文标记（wry smile / abortion / Little Zhuge / privacy 等）全部命中；2) MI9 真机重启后自动进入双语版，恢复位置 334/395，当前页文本层采样为中文原文与英文译文同页交错（如 `completely smashed and nothing happened between us, right?" 杨妮儿一脸得意地说：…`）；3) 截图像素分析：整页 19 个 #FDF2D8 背景译文带（y202~2126，带高 36~50px），带内文字像素密集渲染——"每段原文下紧跟带背景色的英文译文、上下两段"效果正常显示。


## [2026-09-04] 新增：AI 翻译「页内双语对照」——译文直接渲染进书页（原文在上、译文在下、背景色区分），并按页后台预翻译

**需求**：原 AI 翻译把译文全部堆在悬浮 TranslatePanel 列表里，与正文无关；改为（1）每段翻译完成后把译文就地排进书籍页面，形成"原文段在上、译文段在下"的两段版式，译文段背景色与原文不同；（2）当前页及前后几页的段落由后台线程持续预翻译，译文一到就触发合并刷新，翻到那一页时该页译文已渲染好，而不是等几页全部翻完一次性铺出来。

**引擎约束与总体方案**：本项目文字书（epub/fb2/txt 等）正文不是 Java 拼 HTML 渲染，而是原生 MuPDF 按"屏宽×屏高×字号"整本重排成一页页位图（EpubContext 等预处理出缓存 epub → MuPdfDocument.openFile 注入 BookCSS.toCssString() 用户 CSS → 原生分页光栅）。Java 侧没有按页 DOM 可插、也没有单页增量排版接口，因此译文"排进书页"只能走**内容预注入 + 整书重排**范式：新增 BilingualBuilder 把每个已缓存译文的源段落后追加 <p class="aitran">译文</p> 生成"双语版本书"（文件名带译文集合快照哈希，MuPDF 加速缓存按内容版本化自动配套失效/复用），BookCSS 给 .aitran 加主题感知背景色规则（原生 html 排版支持块级 background-color，已在 C 源码 css-properties.h:155 / css-apply.c:1397 / html-layout.c:2308 核实）；新增 BilingualSession（单后台线程按当前页±N 对应源段窗口串行翻译、pid 去重、失败有限重试、3s 防抖合并 → 重建双语书 → restartActivity 原位重开、进度按百分比恢复）。段落 pid 统一为内容 md5（不再带 outline 章号，规避页残段/章号不一致），与旧列表模式缓存可互认（TranslationCache.doneByTextHash 忽略 ch 前缀按 _h<md5> 匹配）。

**改动文件**：
- 新增 com/foobnix/ai/BilingualBuilder.java：双语书生成器（逐章 <p> 段后注入 <p class="aitran">，其余条目原样拷贝）+ 源段枚举（供会话调度）。
- 新增 com/foobnix/ai/BilingualSession.java：双语会话（窗口调度/串行翻译/缓存 upsert/防抖重建/宿主重启钩子），提供 attachForController/feedForController 供两种阅读模式统一接线。
- com/foobnix/ai/TranslationCache.java：新增 doneByTextHash(src,tgt) 按内容 md5 索引（跨 pid 约定），save/lookup 加同步；双语模式会话内始终落盘该缓存（无论旧的"保存翻译结果"开关）。
- com/foobnix/ai/AiTranslateDialog.java + res/layout/ai_translate_dialog.xml + strings：新增"在页面内显示译文（双语对照）"勾选（默认开，mobi/azw 等非 epub 链格式自动隐藏走列表）；本书双语激活时显示红色"关闭页内双语模式"按钮。
- model/AppState.java：新增 aiBilingual / aiBilingualBook / aiBilingualSrc / aiBilingualTgt / aiDefaultModeBilingual（随 app-State.json 持久化，同一时刻仅一本书双语）。
- pdf/info/model/BookCSS.java：toCssString() 末尾追加 .aitran{background-color:…; text-indent:0; padding:…}（日/夜两套底色，白天 #FDF2D8）。
- org/ebookdroid/droids/mupdf/codec/PdfContext.java：新增 openTextDoc() 双语接入点（打开前先 BilingualBuilder.ensure 生成/复用双语文件）；EpubContext/Fb2Context/TxtContext/HtmlContext 构造 MuPdfDocument 处统一改走该方法（翻页与竖滑两种阅读引擎都经同一 Context 链，自动同时生效）。
- HorizontalViewActivity.java（翻页模式）与 pdf/info/wrapper/DocumentWrapperUI.java（竖滑/共享工具栏）：onResume/initAsync 挂会话、翻页/updateUI 上报当前页、onPause 暂停后台翻译。

**验证（MI9 真机，pro 包，BENCH 日志 + 截图像素）**：开启后重启进入双语模式 → BilingualSession paras total=466 枚举源段、窗口入队并串行请求（约 17~36s/段，失败自动重试）→ 每段落位 3s 防抖后 BilingualBuilder build injected=N（与缓存 done 一致，重复段落文本去重）→ 自动 restartActivity 打开新的 __bi_<hash>.epub（页数随注入增长，进度保持原位）→ 屏幕截图像素分析：约 10.2 万个 #FDF2D8 色块像素（多行带）确认译文块带背景渲染在页面内。关闭按钮：回到原版、会话停止、无双语打开日志。译文缓存已落盘（profile.HowRead/device.MI_9/ai-translation/<书sha>.jsonl），下次开启命中缓存免 API。

**说明/边界**：页内双语为"重排式"——译文按阅读窗口渐进出现，双语期间页数与原文版不同（与改字号同理，进度按百分比恢复）；仅走 epub 链的书支持页内双语（mobi/azw 直开原生，仅列表模式）；每次新译文落位伴随一次短暂后台重建+重开（约 1s，已用 3s 防抖合并减少频次）；鸿蒙工程零改动，未执行任何 git 命令。


## [2026-09-04] 修复：偏好设置"字体大小"选 +7 变大后，再选"正常"切不回正常字号

**问题**：设置→UI配置→主题配置→"字体大小"弹窗里先选"增大(+7)"（全局字号变大，重启后生效），之后在同一弹窗选"正常"无法恢复——界面字号仍是 +7 大小。"正常"与 +7 走的是同一条代码路径（`PrefFragment2` 弹窗 → `BookCSS.appFontScale = 1.0f/1.7f` → `onTheme()` 保存并整 Activity 重启），所以不是选项本身的问题。

**根因**（`BookCSS.java` + `IO.java`）：`onTheme()` 先 `AppProfile.save()`（其中 `BookCSS.save()` 经 `IO.writeObj` **异步入队**到单线程池，且排在更早入队的 PasswordState/AppState 写入之后），随即 `clear() + finish() + 重启 MainTabs2`；新 Activity 在 `attachBaseContext → AppProfile.init → BookCSS.load1` 里**同步读 app-CSS.json** 决定最终字号。于是"重启同步读盘"与"异步写盘"形成竞态：重启经常抢在写盘落盘前读到旧值（1.7f），并被 `IO.readString` 的单槽读缓存把旧内容回灌进内存 —— 字号切换（尤其从非默认切回"正常"）表现为"没生效"。+7 能生效只是那次写盘恰好赶上了读取。

**修复**：
1. `BookCSS.save()` 的 `IO.writeObj(...)` 改为 `IO.writeObjSync(...)`（同步落盘，带注释说明）。app-CSS.json 体积小且 hash 门槛只在样式真正变化时才写，主线程同步写开销可忽略；对全部走 `onTheme()` 的流程（字体/主题/配色等）一并消除该竞态。
2. `MyContextWrapper.wrap()` 的 `appFontScale == 1.0f` 精确比较改为容差判断（`|scale-1.0f| < 1e-3f`），避免 float 精确比较的脆弱性。

**验证（MI9 真机，pro 包 `com.howread.reader.pro`）**：构建 `:app:assembleProDebug` BUILD SUCCESSFUL。设置→字体大小 反复切换：+7 → 重启后字号变大、`app-CSS.json` 中 `appFontScale` 为 1.7；再选"正常" → 字号恢复、行标签显示"正常"、JSON 为 1.0；+7↔正常 多次来回均即时生效、无残留大号。主题配色/日夜间切换回归正常。鸿蒙零改动。

---

## [2026-09-04] 新增：阅读界面 AI 翻译（EPUB/TXT/MOBI/AZW3 逐段翻译 + 覆盖面板 + 可选缓存）

**功能**：阅读页工具栏的"替换文本"按钮改为"AI 翻译"。点击后弹出语言选择对话框（源语言自动识别、可改；目标语言限 英文/中文/日文），确认后在页面右侧（横屏）/底部（竖屏）叠加一个可滚动译文面板，把当前页 ±（前 1 页、后 3 页）的正文**逐段**发给已配置的大模型翻译，增量刷新面板。可选"AI翻译结果保存"（偏好页新增 CheckBox），开启后按书 SHA-256 落 JSONL 缓存（`SYNC_FOLDER_DEVICE_PROFILE/ai-translation/<sha256>.jsonl`），重翻命中缓存不再调用 AI。不支持的格式（PDF 等无文本层）按钮置灰不可点。

**新增文件**（`app/src/main/java/com/foobnix/ai/`）：
- `AiTranslator.java` — 翻译引擎：页面窗口 [current-1 .. current+3]、逐页取段落、算稳定锚点 pid、查缓存、调 `AiClient.ask`、写缓存、进度回调。含共享的 `htmlToParagraphs()` 段落解析器。
- `TranslationCache.java` — JSONL 读写（按书 SHA-256 定位，`lookup`/`save`/`flush`，含原文漂移守卫）。
- `LanguageDetector.java` — 源语言识别：优先 `FileMeta.lang`，缺失则采样前几页按 CJK/假名/拉丁占比判 zh/ja/en。
- `AiTranslateDialog.java` — 语言选择对话框（AlertDialog，源/目标 Spinner + 取消/开始）。
- `TranslatePanel.java` — 译文覆盖面板（ScrollView + 段落块，逐段追加，失败标红）。

**修改文件**：
- `res/layout/document_title_buttons.xml` + `res/layout/activity_horiziontal_view.xml`：`onTextReplacement` 图标改 `my_glyphicons_ai_translate`、contentDescription 改 `ai_translate`。
- `DocumentWrapperUI.java`（竖屏）+ `HorizontalViewActivity.java`（横屏）：点击改 `AiTranslateDialog.show()`；格式门控（EPUB/TXT/MOBI/AZW3 可点，其余 `alpha=0.3f`+`setEnabled(false)`，始终可见）。
- `VerticalModeController.java` + `HorizontalModeController.java`：`getPageParagraphs()` 改用 `getPageHTML()` + `AiTranslator.htmlToParagraphs()`（原实现调 `MuPdfPage.text()`，见下方"关键 bug"）。
- `model/AppState.java`：加 `public boolean isSaveAiTranslation = false;`。
- `res/layout/fragment_preferences.xml` + `ui2/fragment/PrefFragment2.java`：偏好页新增"AI翻译结果保存"CheckBox 并绑定。
- `res/values/strings.xml` + `res/values-zh-rCN/strings.xml`：新增 AI 翻译相关字符串。
- `res/drawable/my_glyphicons_ai_translate.xml` + `res/drawable/ai_translate_block_bg.xml` + `res/layout/ai_translate_dialog.xml` + `res/layout/ai_translate_panel.xml`：图标/背景/布局。
- `android/utils/FileHash.java`：加 `sha256(File)`。

**关键 bug（本条最重要的修复）**：翻译最初"打开空白、无任何反应"。BENCH 日志定位根因——`VerticalModeController`/`HorizontalModeController` 的 `getPageParagraphs()` 调用 `((MuPdfPage) cp).text()`，而预编译 `libMuPDF.so`（`prebuilt/native/mupdf-1.23.7`）**没有导出 `Java_..._MuPdfPage_text` 符号**（只导出了 `text116` 与 `getPageAsHtml`），于是每次抛 `UnsatisfiedLinkError` 被 catch 吞掉、返回 null，段落全空，AI 从未被调用（日志 `done=0 failed=0 empty=0`）。修复：`getPageParagraphs()` 改用**可用**的 `getPageHTML()`（走 `getPageAsHtml` 原生，TTS/搜索同款路径），由 `AiTranslator.htmlToParagraphs()` 解析。

**段落解析细节（经真机 RAWHTML 采样确认）**：该 MuPDF 版本的页面 HTML 用 `<pause>` 标记**段落边界**、用 `<p>…</p>` 标记段内**每一行**（并非 `<end-block>`/`<end-line>`），行末软连字符断词（如 "transla-" / "tion"）。故 `htmlToParagraphs()` 按 `<pause>` 切段、段内合并 `<p>` 行、去连字符（行尾 `-` 去掉且不加空格）还原整词。修复前按行切导致译文是半句碎片（如"在雨天他这样做。他站在窗前——"）；修复后为完整段落。

**稳定锚点 pid**：`ch<章节序号>_h<md5(原文)>`（章节 + 原文内容哈希）。不依赖行号/页码（随字号/屏宽变化），reflow 后同文本同哈希，缓存的原文漂移守卫已保证正确性；且无需为算"章内段落序号"预扫整章（那会让首条结果等很久）。

**验证（MI9 真机，MIUI，adb `48fee174`，pro 包 `com.howread.reader.pro`，测试书 `/sdcard/Download/test_en.epub` 英文 2 章）**：pro debug BUILD SUCCESSFUL。打开 EPUB → 点 AI 翻译 → 源语言自动识别为英文、目标中文 → 开始翻译 → 面板逐段显示完整中文译文（连字符断词正确还原："transla-tion"→"翻译"、"cov-ered"→"覆盖"、"win-dow"→"窗边"）。BENCH 日志确认 AI 被真实调用：2 页共 4 段，逐段 `AI ask orig.len=9/420/9/283` → `AI res ok=true reply.len=3/120/3/81`，`done=4 failed=0 empty=0`（修复前为 `done=0`）。鸿蒙零改动。

---

## [2026-09-04] 三项：更新第三方库许可内容 / "我的文件"搜索项上移并去掉"新文件" / 修复书签笔记删除后被 WebDAV 同步回来

### 一、更新第三方库许可内容（`android/app/src/main/assets/licenses.html`）

**问题**：设置→关于→"第三方库许可"页（`AboutSectionBinder.showLicenses()` 用 WebView 加载 `file:///android_asset/licenses.html`，三 flavor 共用）内容严重过时：版本错误（MuPDF 1.12 实为 1.23.7、jsoup 1.8.3 实为 1.22.2、okhttp 3.8.1 实为 3.12.6、greendao 3.2.0 实为 3.3.0 等）；greendao 条目贴的是 "EventBus License" 文本；已移除的库（Universal Image Loader、commons-compress）仍在列；缺失大量现用依赖（AndroidX、Glide、Mammoth、CommonMark、Zip4j、Guava、Sardine、LAME、libmobi、antiword、DjVuLibre、libwebp、MuPDF 内嵌 C 库、google 版 Play Services Ads/UMP 等）。

**修复**：整体重写 `licenses.html`，保持原 `<h3>库名 版本</h3> + <pre>许可文本</pre>` 结构与 CSS，按"应用本体（GPLv3）→ PDF 引擎（MuPDF 1.23.7 AGPL-3.0 及内嵌 FreeType/HarfBuzz/jbig2dec/lcms2/libjpeg/MuJS/OpenJPEG/zlib）→ 原生解析库（LAME/libmobi/antiword/DjVuLibre/hqx/libwebp）→ Java 依赖（按 Apache 2.0 / MIT / BSD / 其他分组）→ EbookDroid 1.6.5（GPLv3）→ Google SDK（仅 google 版，Google SDK License）"组织；修正 greendao 为正确的 Apache 2.0 文本；删除 Universal Image Loader 与 commons-compress。版本逐一对照 `gradle/libs.versions.toml` 与 `app/build.gradle` 核实。

### 二、"我的文件"页：搜索项上移 + 去掉"新文件"项（`BrowseFragment2.java` + `fragment_browse2.xml`）

**问题**："我的文件"页里"搜索"区块（"在多个文档中搜索"条目）挂在页面下方的 `netSection2` 容器，位置太靠下，与上方的 OPDS / WebDAV / 书库文件夹不同区；同区块还有一个"新文件（.txt）"条目需移除。

**修复**（`buildNetSections()`，三 flavor 共用）：
1. 把"搜索"区块（分隔线 + `R.string.search` 区块头 + "在多个文档中搜索"条目）从 `netSection2` 移到 `netSection`，置于书库文件夹区块之前——与 OPDS / WebDAV / 书库文件夹同区靠上（顺序：OPDS → WebDAV → 搜索 → 书库文件夹）。
2. 删除"新文件（.txt）"条目。
3. `netSection2` 清空后连带清理：字段声明、布局中的 `netSection2` 容器、`displayAnyPath()` 中对它的可见性控制。
4. **未动**：长按菜单的 `new_file_txt`（保留字符串资源）、PopupMenu 的"扫描书库"项。

### 三、修复：书签笔记中删除一本书的笔记/书签后，仍被 WebDAV 同步回来（`WebDavSyncer.java` + `SharedBooks.java` + `BookmarksData.java`）

**问题**：在"书签笔记"里删除某本书的笔记和书签后，下次同步这些被删的书签又从服务器合并回来（复活）。

**根因**（`WebDavSyncer.doSync()` + `SharedBooks.DeletedBooks`）：删除靠墓碑文件 `app-DeletedBooks.json` 抑制回合并，但 `doSync()` 末尾**无条件 `clear()` 全部墓碑**，不验证删除是否真正落到服务器。于是：
- 若某轮远端书目列表拉取失败（网络抖动、PROPFIND 解析失败），该书本轮不在列表，其墓碑未被消费却照样被全清 → 下一轮云端旧书签被 union 合并回来；
- 若发布 PUT / 删文件 DELETE 失败（只记日志），墓碑仍被清 → 云端保留旧数据 → 复活；
- 删除后不触发同步，只能等启动 4s / 定时 / 手动同步，撞上上面任一情形即复活。

**修复**（核心：墓碑改为"确认生效才清除"，3 文件）：
1. `WebDavSyncer.doSync()`：新增 `Set<String> consumedNames`——删服务器文件成功（`s.delete` 无异常）或合并发布 PUT 成功（`putBookInfo` 无异常）的书名记入；末尾的 `clear()` 改为只清除 `consumedNames` 中的条目，未确认的书名墓碑保留到下一轮重试。`SharedBooks.DeletedBooks` 新增 `clearNames(Set<String>)`（按书名精确清除，含其 keys），替代对 `clearKeys` 的即时调用。
2. `listRemoteBooks()` 区分"404/空"与"网络错误"：`fetchJson()` 对网络错误返回 `null`（404 仍返回空对象），出现网络错误时置 `booksListFailed` 标志，该轮**不消费任何墓碑**（保守处理，全部保留重试）；`migrateLegacy()` 对网络错误同样跳过。
3. `BookmarksData.remove()`（删单个书签）与 `cleanBookmarks()`（清空）末尾调用 `WebDavSyncer.notifyConfigChanged(LibreraApp.context)`——删除后自动触发一次同步（复用现有 10s 防抖 + `syncingNow` 保护），不必等定时/启动同步，避免撞上瞬时失败。

**效果**：删除书签/笔记后自动同步传播到服务器；服务器文件被清除（或删掉）；墓碑只在删除**确认**落到服务器后才清除，任何一轮瞬时失败都不会让"已删"的书签被 union 合并回来。

**验证（MI9 真机，MIUI，adb `48fee174`，pro 包 `com.howread.reader.pro`）**：三 flavor（google/pro/fdroid）debug 均 BUILD SUCCESSFUL。用标准 WebDAV 服务器（wsgidav 4.3.5，`http://192.168.50.111:8099`，PROPFIND 可被 Sardine 0.9 解析）造场景：
- **任务一**：设置→关于→"第三方库许可"，WebView 正常渲染新内容（HowRead→MuPDF 1.23.7→原生解析库→Java 依赖→EbookDroid→Google SDK）。
- **任务二**："我的文件"页，"搜索"区块与 OPDS / WebDAV / 书库文件夹同区靠上，"新文件（.txt）"项消失。
- **任务三**（HiFB开发指南.pdf，原 1 条 AI 笔记书签）：① 在"书签笔记"删除该书签 → 本地书签 31→30、墓碑记录 `{"b":…,"keys":{"1788082891886":…}}`、自动触发同步 → 服务器该书文件 `bookmarks` 字段被清除（仅剩 progress）、墓碑被消费（`app-DeletedBooks.json` 变 `{}`）；② **复活压测**：手动把已删书签重新注入服务器文件（模拟"服务器仍持有已删书签"）+ 本地保留墓碑 → 再同步 → 服务器书签**未**被合并回来（仍无 bookmarks）、本地书签数保持 30（无复活）、墓碑被消费。日志确认 `remoteBooks=58 failed=false`、`consumed HiFB开发指南.pdf`、`consumedNames=[HiFB开发指南.pdf]`。鸿蒙零改动。

---

## [2026-09-03] 修复：桌面图标"書"字顶部被 launcher 圆角蒙版裁切

**问题**：上一条修复（去 inset 全出血）后，米色牌铺满画布，但"書"字在牌内位置偏上（顶距图顶仅 ~10%），launcher 的圆角蒙版把"書"字顶部裁掉了，"書"字不完整。

**根因**：adaptive 前景 `adaptive_pdf_reader.png`（192×192）由 `gen_icons3.ps1` 从源图 cover 铺满生成，"書"字+书内容整体偏上，"書"字顶落在 adaptive 安全区（中央 66dp，约 19% 起）之外，被 launcher 圆角裁切。

**修复**（重新生成 `mipmap-xxxhdpi/adaptive_pdf_reader.png`，432×432）：
- 源改用 1536×1536 高清设计图 `howread_cleaned.png`（gen_icons3 去水印输出，比原 192px 小图清晰得多）。
- 米色牌背景仍**全出血铺满**画布（保住 MIUI 绿边修复），背景用牌四边采样色做平滑米色渐变（top #E8E1DA→bot #D4D0CF，左右微混），与裁出的 artwork 米色边缘无缝。
- "書+书"内容从源图裁出（排除牌投影/边缘），缩小并**下移**：顶 24%、底 87%、水平居中——"書"字完整落在安全区内，书底仍在安全区内。
- 脚本 `Z:\opt\librera\bench\gen_adaptive_v3.py`（可复现）。

**效果**：三 flavor 图标"書"字完整、位置居中、全出血无透明环（MIUI 无绿边）、高清无虚化。

**验证（MI9 真机，MIUI，adb `48fee174`）**：三 flavor（google/pro/fdroid）debug 均 BUILD SUCCESSFUL（22s）。逐一安装并桌面截图放大确认：Pro（好好读 Pro）、google（好好读）、fdroid（好好读 FD）三版图标"書"字均**完整**、米色牌铺满圆角方块、**边缘无绿色色带**。鸿蒙零改动。

---

## [2026-09-03] 修复：Pro 版桌面图标在 MIUI（小米 12S）上有一圈绿色边缘

**问题**：小米 12S（MIUI）上 HowRead Pro 的桌面图标（米色"書+书"圆角牌）边缘有一圈绿色色带。

**根因**（`mipmap-anydpi-v26/icon_pdf_pro.xml`）：Android 8.0+ 走自适应图标，前景层是铺满画布的米色牌 PNG（`adaptive_pdf_reader.png`，192×192 全不透明），但被套了 `inset=23%`——把牌缩到中央 54%，四周留 **23% 透明环**。MIUI launcher 对自适应图标套自己的蒙版/底色，**不**像 AOSP 那样把 app 背景层填到前景透明环下面，于是透明环透出 launcher 底色，实测一圈偏绿色带（RGB ≈ #418050~#9DB769，非 app 内任何颜色）。附带问题：pro 背景层 `bg_pdf_reader_pro.xml` 是**蓝色渐变**，与米色牌配色冲突。google 版 `icon_pdf_reader.xml` 是同样的 inset 反模式（背景米色，绿边不明显）。

**修复**（3 个 XML，纯资源改动，复用现有 PNG，不重新生成图片）：
1. `mipmap-anydpi-v26/icon_pdf_pro.xml`：去掉 `<inset>` 包裹，前景直接 `<foreground android:drawable="@mipmap/adaptive_pdf_reader" />` 全出血铺满 108dp 画布——消除透明环，"書+书" artwork 在中央安全区内，launcher 裁圆角时四角被裁也无感。
2. `mipmap-anydpi-v26/icon_pdf_reader.xml`（google 版）：同样去掉 `<inset>`，消除同款反模式。
3. `drawable/bg_pdf_reader_pro.xml`：蓝色渐变（#2b549a→#337ef7）改为与米色牌边缘一致的米色渐变（#C9C8C6→#E9E2DB，复用 `bg_pdf_reader` 配色）——个别 launcher 在蒙版边缘露出背景层时也能与牌无缝融合，不再有色差/绿边。

**效果**：自适应图标自包含（全出血前景 + 同色背景双保险），任何 launcher 上都不再有透明环/绿边；pro 与 google 图标风格统一。

**验证（MI9 真机，MIUI，adb `48fee174`）**：三 flavor（google/pro/fdroid）debug 均 BUILD SUCCESSFUL（21s）。安装 pro 版后桌面截图：图标为**铺满的米色"書+书"牌、边缘无绿色色带**（修复前同一位置实测 9727 个绿色像素环带）；另装 google 版对照，同样全出血无绿边。API<26 旧设备仍用遗留位图 `icon_pdf_pro.png`（未动），不受影响。鸿蒙零改动。

---

## [2026-09-03] 修复：WebDAV 同步只恢复 AI 的 API 链接、不恢复 API key

**问题**：AI 的 API key 有备份到 WebDAV 服务器，但每次同步只恢复了 API 链接（endpoint），没有恢复 API key。

**根因**（AI 的"链接"与"密钥"存两处、走两条不同同步路径，密钥那条有 mtime 竞态缺陷）：
- API 链接 `aiBaseUrl` 存 `AppState`→`app-State.json`，走 `syncGlobalFile`（`WebDavSyncer.java:295/338`）——有 `localIsDefault` 保护 + 字段级并集 `mergeAiState`，**抗竞态**。
- API 密钥存 `AiCredentials`（加密 SharedPreferences `"ai"`）→镜像 `app-AI.json`，走 `syncWholeFile`（`WebDavSyncer.java:313`）——**纯 mtime 新者赢，无"本地为空不赢"保护**。
- 复现链（本地无密钥的设备——新装或本地数据丢失后）：① `exportAi`（`:304`）把本地 `app-AI.json` 写成 `{"apiKey":""}`，`writeIfChanged` 发现内容不同就重写并把 mtime 刷成"现在"；② `syncWholeFile`（`:313`）比 mtime，服务器文件是别的设备过去写的（更旧）→ 判定本地"更新"→ **空密钥上传覆盖服务器**，服务器密钥被毁；③ `importAi`（`:315`）读本地 `app-AI.json` 仍是 `{"apiKey":""}`，旧守卫 `isNotEmpty` 为假 → 不保存。结果：链接（抗竞态路径）恢复了，密钥（竞态路径）没恢复，且服务器副本被空值覆盖、bug 自我延续。佐证：`ProfileStateIO.mergeAi` 这个专为密钥文件写的合并器存在但**从未被调用**（死代码）。

**修复**（2 文件，核心 1 处改动）：
1. `ProfileStateIO.mergeAi`（`ProfileStateIO.java`）：签名从 `mergeAi(Context, remote)` 改为匹配 `JsonMerger` 接口的 `mergeAi(LinkedJSONObject local, LinkedJSONObject remote)`，实现"**设值赢未设值，真冲突（两边都有且不同）服务器赢**"（与 `mergeAiState` 同构的单字段版）：本地空+服务器有→用服务器（**修复恢复**）；本地有+服务器空→用本地（能换密钥、能播种服务器）；两边都有且不同→服务器赢（用户选定）；两边都空→空。
2. `WebDavSyncer.doSync`（`WebDavSyncer.java:317`）：`syncWholeFile(...syncAI...)` → `syncMergedObjectFile(...syncAI..., ProfileStateIO::mergeAi)`。`syncMergedObjectFile` 提供**结构性抗竞态**：GET 临时错误不碰任何东西；远端缺失（404）则上传本地；否则 `merged = mergeAi(local, remote)`，本地变了写本地、服务器变了上传。
3. `ProfileStateIO.importAi`（`ProfileStateIO.java`）：从"仅本地为空时填"改为"文件值≠本地加密存储就应用"——合并后 `app-AI.json` 已含正确密钥，`importAi` 负责把文件值落到加密存储，既补缺失（恢复）也收敛冲突（服务器赢）。安全：`exportAi` 每轮同步开头都会把本地存储重新镜像进文件，用户在 AI 对话框刚保存的密钥在合并前已在文件里，不会被旧值覆盖。

**效果**：本地重置/新装设备同步后从服务器恢复密钥；本地改密钥（服务器空）能上传；两边冲突服务器赢；不再出现"空密钥覆盖服务器"的竞态。与 `app-State.json` 用 `mergeAiState` 处理 AI 模型配置完全同构。

**验证（模拟器 MedicineAVD，fdroid 无广告版 `com.howread.reader.pro`）**：三 flavor（google/pro/fdroid）debug 均 BUILD SUCCESSFUL。用本地最小 WebDAV 服务器（`http://10.0.2.2:8099`，模拟器可直达 Windows 宿主）造三种场景：① **本地无密钥 + 服务器有**（`sk-server-key-AAA111`）→ 同步后本地 `ai.xml` 生成、AI 设置页显示该密钥（掩码），服务器密钥**未被空值覆盖**（修复前此场景服务器密钥会被毁）；② **本地有密钥 + 服务器空** → 同步后服务器拿到本地密钥（日志 `PUT app-AI.json (33 bytes)`）；③ **两边不同密钥**（本地 AAA111 / 服务器 CCC333）→ 同步后本地收敛到服务器值 CCC333，服务器不变（日志仅 `GET` 无 `PUT`）。回归：AI 链接/模型配置（`app-State.json` 的 `aiBaseUrl/aiModel/aiProtocol/aiMaxTokens/aiThinking`）同步路径未动、字段完好，AI 设置页正常显示；其他 global 文件（recent/stats/misc/network）同步不变。鸿蒙零改动。

---

## [2026-09-03] 修复：软件说明页 build 时间停留在首次 clean 构建（增量构建不刷新）

**问题**：安装 9 月 3 日新构建的 APK 后，"软件说明"页显示的 build 时间仍是 9 月 2 日的。

**根因**（`app/build.gradle` 的 `generateBuildTimeSource` 任务）：该任务生成 `BuildTime.java`（`BUILD_TIME` 常量，软件说明页展示），声明了 `outputs.file` 但**未声明任何 inputs**。Gradle 对"无 inputs 且输出已存在"的任务判定为 UP-TO-DATE 直接跳过，`doLast` 里的 `new Date()` 不再执行——时间戳被冻结在**首次 clean 构建**（202609022352），之后所有增量构建都复用旧文件。

**修复**：任务内加 `outputs.upToDateWhen { false }`，强制每次构建都重新生成 `BuildTime.java`，时间戳与 APK 实际构建时刻一致。

**验证（模拟器 MedicineAVD，pro 版 `com.howread.reader.pro`）**：三 flavor（google/pro/fdroid）debug 增量重编 BUILD SUCCESSFUL（1m27s，13 executed）；`BuildTime.java` 刷新为 `202609031655`；安装 pro 版后"软件说明"页显示 `HowRead Pro: v0.9.0 build 202609031655`（与 APK 构建时刻一致）。鸿蒙零改动。

---

## [2026-09-03] 修复：书签笔记页删除一本书的笔记后被 WebDAV 同步"复活"

**问题**：手机上在书签笔记页删除某本书的笔记，过一会儿笔记又被 WebDAV 服务器同步回来，删除的笔记重新出现。

**根因**（`WebDavSyncer.doSync` + `SharedBooks.DeletedBooks`）：
- 书签同步是**按创建时间键（`t`）的盲目并集**（`WebDavSyncer.java:414-432`），只增不删——服务器 `books/<hash>.json` 里有的键，只要本地没有就一律加回。
- 删除时只记录**按书**的墓碑 `DeletedBooks.record(path,"b")`（结构 `{书名:{"b":时间戳}}`），**不记录删的是哪几条**；且该墓碑**一次性**（每轮同步结束 `DeletedBooks.clear()` 清空，`WebDavSyncer.java:479`），只压制**一轮**合并。
- 服务器文件仅在"该书本地既无进度又无其他书签"时才整文件删除（`WebDavSyncer.java:381-388`）。
- 复现链：删笔记后该书通常**仍有阅读进度** → 服务器文件不删 → 本轮 `delBookmarks=true` 跳过合并（笔记暂时消失）→ 行 479 清空墓碑 → **下一轮** `delBookmarks=false` → 盲目并集把服务器残留的笔记重新加回 → **复活**。

**修复**（按时间键精确传播删除，3 个文件）：
1. `SharedBooks.DeletedBooks`（`SharedBooks.java`）：新增 `recordKey(path, key)`——在 `{书名:{...}}` 下维护 `"keys"` 子对象（`{时间键:时间戳}`）累加被删时间键；`keysOf(markers, name)` 返回该书已删键集合；`clearKeys(path)` 只清除该书的 `"keys"`（保留 `"p"`/`"b"`），供同步成功发布后调用。原有 `record(path,kind)`/`all()`/`clear()` 不变。
2. `BookmarksData.remove`（`BookmarksData.java:53-80`）：删除单条笔记时，在既有 `record(path,"b")` 之外再 `recordKey(bookmark.getPath(), bookmark.t)` 记下被删时间键。
3. `WebDavSyncer.doSync`（`WebDavSyncer.java`）：循环内取该书 `deletedKeys = keysOf(deletedBooks, name)`；书签合并遍历时**跳过**任何在 `deletedKeys` 中的键（不重新加回本地）；发布前从 `subsetFor(localB, name)` 结果里**剔除** `deletedKeys` 中的键，使服务器文件收敛（不再残留已删笔记）；成功 `putBookInfo` 后 `clearKeys(name)` 清除已传播键。整文件删除分支（本地无进度且无书签）与旧版墓碑（只有 `"b"` 无 `"keys"`）的"整书跳过一轮"兼容均保留。

**效果**：删除 → 记录时间键 → 下一轮同步从服务器剔除该键并跳过合并 → 服务器收敛 → 笔记不再复活；其他设备下次同步也拉不到已删笔记。进度删除（kind `"p"`）、同名不同文件 conflict 逻辑均不动。

**验证（模拟器 MedicineAVD，fdroid 无广告版 `com.howread.reader.pro`）**：三 flavor（google/fdroid/pro）debug 均 BUILD SUCCESSFUL。用本地最小 WebDAV 服务器（`http://10.0.2.2:8099`，模拟器可直达 Windows 宿主）复现并验证：① 造出"书有笔记 + 50% 阅读进度"状态（本地与服务器一致，正是旧代码会复活的场景）；② 书签笔记页删除该笔记 → 墓碑正确写入 `{"montecristo.epub":{"b":…,"keys":{"1788411354035":…}}}`，本地书签清空、进度保留；③ **连续三次**重启触发同步，本地书签始终为空（笔记**不复活**，含旧代码复活的第三轮），墓碑已清空，进度 50% 全程保留；④ 服务器 `books/<hash>.json` 从含笔记（380 字节）收敛为**仅进度**（126 字节），已删键 `1788411354035` 不存在；⑤ 回归——删除后向同一本书新增一条笔记并同步，新键 `1788430000000` 正常上传服务器、旧已删键仍不存在（未误伤同书其他笔记）。鸿蒙零改动。

---

## [2026-09-03] 笔记导出：可选格式（TXT/Markdown，默认 TXT）+ 时间行下新增"位置"行

**需求**：导出笔记时可选导出格式；分析 md/pdf/doc/txt 可行性，改动大则只支持 md+txt、默认 txt。同时把笔记时间对应的书籍位置信息写入导出文件，放在时间行下面一行。

**格式可行性结论**：
- **txt**：已实现，现有导出路径即 txt。
- **md**：极小改动——纯文本本身即合法 Markdown，`commonmark 0.29.0` 已在 classpath（仅解析用，生成不依赖它），只需换扩展名 + 轻量 md 排版（`##` 标题 / `>` 位置引用 / `**AI:**` 加粗 / `---` 分隔）。
- **pdf**：改动大，本期不做。仓库内 `com.artifex.mupdf` Java 类与预编译 `libMuPDF.so` 导出的 JNI 符号不匹配（调用即 `UnsatisfiedLinkError`）；两条路都是大改——① 框架 `android.graphics.pdf.PdfDocument` 需手动换行 + 加载 CJK 字体（默认 Helvetica 渲染不了中文）；② 重编 `libMuPDF.so` 暴露 Story API（4 ABI native 重编）。
- **doc/docx**：改动大，本期不做。无任何 docx 写入库（mammoth 只读 docx→HTML）；需新增 Apache POI（重）或手写 OOXML zip。

按既定标准（改动大就只支持 md+txt，默认 txt）→ **本期支持 TXT + Markdown，TXT 为默认**。PDF/DOCX 留作后续可选项。

**改动**：
1. `BookmarksFragment2.java`：
   - 合并笔记行的 "⋮" 菜单由单项拆为两项：`notes_export_txt`（默认，在前）→ `exportNotesToFile(notes, "txt")`；`notes_export_md` → `exportNotesToFile(notes, "md")`。
   - `exportNotesToFile(merged, format)`：示例文件名 `书名-notes.<format>`（预填扩展名）；内容改由新私有方法 `renderNotesForExport(merged, format)` 从 `merged.notes`（新→旧）逐条渲染；选择器文件名完全可编辑，回调里若用户改掉扩展名则强制补回所选格式。
   - `renderNotesForExport`：每条笔记渲染 时间行 + **位置行** + 正文 +（若有）AI 回答。位置行取一次总页数 `AppDB.get().load(MyPath.toAbsolute(path)).getPages()`：取到则 `位置：第 X / Y 页 (P%)`（`X = max(1, round(p*Y))`），取不到降级 `位置：P%`；百分比复用 `TxtUtils.percentFormatInt`（与列表口径一致）。TXT 用纯文本风格（时间行下插位置行）；MD 映射为轻量标准 Markdown。
2. 字符串（`values/strings.xml` + `values-zh-rCN/strings.xml`）：新增 `notes_export_txt`/`notes_export_md`/`note_export_position_page`/`note_export_position_percent`；原 `notes_export` 已无引用，一并删除。
3. **顺带修复（验证时发现）**：`BrowseFragment2.java` 的 create-file / select-file 结果此前用共享的 `BookCSS.get().dirLastPath` 拼路径，但上一条改动把导出选择器改成了**分离目录页**（`browsePath != null`），`displayAnyPath` 对分离页刻意不更新 `dirLastPath`（见 `BrowseFragment2.java:1614`），导致 `dirLastPath` 恒为 `null`，导出落盘路径变成 `null/<文件名>` 而 `FileNotFoundException`。新增 `chooserDir()`：分离页返回本页 `browsePath`，普通"我的文件"页保持原 `dirLastPath` 行为，两处结果改用 `chooserDir()` 拼接。

**验证（模拟器 MedicineAVD，fdroid 无广告版 `com.howread.reader.pro`）**：三 flavor（google/fdroid/pro）debug 均 BUILD SUCCESSFUL。① 笔记行 ⋮ 菜单显示两项，TXT 在前、Markdown 在后；② TXT 导出文件内容为 `[2026-09-03 04:55]` + 下一行位置行 + 正文；③ Markdown 导出为 `## [时间]` / `> 位置：…` / 正文 样式；④ 位置行两种形态均验证——书页数未知时降级 `Position: 0%`，向 DB 注入 `PAGES=287` 并重启后为 `Position: page 1 / 287 (0%)`（英文为模拟器 locale，中文串在 values-zh-rCN）；⑤ 扩展名兜底——文件名手改为无扩展名的 `plainexport`，落盘仍为 `plainexport.md`；⑥ 普通书签行无 ⋮（仅合并笔记行有，代码层 `BookmarksAdapter2` 已确认）。鸿蒙零改动。

---

## [2026-09-03] 修复：笔记导出选择器"返回上一层"误跳回"我的文件"

**问题**：书签笔记页 → 笔记导出 → 文件路径选择器中，停在某个书库扫描目录时点工具栏"返回上一层"，会跳回"我的文件"根视图（OPDS/WebDAV/文件夹分组），并污染底层"我的文件"标签的共享路径。

**根因**：`ChooserDialogFragment` 内嵌的 `BrowseFragment2` 此前 `browsePath == null`，`path()`/`setPath()` 走共享的 `AppState.displayPath`；`onBackAction()` 的扫描目录分支（`BrowseFragment2.java:1495-1501`）命中 `scanPath.equals(path())` 时执行 `displayAnyPath(ROOT_PATH)`，即跳回"我的文件"根视图并改写共享路径。

**修复**（`ChooserDialogFragment.onCreateView`）：把内嵌浏览器改为**分离目录页**——构造时注入 `folderPath = validStartDir(外部存储根)`，使 `browsePath` 非空、独立于共享路径。此后 `onBackAction()` 走分离页分支（`BrowseFragment2.java:1481-1492`）：入口根处返回 `false`（由 X/关闭按钮或系统返回关闭对话框），子目录处 `new File(browsePath).getParent()` 上移一级，全程不再触碰 `AppState.displayPath`，也不会落到扫描目录跳根分支。选择器因此锚定在真实文件树（外部存储根），"返回"沿真实目录上移。

**验证（模拟器 MedicineAVD，fdroid 无广告版）**：google 版在无网络模拟器上 AdMob/UMP 阻塞窗口焦点致黑屏，改用 fdroid 版正常渲染。① 选择器打开即锚定 `/storage/emulated/0`（真实文件树，非"我的文件"根视图）；② 进入 Documents 子目录后点工具栏"返回上一层"，路径栏由 `emulated/0/Documents` 上移一级回到 `emulated/0`（**未跳回"我的文件"**）；③ 存储根处再点"返回"为空操作（与既有分离目录页行为一致，无回归）；④ 关闭对话框后底层"My files"标签仍显示根视图（OPDS/WebDAV/Library folders），共享路径未被污染。鸿蒙零改动。

---

## [2026-09-03] OPDS 预置目录替换 + 书签笔记页两项修复/新增

**1. OPDS 预置目录替换**（`AppState.OPDS_DEFAULT`）：把内置预置目录整体替换为 5 个公版书库——Project Gutenberg（`gutenberg.org/ebooks/search.opds/`）、Wolne Lektury（`wolnelektury.pl/opds/`）、textos.info（`textos.info/opds`）、文渊阁·公版部分（`wenyuange.org/opds/`）、CBETA 电子佛典（`cbeta.org/opds/`）；图标统一复用 `assets://opds/opds.png`。删除了原 Internet Archive 与内置"获奖书单"（`SamlibOPDS.ROOT_AWARDS`，随之移除 `AppState` 里已无引用的 `SamlibOPDS` import）。解析/持久化/"恢复默认"逻辑不变；已装用户保留其 profile 里已持久化的目录列表，新预置对全新 profile 或点"恢复默认"后生效。

**2. 书签笔记页·笔记聚合行名称修复**（`BookmarksAdapter2.onBindViewHolder`）：进入某本书后，笔记聚合行（`mergeNotes` 合成项，`isAiNote && notes != null`）此前复用书签行模板，标题显示的是书籍文件名，与"笔记"身份不一致（看起来像"XXX书笔记"）。现改为：笔记聚合行标题显示其自身的"笔记 (N)"标签（`item.text`），普通书签行仍显示书名；书籍 header 行、单条笔记显示不变。

**3. 书签笔记页·笔记导出**（`bookmark_item.xml` + `BookmarksAdapter2` + `BookmarksFragment2`）：进入某本书后，笔记聚合行新增"⋮"按钮（`moreMenu`，仅笔记聚合行可见，header/普通书签/单条笔记隐藏，含 view-holder 复用时的可见性复位）。点"⋮"弹出菜单（`MyPopupMenu`，锚定按钮），含"笔记导出"项；选中后弹出"选文件夹+文件名"选择器（复用 `ChooserDialogFragment.createFile`，与"导出书签"一致），把该书全部笔记（`mergeNotes` 预渲染的 `aiAnswer`：时间戳+正文+AI 回答）写入所选 .txt。新增字符串 `notes_export`（Export notes / 笔记导出）。

**验证（MI9）**：google debug 构建 BUILD SUCCESSFUL（0.9.0），安装启动无异常。① 我的文件·OPDS 区显示 5 个新目录（Gutenberg / Wolne Lektury / textos.info / 文渊阁 / CBETA），Internet Archive 与获奖书单已消失；② 进入《侯卫东官场笔记》单书视图，笔记聚合行标题显示"笔记 (3)"而非书名，普通书签行仍显示书名（第二本《HiFB开发指南》"笔记 (1)"同样正确）；③ 笔记聚合行"⋮"→"笔记导出"→ 选文件夹+文件名（自动填充"<书名>-notes.txt"）→ 点"选择"→ Toast"成功"，文件落盘 3674 字节，内容为 3 条笔记（时间戳+正文+AI 回答，分隔线正确）。鸿蒙零改动。

---

## [2026-09-02] F-Droid 彻底去 GMS：删除 Google Drive 同步 + 删除 5 个上游遗留版本

**背景**：F-Droid 上架要求包内不得含任何广告 SDK。广告 SDK 解耦此前已完成（`src/admobAds` vs `src/noAds` 互斥源集 + `libDepFree` 依赖隔离，fdroid 字节扫描 PASS）。但审计发现代码里还残留"打桩"——Google Drive 同步功能在**所有版本里都是死的**：`GFile.java` 用到的 `com.google.api.client.*` / `com.google.api.services.drive.*` / `GoogleSignIn` 真类全部来自 main 里手写的 14 个 `com/google/**` 假类（`:appLibDrive` 模块被注释且不存在，`libs.versions.toml` 无任何真 Drive 依赖），`getLastSignedInAccount` 恒返回 null，UI 入口早已隐藏。经确认，本次将 GMS/Drive 从所有版本彻底删除，并顺带删除 5 个上游遗留 UI 版本 flavor。

**1. 删除 Google Drive / GMS（所有 flavor）**：
- 删除文件：`GFile.java` + `GFile.java.stub`、main 下 14 个 `com/google/**` 假类（2 个 android.gms + 12 个 api client/drive）、`src/gmsStubs/`（5 个假类整目录）、`SynctornizatoinWorker.java`、`GDriveSycnEvent.java`、`GoogleDriveFragment2.java`、`fragment_google_drive.xml`。
- 清理 12 个 main 调用点：`UITab`（删枚举项 GoogleDrive2Fragment，`isShowCloudsPreferences` 改恒 false）、`MainTabs2`（删 sign-in 分支、pull-to-refresh 同步、drawer 同步块、fab 同步日志入口）、`PrefFragment2`（删 updateSyncInfo/onSync 订阅与 sync section 绑定）、`AppsConfig`（删 `isGooglePlayServicesAvailable` 及广告前置判断）、`AppProfile`/`FileInformationDialog`/`BrowseFragment2`（删 `deleteRemoteFile` 分支）、`FileMetaComparators`（`BY_SYNC_DATE` 改恒等比较）、`Dialogs`（删 `showSyncLOGDialog`）、`SlidingTabLayout`（删 swipeRefresh 联动）、`HorizontalViewActivity`/`VerticalViewActivity`（删 `runSyncService`）、`ShareDialog`（死 import）、`AppSP`（删 `isEnableSync` 字段）。
- `fragment_preferences.xml` 删孤儿 sync 视图（syncHeader/signIn/syncInfo/syncInfo2/isEnableSync/isEnableSyncSettings），保留 section8 容器。
- 结果：**main 从此零 `com.google.android.gms` / `com.google.api` 类型**（grep 断言 0 命中）。

**2. 删除 5 个上游遗留 UI 版本**（pdf_classic/ebooka/pdf_v2/tts_reader/epub_reader）：
- `app/build.gradle` 删 5 个 flavor 块；admobAds 源集只挂 google；`dep_free`（play-services-ads+UMP）只挂 google；`dep_pro`（junrar+Play Review）挂 google+pro；gmsStubs 挂载移除。
- 删 5 个源集目录（各含 LibreraBuildConfig + strings.xml）+ 10 个专属图标（5 组 mipmap png + 5 个 adaptive icon xml）；`drag_popup.xml` 的 `icon_pdf_droid` 改 `icon_pdf_pro`。
- flavor 现为 3 个：**google**（主渠道，AdMob）/ **fdroid** / **pro**（后两者 GMS-free、零广告）+ 预留 xiaomi/huawei。

**3. 工具链/文档同步**：`build-librera.ps1`（ValidateSet）、`build_remote.sh`、`BUILD-README.md`、`AGENTS.md`（新增 gotcha 14）、`MULTI_PLATFORM.md`（目录树 + 广告分层 + 矩阵）、`store/android/fdroid/README.md`。

**验证（Ubuntu server + MI9）**：
- 三渠道同批 `assembleGoogleDebug assembleProDebug assembleFdroidDebug` → BUILD SUCCESSFUL，全部 0.9.0/7198。
- 三个 APK 均含 `lib/arm64-v8a/libMuPDF.so`（jniLibs 路径生效）。
- fdroid APK：`scan_apk_ads.py` 零广告标记 PASS；dex + manifest/arsc 字节扫描 `com/google/android/gms` / `com/google/api/client` / `com/google/api/services/drive` **全部 0 命中**（零 GMS）。
- google APK 反向断言：仍含 AdMob（dex 1013 命中）且零 Drive 假类（0 命中）。
- pro APK：ads/drive/api-client 均 0 命中；仅含 `play-services-basement`（`com/google/android/gms/common|tasks`，由 Play Review 评分库传递引入，非广告非登录非 Drive）。
- main 源码 grep 断言：GFile/SynctornizatoinWorker/GoogleDriveFragment2/GDriveSycnEvent/com.google.android.gms/com.google.api.client/com.google.api.services.drive/isEnableSync 全部 0 文件命中。
- MI9 安装 google debug HowRead-0.9.0-arm64：启动成功，MainTabs2 前台 resumed，进程稳定，crash 缓冲区为空，无 NoClassDefFound/FATAL。

---

## [2026-09-02] 书籍菜单新增"AI 简介书籍"；长按书籍同时选中并弹菜单

**1. 书籍菜单新增"✨ AI 简介书籍"**（`ShareDialog.show()`，长按或右下角 ⋮ 弹出的菜单，位于"文件信息"上方）：点击后把文件名 + 元数据标题/作者/注释（注释为空时回退到书籍概述）发给已配置的 AI 模型，提示词末尾固定追加"请简要介绍一下这本书籍的内容，不需要思考，直接输出回答！"；对话框先显示"AI 思考中…"（保存按钮置灰），回复到达后显示全文并启用"保存到笔记"——点击按 `AppBookmark`（isAiNote）写入书签笔记页，可随同步传播。未配置 AI 时仅 Toast 提示。修复过程中发现点击分发链的 `else if (cond && which == i++)` 分支顺序必须与 items 添加顺序一致，否则点 AI 会打开文件信息（首轮构建已修复验证）。

**2. 长按书籍 = 选中 + 弹出书籍菜单**（`SearchFragment2` 书库长按监听）：长按既进入"已选 N 本"多选状态，同时弹出该书的单书菜单（与 ⋮ 按钮等效）；文件夹长按行为不变。

**3. 撤销上一条目的中间实现**：文件信息（元数据）页的 AI 按钮与保存按钮移除（`dialog_file_info.xml`/`FileInformationDialog.java` 恢复原状），AI 入口统一收敛到书籍菜单。

**验证（MI9）**：长按《人类简史》→ 出现选中栏 + 菜单；点"AI 简介书籍"→ 思考中 → 显示完整简介（结合了元数据标题/作者）；"保存到笔记"→ 书签笔记页该书下出现"笔记 (1)"条目；点"文件信息"仍正常打开元数据页。

---

## [2026-09-02] 书签/我的文件页五项修复与交互优化

**1. 笔记删除不了（修复）**：按书视图下的"笔记 (N)"条目是 `mergeNotes()` 的合成对象，`file` 为 null 且 `t` 只是第一条笔记的 key——`BookmarksData.remove()` 定位不到存储直接 NPE 被吞，删除无效。现在 `BookmarksFragment2.onDeleteResponse` 特判合并条目：遍历其携带的真实笔记逐条删除；`BookmarksData.remove()` 对 `file == null` 的合成对象记日志防 NPE。

**2/3. 删除 X、编辑按钮增大**：书签页 `bookmark_item.xml`（remove/remove2）、文件列表 `browse_item_list.xml`（delete/itemMenu）从 25dp 提到新增的 `wh_button_touch`=40dp；"我的文件"OPDS/WebDAV 行程序化构建的编辑笔/X 从 30dp 提到 40dp（`netListItem`，padding 相应缩小）。

**4. 添加文件夹后页面不恢复（修复）**：根因是文件夹选择器内嵌的 BrowseFragment2（browsePath==null）浏览时把共享的 `AppState.displayPath` 改成了对话框内最后浏览的目录，关闭后只 `populate()` 不回根视图，重启才恢复。现在 `addLibraryFolder`/`addLibraryFile` 选完后调 `displayAnyPath(ROOT_PATH)`——恢复根视图（OPDS/WebDAV/书库文件夹/搜索区）并刷新列表。

**5. "在多个文档中搜索"/"新文件(.txt)" 移到文件夹列表下方**：新增 `netSection2`（`fragment_browse2.xml` 中 RecyclerView 容器之后，`bankSpace` 改 0dp+weight=1），`buildNetSections` 往其追加 divider + "搜索"分节头（无添加按钮时隐藏"+ 添加"标签）+ 两个工具项；可见性跟随根视图（`displayAnyPath` 同步切换）。

**验证（MI9）**：合并笔记条目删除后消失且不再回弹；书签页/文件列表 X、编辑笔明显变大易点；添加文件夹选完后立即回到根视图（重复添加报"已存在"也不停留）；"在多个文档中搜索""新文件"出现在文件夹列表下方"搜索"区且功能正常。

---

## [2026-09-02] 图标改用 HowRead.png 面板设计并去除右下水印

上一轮香槟金配色不满意，改用仓库根目录新设计稿 `HowRead.png`（1536x1536，米白圆角面板 + 金色"书"字 + 木质翻开书）重新生成，**保留原图配色**（面板设计本身主体突出、层次分明）：

| 文件 | 改动 |
| --- | --- |
| 生成脚本 `Z:\opt\librera\bench\gen_icons3.ps1` | ① 平滑去除右下角"元宝 AI生成"水印（矩形区域按左/上邻色双线性混合填充 + 两轮 3x3 盒式模糊）；② 按内容包围盒裁掉面板外围页边距；③ 双线性 cover 铺满画布（原图色彩不动） |
| `mipmap-xxhdpi/icon_pdf_reader.png` | 144x144 重绘（13% 圆角） |
| `mipmap-xxxhdpi/adaptive_pdf_reader.png` | 192x192 前景全幅重绘 |
| `drawable/bg_pdf_reader.xml` | 自适应背景层改为采样面板上下边缘色调的柔和渐变（#C9C8C6→#E9E2DB） |

**验证（MI9）**：构建安装后桌面"好好读"图标为面板设计原貌，水印不可见，MIUI 圆角遮罩正常。

---

## [2026-09-01] 图标优化：主体放大 + 暖金香槟渐变背景

第一版图标直接把 `HowRead.jpg` 原图（含大量近白边距）缩进画布，书本/书字偏小，且背景取样自图片近白色（#FBFAF8→#F0EDE8），与白色书页几乎同色，主体不突出。本轮重做：

- **主体放大**：逐像素扫描计算"书"字+书本的实际内容包围盒（按行左右边缘估计原图底色、色差判定），裁掉四周空白后放大绘制——legacy 图标占画布 96%、adaptive 前景占 72%（配合既有 23% inset）；
- **背景换色**：原图近白背景平滑映射为暖金香槟渐变（上 #C8A26B → 下 #E9D8BA），金色"书"字、木质封面与书页阴影保留原图；阈值渐变（低于 16 视为纯背景、高于 52 视为前景、中间平滑过渡）避免生硬光晕；
- **无接缝合成**：每个图标在整幅画布上单次逐像素合成，作品背景与画布渐变按绝对位置对齐（第一版"作品单独贴回渐变画布"的做法在 adaptive 图上有可见矩形接缝，已修复）；生成脚本 `Z:\opt\librera\bench\gen_icons2.ps1`。

| 文件 | 改动 |
| --- | --- |
| `mipmap-xxhdpi/icon_pdf_reader.png` | 144x144 重绘：香槟渐变底 + 放大主体 + 13% 圆角 |
| `mipmap-xxxhdpi/adaptive_pdf_reader.png` | 192x192 前景重绘：香槟渐变底 + 放大主体（inset 不变） |
| `drawable/bg_pdf_reader.xml` | 背景层渐变改为 #C8A26B（上）→ #E9D8BA（下），与前景底色一致 |

**验证（MI9）**：构建安装后桌面"好好读"图标主体明显变大，金色书+书字在香槟金底上突出，MIUI 圆角遮罩下无接缝、无裁切异常。

---

## [2026-09-01] 应用图标替换为 HowRead 设计图（书+书字）

用仓库根目录的 `HowRead.jpg`（"书"字 + 翻开书立体图，浅暖米色背景）替换 librera 主 flavor（`com.howread.reader`）的启动图标 `@mipmap/icon_pdf_reader`：

| 文件 | 改动 |
| --- | --- |
| `mipmap-xxhdpi/icon_pdf_reader.png` | 重绘为 144x144：图片等比居中 + 四角取样背景色（#F4F0EA）填充 + 13% 圆角，旧启动器使用 |
| `mipmap-xxxhdpi/adaptive_pdf_reader.png` | 自适应图标前景重绘（192x192，保持原资产尺寸）：整幅取样背景色填充 + 图片等比居中，XML 既有 23% inset 不变 |
| `drawable/bg_pdf_reader.xml` | 自适应图标背景层由旧绿色渐变（#236e45→#add074）改为图片本身的色调渐变（#FBFAF8→#F0EDE8），与前景无缝衔接 |

生成脚本：`Z:\opt\librera\bench\gen_icons.ps1`（PowerShell System.Drawing，取样四角均值色、圆角裁剪、高质量插值缩放）。

**验证（MI9）**：覆盖安装后桌面"好好读"图标显示新设计（MIUI 圆角遮罩正常），应用内与设置页图标同步更新。

---

## [2026-09-01] 软件说明标题栏显示版本号 v 前缀与编译时间

| 文件 | 改动 |
| --- | --- |
| `app/build.gradle` | 新增 `generateBuildTimeSource` task：每次构建生成 `com.foobnix.pdf.info.BuildTime`（`BUILD_TIME = "yyyyMMddHHmm"`，构建机本地时间），加入 main java 源目录并挂到 `preBuild` 依赖。用生成源文件而非 `buildConfigField`——Configuration Cache 会冻结 buildConfigField 的值，增量构建显示的是过期时间 |
| `Apps.java` | 新增 `getBuildTime(Context)` 返回生成的 `BuildTime.BUILD_TIME` |
| `AboutSectionBinder.java` | 软件说明标题栏（section6）改为 `好好读: v0.9.0 build 202609012308`（版本号加 v 前缀、后接编译时间戳）；"更新日志"行同样加 v 前缀（`更新日志 好好读 v0.9.0`） |

**验证（MI9）**：软件说明弹窗标题栏显示"好好读: v0.9.0 build 202609012312"，与该次构建的实际时间一致；更新日志行显示 v0.9.0。技术备注：曾尝试读取 APK 内 classes.dex 的 ZIP 时间戳，但 AGP 打包为可复现构建会归一化 zip 条目时间（显示 1981-01-01），故改用构建期生成源文件方案。

---

## [2026-09-01] 同步方案三:列表/统计改整文件同步;进度与书签删除同步到服务器

### 一、OPDS/WebDAV/书库文件夹、最近阅读/珍藏、统计/AI/杂项 → 整文件同步

按需求把六类数据从"逐条/逐字段合并"改为**整文件方案**（与 app-State/app-CSS 一致的 newer-mtime-wins）：内容相同则跳过；远端不存在则 seed 上传；其他失败跳过本轮；否则修改时间新的一方整份胜出。删除靠整份覆盖天然传播，不再需要墓碑/复活标记（上一轮的标记机制整体退役删除）。

| 文件 | 改动 |
| --- | --- |
| `WebDavSyncer.java` | 新增通用 `syncWholeFile()`（no-op / seed / 跳过 / 双向整份覆盖，带 BENCH 日志）；`doSync` 中 `app-Recent`、`app-Favorite`、`app-Stats`、`app-AI`、`app-Misc`、`app-NetworkSources` 六个文件改走整文件；`app-BookStates`（已读/未读标记）保留逐项合并；移除 `syncMergedArrayFile` 与相应 `syncMergedObjectFile` 调用；本机被远端更新时调用 `AppData.invalidateListCache()` 刷新首页 |
| `ProfileStateIO.java` | `exportNetworkSources/exportStats/exportAi/exportMisc` 改为**内容变化才写盘**（整文件方案以 mtime 判断"谁改过"，导出必须保真旧 mtime）；`exportNetworkSources` 只写 opds/webdav/folders 三段；`importNetworkSources` 改为整份应用（folders 整段替换 `searchPathsJson`）；删除退役的 `mergeNetworkSources`、`mergeSimpleMetaArrays`、`mergeStats/mergeAi/mergeMisc`、`updateMarkers/mergeMarkers/unionMarkers/markerKeys/filterTombstoned/tombstonesToArray/keysOf/unionLines/entryKey/readSimpleMetaArray/appendAll` 及标记段常量 |

**已知语义**（整文件方案的固有代价，用户已确认）：两台设备在一次同步间隔内各自改动同一文件时，后同步的一方整份获胜，另一方的中间改动被覆盖；依赖设备与服务器时钟大致一致。

### 二、每本书的进度/书签本地删除 → 同步删除服务器

| 文件 | 改动 |
| --- | --- |
| `AppProfile.java` | 新增 `app-DeletedBooks.json`（设备目录，`{"书名":{"p":时间,"b":时间}}`，p=进度 b=书签） |
| `SharedBooks.java` | 新增 `DeletedBooks` 记录/读取/清空助手；`deleteProgress()`（标记未读）记录 p 墓碑 |
| `BookmarksData.java` | `remove()`（单条/按书删除书签的公共路径）记录 b 墓碑；**修复既有 bug**：`cleanBookmarks()`（清除所有书签）原来向对象格式文件写入空数组 `[]` 导致文件不可解析，改为正确清空并记录全部受影响书名 |
| `WebDavSyncer.java` | doSync books 段：远端条目命中删除墓碑时**跳过其 progress/bookmarks 回灌本地**（否则删除会被服务器合并回来）；若该书本地已无任何进度与书签残留 → 直接删除服务器 `books/<hash>.json`（计入 `SyncResult.booksDeleted`，同步摘要显示"删N"）；处理完成后清空墓碑。BENCH 输出 books synced/associated/deleted 计数 |

**验证（MI9，服务器 192.168.50.100:5005）**：整文件三态日志齐全（identical 静默 / uploaded (local newer) / downloaded (remote newer)），books 段 `synced=86 associated=49`；对《侯卫东官场笔记》"标记为未读（清空进度）"后 progress 键即时删除、墓碑记录，同步后**进度未被服务器回灌**（本地保留已删状态），因该书尚有书签残留服务器文件按语义保留（仅去掉进度部分）；连续两轮同步状态收敛。

### 三、构建

- Ubuntu 编译 `:app:assembleLibreraDebug :app:assembleLibreraRelease` 成功；debug 包已在 MI9 验证。

---

## [2026-08-31] 同步修复二轮:删除跨设备传播;最近阅读/珍藏不再被冲掉

### 一、A 机删除的 OPDS/书库文件夹,B 机同步后也删除

**根因**:上一轮墓碑合并规则中"本机快照段仍含该条目 → 丢弃墓碑"——未执行删除的设备永远以自己副本为准并回传服务器,删除只在本机生效,无法跨设备传播。

| 文件 | 改动 |
| --- | --- |
| `ProfileStateIO.java` | 墓碑在合并中**无条件保留**(按 k 并集、时间取 max),不再因"本机仍有"被丢弃;B 机合并后由既有 import 逻辑(opds/webdav 整串替换、folders 按墓碑移除)落盘,删除随之传播到所有设备并回传服务器 |
| 同上 | 新增复活标记段 `opds-add`/`webdav-add`/`folders-add`:本机"见过删除后又重新添加"的条目记录 `{k,t}` 标记;合并时**时间较新的复活标记取消其墓碑**(后到优先),使重新添加同样全局传播。复活标记仅在"本机墓碑段存在该键且条目重新出现"时记录,避免新设备首次同步把全列表误标为复活 |

**验证(MI9,单机模拟双机)**:①模拟 A 机删除 My:Awards → 同步生成墓碑并发布;②模拟 B 机(快照/活列表均 3 项、无墓碑)→ 同步后**活列表与快照均变 2 项**(旧行为保留 3 项);③模拟重新添加 → `opds-add` 复活标记生成、墓碑清空、条目恢复并发布。

### 二、最近阅读/我的珍藏:B 机看不到、A 机自己被冲掉

| 文件 | 改动 |
| --- | --- |
| `IO.java` | 单槽读缓存 `cacheFile/cacheString` 两个 volatile 字段两步赋值,并发写不同文件时交错,读方会拿到"旧文件名+新内容"(最近阅读读到珍藏的数组),随后的读改写把列表整文件写坏——改为单一 volatile 不可变 `String[]{path, content}` 原子发布,读写都取完整快照 |
| `WebDavSyncer.java` | `fetchText` 错误语义:404(不存在)返回 ""、其他一切失败(网络/鉴权/SSL/超时)返回 null;`syncMergedArrayFile`/`syncMergedObjectFile`/`syncGlobalFile` 在 null(暂时性失败)时**跳过该文件本轮**(不覆盖服务器也不动本地)——消除"一次网络抖动把残缺本地列表发布成全局并永久化"的窗口;404 仍保持既有 seed 上传 |
| 同上 | `syncMergedArrayFile` 写回前**重读本机文件再合并**(远端 GET 期间本机 add() 追加的条目不再被丢失);写回 recent/favorite 后调用 `AppData.invalidateListCache()` 立即刷新首页;新增 `Log.i("BENCH")` 输出 local/remote/merged 条数,真机可观测 |

**说明**:同步到 B 机的条目若对应书籍文件在 B 机相同路径不存在,首页仍会按 `isFile()` 过滤不显示(打不开就不显示);首页每栏只显示最近 8 条,完整列表在"最近阅读"页。

**验证(MI9)**:BENCH 日志输出 `sync app-Recent.json: local=1 remote=1 merged=1` 等计数正常;打开第二本书后列表正确增长(1→2),再同步后内容稳定不丢;偏好页 WebDAV 服务器地址显示正常。

### 三、构建

- Ubuntu 编译 `:app:assembleLibreraDebug :app:assembleLibreraRelease` 成功;debug 包已在 MI9 验证。

---

## [2026-08-31] 修复阅读位置"慢一拍";修复 WebDAV 同步冲掉本地删除(OPDS/书库文件夹)

### 一、重开书籍恢复到上次退出前的准确位置

**根因**:翻页位置在内存即时更新,但写盘走 1 秒防抖(`DocumentController.saveCurrentPage` 的 `handler2.postDelayed`);返回键退出链路(`onCloseActivityAdnShowInterstial` → `closeActivityFinal`)全程没有任何保存,且 `closeActivityFinal` 先 `documentModel.recycle()`,此后防抖任务被 `saveCurrentPageAsync` 的 `getPageCount()<=0` 守卫丢弃——磁盘上停留在"最后一次停顿≥1 秒"的旧位置,重开即"慢一拍"。

| 文件 | 改动 |
| --- | --- |
| `DocumentController.java` | 新增 `saveCurrentPageNow()`:取消防抖回调并立即走一次 `saveCurrentPageAsync()`(文档尚存活时调用,页数有效) |
| `ViewerActivityController.java` | `closeActivityFinal` 在 `documentModel.recycle()` **之前** flush(覆盖返回键/关闭按钮/自动关闭全部退出路径);`onPause` 同样 flush(覆盖 Home 键/最近任务划掉等不经 closeActivityFinal 的场景),均带 try/catch + null 保护 |
| `HorizontalViewActivity.java` | `onPause` 恢复被注释掉的保存,改为 `dc.saveCurrentPageNow()`(书籍/横向模式同样在文档存活时落盘) |

**真机验证(MI9)**:《侯卫东官场笔记》连翻 4 页后**立即**按返回退出,`app-Progress.json` 由 pg=234 → pg=238(此前该场景保留旧值);重开直接落在 9/9(第 239 页),与退出位置一致。

### 二、WebDAV 同步不再复活本地删除的 OPDS 条目/书库文件夹

**根因**:OPDS 目录、WebDAV 服务器、书库文件夹三个列表走"纯并集"合并(`ProfileStateIO.unionLines`),没有删除传播——本地删除的条目在服务器快照里还在,同步时被无条件并回活内存并再次发布,删除永远无法收敛。

**方案**:在 `app-NetworkSources.json` 中新增三个墓碑段 `opds-del`/`webdav-del`/`folders-del`(元素 `{k,t}`,k 为 entryKey),只影响上述三个列表;最近阅读/收藏、阅读进度、书签的同步逻辑不变。

| 文件 | 改动 |
| --- | --- |
| `ProfileStateIO.java` | `exportNetworkSources()`:导出时对比"文件旧快照"与当前活列表,旧有今无的键记墓碑;活列表中重新出现的键清除其墓碑(重新添加优先);墓碑按时间取最新 500 条封顶 |
| 同上 | `mergeNetworkSources()`:三段先并集;墓碑段按 k 并集、t 取 max;**本机活列表仍存在的键**丢弃其墓碑(另一台设备保留该条目时,以保留方为准);用墓碑过滤并集结果后随快照回写本地并发布服务器,删除就此收敛 |
| 同上 | `importNetworkSources()`:opds/webdav 两段改为**按段存在性守卫的整串替换**(原 `isNotEmpty` 守卫导致"全部删除"无法落盘;旧格式文件无该段时不动 live);folders 段在既有 add-only 之外,新增按 `folders-del` 墓碑从 `searchPathsJson` 显式移除,使其他设备上的删除也能落到本机 |

**语义**:删除优先于服务器旧副本;若另一台设备仍保留该条目,则以保留方为准(不会强制清空其他设备);任何设备重新添加即全局恢复。

**真机验证(MI9,同步服务器 192.168.50.100:5005)**:①UI 删除 OPDS 条目"Top Books to Read"→ 同步 → `opds-del` 记录 `My:Awards` → 强杀重启 + 自动同步后**不复活**;②恢复条目 → 同步 → 墓碑清除、三设备状态收敛;③书库文件夹移除 DSfile → 同步 → `folders-del` 记录 → 重启后不复活;④加回 DSfile → 同步 → 墓碑清除、恢复 2 项。测试后设备配置已完全还原(OPDS 3 项原顺序、书库文件夹 2 项)。

### 三、构建

- Ubuntu 编译 `:app:assembleLibreraDebug :app:assembleLibreraRelease` 成功;debug 包已在 MI9 真机验证通过。

---

## [2026-08-12] 移除 Google/Drive 依赖;新增书库「格式配置」「书库文件夹配置」

### 一、构建彻底去 Google 化

| 文件 | 改动 |
| --- | --- |
| `app/build.gradle` | 移除 `com.google.gms.google-services` 插件;`signingConfigs.release` 与 8 个 flavor 的 `appGdriveKey`/`admob*` 全部改为 `project.findProperty(...) ?: ''`,不传 -P 也能配置、能编译 |
| `build.gradle.kts` | 移除 googleServices 插件 alias |
| `gradle/libs.versions.toml` | 移除 googleServices 版本/插件;移除 5 个 Google 库(google-api-client-android、google-api-services-drive、google-http-client-gson、google-oauth-client-jetty、play-services-auth) |
| `libDepPro/build.gradle.kts` | 移除上述 5 个 Drive 相关依赖 |
| `Builder/link_to_mupdf_1.23.7.sh` | 新增本机 NDK 路径 `PATH3=/docker/opt/android-sdk/ndk`,fdroid/普通构建的 ndk-build 搜索列表加入 PATH3 |

**Stub 迁移(fdroid → main)**:`app/src/fdroid/java` 下 `com.google.android.gms.*`、`com.google.api.*`、`com.google.api.services.drive.*` 共 19 个 stub 类移到 `app/src/main/java`,删除 `FirebaseAnalytics.java` stub——所有 flavor(pro/pdf_classic 等)在无 Play Services 环境下均可编译;`UITab.java` 中 Google Drive 标签在所有构建中隐藏(Drive 功能已整体移除)。

### 二、设置页「格式配置」

- `fragment_preferences.xml`:书库设置区 14 个格式 CheckBox 收进一行「格式配置」入口
- `PrefFragment2.java`:弹窗从上到下列出 14 个格式,每行右侧显示该格式在书库中的文件数;切换即写 `AppState` 对应 flag、刷新 `ExtUtils.updateSearchExts()`、自动触发扫描
- `AppDB.java`:新增 `getExtCounts()`(按 EXT 分组统计书库 `IS_SEARCH_BOOK=1` 文件数)
- `strings.xml`(+zh-rCN/+zh-rTW):新增 `formats_settings`

### 三、设置页「书库文件夹配置」

- `fragment_preferences.xml`:「Folders to Scan」标签+路径+添加按钮收进一行「书库文件夹配置」入口
- `PrefDialogs.java` 重写 `chooseFolderDialog`:底部新增「添加文件夹」「添加文件」链接(支持配置多个文件夹/文件);每行显示该文件夹(递归)/该文件在书库中的支持文件数
- `PathAdapter.java` + `path_item.xml`:行右侧新增计数 TextView(默认隐藏,不影响其他复用场景)
- `AppDB.java`:新增 `getSearchBookPaths()`(按前缀/精确匹配计数)
- 配套修复(否则「添加文件」不生效):
  - `BookCSS.filtered()`:`isDirectory()` → `exists()`,重启不再丢弃文件条目
  - `SearchAllBooksWorker` / `CheckDeletedBooksWorker`:`root.isDirectory()` → `root.exists()`,单文件可被扫描入库
- `strings.xml` ×3:新增 `library_folders_settings`、`add_file`

### 四、其他调整

- `BrowseFragment2.getInitPath()`:标签栏「文件夹」页优先打开第一个已配置的书库文件夹
- `path_item.xml`:计数文本 12sp → 14sp

### 五、验证

- Ubuntu 远程构建:fdroid Debug **BUILD OK**(5 个 APK)、librera Debug **BUILD OK**(5 个 APK)
- 全仓无残留 `R.id.searchPaths` / `R.id.onConfigPath` 引用;改动均在 main source set,8 个 flavor 全部生效

### 六、附注

- `keystore.pkcs12`(签名密钥)不随代码入库,已加入 `.gitignore`
- 编译产物(`app/build` 等)不入库,已清理

---

## [2026-08-12] 新增 WebDAV 标签(独立于 OPDS,只读)

- **独立模块**:新增 `com.foobnix.webdav` 包(WebDavFragment2 / WebDavClient / WebDavStore / WebDavCredentials / WebDavAdapter 等),不 import 任何 OPDS 类;将来可删除 OPDS 而不影响 WebDAV
- **独立标签**:UITab 新增 `WebDavFragment(8, ...)`,新装默认可见;已装设备通过 `UITab.getOrdered()` 自动追加新标签(无需手动开启)
- **WebDAV 客户端**:Sardine-Android 0.9(经 jitpack 仓库拉取);因它传递引入 okhttp 4.x,已用 `resolutionStrategy.force` 将全局 okhttp 钉回 3.12.6(已验证 sardine 仅用 okhttp 稳定公开 API),并 exclude xpp3/stax 避免 XML 解析冲突
- **功能**:浏览服务器目录(PROPFIND,目录优先排序),点文件下载到下载目录、入库并直接打开;认证失败有提示
- **凭据安全**:账号密码用 AndroidKeyStore AES/GCM 加密后存 SharedPreferences(按服务器 URL 为 key)
- **添加/编辑**:标签页 "+" 添加 WebDAV 服务器(URL/名称/账号/密码),保存前后台 PROPFIND 验证,失败可强制添加;长按编辑、行内删除
- 只读范围:不做上传/删除/新建文件夹

---

## [2026-08-12] WebDAV 并入「网络」页(与 OPDS 同为子项,移除独立标签)

### 一、网络页根视图 = 两个子项区块

- **OPDS 子项**:区块头(标题 OPDS + 右侧加号 → 添加目录);区块内依次为「代理设置」齿轮行(原顶栏齿轮移入,内容不变:代理服务器、下载目录、OPDS 大封面等)、OPDS 链接列表、「恢复默认目录」「什么是 OPDS?」两行(原页底链接移入)
- **WebDAV 子项**:区块头(标题 WebDAV + 右侧加号 → 添加服务器);区块内为 WebDAV 服务器列表(长按编辑、行内删除不变)
- 实现:`NetworkRootAdapter.java`(组合适配器,7 种视图类型;OPDS 行委托 `EntryAdapter`、WebDAV 行委托 `WebDavAdapter` 渲染,点击/长按/删除逻辑不变);新增布局 `network_section_header.xml` / `network_settings_row.xml` / `network_text_row.xml`

### 二、WebDAV 浏览并入网络页

- **移除独立 WebDAV 标签**:`UITab.java` 删除 `WebDavFragment(8)` 枚举;`getOrdered()` 增加「未知 id 跳过」保护(旧数据残留 `8#` 不会误映射成重复搜索标签);删除 `WebDavFragment2.java` 与 `fragment_webdav.xml`
- `OpdsFragment2.java` 增加 WebDAV 浏览模式:点服务器/目录进入浏览(复用 `WebDavStore`/`WebDavCredentials`/`WebDavClient`/`WebDavAdapter`/`AddWebDavDialog`),返回/主页回到根合并视图;点文件 → 确认后下载到下载目录 → 入库 → 直接打开;认证失败有提示
- 顶栏精简:删除齿轮、加号及分隔线,只留 返回/标题/星标/进度/主页;根视图标题改用「网络」
- **默认可见性**:`DEFAULTS_TABS_ORDER` 网络页(5)由 `5#0` 改为 `5#1`(默认显示;之前 WebDAV 标签默认可见,合并后需网络页默认可见才能看到 WebDAV,不需要可改回 `5#0`)
- 字符串 ×3 新增 `opds`(协议名,三语言一致)

### 三、隔离性(保持不变)

- `com.foobnix.webdav` 包仍不 import 任何 OPDS 类;合并逻辑只放在 `com.foobnix.ui2.*` 层(OpdsFragment2 / NetworkRootAdapter);将来「只要 WebDAV」:删 opds 包后网络页根逻辑需重写,webdav 包本身零依赖

### 四、验证

- Ubuntu 远程构建:fdroid Debug + librera Debug BUILD OK(各 5 个 APK)
- grep 验证:webdav 包无 opds import;`WebDavFragment2`/`fragment_webdav`/`onProxy`/`onPlus` 无残留引用

## [2026-08-12] WebDAV 并入「网络」页 — 代码检视修复 11 项

对 WebDAV 并入网络页改动做代码检视后发现并修复 11 处问题(高 3 / 中 2 / 低 6),全部通过 fdroid + librera Debug 构建验证。

### 一、高优先级

| BUG | 文件 | 修复 |
| --- | --- | --- |
| 老用户升级后网络页隐藏、WebDAV 无入口 | `AppState.java` | `loadInit()` 加幂等迁移:`tabsOrder9` 含 `8#`(旧 WebDAV 标签残留)时剥离,并把 `5#0`→`5#1`(网络页可见);无 `8#` 后不再触发,尊重用户后续手动隐藏 |
| downloadWebDav 流泄漏 | `OpdsFragment2.java` | `doInBackground` 改 `finally` 关闭 InputStream/OutputStream |
| downloadWebDav 失败时部分文件残留 | `OpdsFragment2.java` | catch 块加 `file.delete()` |

### 二、中优先级

| BUG | 文件 | 修复 |
| --- | --- | --- |
| WebDAV 错误提示误导(认证/网络混淆) | `WebDavClient.java` / `OpdsFragment2.java` | `list()` 失败时用 `lastErrorWasAuth` 区分 401/403 与其它;`OpdsFragment2` 加 `webDavLoadFailed` 字段,分别提示「认证失败」/「网络错误」 |
| downloadWebDav 不支持外置 SD 卡 | `OpdsFragment2.java` | 加 `isExteralSD` 分支,走 `DocumentsContract`/SAF(与 OPDS 的 `onClickLink` 一致) |

### 三、低优先级

| BUG | 文件 | 修复 |
| --- | --- | --- |
| WebDavStore 并发数据竞争 | `WebDavStore.java` | `load/findForUrl/add/remove` 统一 `synchronized(LOCK)` |
| URI.resolve 对「相对 href + 无尾斜杠 base」丢路径段 | `WebDavClient.java` | `resolve()` 给 base 补尾斜杠 |
| NetworkRootAdapter 子 adapter notify 不同步 | `NetworkRootAdapter.java` | 构造时注册子 adapter 的 `AdapterDataObserver` 转发到 root;`setOpdsEntries` 改为直接操作 list 避免双 notify |
| onBackAction WebDAV→根分支状态未重置 | `OpdsFragment2.java` | 补 `authFailed`/`webDavLoadFailed`/`currentServerUrl` 重置(与 `onHome` 一致) |
| UITab.getOrdered 对脏数据无防御 | `UITab.java` | 循环体加 try/catch + `tab.length` 检查跳过坏 pair |
| downloadWebDav onPostExecute 无 isAdded 防护 | `OpdsFragment2.java` | 加 `if (!isAdded()) return;`(在关进度条之后) |

### 四、验证

- Ubuntu 远程构建:fdroid Debug + librera Debug BUILD OK(各 5 个 APK,2026-08-12 22:11/22:13)
- 改动文件纯 LF 行尾;webdav 包零 opds import(隔离性不变)

## [2026-08-12] 修复 librera 等含广告 flavor 启动即崩(AdMob App ID 为空)

### 根因

含广告的 flavor(librera/pdf_v2/ebooka/tts_reader/pdf_classic/epub_reader)依赖 `libDepFree` → `play-services-ads 25.4.0`,其 `MobileAdsInitProvider`(ContentProvider,在 `Application.onCreate()` **之前**启动)校验 manifest 的 `com.google.android.gms.ads.APPLICATION_ID`。该值来自 `admobAppId` 占位符,而 `gradle.properties` 未配 `*_admobAppId`,默认空字符串 → SDK 抛 fatal `IllegalStateException` → 应用启动即崩。

fdroid/pro 不崩:fdroid 不依赖 `libDepFree`(用 `libPro` 桩类,manifest 无 `MobileAdsInitProvider`);pro 只依赖 `libDepPro`(不含 ads)。

### 修复

`app/build.gradle`:新增 4 个 Google 官方公开测试 AdMob ID 常量([测试广告文档](https://developers.google.com/admob/android/test-ads)),作为 6 个含广告 flavor 的 `admobAppId`/`admobBannerId`/`admobFullId`/`admobRewardId` 默认值(原 `?: ''` → `?: sampleAdmobXxx`)。

- 开发时(未配 gradle.properties):用测试 ID,应用正常启动,AdMob 初始化显示测试广告
- 上线时:在 `~/.gradle/gradle.properties` 配 `librera_admobAppId=真实ID` 等即自动覆盖
- 测试 App ID `ca-app-pub-3940256099942544~3347511713` 是 Google 官方公开值,不会产生真实广告收入

### 验证

- librera Debug BUILD OK(5 APK,2026-08-12 22:29)
- merged manifest 确认 `APPLICATION_ID="ca-app-pub-3940256099942544~3347511713"`(不再是空)

---

## [2026-08-13] 冷启动首帧优化(Phase 1 + Phase 2)

**目标**:缩短「点图标 → 首帧绘制」的等待。真机为 MIUI Android 14(serial `48fee174`)。

### 一、Phase 1 — 首帧基础优化

| 改动 | 文件 | 说明 |
| --- | --- | --- |
| 移除 androidx Startup 初始器 | `AndroidManifest.xml` | `InitializationProvider` 用 `tools:node="remove"` 移除 3 个在 `Application.onCreate` **之前**于主线程运行的初始器:`EmojiCompatInitializer`、`ProcessLifecycleInitializer`、`ProfileInstallerInitializer` |
| 启动画面(消除冷启动白屏) | `res/values/styles.xml`(+`values-v21`)、`AndroidManifest.xml` | 新增 `StyledIndicatorsBlack.Launch` 主题:`windowDisablePreview=false` + `windowBackground=@drawable/splash`,并套到启动 Activity `MainTabs2`。原基础主题 `windowDisablePreview=true`(冷启动全白屏);现系统立即用 splash drawable 作 starting window,用户即时看到画面 |
| MuPDF 懒加载/后台预加载 | `AppsConfig.java`、`LibreraApp.java` | `ensureMuPdfLoaded()` 改 `synchronized` + `volatile mupdfLoaded` 守卫(首次调用才 `loadLibrary`,不再 init 阶段同步加载 21MB);`LibreraApp` 把预加载提交到 `executorService`(后台线程) |
| offscreenPageLimit 10→1 | `MainTabs2.java` | ViewPager 首帧只创建「当前 + 相邻」标签页 Fragment(原为 10,首帧会实例化全部标签) |
| 新增独立单线程执行器 | `AppsConfig.java` | `executorServiceSingle = newSingleThreadExecutor()`,供 Phase 2 的 getCount 使用,避免与 `executorService` 上的 MuPDF 预加载互相阻塞 |

### 二、Phase 2 — 延迟加载(不阻塞首帧)

| 改动 | 文件 | 说明 |
| --- | --- | --- |
| getCount 异步 + 安全等待 | `AppProfile.java`、`SearchFragment2.java` | `AppDB.open()` **保持同步**(保证 DB 全程可读);仅 `getCount()` 提交到 `executorServiceSingle` 异步执行。`bookCount` 改 `volatile`;新增 `CountDownLatch DB_READY` + `awaitDBReady(timeoutMs)`。`SearchFragment2.onCreateView` 读 `bookCount` 前先 `awaitDBReady(2000)`,避免「书库尚未计数完」被误判为「空书库」而触发 `seachAll()`→`deleteAllData()` **误删真实书库**的破坏性竞态 |
| UMP 同意/广告延迟到首帧后 | `MainTabs2.java` | UMP consent/ads 块用 `handler.post(() -> {...})` 包裹,推迟到首帧绘制后执行(密码门禁 / `EXTRA_EXIT` 早退语义不变) |
| 主线程杂活后台化 | `LibreraApp.java` | `MobileAds.initialize`(官方允许任意线程)、`WorkManager.pruneWork/cancelAllWork`、`TTSNotification.initChannels` 全部提交到 `executorService` 后台线程 |

### 三、安全设计说明(关键)

**AppDB.open 保持同步**是相对原「open + getCount 全异步」设计的关键修正。全异步会让 worker / widget 在 DB 未 open 时读到空/陈旧数据,并使 `SearchFragment2` 的 `bookCount==0` 判断误触发破坏性重扫。**open 同步 + 仅 getCount 异步**从根源消除全部竞态,worker / widget 一行不用改。

### 四、验证

- 真机冷启动(force-stop → 清 logcat → 启动 → 读首帧):
  - fdroid:首帧 ~1295–1311ms;librera:~1327–1331ms(均稳定,相对 Phase 1 的 ~1.35s 无回归)
  - 书库列表正确(items=3,无误删);tab 切换无异常;无崩溃
  - getCount、WorkManager 确认在后台线程;无 GMS 设备 MobileAds/UMP 不执行(`isShowAdsInApp` 返回 false)
- Ubuntu 远程构建:fdroid Debug + librera Debug BUILD OK(先 `gradlew --stop` 规避 Samba `compileTransaction` 锁)
- 本轮全部 STARTUP 埋点已清理(6 文件):`LibreraApp` / `AppsConfig` / `AppProfile` / `MyContextWrapper` / `MainTabs2` / `SearchFragment2`;改动文件均纯 LF 行尾

### 五、说明

- 剩余 ~350–540ms 为 ART 类加载(process→onCreate),属 **Baseline Profile** 范畴(需另加 macrobenchmark 模块),本次未涉及;如需再压可单独规划
- 测试设备无 Google Play Services,故广告 flavor 的 MobileAds/UMP 实际未执行(代码正确,会在有 GMS 的设备上运行)

---

## [2026-08-13] 预编译二进制缓存 prebuilt/ —— 离线构建

**目标**:把所有「需从网上拉取」的二进制缓存进源码树,彻底解决因网络限制(`git://git.ghostscript.com` 被封、jitpack.io 被墙、mavenCentral 慢)导致的编译失败。

### 一、新增 `prebuilt/`(合计 ~374 MB,普通 git blob 入库,**免 LFS**)

| 子目录 | 内容 | 原网络来源 | 体积 | 接入方式 |
| --- | --- | --- | --- | --- |
| `native/mupdf-1.23.7/<abi>/` | MuPDF + liblame 原生库(4 ABI × 2 = 8 个 `.so`,RAW) | `git clone git://git.ghostscript.com/mupdf` + ndk-build | 85 MB | `app/build.gradle` `jniLibs.srcDirs` 直读(无需还原) |
| `gradle-cache/modules-2.tar.gz.part00/01` | 全部 Gradle/Maven 依赖 + 插件(AGP/Kotlin/KSP/jitpack…),**Gradle 原生缓存格式**(158MB 拆 2 片 ≤90MB) | mavenCentral / google / jitpack.io / gradlePluginPortal | 158 MB | `scripts/restore-cache.sh` `cat` 重组后解压到 `~/.gradle/caches/`,用 `--offline` 构建 |
| `gradle/gradle-8.14.5-bin.zip.part00/01` | Gradle 发行包(132MB 拆 2 片 ≤90MB) | services.gradle.org | 132 MB | `scripts/bootstrap-gradle.sh` `cat` 重组后灌入 `~/.gradle/wrapper/dists/` |

### 二、关键设计决策:用 Gradle 原生缓存,不用 maven 仓库

最初尝试把依赖转成**文件型 maven 仓库**(`prebuilt/maven` + `settings.gradle.kts` 置首),但**构建失败**:现代 AndroidX(room/lifecycle/compose…)用 Gradle Module Metadata 发布**变体产物**(如 `room-runtime-android` 的 .aar 实际名为 `room-runtime-release.aar`),Gradle 从文件仓库按默认名查找、找不到,且因元数据已存在**不回退网络** → 失败。

改为 **vendor Gradle 自己的 `modules-2` 缓存**(原生格式,变体解析天然正确),fresh 机器还原进 `~/.gradle` 后用 `--offline` 构建。**实测**:全新 `GRADLE_USER_HOME`(只有该缓存)+ `--offline` + `clean` → BUILD SUCCESSFUL。

### 三、接入改动

| 文件 | 改动 |
| --- | --- |
| `app/build.gradle` | `sourceSets.main.jniLibs.srcDirs = ["${rootDir}/prebuilt/native/mupdf-1.23.7"]`(替换默认 `src/main/jniLibs`,消除旧符号链接冲突) |
| `Builder/link_to_mupdf_1.23.7.sh` | `LIBS` 改指向 `prebuilt/native/mupdf-1.23.7`;符号链接(`ln -s`)改真实拷贝(`cp`)—— 源码编译结果直接灌入缓存 |
| `.gitignore` | 加 `/app/src/main/jniLibs/`(gradle 已不读,防旧符号链接误入库) |
| `.gitattributes`(新增) | `prebuilt/**` 二进制标记 `-text`(**不走 LFS**,含 `.part*`);`*.sh/*.kts/*.gradle` 强制 LF 行尾 |
| `settings.gradle.kts` | **未改**(依赖走原生缓存 + `--offline`,不需要文件仓库) |

### 四、脚本(均 in-repo)

| 脚本 | 作用 |
| --- | --- |
| `scripts/vendor-cache.sh` | 联网构建后运行:打包 `modules-2.tar.gz` 后自动拆成 ≤90MB 分片(`.part00/.part01`)并删原件 |
| `scripts/restore-cache.sh` | fresh 机器运行:`cat` 重组分片 → 解压到 `~/.gradle/caches/` + 调 bootstrap-gradle.sh |
| `scripts/bootstrap-gradle.sh` | `cat` 重组分片 → 灌入 wrapper 缓存(`~/.gradle/wrapper/dists/`) |
| `Builder/prepare-native.sh` | 检查 8 个 `.so`;缺失→调 `link_to_mupdf_1.23.7.sh` 拉源码编译 |

### 五、验证(已通过)

1. **联网构建不回归**:回退 settings.gradle.kts 后正常解析网络,构建通过。
2. **主机离线**:`./gradlew --offline :app:assembleFdroidDebug` → BUILD SUCCESSFUL(用主机现有缓存)。
3. **fresh 机器离线(终极)**:全新 `GRADLE_USER_HOME` + vendored 缓存 + `--offline` + `clean` → **BUILD SUCCESSFUL**(38 任务真实执行,非 UP-TO-DATE)。
4. **gradle zip bootstrap**:`cat` 重组分片 → wrapper 自动解压 → 离线构建成功。

### 六、入库说明

- **免 Git LFS**:本仓库远程是 GitHub **public fork**,public fork 不允许上传 LFS 对象(push 报 `can not upload new objects to public fork`)。故二进制全部以**普通 git blob** 入库,无需 `git lfs install`。
- 为绕过 GitHub **100MB/单文件**硬上限,两个大包(`gradle-8.14.5-bin.zip` 132MB、`modules-2.tar.gz` 158MB)各拆成 **≤90MB 分片**(`.part00/.part01`),构建脚本用 `cat ...part*` 重组;`.so` 单个均 <25MB,直接入库。
- 持久主机无需 restore(它已有 `~/.gradle`);restore-cache.sh 仅用于 fresh 机器。
- `keystore.pkcs12` 仍不入库。

---

## [2026-08-28] 新增 AI 大模型对话、笔记保存与阅读位置绑定

### 一、AI 大模型对话入口

- `DragingDialogs.java` onSendToAi:从阅读页底部工具栏「发送到 AI」进入对话,传入`controller.getPercentage()`作为当前阅读位置(0..1 小数,与书签一致)
- `AiAskDialog.java`:
  - 新增 `void show(Activity a, String text, String bookPath, float percent)` 重载,保存 `savePercent`
  - 新加 `show(Activity a, String text)` 和 `show(Activity a, String text, String bookPath)` 委托到 4 参重载
  - 保存笔记时 `note.p = savePercent`(原为 `0`),记录阅读位置

### 二、笔记按书分组、合并展示

- `AppBookmark.java`:新增 `transient public List<AppBookmark> notes;`(transient,不参与 JSON 序列化)
- `BookmarksFragment2.java`:
  - `mergeNotes()`:合并同名书籍笔记时,将各条笔记按时间倒序存入 `merged.notes` 列表
  - `showNoteDialog()`:当 `note.notes` 非空时,改用 `AlertDialogs.showViewDialog` 构建每条笔记的自定义视图——每条笔记包含:
    - 时间行:蓝色+下划线+粗体,显示 `[yyyy-MM-dd HH:mm]`
    - 内容行:选中文本 + AI 回答(可选文本)
    - 分隔线
  - 点击时间行:dismiss 对话框,若 `getPercent() > 0` 且文件存在,`ExtUtils.showDocumentWithoutDialog2` 跳转到对应阅读位置
  - 当 `notes` 为空时回退到原有的纯文本 `AlertDialogs.showOkDialog`

### 三、涉及文件

| 文件 | 改动 |
| --- | --- |
| `AiAskDialog.java` | 新增 `savePercent` 字段、4 参 `show` 重载、保存时 `note.p = savePercent` |
| `DragingDialogs.java` | `onSendToAi` 传入 `controller.getPercentage()` |
| `AppBookmark.java` | 新增 `transient List<AppBookmark> notes` |
| `BookmarksFragment2.java` | `mergeNotes` 保存笔记列表;`showNoteDialog` 自定义视图+可点击时间跳转 |

---

## [2026-08-29] 三项功能修改:首页 Tab 返回覆盖页、笔记时间跳转加固、备份按书维度

### 一、修复首页 Tab 无法从覆盖页返回

**根因**:最近阅读/书签笔记/我的珍藏/网上书库 4 个页面以 `overlayContainer` 临时覆盖层打开,ViewPager 仍停留在首页(index 0)。点击「首页」Tab 时 `SlidingTabLayout.TabClickListener` 判定 `i == currentReal`(0==0),走重选钩子 `setOnTabReselect`,但该 lambda 只调 `DashboardFragment2.onTabReselect()`(空实现),从不关闭覆盖层。点其他 Tab 因 index≠0 走 `pager.setCurrentItem` → `onPageSelected` → `hideTabOverlay()`,所以能切换。

**修改**:`MainTabs2.java` `setOnTabReselect` lambda 中,在调 `onTabReselect()` 之前先调 `hideTabOverlay()`(该方法自带可见性守卫,不可见时直接 return,无副作用,同时恢复顶栏标题与「继续阅读」悬浮按钮)。

### 二、笔记保存时记录阅读位置、时间可点击跳转(加固)

- `AiAskDialog.java` 保留 2026-08-28 的 `percent` 参数传递
- `DragingDialogs.java` 保留 `controller.getPercentage()` 传入
- `BookmarksFragment2.java` `showNoteDialog()` 完善:点击时间 → dismiss 对话框 → 若 `getPercent() > 0` 且文件存在 → `ExtUtils.showDocumentWithoutDialog2` 跳转(与书签点击同一机制);旧笔记 percent=0 时点击时间无跳转

### 三、备份包含书签与笔记,按书籍维度

**原理**:现有导出把 `profile.<名>/device.<机型>/` 整个目录打进 zip(`app-Bookmarks.json` 已在其中,但按时间戳平铺)。新增 `app-BookmarksByBook.json`(结构:`{ 书名: { 时间戳: 书签/笔记对象 } }`),导出时写入、随 zip 打包;导入时合并回 `app-Bookmarks.json`(按时间戳键幂等合并)。

| 文件 | 改动 |
| --- | --- |
| `AppProfile.java` | 新增常量 `APP_BOOKMARKS_BY_BOOK_JSON`、字段 `syncBookmarksByBook`、`init()` 中初始化 File |
| `BookmarksData.java` | 新增 `saveByBook()`:遍历 `getAll()` 按 `ExtUtils.getFileName(path)` 分组写入;`importByBook()`:逐条合并进 `AppProfile.syncBookmarks`(幂等,不覆盖已有键) |
| `PrefDialogs.java` | `exportDialog.doInBackground`:`zipFolder` 前调用 `saveByBook()`;`importDialog.doInBackground`:`unZipFolder` 后调用 `importByBook()` |

### 四、验证(MI9 真机)

1. **首页 Tab 返回**:首页→书签笔记→点首页 Tab→返回首页(覆盖层关闭,顶栏恢复「首页」);书库/我的文件 Tab 切换正常;最近阅读/我的珍藏/网上书库同理
2. **笔记位置跳转**:阅读页选文本→发送到 AI→保存笔记→书签笔记→打开合并笔记→点击笔记时间→跳转到对应阅读位置(第一章 2/10,`p=0.06521739`)
3. **备份按书**:导出后 zip 内含 `app-BookmarksByBook.json`,按书籍分组(3 本书:致命弱点.mobi/没有人给他写信的上校.epub/《驻京办主任3》-王晓方著.epub),书签+笔记完整;导入后恢复 12 条记录、8 条笔记,位置保留,无重复

## [2026-08-30] 代码检视修复 7 个隐含 BUG

### 一、修复内容

| BUG等级 | 文件 | 问题描述 | 修复方案 |
|---------|------|----------|----------|
| **HIGH** | `AppBookmark.java` | `equals()` 仅依赖时间戳，可能导致错误删除 | 添加 `path` 和 `text` 比较，确保唯一性 |
| **HIGH** | `AppProfile.java` | `clear()` 未重置 CountDownLatch，导致 `awaitDBReady()` 永久阻塞 | 通过反射重置 `DB_READY` 计数器 |
| **MEDIUM** | `BookmarksFragment2.java` | `mergeNotes()` 中的 `SimpleDateFormat` 在后台线程创建 | 缓存为类字段，在主线程初始化 |
| **MEDIUM** | `ExtUtils.java` | `getAllExportString()` 未处理 null 路径和空列表 | 添加 null 检查和空列表处理 |
| **MEDIUM** | `MainTabs2.java` | `getCurrentRealIndex()` 可能返回越界索引 | 先检查 `tabFragments.isEmpty()` |
| **LOW** | `AppBookmark.java` | `getPage()` 未处理负数和 NaN | 边界检查，限制 `p` 在 [0, 1] 范围 |
| **LOW** | `ExtUtils.java` | `exportAllBookmarksToFile()` 文件写入未关闭 | 使用 try-with-resources 确保关闭 |

### 二、技术细节

1. **AppBookmark.equals()**: 原 `a.t == t` 改为 `Objects.equals(path, a.path) && Objects.equals(text, a.text) && t == a.t`
2. **AppProfile.clear()**: 使用反射重置 `static final` CountDownLatch
3. **SimpleDateFormat 缓存**: 避免后台线程重复创建，提升性能
4. **null 检查**: 防止 NPE，增强健壮性
5. **边界检查**: 确保 `getCurrentRealIndex()` 不越界
6. **数值约束**: `getPage()` 处理异常百分比值
7. **资源管理**: try-with-resources 防止文件泄漏

### 三、验证结果

1. **构建成功**: APK 生成无错误，所有修复编译通过
2. **安装运行**: 应用成功安装到 MI9，启动正常，无崩溃
3. **功能验证**: 书签管理、导出功能正常，界面显示完整
4. **日志检查**: `logcat` 无 AndroidRuntime 错误，运行稳定
5. **界面截图**: 主界面正常显示，Tab 切换正常

## [2026-08-30] 第三轮复审:验证前两轮修复质量 + 修复 8 个问题

前两轮共修复 13 个 BUG。本轮复审发现其中 4 处修复本身有问题（需返工），另发现 4 个此前未覆盖的新 BUG。

### 一、前两轮修复的返工（Q1-Q4）

| 编号 | 文件 | 问题 | 返工方案 |
|------|------|------|----------|
| **Q1 (HIGH)** | `AppProfile.java` | BUG 8 的反射重置 `static final` 字段在 ART 上会抛 IllegalAccessException（即使 setAccessible(true)），且 R8 混淆后 `getDeclaredField("DB_READY")` 找不到字段 | 声明改为 `private static volatile CountDownLatch DB_READY`（去 final），`clear()` 直接赋新 latch，删除全部反射代码 |
| **Q2 (MEDIUM)** | `AppBookmark.java` | BUG 7 只改了 equals（t+path+text），hashCode 仍是 `(path+text+p)`——不含 t、含 p，违反 equals/hashCode 契约 | hashCode 改为 `Objects.hash(t, path, text)` |
| **Q3 (MEDIUM)** | `BookmarksFragment2.java` | BUG 9 属误诊（后台线程"创建" SimpleDateFormat 无害，局部变量本就线程安全），原修复反把它变成跨线程共享字段；`populate()` 用 2 线程池且 `inProgress` 有竞态窗口，并发 `mergeNotes()` 会踩坏 SimpleDateFormat | 回退为 `mergeNotes()` 内局部变量，删除共享字段 |
| **Q4 (HIGH·功能)** | `BookmarksData.java` | BUG 10 只堵了崩溃：`getBookmarksMap()` 是永远 `return null` 的 stub，导出书签到文件/Gmail 永远输出 "No bookmarks found"（崩溃前 Gmail 导出直接 NPE，这一点确实修掉了） | 真正实现 `getBookmarksMap()`：`getAll()` 按 `getPath()` 用 LinkedHashMap 分组，null path 跳过 |

### 二、新发现的 BUG（N1-N4）

| 编号 | 文件 | 问题 | 修复 |
|------|------|------|------|
| **N1 (HIGH)** | `ExtUtils.java` | `doifFileExists(Context, File)` 的 `Clouds.isCloud(file.getPath())` 在 `file != null` 检查**之前**执行（null 检查是死代码）；`doifFileExists(Context, String)` 传 null 时 `Clouds.isCloud(null)`（`path.startsWith`）直接 NPE | 两个重载入口加 null guard，null 直接返回 false |
| **N2 (HIGH)** | `BookmarksFragment2.java` | 书签长按查看详情 `new File(result.getPath())` 无 null 守卫（单击有守卫、长按没有），path 为 null 的书签长按即崩溃 | 长按回调加 `result == null \|\| result.getPath() == null` 守卫 |
| **N3 (MEDIUM)** | `BookmarksFragment2.java` | `onDeleteResponse` 的 `b.getPath().equals(path)` 任一为 null 即 NPE（后台线程直接崩应用）；`new File(result.getPath())` 同理；`countBookmarksForPath` 同样问题 | 改 `path != null && path.equals(b.getPath())` / `Objects.equals`；`sendBookmarksTo` 分支前判空 |
| **N4 (MEDIUM)** | `AppBookmark.java` `BookmarksAdapter2.java` `BookmarksFragment2.java` | 书籍标题行靠 `text.contains(" items")` 判定：用户书签名含 " items"（如 "100 items"）会被误判为标题行——点击变筛选、删除会删光该书全部书签 | `AppBookmark` 新增 `transient public boolean isBookHeader`（Objects 序列化跳过 transient，无兼容问题），`prepareDataInBackground` 创建标题行时置位；adapter 的 `isBookHeader()`、Fragment 的单击/删除判断全部改用该标志；删除已无引用的 `HEADER_SUFFIX` 常量 |

### 三、验证（MI9 真机，构建 + 安装 + 冒烟）

1. **构建**: `BUILD SUCCESSFUL in 36s`，93 tasks 全部通过
2. **安装启动**: push + `pm install -r -t` 成功，monkey 启动正常，`logcat -s AndroidRuntime:E` 零错误
3. **书签页冒烟**: 首页→书签笔记"更多"→按书视图 7 本书标题行渲染正常（isBookHeader 标志链路 ✓）
4. **筛选链路**: 点世界观标题行→筛选出"[2026-08-29 23:44] 笔记 (2)"（时间格式正确，局部 SimpleDateFormat ✓）+ 66% 快速书签（页码徽章 ✓），"返回"链接正常

---

## [2026-08-29] 代码检视修复 6 个隐含 BUG

### 根因分析过程

对 8 个修改过的源文件进行系统检视，发现 6 个 BUG（2 高/2 中/2 低）：

| BUG | 文件 | 类型 | 根因 |
|-----|------|------|------|
| 1 (HIGH) | `BookmarksFragment2.java:387` | NPE 崩溃 | 点击笔记时间时 `n.getPath()` 返回 null → `new File(null)` 崩溃 |
| 2 (HIGH) | `BookmarksData.java:192,215` | NPE 崩溃 | `syncBookmarksByBook` 在 `init()` 前为 null → IO 操作 NPE |
| 3 (MED) | `BookmarksFragment2.java:582` | 后台线程 UI 调用 | `mergeNotes()` 在 executor 线程调 `Fragment.getString()` |
| 4 (MED) | `BookmarksData.java:190,213` | 并发安全 | `saveByBook()`/`importByBook()` 无同步，非线程安全容器 |
| 5 (LOW) | `BookmarksFragment2.java:387` | 功能缺失 | `n.getPercent() > 0f` 跳过 position=0 的笔记 |
| 6 (LOW) | `AppProfile.java:496-499` | 状态不一致 | `clear()` 未重置 `syncBookmarksByBook` 静态字段 |

### 修复方案

| BUG | 改动 |
|-----|------|
| 1 | `BookmarksFragment2.java:387` 加 `n.getPath() != null &&` 守卫 |
| 2 | `BookmarksData.java:saveByBook/importByBook` 开头加 `if (AppProfile.syncBookmarksByBook == null) return;` |
| 3 | 新增字段 `readingNoteLabel`，`onCreateView` 主线程缓存，`mergeNotes` 改用缓存值 |
| 4 | `saveByBook()`/`importByBook()` 加 `synchronized` 关键字 |
| 5 | `n.getPercent() > 0f` → `n.getPercent() >= 0f` |
| 6 | `AppProfile.clear()` 末尾加 `syncBookmarksByBook = null;` |

### 验证

- Ubuntu 远程构建 librera Debug
- 真机确认：打开书签笔记 → 点击笔记时间 → 不崩溃、正确跳转
- 导出备份 → 不崩溃、按书分组文件正确写入

## [2026-08-30] WebDAV 按书信息同步 + 长按多选 + 多选假选中修复

### 一、WebDAV 同步重构（全局配置 + 每本书籍信息）

**同步内容**（不同步书籍文件本身）：
- `global/app-State.json`、`global/app-CSS.json`：全局配置双向同步（内容相同则跳过，不同时较新文件胜；`webdavLastSyncTime/Info` 等易变字段剔除后再比较，避免两台设备回声互覆）
- `books/<文件哈希>.json`：每本书一个"书籍信息"文件，含 `name`（书籍文件名）+ `hash`（书籍文件内容哈希）+ `t` + `progress`（阅读进度）+ `bookmarks`（书签与 AI 笔记，t 键映射）

**新增/修改文件**：

| 文件 | 改动 |
|------|------|
| `FileHash.java`（新增） | 基于系统内置 `java.security.MessageDigest`（MD5）的文件内容哈希；8KB 流式分块；按 `path+lastModified+length` 内存缓存，未变的书不重复计算；另提供文本 MD5（无本地文件时的稳定合成 ID） |
| `WebDavSyncer.java`（重写） | 远程布局改为 `global/` + `books/<hash>.json`；逐本恢复：每本信息文件独立 try/catch，一本损坏不影响其它；哈希关联：按名定位本地候选文件并计算哈希，**一致 → 完整关联**（进度按策略 newer/farther 合并、书签 t 并集且 path 改写为本地文件），**不一致（同名异书）→ 不覆盖本地进度**，仅书签并集保留"待关联"，无本地文件 → 进度照存（文件日后出现自动生效）；本地新书逐本上传（同名异书各自以自己的哈希共存）；旧 `progress.json`/`bookmarks.json` 一次性迁移后从服务器删除；`global` 同步、`resolveConfig`、错误分类（auth/ssl/network）、对话框与触发点全部保留 |
| `BookmarksData.importByBook()` | 按书循环体加独立 try/catch（备份 zip 恢复同样逐本容错） |

### 二、书库/最近页长按默认多选

| 文件 | 改动 |
|------|------|
| `SelectionBarController.java`（新增） | 从 SearchFragment2 抽取的共享多选栏（已选计数 + 标记已读/未读/在读 + 全选 + 取消），绑定 `selectionBar` 系列 id |
| `SearchFragment2.java` | 选择栏改用 SelectionBarController；长按书籍 → `startSelection`（目录保留原菜单）；返回键先退多选 |
| `RecentFragment2.java` | 新增多选能力（复用 FileMetaAdapter.selectionPaths + 控制器），长按 → 多选，标记已读状态走 BookStateStore，返回键先退多选 |
| `fragment_recent.xml` | 加入与书库相同结构的选择栏 |

### 三、多选"假选中"修复（根因修复）

`FileMetaAdapter.bindFileMetaView`：原来选中行设置高亮后**未选中分支不恢复背景**（默认 `isBorderAndShadow=true` 时整段跳过），ViewHolder 复用后旧高亮残留、`setBackgroundColor` 又永久覆盖涟漪背景。现改为每次绑定按模型推导：选中 → 高亮；未选中 → 依次按 OLED 黑 / `!isBorderAndShadow` 透明 / 恢复 `onCreateViewHolder` 捕获的默认涟漪背景（`FileMetaViewHolder.defaultBackground`）。书库/最近/我的文件所有页面同时生效。

### 四、验证（MI9 真机）

1. 构建 `BUILD SUCCESSFUL`，安装启动无崩溃（logcat 零 AndroidRuntime 错误）
2. 书库长按某书 → 选择栏出现"已选 1 本"，**仅该书高亮**；连续滚动两屏后可见 9 本书全部无高亮（复用不再泄漏选中色）→ 取消正常退出
3. 最近阅读长按 → 多选栏出现、仅选中项高亮 → 取消正常
4. WebDAV：无服务器环境验证不崩溃、无错误日志；对话框与触发点代码未动

## [2026-08-30] 木纹书架 + 我的文件条目编辑 + 图标文字加大

### 一、书库页面：纹理实木书架（静读天下风格）

| 文件 | 改动 |
|------|------|
| `WoodShelf.java`（新增） | 程序化木纹纹理，无图片资源依赖：固定随机种子的 512×512 tile——横向木板拼条、波浪木纹线、板缝深槽+受光边、每板轻微色差、偶发节疤；`BitmapShader(REPEAT)` 平铺，进程内缓存单例 |
| `SearchFragment2.java` | 封面/网格模式（MODE_COVERS/MODE_GRID）RecyclerView 背景设为木纹；列表/紧凑模式恢复默认 |
| `FileMetaAdapter.java` | 新增 `shelfMode` 标志（仅书库页）：封面/网格条目 CardView 透明（`setCardBackgroundColor(TRANSPARENT)`+卡片阴影归零），列表模式恢复原卡片色/阴影；每次绑定显式设置，回收复用安全；选中高亮不受影响 |

### 二、我的文件：OPDS / WEBDAV 条目可编辑

两个添加对话框本就内置编辑模式（传入已有条目 = 删旧行+存新行+持久化），我的文件页仅缺入口：

| 文件 | 改动 |
|------|------|
| `BrowseFragment2.java` | `netListItem` 增加可选 `onEdit` 参数（行尾铅笔图标 `my_glyphicons_pen`，与删除图标并排）；OPDS 行编辑：构造 `Entry{appState=原始行, logo}` → `AddCatalogDialog`（预填 URL/名称/描述）；WebDAV 行编辑：`AddWebDavDialog.showDialog(a, rebuild, srv)` 直接预填 URL/名称/凭据/信任证书；工具行无编辑图标 |

### 三、我的文件图标文字加大

| 位置 | 现值 → 新值 |
|------|------------|
| 条目行图标（OPDS/WEBDAV/工具行） | 22dp → 30dp |
| 条目行标题 | 15sp → 18sp |
| 编辑/删除图标 | 26dp → 30dp |
| 分区标题 / "+ 添加" | 16sp → 19sp / 15sp → 17sp |
| 书库文件夹行（仅我的文件根页） | FileMetaAdapter 新增 `myFilesRoot` 标志（displayAnyPath 按 ROOT_PATH 置位/复位，进入子目录自动还原）：text1 16→20sp、text2 12→14sp、文件夹图标 36→44dp |

### 四、验证（MI9 真机）

1. 构建 `BUILD SUCCESSFUL`，安装启动无崩溃（logcat 零 AndroidRuntime 错误）
2. 书库封面模式：木纹背景（板条/纹路/板缝清晰）+ 封面卡片透明直接"坐"在木架上；长按多选高亮在木纹上清晰可见且仅选中项着色（回归通过）
3. 我的文件：OPDS（Project Gutenberg）与 WebDAV（TestSync）行均出现铅笔编辑图标；点击 TestSync 铅笔弹出对话框且 URL/名称已预填；书库文件夹行图标文字明显变大

## [2026-08-30] 备份/同步状态信息补全 + 真实书架（木板随滚动）

### 一、状态信息审计与补全

**审计结论（本次修改前）**：

| 信息 | 存储 | zip 备份 | WebDAV 同步 |
|------|------|---------|------------|
| 书签/笔记、进度、全局配置、OPDS/WebDAV 列表 | profile 下 app-*.json | ✓ | ✓（books/ + global/） |
| 最近阅读 | app-Recent.json（真相源，DB 只是缓存） | 文件在 zip，但恢复后无主动 JSON→DB 同步 | ✗ 未同步 |
| 我的珍藏 | app-Favorite.json | 同上 | ✗ 未同步 |
| 已读/未读覆盖 | app-BookStates.json | ✓ | ✗ 未同步 |
| 阅读统计（总时长/本月/每日/页数） | **AppSP "AppTemp"**（readTimeMs 等） | ✗ 丢失 | ✗ 丢失 |
| AI 地址/模型/参数 | app-State.json | ✓ | ✓ |
| **AI API Key** | **SP "ai"**（AndroidKeyStore 加密） | ✗ 丢失 | ✗ 丢失 |

**补全实现**：

| 文件 | 改动 |
|------|------|
| `AppProfile.java` | 新增 `APP_STATS_JSON`/`APP_AI_JSON` 常量与 `syncStats`/`syncAI` 文件字段（clear() 一并置空） |
| `ProfileStateIO.java`（新增） | 状态文件读写/合并：统计镜像（AppSP read* ↔ app-Stats.json，数值取 max、月/日 bucket 逐 key max）；AI key 镜像（app-AI.json，非空者胜）；SimpleMeta 数组并集（app-Recent/Favorite 按 path 去重取 time 新者） |
| `PrefDialogs.exportDialog` | 打包前生成 app-Stats.json + app-AI.json 到设备 profile 目录（zipFolder 自动携带） |
| `PrefDialogs.importDialog` | 解压后：恢复统计/AI key，并**主动触发 JSON→DB**（getAllRecent/getAllFavoriteFiles/TagData.restoreTags），最近阅读、珍藏、统计、AI 配置重启后立即可见 |
| `WebDavSyncer` | global 组扩展：`app-Recent.json`、`app-Favorite.json`（数组并集）、`app-BookStates.json`（按 key 取 t 新者）、`app-Stats.json`（数值/bucket max）、`app-AI.json`（非空 key 胜，双向回填） |

安全说明：app-AI.json 内为明文 API Key（用户要求 AI 配置可完整恢复）；仅进用户自己的备份 zip / 私有 WebDAV 服务器。WebDAV 登录凭据因 AndroidKeyStore 设备绑定仍不导出。

### 二、真实书架（木板随滚动）

| 文件 | 改动 |
|------|------|
| `ShelfRowDecoration.java`（新增） | `RecyclerView.ItemDecoration`：每次布局按可见 child 位置分组画排木板（深木色 + 顶部高光 + 底部深线 + 木纹短线），坐标相对 child → **木板随上下滚动与书籍一起移动** |
| `SearchFragment2.applyBookshelfBackground()` | 封面/网格模式：木纹背景 + 挂载 ShelfRowDecoration；列表模式：移除 decoration 并还原 |

### 三、验证（MI9 真机）

1. 构建 `BUILD SUCCESSFUL`，安装启动无崩溃
2. 书库封面/网格模式：每排书下方一条木板（书本"立"在搁板上），滚动半屏后木板精确跟随各自排——真实书架效果达成
3. 备份/同步链路为编译期改动 + 既有对话框流程，真机冒烟无异常；WebDAV 端到端待服务器环境验证

## [2026-08-30] 备份配置项清单化补全 + 选中可见性 + 软件说明联系方式 + 书架回退

### 一、备份/同步配置项完整清单（现状）

| 类别 | 内容 | 载体 | zip 备份 | WebDAV 同步 |
|------|------|------|---------|------------|
| 书签/笔记 | 全部书签 + AI 笔记 | app-Bookmarks.json、app-BookmarksByBook.json | ✓ | ✓（books/按书） |
| 阅读进度 | 每本书位置 + 视图状态 | app-Progress.json | ✓ | ✓（books/按书） |
| 已读状态 | 已读/未读/在读覆盖 | app-BookStates.json | ✓ | ✓（global/） |
| 最近阅读 | 最近列表 | app-Recent.json | ✓ | ✓（global/并集） |
| 我的珍藏 | 收藏列表 | app-Favorite.json | ✓ | ✓（global/并集） |
| 阅读统计 | 总时长/今日/页数/月表/日表 | app-Stats.json（镜像 AppSP） | ✓ | ✓（global/取大） |
| AI 配置 | 协议/地址/模型/参数 | app-State.json | ✓ | ✓ |
| AI API Key | 密钥 | app-AI.json | ✓ | ✓（非空者胜） |
| 全局设置 | 主题/排序/过滤/webdav/opds 列表等全部 AppState 字段 | app-State.json | ✓ | ✓（global/较新胜） |
| 排版样式 | BookCSS 全部字段 + 书库文件夹/SAF 路径 | app-CSS.json | ✓ | ✓（global/较新胜） |
| 排除列表/标签/替换规则/词典/播放列表 | — | app-Exclude/Tags/Tags2/TextReplacement/WebDict/WebSearch.json | ✓ | 部分（随 profile json，global 未单列） |
| **AppSP 全量**（最后一本书/阅读模式/同步开关等） | — | **app-Misc.json（新增）** | ✓ | ✓（global/按段并集） |
| **OPDS 服务器登录凭据** | — | **app-Misc.json（新增）** | ✓ | ✓ |
| **应用/书籍密码**（PasswordState） | — | **app-Misc.json（新增）** | ✓ | ✓ |
| **阅读器按钮布局**（DraggingPopups） | — | **app-Misc.json（新增）** | ✓ | ✓ |
| WebDAV 登录凭据 | SP "webdav"（AndroidKeyStore 设备绑定） | — | ✗ 设计上不导出（密文跨设备不可解） | ✗ |

本轮改动：`app-Misc.json`（ProfileStateIO.exportMisc/importMisc：AppSP 全量快照 + OPDS 登录 + PasswordState + DragingPopups 四段，恢复时逐段写回）；zip 导出/导入与 WebDAV 同步（按段"本地非空优先"并集）均已接入。**仍不备份**：WebDAV 凭据（加密密文设备绑定，无恢复价值）、诊断/缓存类 SP（Errors、lastmodified2 等，非用户配置）。

### 二、软件说明联系方式

- `values/config.xml`：`my_email` → `380121850@163.com`；`my_site` → 留空
- `AboutSectionBinder`：官网为空时隐藏"网页"链接（邮箱行保留，点击发信到新邮箱）

### 三、多选选中状态可见性修复

选中书籍的半透明底色被不透明封面盖住（尤其封面/网格模式）。`FileMetaAdapter` 绑定时为选中项增加**卡片前景叠加**（`setForeground`，API 23+）：半透明主题色蒙层 + 3dp 描边，绘制在封面之上，任何显示模式下选中一目了然；取消选中恢复无前景。真机验证：长按进入多选后选中书封面整体蒙层+描边清晰可见。

### 四、书架回退

按需求移除上一轮的"每排木板"（ShelfRowDecoration 已删除、SearchFragment2 不再挂载），书库恢复为木纹平铺背景 + 封面卡片透明的效果（修改前状态）。

### 五、验证（MI9 真机）

构建 `BUILD SUCCESSFUL`，安装启动无崩溃；多选选中封面叠加+描边清晰；书架为纯木纹背景。



## [2026-08-30] 大文件打开速度优化（两阶段：缓存保活 + 静读天下式分阶段排版）

针对 20-30MB 大书"每次打开都慢"的问题，分两个阶段实施，MI9 真机全量验证。

### 一、第一阶段：缓存保活 + 主线程瘦身（Java 层）

根因：TXT 每次打开清空全部缓存目录、每次打开书删除其它书全部转换产物，导致 MuPDF accel 排版缓存永远无法命中 → 每次全量转换 + 全文排版。另有多处主线程重复劳动。

- `TxtContext`：删除打开时的 emptyAllCacheDirs()（不再殃及全部缓存）
- `TxtExtract.extract1`：txt→fb2 全量转换结果缓存化（key 含路径+连字符/语言/编码设置，tmp+rename 防半文件）
- `AbstractCodecContext`：转换缓存"删其它全部"→ LRU 保留最近 4 本（含同名 .json 脚注缓存）；`CacheZipUtils.trimFiles/trimAccel` 新增
- `MuPdfDocument`：accel 文件 LRU 上限 8 个；accel 键加入文件 salt（length+lastModified），书文件更新后不再复用过期页数
- `VerticalViewActivity/ViewerActivityController`：主线程移除重复元数据提取（checkOrCreateMetaInfo/createMetaIfNeed 二选一）与 detectLang，全部移入 BookLoadTask 后台线程；addRecent（DB+JSON 写）移入后台
- `BookLoadTask`：SERIAL_EXECUTOR → THREAD_POOL_EXECUTOR（native 访问已有 TempHolder.lock 串行化）
- 新增 `BookWarmer`：后台空闲预热 MuPDF accel（最近阅读书 + 扫描新书，阅读器前台时自动让位）

### 二、第二阶段：静读天下式分阶段排版（C 层 + Java 层）

首屏不再等待全书排版：利用 mupdf 1.23.7 公开 API `fz_count_chapters/fz_count_chapter_pages`（EPUB 按章惰性排版），打开时仅排版到上次阅读位置即显示第一屏，其余章节后台补齐。

- `Builder/jni/libmupdf-librera.c`：新增 JNI `getPageCountProgressive(handle,w,h,em,uptoPage)` —— 逐章排版累计至 uptoPage 即停（fz_save_accelerator 保存部分页数）；无章节支持的格式回退全量计数；已重建 arm64 libMuPDF.so
- `CodecDocument/DecodeService/DecodeServiceBase/MuPdfDocument`：渐进计数管道（默认实现 = 全量计数；仅重排格式启用）
- `DocumentModel`：`setProgressiveUpto` + `appendPages`（尾追加页，既有页 bounds 不动 → 滚动位置与页码天然稳定）；渐进路径跳过 PageCacheFile 读写
- `AppBook`：新增 `pg`（绝对页码锚点，随进度 JSON 自动持久化）；旧进度无 pg 时用书库 DB 历史页数×百分比估算目标；新书（p=0）只排前 150 页
- `AbstractViewController.show()`：优先按 pg 恢复位置（比百分比换算更精确）
- `ViewerActivityController`：二阶段编排 —— 首屏上屏后后台完成全量排版 → `appendPages` 尾部扩容 → `invalidatePageSizes(PAGE_LOADED)` 增量堆叠 → 进度条/页码/刻度刷新（`DocumentWrapperUI.refreshPageCount`）
- `Fb2Context`：打开时的损坏探测由全量 getPageCount 改为仅排第 1 章（原实现使 TXT/FB2 的渐进排版完全失效）
- 设置项：`AppState.isFastOpen`（默认开）；EXTRA_PERCENT 入口自动走全量路径

关键修复（mupdf 内部日志验证）：排版 em 参数两种单位（sp/px）并存导致 accel 每次打开失效 —— 渐进路径统一 sp→px 与全量计数一致；Fb2Context 探针使 TXT 合成 EPUB 退化为单巨章时首屏仍可控。

### 三、MI9 真机收益（22-29MB 测试书，单位秒）

| 格式 | 打开 | 优化前 | 优化后 | 提升 |
|---|---|---|---|---|
| EPUB | 热 | 0.35 | **0.17** | 2× |
| TXT | 热 | 27.0~29.6（缓存永不命中） | **0.085** | ~300× |
| FB2 | 热 | 0.20 | **0.078** | 2.6× |
| PDF | 冷 | 0.20 | 0.20 | 持平 |
| EPUB | 冷 | 17.2 | **7.1**（首屏；余量后台补齐） | 2.4× |
| TXT/FB2 | 冷 | 27~29 | 28.5/11.0（转换 O(文件) 固有，之后走缓存） | — |

注：冷打开耗时主要为 O(文件) 的格式转换（TXT 双重转换、EPUB 连字符/脚注全量重写——与用户开启的排版特性相关），首次之后全部命中缓存；epub 冷打开的全书排版移至首屏之后的后台完成。已知边界：TXT/FB2 合成 EPUB 为单巨章，首屏渲染需整章排版（约数秒，属 mupdf fz_store 行为）——拆分 spine 章节列为后续优化项。

### 四、验证

MI9（48fee174）：四格式冷/热全矩阵计时通过；滚动渲染、进度恢复（pg 锚点精确恢复）、多书缓存共存（LRU-4）、二阶段后台补全后页码/进度条自动校正、退出重开零崩溃（logcat crash buffer 无 FATAL）；书库/最近阅读/书签笔记等既有功能回归正常。

## [2026-08-30] MOBI 打开修复 + 二阶段追加页数坍缩 BUG 修复 + TXT 单遍直转 EPUB

### 一、二阶段打开后总页数坍缩（MOBI 二次打开"只剩一页"，复现并修复）

复现：MOBI《2014中日战争》第一次打开 1613 页正常，第二次打开进度条变为 29/108（总页数坍缩到渐进边界）。
根因：`startPhaseTwoLayout` 在 `appendPages` **之前**取"第一个新页"作为增量重排标记——此时该页尚不存在（`getPageObject` 返回 null），`append && marker != null` 被 `&&` 短路，导致新页 bounds 未重排、页码/进度条 UI 未刷新：画布被钳在旧边界，页数显示坍缩（EPUB 同样潜在受影响）。
修复：改为 append 之后再取标记页（用第一段最后一页作重排起点），重排 + UI 刷新必达。修复后二次打开恢复 29/1613，加载 108ms。

### 二、MOBI 每次打开都重新整本转换（修复）

`MobiContext` 缓存命中判断的文件名（双 hash）与转换输出名（单 hash）不一致，自动连字符关闭时命中分支永不成立——每次打开都无条件 `convertToEpub` 整本转换，且转换文件 mtime 变化连带 accel 键失效、全文重排版。现统一：转换直接落到缓存文件名（缓存存在即复用），转换只发生一次。

### 三、TXT 单遍直转 EPUB（冷打开 28.5s → 12.0s，且按章分章）

旧链路 txt→fb2→epub 两次全量遍历，且章节启发式不识别中文标题 → 合成 EPUB 单巨章（渐进排版与首屏渲染都退化为整本粒度）。
新增 `TxtExtract.extractEpub`：单遍读入直接流式写出合成 EPUB；章节断点在原 chapter/глава 规则上新增中文规则（行首 `第X章/节/回/卷/部/篇/集`、序章/楔子/尾声/番外/前言/后记等，X 支持中文数字），每章一个 spine 项 → mupdf 按章惰性排版/渲染。缓存 key 含全部设置维度与规则版本，tmp+rename 防半文件。`TxtContext` 接入（保留第 1 章损坏探针）；isPreText 分支不变。
实测（25MB TXT）：冷打开 28.5s → **12.0s**（转换约 10s + 首章排版）；热打开 2.8s；首屏渲染不再等待整本。

### 四、周边

- 转换缓存 LRU 保留 4 → 8 本（多书轮换少互踢；注：MIUI 上每次重装 APK 会清应用缓存，重装后首次打开属冷打开，为设备行为非应用问题）。
- 基准说明：bench 书"再次变慢"即上述 LRU 淘汰/重装清缓存所致，冷打开成本为 O(文件) 的格式转换固有成本，每本书只付一次。

### 五、验证（MI9 真机）

MOBI《2014中日战争》：一次 567ms（1613 页）/ 二次 108ms 且页数完整、可翻全本；TXT 冷 12.0s、热 2.8s（28038 页，渲染与章节标题正确）；EPUB 热 193ms、FB2 热 78ms 回归正常；logcat crash buffer 无 FATAL。

## [2026-08-30] 二阶段补全长锁独占修复（退出卡死/首屏空页/概率打不开）

### 根因（真机线程时序定位）

二阶段补全与 BookWarmer 预热各用**一次 native 调用**完成全量计数，大书要持有全局锁 `TempHolder.lock` 10~40 秒（ReentrantLock 非公平，循环立即重抢锁会把等锁的主线程饿死）。后果：翻页解码排队（首屏长时间空白）、返回键在主线程排队 30+ 秒才被处理（体感"退出卡死"）、期间打开其它书（FB2"概率打不开"、侯卫东再开变慢）、退出时 `freeDocument` 等锁。

### 修复

1. **补全分块化**：`startPhaseTwoLayout` 改为每轮 `getPageCountProgressive(已排+400)` 的循环（约 400 页/百 ms 级），轮间释放锁并让路——`TempHolder.lock.hasQueuedThreads()` 为真时每次让出 100ms，保证 UI/解码线程优先拿锁。
2. **可取消**：`AtomicLong phase2Gen` 代号，`VerticalViewActivity/HorizontalViewActivity.onDestroy → cancelPhase2()`（发现 controller 的 onDestroy/beforeDestroy 在此 fork 中无调用方，取消必须挂在 Activity 生命周期上）；back = moveTaskToBack 的场景由循环内 `isDestroyed/isFinishing` 自检兜底；`onStart → resumePhase2()`、`onStop → pausePhase2()` 暂停/恢复（后台不再空转占锁）。
3. **BookWarmer 分块化**：同样 400 页步进 + 等锁让路 + readerActive 立即让出。
4. **MuPdfDocument.getPageCountProgressive**：去掉内部"失败回退全量计数"（保证分块单次调用有界；主打开路径的回退保留在 DocumentModel）。
5. 二阶段启动延时 800ms → 2000ms，首屏位图解码优先于补全。

### MI9 真机验证

- TXT 冷打开后二阶段进行中按返回：**~1 秒内完成退出**（修复前主线程饿死 30 秒+），补全任务即时取消并丢弃部分进度（无 UI 污染）；
- 回到阅读器（onStart）补全自动恢复，28038 页全部补齐；
- EPUB 冷打开首屏正常（恢复位置若为章末页，页面本身内容少属书籍内容而非缺陷）；EPUB 热 193~233ms；TXT 热 2.8s；FB2 热 78ms；
- MOBI《2014中日战争》两连开 742ms/611ms，总页数 1613 完整（回归通过）；
- logcat crash buffer 无 FATAL。

## [2026-08-30] 打开空页根治 + 书库文件夹选择器修复 + 书库滚动位置记忆

### 一、打开书出现空页 → 加载框保持到首屏整屏解码完成(根治)

两段修复:

1. **FirstPaintGate(首屏门闩)**:新增 `com/foobnix/sys/FirstPaintGate.java`。「请稍候」加载框原先在排版完成时即关闭(`BaseAsyncTask.onPostExecute`),位图尚未解码,用户先看到空白占位页。现在成功路径持框直到首屏解码齐(500ms 静默期 + 8s 硬上限 + 2s 无解码判定),`BaseAsyncTask` 增加 `holdProgressDialog` 跳过自动关闭;`PageTreeNode.decodeComplete` 喂给门闩;`VerticalViewActivity.onDestroy`/`ViewerActivityController.onDestroy` 兜底取消;密码/错误/手动取消路径行为不变。

2. **首屏第二页空白 5~15 秒的真正根因(三处)**:
   - **目录(Outline)抢占解码线程**:`startDecoding` 回调里的 `loadOutline()` 在渐进打开后立即执行,`getOutline()` 的 native 调用会强制排版全书(24MB 书 10~15 秒),期间解码执行器被独占,第二屏解码全部排队。修复:渐进模式把 outline 延迟到 phase-two 排版完成后再加载(届时毫秒级);非渐进格式(PDF 等)保持原行为(`loadOutlineOnce()`,phase2 成功/`knownCount<=0` 兜底触发)。
   - **节点回收取消在途解码**:`AbstractEventScroll.process(node)` 对「不在内存保留范围」的节点直接 recycle→stopDecoding;渐进打开初期页面尚无真实边界,刚排队的第二屏解码被后续布局事件取消。修复:`decodingNow` 为 true 的节点跳过 recycle。
   - **页级回收同样绕过守卫**:`AbstractEvent.process(Page)` 的 `recyclePage` 增加相同守卫(根节点在解码中则不回收该页)。
   - 附带加固:`DecodeServiceBase.tasks` 改为 `Collections.synchronizedList`(原先裸 ArrayList 被 UI 线程与消费线程并发读写);phase-two 与 BookWarmer 移出 2 线程共享池 `AppsConfig.executorService`(改独立线程,解码消费者常驻该池),phase2 线程用 `THREAD_PRIORITY_LESS_FAVORABLE`(BACKGROUND 的 cgroup 会被 MIUI 限速)。

**MI9 实测**(big25.epub 冷开):排版完成 → 首屏 3 个节点 **230ms 内连续解码完成** → 加载框 807ms 时关闭、内容整屏出现,不再有「页 N」空白占位;之前第二页空白 5~15 秒。TXT 冷开(转换 13s)同样 825ms 整屏出现。

### 二、「我的文件 → 书库文件夹添加」无本地存储路径选择 → 修复

**根因**:我的文件根页是伪路径 `my-files:`,`displayAnyPath` 把它无条件写入 `BookCSS.dirLastPath`;「添加文件夹」把这个伪路径当初始目录传给 ChooserDialogFragment,弹出的还是伪根页(只有书库文件夹列表),看不到真实文件系统,确认按钮也报「值不正确」。

修复:
- `ChooserDialogFragment.chooseFolder` 内部对初始路径做净化(新增 `validStartDir`:非真实本地目录一律回退到机身存储根),所有调用点(我的文件、偏好里的书库文件夹配置)一并修好;
- `BrowseFragment2.displayAnyPath` 只有真实本地目录才写入 `dirLastPath`(同时防 OPDS/content 路径污染);
- 选择器弹窗内启用存储快捷入口 chips(机身存储/Download/SD 卡/Librera 下载,复用 `buildQuickDirChips`,`TYPE_SELECT_FOLDER` 也构建)。

**MI9 实测**:添加文件夹 → 选择器打开在真实文件系统(Alarms/Android/Download…)、chips 可见可点、进入 Download → 选择 → 文件夹入库、列表即时刷新。

### 三、书库页面记住浏览位置

`SearchFragment2`:书库列表(网格/封面/列表模式)滚动停止时记录首个可见项位置+偏移到独立 SharedPreferences(`lib_scroll`),key=`模式|过滤文本|排序|方向|数据 hash`——搜索词/排序/数据集变化自动失效归零,不会串位置;`populateDataInUI` 重灌后 `scrollToPositionWithOffset` 恢复(含 StaggeredGrid 变体);分组模式(作者/系列等)保留原有 rememberPos 逻辑。

**MI9 实测**:书库滚到中部 → 切首页再切回 → 位置精确恢复(截图逐像素一致)。

### 四、AI 大模型配置与备份/同步(检查结论,无代码改动)

备份(`PrefDialogs.exportDialog` 打包 `profile.*/<设备>/`)已包含 `app-AI.json`(API Key,`ProfileStateIO.exportAi`)与 `app-State.json`(aiProtocol/aiBaseUrl/aiModel/aiMaxTokens/aiThinking);WebDAV 同步同样覆盖(`WebDavSyncer` exportAi/syncMergedObjectFile/importAi,双方非空 Key 者胜)。**AI 配置已随备份/同步走,无需修改。**

### MI9 真机验证汇总

- EPUB 冷开:整屏 0.8s 内随加载框关闭一起出现,无空白占位页(修复前第二页空 5~15s);
- TXT 冷开 + 阅读中返回退出:~1s 完成,无卡死(phase2 正常补全 14154 页);
- MOBI《2014中日战争》冷开 749ms/热开 141ms,两开页数均完整 1613(回归通过);
- 我的文件 → 书库文件夹添加:选择器真实路径 + 存储 chips + 添加成功;
- 书库滚动位置:切标签往返精确保留;
- logcat crash buffer 无 FATAL。

## [2026-08-30] 书库滚动记忆补充修复:从阅读器返回书库也恢复位置

上一轮的滚动记忆 key 里包含数据版本号(`TempHolder.listHash`),而**阅读一本书**会更新最后阅读时间/阅读状态并使 `listHash++`——返回书库触发 `resetFragment` 重灌数据,key 失效 → 回到顶部。表现为「书库打开书再返回,位置丢失」。

修复:`SearchFragment2.buildLibScrollKey()` 去掉 `listHash` 维度,只保留 模式|过滤文本|排序|方向——阅读返回照常恢复;换搜索词/排序/阅读模式仍正常失效归零。

**MI9 实测**:书库滚到中部 → 点开《映画周星驰》(恢复到该书上次阅读位)→ 关闭阅读器 → 书库精确停回原浏览位置(前后截图一致)。

## [2026-08-30] 书库新增「书架」视图模式(仿静读天下/Moon+ Reader)

### 功能
- 书库查看菜单新增「书架」模式(`AppState.MODE_SHELF = 13`):每行固定 3 本书(平板按屏宽增加),木质书架背景上每行下方绘制木板与落影,封面直立在木板上,视觉对齐 Moon+ Reader 书架。
- 封面左下角圆形阅读进度角标(≥1% 才显示,与 Moon+ 一致);右下角 ⋮ 按钮直接弹出单书操作菜单(与长按菜单同一回调)。
- 无封面书籍的占位封面改为显示书名(解析 PageUrl 取真实路径,用文件名去扩展名),替换原先的 "#error null" 字样;该改进对 列表/网格/封面 模式同样生效。

### 改动文件
| 文件 | 改动 |
| --- | --- |
| `model/AppState.java` | 新增 `MODE_SHELF = 13` |
| `ui2/fragment/SearchFragment2.java` | 查看菜单加「书架」项;`applyBookshelfBackground` 扩展(书架模式加挂木板装饰、其余模式摘除);onTextChanged / prepareDataInBackground / populateDataInUI / saveLibScrollPosition 四处扁平模式白名单加入 MODE_SHELF(搜索、排序、滚动位置记忆在书架模式下全功能可用) |
| `ui2/fragment/UIFragment.java` | `onGridList` 新增书架分支:`GridLayoutManager` 列数 `max(3, 屏宽dp/120)`,分组标题类条目跨全列 |
| `ui2/adapter/FileMetaAdapter.java` | 新增 `ADAPTER_SHELF = 5`;onCreateViewHolder 选择新布局;书架封面固定槽位尺寸(宽=(屏宽-30dp)/列数,高=宽×1.4 即 WIDTH_DK);进度角标文本/可见性与 ⋮ 菜单点击绑定(setInkTextView 之后执行,保证白字);shelfMode 透明卡条件扩展 |
| `res/layout/browse_item_shelf.xml` | 新建书架 item 布局:封面 + 左下进度圆标(shelfBadge/idPercentText)+ 右下 ⋮(shelfMenu),隐藏文字区但保留全部既有 id(复用 FileMetaViewHolder,不空指针) |
| `res/drawable/shelf_badge_bg.xml` | 新建角标/⋮ 半透明黑圆底 |
| `ui2/ShelfBoardsDecoration.java` | 新建:行木板 ItemDecoration(渐变木板 + 顶部亮边 + 底部暗线 + 板上方 8dp 落影,按行底 Y 去重通宽绘制) |
| `pdf/info/wrapper/PopupHelper.java` | `updateGridOrListIcon` 加 MODE_SHELF 分支(glyphicons_422_book_library 图标) |
| `sys/LibreraAppGlideModule.java` | 封面提取失败时的占位图标题改为书名(新增 `coverTitle()`,替换 "#error null"/"#error") |
| `strings.xml` ×3 | 新增 `shelf` = Bookshelf / 书架 / 書架 |

### 验证(MI9 真机)
- 书架模式渲染:3 本/行、木板、进度角标(6%/100% 完整显示,0% 隐藏)、⋮ 菜单弹出完整书籍操作、无封面书显示书名占位。
- 打开《悲惨世界》恢复到 3545/3545 页;关闭返回后书库浏览位置保持(不跳回顶部)。
- 切回 封面/网格 模式:木纹背景正常、无木板无角标,无回归;模式选择随 AppState 持久化,重启后保持。

## [2026-08-30] 书架模式打磨:加厚木板、木纹随内容滚动、胡桃木纹理

- **木板加厚**:13dp → 20dp(顶部亮边 2dp、底部暗线 3dp、板上方落影 9dp),书立在板上的立体感更强。
- **修复背景不随内容滚动**:书架模式的木纹改由 `ShelfBoardsDecoration` 在内容坐标系绘制(BitmapShader 锚定内容原点),书籍、木板、木纹三者一起滚动;`applyBookshelfBackground` 中书架模式不再设置固定背景。封面/网格模式保持固定木纹背景不变。
- **胡桃木纹理**:`WoodShelf` 换为深棕胡桃木(基调 #5C4232/#543A2B/#664834),纹理线更细密近直、板缝亮边更低调;新增 `WoodShelf.tile()` 供装饰层共用同一张 tile,板面与背景纹理无缝衔接。
- 验证(MI9 真机,像素级对照):内容滚动 846px 后,书条带差值 0.87、行间木纹带差值 0.61(完全随动),若按"固定背景"对齐则差值 113.8(错位);不滚动的工具栏区域位移 0 处差值 0.0(对照组)。

涉及:`ui2/WoodShelf.java`、`ui2/ShelfBoardsDecoration.java`、`ui2/fragment/SearchFragment2.java`。

## [2026-08-30] 书架纹理调浅+整块木板纹理;修复 WebDAV 同步后 AI 模型配置丢失

### 书架视觉(应用户反馈)
- 木纹背景调浅:深胡桃 #5C4232 一系 → 浅胡桃 #94745A 一系。
- 取消横纹:不再按 128px 画板缝与每板色带,改为**一整块木板**——纯竖向细密木纹 + 少量木节;纹理线在 tile 高度上按整周期绘制、横向不出界,双向无缝平铺(`WoodShelf.makeTile` 重写)。
- 木板装饰配色适配浅色底:亮边 #C29A6E、底线 #4A3018。

### WebDAV 同步 AI 模型配置丢失(修复)
AI 模型配置(aiProtocol/aiBaseUrl/aiModel/aiMaxTokens/aiThinking)存在 `app-State.json`,此前同步规则是"文件修改时间新者胜",存在两个问题:
1. **重置后本机文件永远"更新"**:刚生成的本地 app-State.json 修改时间最新,同步反而把空配置上传覆盖服务器副本,AI 配置永远同步不下来。
2. **同步不应用到运行中的 APP**:即使远端赢,同步只写文件不刷新内存 AppState;同步结束时的 `AppState.save()` 又把内存旧状态写回文件。

修复:
- `ProfileStateIO.mergeAiState`:app-State.json 同步时对 5 个 AI 字段做字段级并集——一边设置、一边未设置 → 取设置过的值;两边都设置且不同 → 新的一方胜出;`WebDavSyncer.syncGlobalFile` 在两种胜负路径都写入/上传合并结果(双向收敛)。
- 新增 `ProfileStateIO.importAppState`:同步完成后把合并后的 app-State.json 原位加载进运行中的 AppState(`Objects.loadFromJson`),配置立即生效,并避免同步末尾的回写覆盖。

涉及:`ui2/WoodShelf.java`、`ui2/ShelfBoardsDecoration.java`、`model/ProfileStateIO.java`、`webdav/WebDavSyncer.java`。真机验证:书架纹理视觉已核对;WebDAV AI 同步请在用户自己的服务器上按"重置 → 配 WebDAV → 同步 → 查看 AI 配置"流程确认。

## [2026-08-30] 修复 WebDAV 同步后阅读统计未应用到本机

**问题**:统计数据(总阅读时长/页数/月度与日度桶,存于 AppSP)的同步链路在服务器侧是对的(`mergeStats` 按字段取最大值合并、合并结果写回本地并上传),但每台设备同步完后看到的仍是自己的旧统计:合并刚写入内存后,`importMisc`(恢复杂项配置)会用 app-Misc.json 里的 AppSP 整体快照覆盖内存——而该快照导出于统计合并**之前**;且 `importStats`(重新应用合并后的 app-Stats.json 并持久化)在同步流程中从未被调用(仅手动备份还原使用)。

**修复**:`WebDavSyncer.doSync` 在 `importMisc` 之后调用 `ProfileStateIO.importStats(c)`,重新应用合并后的 app-Stats.json(applyStats 为幂等的最大值合并)并 `AppSP.get().save()` 持久化。重置场景同样覆盖:重置后本机统计为零,同步即取回服务器上的累计统计。

涉及:`webdav/WebDavSyncer.java`(一行调用 + 注释)。请在用户自己的 WebDAV 服务器上按"重置 → 配 WebDAV → 同步 → 查看首页阅读统计"流程确认。

## [2026-08-30] 应用品牌更名 HowRead(好好读)+ 更换包名与签名

### 品牌定稿
- 中文主品牌:好好读;英文主品牌:HowRead("How"=好(谐音)+ How(如何)——如何好好读一篇文字);
- 免费版 HowRead(好好读);专业版 HowRead Pro(好好读 Pro);
- Android 包名 com.howread.reader;域名 howread.app / github.com/howread。

### 1. 显示名资源化(支持中英双语显示)
- Manifest 3 处 label(application + 2 widget)由 `${appName}` 占位符改为 `@string/app_name`;
- main 资源:values="HowRead"、values-zh-rCN="好好读"、values-zh-rTW="好好讀";
- 新增各 flavor 覆盖资源 app/src/<flavor>/res/values[-zh]/strings.xml:pro="HowRead Pro"(好好读 Pro)、fdroid="HowRead FD"(好好读 FD)、pdf_classic/pdf_v2="PDF Reader"、ebooka="Book Reader"、tts_reader="TTS Reader"、epub_reader="Epub Reader";
- build.gradle 中 appName 占位符定义保留(已无引用)。

### 2. 包名 applicationId(8 处,app/build.gradle)
- librera→com.howread.reader;pro/fdroid→com.howread.reader.pro(同包,与原布局一致);pdf_classic→com.howread.reader.classic;ebooka→com.howread.reader.book;pdf_v2→com.howread.reader.pdf;tts_reader→com.howread.reader.tts;epub_reader→com.howread.reader.epub;
- 连改硬编码:AppsConfig.java(PRO_LIBRERA_READER/LIBRERA_READER 常量值,常量名不变)、Urls.java openPdfPro 商店链接;
- 代码 namespace(com.foobnix.*)、LibreraApp 类名、org.librera 包、meta-data、deep-link 全部不动。

### 3. 签名(新 keystore)
- 新增 `howread.keystore`(PKCS12,别名 howread,密码 850318@Hz,有效期 10000 天);
- `~/.gradle/gradle.properties` 四项指向新 keystore;旧 keystore.pkcs12 保留;代码零改动。

### 4. 界面文案(只改值不改 key,44 个语言目录)
- 5 个品牌 key 中 "Librera"→"HowRead"(librera_pro/librera_cloud/close_book_and_application/librera_pro_no_ads_leading_book_book_reader_and_pdf/pro_pdf_description_ads_free);
- **保留** msg_migration/msg_sync 的"[Librera]"(指向真实本地目录名)、dialog_proxy_server"Dowloads/Librera"、dialog_webdav_sync"/Librera";
- URL 换新域:about_section.xml(librera.mobi/beta/faq → howread.app)、config.xml wiki_url、Urls.java rateIT GitHub 链接 → github.com/howread;
- 清理调试残留 fragment_preferences.xml"Librera_111"→"HowRead"。

### 5. 构建元数据
- APK 文件名前缀 "Librera " → "HowRead "(build.gradle 输出名两处);
- settings.gradle.kts rootProject.name → "HowRead"。

### 验证(MI9 真机)
- 新包 com.howread.reader 9.4.24 安装成功(MIUI 首装被 INSTALL_FAILED_USER_RESTRICTED 拦截,`pm install -i com.android.vending` 绕过);
- 桌面图标显示**好好读**(中文),与旧版 Librera 并存;应用启动、首页/书库正常;
- 构建产物名:HowRead Librera-9.4.24-arm64.apk。
- 待办提醒:release 正式包请先用新签名试装一次;howread.app 网站内容需自行部署。
# 2026-08-30 HowRead 0.9.0：目录换新 + release 试装

## 1. 真实目录 "Librera" 全部换新（方案 A：换新 + 自动迁移）
- 本机存储根目录 `/sdcard/Librera` → `/sdcard/HowRead`：`AppSP.getRootDir()` 改名；`AppSP.init()` 新增一次性迁移——持久化 `rootPath1` 仍等于旧默认时自动切到新根（用户自定义路径不动）。库 DB 文件名含根路径 hash，自动重建后重扫。
- 下载目录族 `Download/Librera` → `Download/HowRead`：`BookCSS` 的 downloads/Cache/TTS/Backup/三个云缓存路径默认值改名；`load1()` 读取持久化配置后调用新增 `migrateLegacyDownloadPaths()`（仅当存量值 == 旧默认才跟随迁移）。
- WebDAV 远程目录 `/Librera` → `/HowRead`：`AppState.webdavSyncRemoteDir` 默认值改名 + `loadInit()` 一次性迁移 + `remoteDir()` 读取兜底（覆盖从旧 app-State.json 同步回 "Librera" 的情况）。
- WebDavSyncer 新增 `importLegacyRemoteDir()`/`copyRemoteTree()`：同步开始时若新目录为空且服务器上旧 `/Librera` 存在，逐文件 GET→PUT **复制**导入（不删旧目录，旧版 Librera 应用同步不受影响）。
- Drive 根目录：`GFile.findLibreraSync()` 只查 "HowRead"、缺失才创建（**不**采用旧 "Librera" 目录，避免新旧两个应用共写同一个 Drive 文件夹）；数据搬迁由 WebDAV 导入承担。
- UI 字样：我的文件菜单 "HowRead/下载"、"HowRead/Sync"（BrowseFragment2 三处）；PopupHelper 图标着色跳过判断 contains("HowRead")；代理对话框占位文本 "Downloads/HowRead"（顺带修正 Dowloads 拼写）；44 个语言的 msg_migration/msg_sync "[Librera]"→"HowRead"。
- 刻意保留：内部 profile 名 "Librera"；`BookCSS.LIBRERA_CLOUD_*` 常量值与 `Clouds.isCloudImage` 的 "Librera.Cloud" 判断（持久化云书籍路径路由依赖字面值）；在线同步目录 `/Librera.Cloud`；cloudrail OAuth 回调 URL；ExportConverter 旧播放列表迁移源 `Librera/Playlist`。

## 2. 版本 0.9.0 + APK 文件名
- `app/gradle.properties`（版本号真实来源，在仓库内）：appVersionNumberBase=0.9、appVersionNumberIndex=0 → versionName **0.9.0**；appCodeNumber 7190→7198（保证升级 versionCode 单调增）。
- `app/build.gradle` APK 模板：librera（主品牌）flavor 去掉 flavor 段 → **HowRead-0.9.0-arm64.apk / HowRead-0.9.0-uni.apk**（文件名不再含空格）；其余 flavor 保留标签 HowRead-Pro-…、HowRead-Fdroid-… 等。
- 8 处 manifestPlaceholders appName 对齐品牌：librera="HowRead"、fdroid="HowRead FD"、pro="HowRead Pro"（马甲包名不变）。
- "Librera_111" 调试残留：仓库已无此字符串（About 页"引擎"行运行时显示真实 MuPDF 版本，如 1.23.7-librera）。

## 3. 链接统一指向 https://github.com/380121850/LibreraReader
- about_section.xml（官网/测试版/FAQ 三处，原 howread.app）、config.xml wiki_url、PrefFragment2 WWW_SITE/WWW_BETA_SITE/WWW_WIKI_SITE（原 librera.mobi）、whatsnew2.xml wiki 文本、Urls.rateIT（FDroid 渠道）、AndroidWhatsNew（详情/更新日志/下载链接/弹窗文案）。
- 保留 SamlibOPDS `?from=librera.mobi`（OPDS 书源服务参数，功能性）。

## 4. release 试打 + MI9 试装（验证通过）
- `assembleLibreraRelease` 构建成功；apksigner 确认签名 **CN=HowRead**（howread.keystore）。
- debug/release 签名不同 → 卸载 debug 版后 `pm install -i com.android.vending -r -t` 安装成功（versionName 0.9.0 / versionCode 7199）。MIUI 偶发 INSTALL_FAILED_USER_RESTRICTED 时用设备端后台安装重试即可。
- 真机核验：桌面图标"好好读"；软件说明弹窗"好好读: 0.9.0 (1.23.7-librera) SDK: 30 Xiaomi"+"HowRead Pro: 无广告的应用程序"；WebDAV 同步对话框"同步路径 /HowRead"；`/sdcard/HowRead/profile.Librera` 已创建；书架/开书（2600 页 PDF）冒烟正常。

## 影响与注意
- 本机目录换新 → 书库重新扫描一次（全新安装本就如此）；旧 `/sdcard/Librera`、`Download/Librera` 文件不自动删除，可手动清理。
- WebDAV 首次同步会多一步旧目录导入；升级路径 versionCode 已保持单调，0.9.0 可直接覆盖安装 9.4.x 之后的构建。
# 2026-08-30 0.9.0 界面打磨（书架默认/关于弹窗/官网/配置文件/横幅标语）

## 1. 书库显示模式默认书架
- `AppState.libraryMode` 默认值 `MODE_GRID` → `MODE_SHELF`：新安装/重置后书库直接进书架；已手动选过显示模式的设备保持其选择。

## 2. 软件说明弹窗精简（AboutSectionBinder，侧边栏与偏好两处入口共用）
- 去掉弹窗顶部"软件说明"标题（`setTitle` 移除）。
- 蓝色版本行由 `好好读: 0.9.0 (1.23.7-librera) SDK: 30 Xiaomi` 精简为 **`好好读: 0.9.0`**（不再展示 MuPDF 引擎串/SDK/厂商；native 库未动）；`pVersion` 行同步用纯版本号；清理不再使用的 import。

## 3. 官网项展示 howread.git 超链接
- `values/config.xml` `my_site` 由空改为 `https://github.com/380121850/howread.git`（官网行原本因空值隐藏）；
- `about_section.xml` openWeb 显示文本 `howread.git`，点击打开完整链接。

## 4. 配置文件默认 HowRead
- `AppSP` 默认 profile `"Librera"` → `"HowRead"`；`init()` 一次性迁移把存量 `currentProfile=="Librera"` 自动改为 `"HowRead"`。
- 影响：库 DB 文件名含 profile → 重建后自动重扫（真机已验证 72 本书扫回）；根目录旧 `profile.Librera` 文件夹保留在配置文件切换列表中，可手动删除。

## 5. 侧边栏横幅标语
- `main_tabs.xml` 顶部 banner（170dip 夜空图）内新增顶部居中 TextView `drawerTagline`：**"值得读的，好好读"**（白字+半透明阴影）；底部随机名言 drawerQuote 保留。

## 验证（MI9，release 覆盖安装）
- 同签名 release `pm install -r` 直接升级成功；
- 真机截图核验：横幅标语 ✓；软件说明弹窗无标题/版本行精简/官网 howread.git ✓；偏好页配置文件 "H HowRead" ✓；书库书架视图 ✓。
# 2026-08-30 横幅标语调整 + 阅读笔记入口（全屏笔记编辑器）

## 1. 侧边栏横幅标语居中 + 字号放大一倍
- `main_tabs.xml` drawerTagline：横幅正中显示（水平+垂直居中）、17sp → **34sp**、单行。

## 2. 阅读界面"注释和手写"图标 → 笔记入口
- 单击阅读页呼出的底栏中，写字板图标（editTop2）原来弹出画笔颜色面板（注释和手写）；现改为打开**全屏笔记编辑器**，图标保留。
- 原逻辑仅 PDF 显示该图标 → 现在全格式显示（笔记是纯文本，不依赖 PDF 绘图）；裁剪/切边模式与密码保护文档下仍隐藏。
- 绘图注释功能本身保留（点按已画注释、手势入口仍可打开画笔面板）。

## 3. 新增全屏笔记编辑器 NoteEditDialog
- 新类 `com.foobnix.pdf.info.view.NoteEditDialog` + 布局 `dialog_note_edit.xml`（仿 AI 全屏对话框，MATCH_PARENT² 大页面方便输入）。
- 首行：创建时间（yyyy-MM-dd HH:mm）+ 当前位置"第 X / Y 页"；中间大尺寸多行输入框（自动聚焦）；底部 [取消] [保存笔记]。
- 保存为 `AppBookmark`（path/内容/当前进度百分比 p/时间 t，isAiNote=true），进首页"书签笔记"卡片（显示内容与进度%，点击跳回保存时位置），并随 WebDAV 书签同步。
- 新字符串：note_edit_save=保存笔记、note_edit_hint=输入笔记内容…、note_edit_page_fmt=第 %1$d / %2$d 页（values + zh-rCN；标题复用已有 reading_note=笔记）。

## 验证（MI9，release 覆盖安装）
- 横幅标语居中放大 ✓；PDF 底栏写字板图标 → 全屏笔记编辑器（首行"2026-08-30 17:40 · 第 7 / 22 页"）✓；输入保存 → Toast"好好读: 笔记已保存" ✓；首页书签笔记出现"HiFB开发指南.pdf / hello_howread_note / 32%" ✓。

## [0.9.0] 2026-08-30
### 交互打磨（阅读面板 / 选中菜单 / 横幅）
- 侧边栏横幅标语"值得读的，好好读"由 34sp 缩至 32sp（部分真机单行溢出）。
- 选中文字弹窗在"发送给AI"下方新增"笔记"入口（onNoteToEdit）：点击后关闭选区并打开全屏笔记编辑器，自动把所选文字预填进编辑框（光标在末尾）；NoteEditDialog 增加三参 show(a, dc, prefill) 重载。
- 阅读界面单击面板由四行精简为两行（图标行 + 进度条行，document_footer.xml）：
  - 移除底部"☰ 最近"播放列表行（playListParent/playlistRecycleView 固定 GONE，DialogsPlaylist.dispalyPlaylist 相应改为不再显示，view 保留仅因 id 仍被绑定）。
  - 图标行移除"书架"（onRecent）、"前往页面"（thumbnail）、"页序"（nextTypeBootom），隐藏后 lockUnlock（锁）与 bookMenu（⋮）自第四行上移至图标行右端。
  - 第四行（播放 autoScroll、TTS textToSpeach、"上下翻页" modeName）整行隐藏；autoScroll/textToSpeach 等 view 保留（HorizontalViewActivity 横屏模式仍绑定这些 id，避免 NPE）。
- 验证：release 覆盖安装 MI9，横幅 32sp 单行居中；长按选中文字 → 弹窗"笔记"项 → 全屏编辑器预填所选文字 + 时间页码首行 → 保存 Toast 成功；单击面板仅剩图标行（搜索/笔记/书签/目录/锁/⋮）+ 进度条行。

## [0.9.0] 2026-08-30（第二轮）
### 检视修复（面板精简的遗留问题）
- 修复横向滚动模式（左右翻页/书本模式）面板功能连带隐藏的回归：document_footer.xml 图标行新增 id=footerIconRow；HorizontalViewActivity 初始化时调用 restoreFooterControls()，把 autoScroll（自动翻页）与 textToSpeach（TTS）从已隐藏行 reparent 到图标行并恢复显示，同时恢复 onRecent/thumbnail 显示；竖屏翻页模式不受影响，保持精简两行。
- DialogsPlaylist.dispalyPlaylist 精简为无操作（入参判空后直接返回）：播放列表条随面板精简退役，清除 updateVisible/监听器/缩略图适配器等死逻辑，方法保留仅为调用方兼容。
- DragingPopup.initState 位置缓存边界校正：缓存记录宽高 <50dp 或超出屏幕范围时回退到默认居中放置，避免浮动弹窗被旧缓存摆到屏幕外/零尺寸而"长按后看不到弹窗"。
- 验证：release 覆盖安装 MI9；竖屏面板仍为图标行+进度条行；切到左右翻页模式，图标行恢复为 搜索/最近/前往页面/书签/目录/单双页/播放/TTS/锁/⋮ 且自动翻页对话框、长按选字弹窗（含"笔记"项）均正常；logcat 无崩溃；偏好"单击"模式已恢复为上下翻页。

## [0.9.0] 2026-08-30（第三轮）
### 阅读面板：图标行间距与对齐
- 单击面板底部图标行与顶部工具栏图标行加大间距（3dip→8dip，新增 buttonWhiteSpaced 样式）并统一**靠右对齐**：
  - 竖屏（上下翻页）：顶部图标行 alignParentRight；底部图标行 gravity=end，代码中（VerticalViewActivity.spaceFooterIcons）加宽间距——横屏底部 10 键已占满行宽，保持紧凑防裁剪。
  - 横屏（左右翻页）：顶部图标行 layout_gravity=right；底部仅右对齐。
- 验证：PDF 竖屏/横屏面板均右对齐、间距明显加大，无图标裁剪。

### 书库（书架模式）
- 阅读进度 100% 的进度角标文字改绿色（#4CAF50），未读完仍白色。
- 封面下边沿新增**收藏键**（shelfStar：进度角标与 ⋮ 之间，半透明圆底）：实心星=已收藏，点击即切换（复用 onStarClickListener，与网格模式星标同一逻辑）。
- 书籍菜单（⋮）移除五项：复制、添加标签、同步书籍、重置进度、多选模式（ShareDialog 条目与 handler 分支成对删除；书架长按进多选手势不受影响）。
- 验证：书架 100% 绿色角标 ✓；点星键变实心 ✓；菜单仅剩 文本重排/打开方式/发送文件/删除/移出书库/加入播放列表/标记已读/未读/在读/文件信息 ✓。

### 首页 / 我的文件
- 首页「我的文件」行改为显示真实配置：逐项列出「我的文件」里添加的 WebDAV 服务器（点击直达该服务器网络页）与书库文件夹（点击直达文件夹页），末尾保留"我的文件"总入口；**移除 Dropbox / GDrive 占位**；行支持横向滚动；添加/删除后即时刷新（BrowseFragment2 rebuild 追发 UpdateAllFragments，DashboardFragment2 每次刷新重建该行）。
- 首页「网上书库」行：点击某个 OPDS 书库直接打开该书库（openNetworkPage），不再只切 tab。
- 「我的文件」页 OPDS / WebDAV 条目图标加大（30dp→40dp）。
- 验证：首页显示 LeeStation（WebDAV）、DSfile（书库文件夹）、我的文件入口，Dropbox/GDrive 消失 ✓；点条目直达网络页 ✓；我的文件页图标明显变大 ✓。

### WebDAV 同步：网络配置纳入备份/恢复
- 新增 app-NetworkSources.json（AppProfile.syncNetworkSources + ProfileStateIO export/import/merge）：备份 **OPDS 书库条目、WebDAV 服务器列表、书库文件夹路径** 三段，按 URL/路径取并集（本地顺序优先，远端新条目追加）。
- WebDavSyncer.doSync：全局 app-State/app-CSS 同步**前**先导出（防 newer-wins 覆盖本地新配置），同步**后**合并回写并再次发布全局文件，双端一次同步即收敛。
- 说明：OPDS 登录已随 app-Misc.json 同步；WebDAV 密码因 AndroidKeyStore 设备绑定不参与跨设备同步（恢复后需重输）。
- 验证：MI9 后台同步后 app-NetworkSources.json 生成，内容含 Gutenberg OPDS、LeeStation、/sdcard/DSfile 三段；logcat 无同步异常。

## [0.9.0] 2026-08-30（第四轮）

### 备份/同步按数据类型分类改造（一个 WEBDAV 多设备冲突修复）
- 新设备首同步保护（本地默认 → 服务器为准）：同步时现场判断本地 app-State/app-CSS 是否仍等于出厂默认（按"个性化字段"视图比较，剔除屏幕尺寸默认值、绝对路径、时间戳等设备字段），等于默认即收养服务器配置而非用默认值覆盖服务器；在"我的文件"里配置同步本身不算个性化，刚输入的同步服务器/路径/策略/定时在收养时保留。
- 设备相关字段隔离：app-State 的 displayPath、installationDate、屏幕相关默认值（tapzoneSize/coverBigSize/progressLineHeight 等）、背景图路径、代理配置（含代理密码）、会话残留（selectedText/searchQuery/isAutoScroll），app-CSS 的 searchPathsJson/cachePath/fontFolder/dictPath/pathSAF 等路径字段——不参与比较、不上传服务器、下载后回填本地值。多设备不再互相污染，服务器副本不再保存设备字段与代理密码明文。
- 修复 app-BookStates（已读/在读标记）合并失效：旧合并代码期望带时间戳的对象而实际值是 int（0 未读/1 在读/2 已读），远端条目永远合并不进来、各设备轮流覆盖服务器。改为按 key 并集取更靠后状态（只进不退，天然无冲突）。
- 配置变化自动触发同步：AppState/BookCSS 真实变更写盘后，10 秒去抖合并触发一次静默 WebDAV 同步；同步过程自身写的 lastSync 水印不会引发循环（已验证静置无重复同步）。
- 定时同步间隔可配置：WebDAV 同步对话框新增"定时同步"行（关闭/每15分钟/每30分钟/每1小时/每3小时/每6小时/每1天），应用存活期间周期后台同步，改动即时生效（每次循环重读配置）。
- 备份 zip 导出前刷新 app-NetworkSources.json：手动备份包里 OPDS 目录、WEBDAV 服务器、书库文件夹始终是最新值（此前只有跑过 WebDAV 同步才有）。
- 备份 zip 跨机型恢复：导入解压后若当前机型目录（device.<机型>）缺 app-State/app-CSS/app-Misc，自动从其他机型目录收养最近一份，设置类配置跨机型迁移生效；importMisc 恢复 AppSP 时保护 rootPath/currentProfile/lastBookPath 等本机字段不被源设备值覆盖。
- 密码策略保持现状：OPDS 登录、书/应用密码、AI Key 明文随 app-Misc/app-AI 同步与备份（自建服务器、多设备免重输的取舍）；WebDAV 密码仍 AndroidKeyStore 设备绑定、不随同步迁移。

### 界面
- "软件说明"与"更新说明"弹窗去掉最后一行"请在 Google Play 反馈并评分"。
- 首页"我的文件"行只显示已配置的 WEBDAV 服务器与书库文件夹项（去掉"我的文件"总入口子项）；一项都没配置时整段（含标题）隐藏。

### 验证（MI9 真机，全部通过）
- 常规同步正常、全程 logcat 无未捕获异常。
- 模拟新机（改走 device 目录 + 只配置同步服务器）：首同步收养服务器个性化配置（主题色/AI 配置/排版一致），同步配置保留，设备字段用本机值。
- app-BookStates int 状态条目随同步完整轮回；配置变化后 10~15 秒内自动同步一次，静置 60 秒无循环。
- 定时同步 7 档设置/回读正常；备份 zip 含最新 app-NetworkSources.json；跨机型 zip 导入后设置文件被当前机型目录收养；软件说明与首页"我的文件"行按预期显示。
- 定时同步默认打开，默认间隔 15 分钟（此前默认关闭）；已有设备如显示关闭，在同步对话框选一次即可。


## [2026-09-02] 广告 SDK 模块化：多商店渠道化（AdsProvider 抽象 + F-Droid 零广告整改）

### 背景
为支持按商店发布（Google Play/小米/华为等渠道可各接不同广告 SDK；F-Droid 政策禁止任何广告 SDK），按根目录 MULTI_PLATFORM.md 方案落地：平台→商店分层、广告代码 provider 化。

### 改动
- 新增广告抽象层（main，零第三方依赖）：`com.foobnix.ads.AdsProvider` 接口 + `RewardListener` + `NoAdsProvider`；`com.foobnix.pdf.info.ADS` 改为纯门面：公开方法与计时/测试设备逻辑保留，SDK 调用全部委托 `AdsProviderFactory.get()`；main 源码不再出现任何 com.google.android.gms.ads / com.google.android.ump 引用。
- 广告实现按变体源集编译（同 FQCN 工厂、组间互斥，仿 LibreraBuildConfig 模式）：
  - `app/src/admobAds`（`AdMobAdsProvider`）：集中原 LibreraApp 的 MobileAds 初始化与测试设备、MainTabs2/PrefFragment2 的 UMP 同意流程与隐私选项、原 ADS 内横幅/插屏/激励实现；挂 6 个带广告 flavor；
  - `app/src/noAds`（`NoAdsProvider`）：挂 fdroid/pro，APK 不含任何广告 SDK 代码。
- 广告位 meta-data（APPLICATION_ID/BANNER/FULLSCREEN/REWARD）从 main Manifest 迁到 `src/admobAds/AndroidManifest.xml`，仅带广告渠道合并；无广告渠道清单零广告痕迹。
- 删除 `libPro/` 模块（原 GMS/UMP 同包名 no-op 假类，被接口层取代）；settings.gradle.kts 与 app/build.gradle 同步摘除。
- 激励广告回调从 GMS OnUserEarnedRewardListener 换为自有 RewardListener（AdsFragmentActivity/ViewBinder 等调用点适配）。
- 新增目录骨架：`app/src/{xiaomi,huawei}`（渠道预留，上架时注册 flavor）、`platform/{ios,desktop}`（预留）、`ci/`（构建与合规闸门说明）、`store/`（按平台/商店的发布物清单，含 fastlane 定位说明）；根新增 `MULTI_PLATFORM.md` 方案文档。
- 新增闸门工具：`Z:\opt\librera\bench\scan_apk_ads.py`（APK dex/清单字节扫描广告 SDK 标记）。

### 验证（全部通过）
- Ubuntu server 三变体编译全绿：assembleLibreraDebug / assembleFdroidDebug / assembleProDebug。注意：**fdroid 必须单独一次调用构建**——app/build.gradle 版本 ext 按整次调用的任务串是否含 "Fdroid" 取值，fdroid 与 librera/pro 同批会把同批全部带成 fdroid 版本号（9.4.21/7174）。
- 零广告闸门：fdroid（9.4.21）与 pro（0.9.0）arm64 APK 扫描 PASS（无 gms.ads / ump / play-services-ads 等任何标记）；对照 librera APK 含 AdMob 实现类（预期）。
- MI9 实机：安装 HowRead-0.9.0-arm64（debug），启动 MainTabs2 正常：进程存活、底部导航与设置菜单渲染、全程 logcat 无 FATAL/异常。
- 静态 grep：main / fdroid / pro / gmsStubs 源集无 com.google.android.gms.ads、com.google.android.ump、:libPro 残留。

## [2026-09-02] 平台目录化迁移：一级目录 android/ harmony/ ios/ desktop/；主渠道 flavor librera 改名 google

### 背景
按 MULTI_PLATFORM.md 方案把仓库目录按平台对齐：安卓 Gradle 工程从仓库根下沉 android/，
ios/desktop 预留位提升为一级目录；安卓主渠道（Google Play/官网）flavor 由 librera 改名 google。

### 目录迁移
- 仓库一级目录 = android/（安卓 Gradle 工程）+ harmony/（鸿蒙）+ ios/ desktop/（预留占位）
  + 根共享层（Builder/ prebuilt/ scripts/）+ 文档/发布（docs/ store/ ci/ README CHANGES …）。
- 迁入 android/：build/settings/gradle.properties、gradle/、gradlew*、app/、libDepFree/
  libDepPro/ libReflow/、local.properties、howread.keystore、keystore.pkcs12、fastlane/
  （Play 商店元数据）、com_files.txt/net_files.txt。
- 删除：KMP 试验种子 composeApp/ shared/ iosApp/（settings 已注释、零构建引用）；
  根 build/ 与 .gradle/ 可再生缓存；原 platform/ 占位目录（内容提升为 ios/ desktop/）。
- 留在根的共享资产未动：Builder/（harmony NAPI CMake 引 ../../Builder/mupdf-1.23.7）、
  prebuilt/（restore-harmony-libs.sh 引 prebuilt/harmony）、scripts/（离线种子）。
- 构建引用适配：android/app/build.gradle 的 jniLibs 路径改 `${rootDir}/../prebuilt/...`；
  android/settings.gradle.kts 加 `:Builder` projectDir=../Builder（模块留在根）。

### flavor librera → google
- app/src/librera → app/src/google；LibreraBuildConfig.FLAVOR="google"（运行时代码无分支依赖，仅非空校验）。
- app/build.gradle：flavor 块名、manifest 占位符属性键 librera_* → google_*
  （google_appGdriveKey/admobAppId/BannerId/FullId/RewardId）、admobAds 源集循环列表、
  googleImplementation 依赖×2、APK 文件名品牌判断（"Google" 主渠道仍无标签
  HowRead-0.9.0-*.apk）。manifest 元数据键 librera.ADMOB_*、deep-link scheme "librera"、
  内部品牌遗留（Librera profile/云路径常量等）有意保留，未改。
- 服务器 ~/.gradle/gradle.properties 无 librera_* 键（广告位一直用测试 ID 兜底）；
  RELEASE_STORE_FILE 路径已同步为 android/howread.keystore。
- 文档/工具链同步：根 MULTI_PLATFORM.md（新目录树+迁移历史）、README.md（构建命令）、
  store/android/google README、Z:\opt\librera 的 build_remote.sh（gradle 根=android/、
  flavor 表）、build-librera.ps1（监听/轮询/产物路径）、BUILD-README.md、
  bench 图标/重品牌脚本路径前缀、Z:\opt\zcode\AGENTS.md（路径/命令/flavor/警示）。

### 验证（全部通过）
- Ubuntu server（cd …/LibreraReader/android）：
  `./gradlew :app:assembleGoogleDebug :app:assembleProDebug` → 0.9.0/7198；
  单独 `:app:assembleFdroidDebug` → 9.4.21/7174。
- 产物命名正确：google=HowRead-0.9.0-arm64.apk（无标签）、pro=HowRead-Pro-…、fdroid=HowRead-Fdroid-…。
- APK 内含 lib/arm64-v8a/libMuPDF.so + liblame.so（../prebuilt jniLibs 路径生效）。
- fdroid/pro 零广告闸门扫描 PASS；MI9 实机安装 google 包（0.9.0）启动正常、logcat 无异常。
- 残留复查：android/ 与文档中无 assembleLibrera / src/librera / "Librera" flavor 引用。
- 鸿蒙侧零改动（Builder/prebuilt 路径未变）。

## [2026-09-02] 品牌可见文案清理：Play 商店描述与鸿蒙显示名改 HowRead

### 背景
全仓 "librera" 遗留审计（255 文件命中）后按确认范围清理**用户可见**残留；
内部包名（mobi.librera 等）与持久化/署名标识按审计分类保留。

### 改动
- `android/fastlane/metadata/android/en-US/full_description.txt`：商店描述品牌全部
  改为 HowRead；修正两处与现状不符的上游文案（FD 与主渠道差异 = 无 GMS/广告；
  minSdk 24 → Android 7.0+）；末尾注明为 Librera 的开源分支并保留原作者捐赠链接。
- 鸿蒙 `harmony/entry/src/main/resources/base/element/string.json`：module_desc /
  EntryAbility_desc / EntryAbility_label 显示名改为 HowRead（原先 Librera Reader /
  Librera Reader for HarmonyOS / Librera）。
- `harmony/entry/src/main/ets/pages/Index.ets`：顶栏标题 'Librera Reader' → 'HowRead'。

### 审计分类备忘（未清理项及原因）
- 有意保留：备份/同步持久化标识（payload 'librera-harmony'、librera_backup.json、
  profile "Librera"、/Librera.Cloud、scheme、meta 键）、上游版权与历史（README 上游段/
  LICENSE/CHANGES/docs 官网）、引擎与签名命名（Builder platform/librera、
  libmupdf-librera.c、librera-sign.p12）、仓库/共享盘路径、com.foobnix 主包空间。
- 可选未做：约 40 个 values-* 语言包品牌词、内部类/包清理（LibreraApp/
  LibreraBuildConfig×9/LibreraAppGlideModule/mobi.librera→mobi.howread 等，待另行确认）。

### 验证
- 鸿蒙 HAP 构建成功（BUILD SUCCESSFUL，CompileArkTS/PackageHap/SignHap 通过）。
- 复查三文件残留仅剩署名与备份标识（有意）。

## [2026-09-02] 版本号统一：fdroid 不再固定 9.4.21/7174，全 flavor 同用 0.9.0/7198

### 背景
此前 fdroid 沿用上游惯例固定版本（9.4.21/7174，`-fdroid` 后缀），与主渠道 0.9.0 不一致；
本次移除特判，所有 flavor 统一读取 app/gradle.properties 版本号。

### 改动
- `android/app/build.gradle`：删除 ext FDroidCodeNumber=7174 / FDroidVersionNumber="9.4.21"
  与"任务串含 Fdroid 则取 fdroid 版本"的分支；versionCode/Name 一律来自
  app/gradle.properties（当前 appCodeNumber=7198 → 0.9.0/7198）。
- 删除 fdroid flavor 的 `versionNameSuffix '-fdroid'`（版本名不再带后缀）。
- 附带收益：fdroid 与 google/pro **可同批构建**（不再有版本串污染问题）；
  产物命名统一 HowRead-Fdroid-0.9.0-*.apk。
- 文档同步：MULTI_PLATFORM.md（构建命令/矩阵/现状）、store/android/fdroid README、
  Z:\opt\zcode\AGENTS.md（构建注释与 gotcha 13）、根 README.md、BUILD-README.md
  （产物名 0.9.0）、build_remote.sh 头部说明。
- 清理改名前的旧构建输出目录 android/app/build/outputs/apk/librera（可再生残留）。

### 验证（全部通过）
- fdroid 单独构建：Version [0.9.0 - 7198]，产物 HowRead-Fdroid-0.9.0-arm64.apk，
  零广告闸门扫描 PASS。
- google+fdroid+pro 同批构建：Version [0.9.0 - 7198]，BUILD SUCCESSFUL，
  三个渠道产物版本一致（HowRead / HowRead-Fdroid / HowRead-Pro 均 0.9.0）。

## [2026-09-07] 鸿蒙移植：PDF 文本精确选择 + 下划线/删除线/波浪线/文字笔记标注（NAPI 扩展）

### 改动（均在 harmony/）
- `entry/src/main/cpp/mupdf_napi.cpp`：
  - `getTextRects` 输出扩为每行 `{x0,y0,x1,y1,text,chars:[x0,x1,...]}`（行文本 JSON 转义 UTF-8 + 逐字符 x 边界，mediabox 归一化），作为逐字符选区数据基础；
  - 新增 `addMarkupAnnotation(handle,page,rects[],type,color)`：underline/strikeout/squiggly/highlight 共用（对齐安卓 addMarkupAnnotationInternal：每 rect 一 quad、pdf_set_annot_quad_points、透明度 highlight 0.4 其余 1.0）；
  - 新增 `addTextNote(handle,page,x,y,text,color)`：PDF_ANNOT_TEXT + 24pt 图标 rect + contents（对齐安卓 addTextNoteInternal）；
  - 注册表新增两函数；`types/libmupdf_napi/Index.d.ts` 增 TextLine 接口与两函数声明。
- `entry/src/main/ets/components/Reader.ets`：
  - 精确选区：中央区长按（600ms）以 FingerInfo.globalX/Y + onAreaChange 归一化定位行/字符为起点；选区模式下全页透明捕捉层把点按转为终点（长按重设起点）；首末行按字符 x 裁剪合成 quads 实时预览，翻页自动退出；
  - 浮动操作条：下划线/删除线/波浪线/高亮/笔记（内联输入→addTextNote）/复制（@ohos.pasteboard）/取消；
  - PageRenderer overlay：underline/squiggly 底部横线、strikeout 中部横线、text 图标、选区蓝色预览。
- 已知坑（记录）：@State 代理数组直传 NAPI 时 napi_get_array_length 识别失败，调用侧需传本地拷贝；启动自检 runApiTest 每次用 rawfile 覆盖 cacheDir/test.pdf，会清掉演示文件已存标注（自检固有行为）。

### 验证（Pura 90 模拟器）
- 选区 5 行解析 → 两点选区 14 字符 → 下划线(annotations:1)/删除线(2)/笔记(3)/复制(Copied 14 chars) 全通过；
- 保存后拉取 PDF 字节含 `Subtype/Underline`，MuPDF 持久化闭环成立；无崩溃。

## [2026-09-07] 鸿蒙：新增 build_hap_all.sh —— DEBUG/RELEASE 双模式产物规范命名分目录

### 改动
- 新增 `harmony/build_hap_all.sh`：依次 assembleHap buildMode=debug/release（同路径输出，逐次拷贝），版本号取 AppScope/app.json5 versionName，ABI 段由 entry/libs/ 目录推导（arm64-v8a→arm64）；
- 产物：`harmony/dist/DEBUG/harmony-HowRead-v1.0.0-arm64-x86_64.hap` 与 `harmony/dist/RELEASE/harmony-HowRead-v1.0.0-arm64-x86_64.hap`（命名参考安卓 harmony-HowRead-Pro-v0.8.0-arm64.hap 格式；HAP 实际含双 ABI，故 ABI 段为 arm64-x86_64）。

### 验证
- 双模式 BUILD SUCCESSFUL，两目录产物就位（DEBUG 113MB / RELEASE 112MB，含标注功能最终代码）；release 模式用现有签名链签名通过（hvigor 不校验 profile type，debug profile 可签 release 包）。

## [2026-09-07] 鸿蒙：RELEASE 产物修正——release profile 签名 + 仅 arm64 打包（华为云平台不再识别为 DEBUG）

### 背景
`dist/RELEASE/harmony-HowRead-v1.0.0-arm64-x86_64.hap` 上传华为开发云平台测试被识别为 DEBUG 包；
且文件名 ABI 段 arm64-x86_64 引起疑问。

### 根因与修正
- **根因 1（DEBUG 标记）**：release 构建复用了 debug 签名 profile（librera-debug.p7b，`"type": "debug"` 且绑定
  模拟器 UDID），平台按包内嵌 profile 类型判定。修正：新增 `harmony/signing/gen_release_profile.sh`，
  复用现有自签链生成 `librera-release.p7b`（`type=release`、无设备绑定；hap-sign-tool 对 release 型 profile
  要求 bundle-info 同时含 development-certificate 与 distribution-certificate，否则报 Require cert in bundleInfo）。
  `build_hap_all.sh` 在 release 构建前把 build-profile.json5 签名 profile 切到 release p7b，debug 构建用回 debug p7b。
- **根因 2（ABI 段）**：arm64-v8a=真机 64 位 ARM，x86_64=模拟器；此前双 ABI 都打进 HAP 导致命名并列。
  修正：release 构建将 entry/build-profile.json5 的 `externalNativeOptions.abiFilters` 切为仅 arm64-v8a，
  并**临时移走 `entry/libs/x86_64/`**——hvigor 会无视 abiFilters 把 libs 下预置 so 全部打包（CMake 产物受控，
  预置库不受控），构建后恢复；debug 构建保持双 ABI 供模拟器使用。

### 产物（dist/，按 HAP 实际内容推导命名）
- `dist/DEBUG/harmony-HowRead-v1.0.0-arm64-x86_64.hap`（内嵌 profile type=debug，双 ABI）
- `dist/RELEASE/harmony-HowRead-v1.0.0-arm64.hap`（内嵌 profile type=release，仅 3 个 arm64 so）

### 验证
- hap-sign-tool verify-profile：librera-release.p7b `verified: True, type: release`；
- 字节级校验：RELEASE HAP 签名块内嵌 profile `type=release`（DEBUG 包为 `type=debug`）；unzip 确认 RELEASE 仅
  arm64-v8a/libc++_shared.so + libmupdf.so + libmupdf_napi.so；
- 脚本结束自动恢复默认（debug profile + 双 ABI + x86_64 库归位，trap 兜底）。

### 说明
- 签名链仍为本地自签（Librera Root CA）。若华为云平台校验证书链（而非仅包类型），需在 AGC 签发正式
  release 证书与 profile 后替换 `harmony/signing/` 材料（build_hap_all.sh 无需再改）。

## [2026-09-07] 鸿蒙：产物改为按硬件平台分 ABI——DEBUG/RELEASE × arm64/x86_64 共 4 个 HAP

### 背景
用户要求：不论 debug 还是 release，均按硬件平台（ARM64 与 X86_64）分开构建产物。

### 改动（build_hap_all.sh 定稿）
- 每次构建单一 ABI：`externalNativeOptions.abiFilters` 设为目标 ABI，并将 `entry/libs/` 下其它 ABI 的
  预置库目录临时移出（hvigor 打包会无视 abiFilters 收入 libs 下全部预置 so；CMake 产物受控、预置库不受控），
  构建后立即恢复；trap 兜底保证异常退出也恢复默认（debug profile + 双 ABI + 库归位）。
- 签名沿用上一条目方案：debug 构建用 librera-debug.p7b，release 构建用 librera-release.p7b（type=release）。
- 文件名 ABI 段仍按 unzip 实际内容推导；脚本启动清空 dist/ 全量重建（修复了按前缀删除旧产物时
  误删同前缀新产物的缺陷）；产物目录固定为大写 DEBUG/RELEASE。

### 最终产物（dist/，4 个，均约 57MB）
- dist/DEBUG/harmony-HowRead-v1.0.0-arm64.hap     （debug profile，仅 arm64-v8a）
- dist/DEBUG/harmony-HowRead-v1.0.0-x86_64.hap    （debug profile，仅 x86_64，模拟器）
- dist/RELEASE/harmony-HowRead-v1.0.0-arm64.hap   （release profile，仅 arm64-v8a）
- dist/RELEASE/harmony-HowRead-v1.0.0-x86_64.hap  （release profile，仅 x86_64）

### 验证
- 4 个产物逐一校验：ABI 内容唯一 + 内嵌 profile 类型正确（DEBUG=debug，RELEASE=release）；
- 模拟器安装 dist/DEBUG x86_64 包启动正常（hilog 无 FATAL，书库/渲染正常）。

## [2026-09-07] 鸿蒙：生成 AGC 发布证书申请材料（发布密钥库 + CSR）

### 背景
华为平台要求正式发布包使用 AGC 签发的发布证书与 Profile；本地生成 CSR 上传申请。

### 改动
- 新增 `harmony/signing/gen_release_csr.sh`（等价 DevEco「Generate Key and CSR」向导，字段值经用户确认）：
  - 密钥库 `howread-release-sign.p12`（ECC NIST-P-256，别名 howread-release，密码 HowRead@2026，仅本地保存）；
  - 本地自签证书 `howread-release-local.cer`（CN=HowRead / OU=LeeStudio / O=Lee / C=CN，25 年，仅 IDE 对等物，不上传）；
  - **`howread-release.csr`——上传 AGC 申请发布证书（.cer）+ 发布 Profile（.p7b）**。
- 备忘：hap-sign-tool generate-cert 无 `-outForm` 参数（传了会报 Param is not trusted）；generate-csr 必须带 `-subject` 与 `-signAlg`。

### 验证
- 三步命令全部 success；CSR 为有效 PEM（openssl 可解析出公钥）。
- 后续：收到 AGC 的 .cer/.p7b 后替换 build_hap_all.sh 的 release 签名材料并重建 4 个产物。

## [2026-09-07] 鸿蒙：生成 AGC 调试证书申请材料（debug 密钥库 + CSR）

### 改动
- 新增 `harmony/signing/gen_debug_csr.sh`（与 gen_release_csr.sh 同构，字段值经用户确认）：
  - 密钥库 `howread-debug-sign.p12`（ECC NIST-P-256，别名 howread-debug，密码 HowRead@2026，与 release 同密码）；
  - 本地自签证书 `howread-debug-local.cer`（主体与 release 一致：CN=HowRead/OU=LeeStudio/O=Lee/C=CN，25 年）；
  - **`howread-debug.csr`——上传 AGC 申请调试证书（.cer）+ 调试 Profile（.p7b，平台侧绑定调试设备 UDID，可多台）**。

### 验证
- 三步命令全部 success；openssl 解析 CSR 主体 = C=CN, O=Lee, OU=LeeStudio, CN=HowRead，与确认值一致。
- 现有自签 debug 链（librera-sign.p12/librera-debug.p7b）保留作后备，AGC 材料到手后统一切换。

## [2026-09-07] 鸿蒙：接入 AGC 签发证书重建四产物（dist 目录改小写 debug/release）

### 背景
用户在 AGC 完成调试/发布证书申请并放入 signing/（HowRead-debug.cer / HowRead-release.cer）；
要求基于新签名重建 debug/release，dist 文件夹改为小写 debug/release。

### 材料核验
- 两份 .cer 均为华为 3 段链（Huawei CBG Root CA G2 → Developer Relations CA G2 → 叶子）；
- 叶子公钥与本地 CSR 逐一匹配（debug↔howread-debug.csr，release↔howread-release.csr）；
- debug 叶子 = Development（1 年），release 叶子 = Release（3 年）。

### 改动
- 新增 `signing/gen_howread_profiles.sh`：生成占位 Profile（type=debug/release，嵌入 AGC 叶子证书，
  bundle-name 动态取 AppScope/app.json5 = com.leestudio.howread.reader.hmos；debug 绑模拟器 UDID），
  由本地 profile CA 签发 → `howread-debug.p7b` / `howread-release.p7b`（verify True）。
  **AGC 正式 .p7b 到手后直接替换这两个文件重打即可**（真机安装/上架必须用 AGC p7b）。
- 新增 `signing/encrypt_pwd.js`：复用现有 material/ 工作密钥加密新密码（不重建 material/，旧配置不受影响）。
- `build-profile.json5` 新增签名配置 `howreaddebug` / `howreadrelease`（certpath=叶子在前的链文件、
  profile=howread-*.p7b、storeFile=howread-*-sign.p12、别名 howread-debug/release、密码 HowRead@2026 加密）；
  default 产品默认指向 howreaddebug；liberadebug/liberaprodebug 及 pro 产品保持不动（另一会话新增）。
- 新增 `signing/HowRead-debug-sign.cer` / `HowRead-release-sign.cer`：把 AGC 链重排为叶子在前
  （hap-sign-tool 取第一条作签名证书，AGC 原件是根在前）。
- `build_hap_all.sh`：default 变体的 set_build_mode 改为切换 default 产品 signingConfig
  （howreaddebug↔howreadrelease），pro 变体维持旧逻辑；**产物目录改为小写 `dist/debug`、`dist/release`**。

### 排障记录（重要）
1. SignHap 报 `11010001 Unknown error: Illegal base64 character 20`——AGC 下载的 .cer base64 行内混有空格，
   嵌入 profile 后 sign-app 严格解码失败；清洗空白并按 64 字符重排后解决（verify-profile 不报，仅 sign-app 报）。
2. release 构建曾报 `00303073 Configuration Error`——与并行会话修改 build 文件撞车（entry 尚无 pro target），
   配置补全后自行消失。

### 产物与验证（dist/，均约 54-55MB）
- debug/HowRead-v1.0.0-arm64-hmos.hap / -x86_64-hmos.hap：profile type=debug + AGC Development 叶子
- release/HowRead-v1.0.0-arm64-hmos.hap / -x86_64-hmos.hap：profile type=release + AGC Release 叶子
- 字节级校验叶子证书特征串逐包匹配；模拟器安装 debug x86_64 包启动正常（新包名独立沙箱，无 FATAL）。

## [2026-09-08] 鸿蒙：release 产物换用 AGC 正式 Profile（云调试 9568322 修复）

### 背景
云调试安装报 `9568322 signature verification failed due to not trusted app source`——包内嵌的是本地自签
占位 profile，云真机只信任华为 Profile CA 签发的链。

### 改动
- 用户提供 AGC 签发的发布 Profile `HowRead-releaseRelease.p7b`（verify True：type=release、
  bundle-name=com.leestudio.howread.reader.hmos、app-identifier=6917615791336205965、
  叶子=AGC Release 证书且公钥与 howread-release.csr 匹配）；
- 替换 `signing/howread-release.p7b`（占位文件保留为 howread-release-placeholder.p7b）；
- `build_hap_all.sh` 重打 4 产物。

### 验证
- 字节级校验：release 两个包内嵌 AGC profile 原文 + App ID 6917615791336205965；debug 两包仍为占位 profile。

### 待办
- **云调试装 debug 包仍需 AGC 调试 Profile**（绑云真机 UDID——注意云真机 UDID 与模拟器 454D55…0000 不同，
  需从云调试页面获取后到 AGC 注册进调试 Profile），到手后替换 howread-debug.p7b 重打。

## [2026-09-08] 鸿蒙：双变体（HowRead / HowRead Pro）× debug/release × arm64/x86_64 共 8 产物

### 背景
鸿蒙平台分两个 APP：HowRead（普通版）与 HowRead Pro，分别对应两种等级；命名如
`HowRead-Pro-v1.0.0-arm64-hmos.hap`。中文应用名分别为「好好读」「好好读 Pro」。

### 排障（关键根因，记录）
pro 构建报 `Current product is 'pro'. No output will be generated because of no executable target`：
从 hvigor-ohos-plugin 源码定位到 `checkHasTargetApplyProduct → getTargetApplyProducts`——
**hvigor 对非 default 的 module target，默认只应用于 ["default"] 产品**；必须在项目级
build-profile.json5 的 `modules[].targets[]` 显式声明：
```
{ "name": "pro", "applyToProducts": ["pro"] }
{ "name": "default", "applyToProducts": ["default"] }
```
另发现产物输出路径按产品名变化：`entry/build/<product>/outputs/<product>/entry-<product>-signed.hap`，
build_hap_all.sh 的 OUT_HAP 已按 variant 参数化（此前误拷 default 产物为 Pro 文件的问题即源于此）。

### 改动
- `signing/gen_howread_profiles.sh` 参数化（bundleName + 文件名后缀），生成 Pro 占位 profile：
  `howread-pro-debug.p7b`（绑模拟器 UDID）/ `howread-pro-release.p7b`（verify True，bundle=com.leestudio.howread.pro.reader.hmos，
  嵌入 AGC 叶子证书，app-identifier=6917615791336205965）。
- `build-profile.json5` 新增签名配置 `howreadprodebug` / `howreadprorelease`（certpath=AGC 叶子在前链、
  storeFile=howread-*-sign.p12）；pro 产品默认 howreadprodebug。
- `build_hap_all.sh`：pro 变体 set_build_mode 切换 pro 产品 signingConfig；OUT_HAP 按 variant；
  dist 清理改为按变体（HowRead 与 HowRead-Pro 产物可共存）。
- entry/build-profile.json5 的 pro target 补 runtimeOS（试验项，无害保留）。

### 产物（dist/，8 个，均约 54-55MB，逐包字节级验证）
- debug/HowRead-v1.0.0-{arm64,x86_64}-hmos.hap        （bundle …howread.reader.hmos，中文「好好读」）
- release/HowRead-v1.0.0-{arm64,x86_64}-hmos.hap     （AGC 正式发布 Profile）
- debug/HowRead-Pro-v1.0.0-{arm64,x86_64}-hmos.hap   （bundle …howread.pro.reader.hmos，中文「好好读 Pro」）
- release/HowRead-Pro-v1.0.0-{arm64,x86_64}-hmos.hap
- 全部：AGC Development/Release 叶子证书按包正确、profile 类型正确、包名正确、ABI 唯一。

### 待办
- Pro 的 release 包若需上云/上架：Pro 包名需在 AGC 注册 App ID 并申请正式发布 Profile，替换
  `signing/howread-pro-release.p7b` 重打；调试 Profile（绑设备）同理。
- default 的 debug 包云调试仍需 AGC 调试 Profile（待用户提供，绑云真机 UDID）。
- 模拟器冒烟：HowRead-Pro debug x86_64 包安装启动正常，与 HowRead 包共存。

## [2026-09-08] 鸿蒙：Pro release 包换用 AGC 正式 Profile

### 改动
- 用户提供 AGC 签发的 Pro 发布 Profile `HowRead pro-releaseRelease.p7b`（verify True：type=release、
  bundle-name=com.leestudio.howread.pro.reader.hmos、app-identifier=6917615791848364635、AGC Release 叶子）；
- 替换 `signing/howread-pro-release.p7b`（占位保留为 howread-pro-release-placeholder.p7b）；
- `build_hap_all.sh pro` 重打 4 个 Pro 产物。

### 验证
- 字节级校验：release 的两个 Pro 包内嵌 AGC Pro Profile 原文 + App ID 6917615791848364635；
  release 的两个普通版包内嵌普通版 App ID 6917615791336205965（互不串扰）；
- debug 包（普通版与 Pro）仍为本地占位 profile——云调试装 debug 包需 AGC 调试 Profile（绑设备）后替换重打。

## [2026-09-08] 鸿蒙：修复 Pro 与普通版桌面名称相同的问题

### 根因
桌面图标显示的是**模块级 EntryAbility_label**（entry/src/main/resources/{base,zh_CN}/element/string.json），
而 build_hap_all.sh 的变体切换（swap_variant）只替换了 AppScope 的 app_name——两个变体的
EntryAbility_label 均为「好好读」，导致桌面名称相同。

### 改动
- `build_hap_all.sh` swap_variant 扩展：pro 变体同时替换 entry 模块的 EntryAbility_label
  （base: HowRead→HowRead Pro；zh_CN: 好好读→好好读 Pro），restore_variant 按新备份文件名恢复；
- 重打 Pro 全部 4 个产物（普通版标签本就正确，无需重打）。

### 验证
- 包内编译资源（resources.index）：Pro 包含「好好读 Pro/HowRead Pro」，普通版仅「好好读」；
- 源文件构建后已恢复默认值（HowRead/好好读）；
- 模拟器实测：两应用共存，桌面 UI 文本同时出现「好好读」与「好好读 Pro」。

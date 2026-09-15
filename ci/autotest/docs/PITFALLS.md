# 自动化测试坑点清单（PITFALLS）

> 来源：2026-09-12 ~ 09-13 L1 功能用例编写与真机调试过程中遇到并解决的实际坑。
> 每条按「现象 → 根因 → 规避做法」组织，末尾附**升格建议分级**，供挑选哪些写入 AGENTS.md 规则。
> 涉及代码处均已落地到 `ci/autotest/cases/ui/tc_function.py` 对应助手函数。

---

## 一、设备侧（MIUI / EMUI 真机怪癖）

### 1. MIUI 密码框聚焦会打崩整个 uiautomator 服务 ⭐ 全会话最坑
- **现象**：AddRemoteDialog 的 password 框被 u2 `set_text`/点击聚焦后，`dump_hierarchy`、`screenshot`、`click` 全部超时失败；恢复要数十秒甚至需要重启 uiautomator。
- **根因**：MIUI「密码保险箱」(com.miui.contentcatcher) 在密码框获焦时接管无障碍服务，与 u2 的 UiAutomation 冲突。
- **规避**：密码类字段**一律走纯 ADB 输入**，不经 u2：先用 `dump_hierarchy` 提前取好密码框与按钮坐标（`_center_from_dump`），`input tap` 聚焦 → `keyevent 123`（移光标到尾）→ 40×`keyevent DEL` 清空 → `input text` → 直接 `input tap` 点「添加」。见 `_fill_password_adb` / `_addremote_fill_and_save`。密码无法回读验证（安全框屏蔽），由后续「目录看到测试书」的结果判定。

### 2. EMUI 安全键盘遮住对话框底部按钮
- **现象**：EMUI 弹自己的安全键盘后，「测试连接」「添加」等对话框底部按钮点击全部落空。
- **根因**：键盘面板覆盖在按钮上方，坐标点击命中键盘。
- **规避**：填表后统一调 `_dismiss_keyboard()`：先 `dumpsys input_method | grep mInputShown` 确认键盘确实弹出才按 back（无键盘时按 back 会误关 AlertDialog，因此不盲按）。

### 3. 长跑中途熄屏，之后所有操作静默失效
- **现象**：用例批量跑到中段，截图全黑、dump 空、点击无效，报错千奇百怪。
- **根因**：MIUI 在无人交互时自动息屏；u2 的 UiAutomation 在息屏后全部失效。
- **规避**：所有用例入口 `_ensure_home()` 第一行固定 `dev.wake_unlock()`；网络用例的预置/凭据路径也各自补一次。**新增用例一律走 `_ensure_home`，不要自己写 `start_app` 裸入口。**

### 4. EMUI 上 toast 经常抓不到
- **现象**：`d.toast.get_message()` 在 EMUI 上频繁返回 None（toast 一闪而过或被系统吞掉）。
- **规避**：结果判定**不依赖 toast**，改用 UI 状态（元素出现/消失）、落盘文件（app-Tags2.json）、服务器端日志（samba/apache）三方证据。FN-47 门禁判定反着用：「对话框未出现」即门禁生效。

### 5. EMUI set_text 可能追加而非替换
- **现象**：SMB 密码曾被填成 11 位（应为 10 位），服务器端 `NT_STATUS_WRONG_PASSWORD`。
- **根因**：EMUI 上 u2 set_text 偶发不清空原值直接追加（尤其字段被上次保存值预填时）。
- **规避**：`_fill()` 统一「点击 → clear_text → set_text → 回读比对」，不符则清空重填一次再验；掩码密码框按「掩码点数 == 明文长度」校验。**敏感字段回读必须验长度**。

### 6. Android 9 的 toybox find 不支持 `-size <N>c`
- **现象**：`find: not integer: 759844c`（Android 11 正常）。
- **规避**：跨版本清理/查找用纯 shell 循环 `wc -c` 逐个比对；`case "$b" in \[*)` 匹配 `[` 开头文件名不可靠，改参数展开取首字符。

### 6b. KSA(EMUI/Android 9) 的「USB 连接方式」弹窗反复弹出 ⭐ KSA 专属
- **现象**：跑到一半「USB 连接方式」系统弹窗盖住下半屏，wait_home 超时、点击全部落空；点掉后过几分钟又弹（USB 口/线接触不良重枚举触发）。
- **规避**：`_ensure_home` 里加 `_dismiss_system_dialogs()`——dump 检测到「USB 连接方式/Use USB to」就点「取消」或 back。**低端机上"主界面 10s 未就绪"先截屏看是不是系统弹窗，别急着改用例。**

### 6c. KSA 冷启后残留阅读器任务抢占前台
- **现象**：`start_app(cold=True)` 后 resumed activity 是 VerticalViewActivity（上一用例没退干净的阅读器），主界面永远等不到。
- **规避**：`_ensure_home` 的 wait_home 失败后 back×3 + 再等（阅读器 back 即退书回主界面）。

---

## 二、uiautomator2 框架层

### 7. UiObject 对象恒为真值，不能用 `a or b` 链
- **现象**：`d(text="x") or d(textContains="x")` 永远返回前者（哪怕不存在），后续 `.click()` 静默失败。
- **根因**：u2 的 UiObject 未实现 `__bool__`，包装对象总是 truthy。
- **规避**：一律显式 `if el.exists:` 判断；封装成 `_click_any()`（click_text → click_desc → textContains 三级）等助手，禁止 or 链。

### 8. JSON-RPC 瞬断（-32002）会自愈，别急着判失败
- **现象**：运行中偶发 `JsonRpc error -32002` / uiautomator crashed，随后一两个操作恢复正常。
- **规避**：`_safe_exists()` 返回三态（True/False/**None**=框架不可用），调用方对 None 退避重试 2 次后才降级或 SKIP；不可把单次异常当用例失败。

### 9. 非 clickable 节点 `el.click()` 无效
- **现象**：元素明明在 dump 里，click 后界面无任何变化。
- **根因**：u2 click 走无障碍 ACTION_CLICK，节点未标 clickable 时点击被丢弃；而坐标点击走 view 分发，不受此限。
- **规避**：行菜单（书行右侧 ⋮）这类节点用 `dump_hierarchy` 正则取 bounds 后按**坐标**点（`_click_row_menu`）；关键操作后必须验证「预期界面变化」而不是信任 click 返回值。

### 10. textContains / 相似文本误命中 —— 书架封面标题是位图
- **现象**：FN-13 找书行 "big25" 时长按到了**搜索框**（搜索框预填词也含 big25）；FN-28 点行标题实际点到顶栏同名标题。
- **根因**：书架/封面视图的书名是绘制位图，无文本节点，唯一能匹配到的文本节点反而是搜索框/顶栏；列表顶栏标题与行标题同名时 u2 返回树中第一个匹配。
- **规避**：`_ensure_list_row()` 先切**列表视图**（列表行有 title1 文本节点），并**排除屏幕顶部 0.22h 区域**内的匹配；按 y 坐标就近选节点而不是取第一个。

### 11. 长按会误触 IME 选择条 / 选择光标残留
- **现象**：长按后弹出的不是预期菜单，或文本选择光标挂在屏上干扰下一个落点。
- **规避**：长按失败重试前先 `back` 收一次（清掉选择状态），再换落点（`fn52` 用 4 个候选落点循环）。

---

## 三、应用侧行为（写用例必须知道的事实）

### 12. 冷启动会丢「只写内存」的偏好
- **现象**：在偏好里切了左右/上下翻页（单机行为）后，用 intent/冷启路径开书，设置丢失，回到默认垂直模式。
- **根因**：`AppSP.AppTemp`（readingMode、configSingeClick 等）只在特定时机落盘，force-stop 即丢。
- **规避**：切完模式后**只能热态开书**（`_open_reader_warm`，浏览路径开书绝不冷启）；用例还原时也一样。**这是 FN-07/16/50 类「模式相关」用例的公共前置。**

### 13. 水平(Book)模式的底栏功能键在垂直模式恒 GONE
- **现象**：跳页(thumbnail)、TTS(textToSpeach) 图标在垂直滚动模式下根本不在 UI 树里。
- **根因**：`HorizontalViewActivity.restoreFooterControls()` 只在水平模式点亮这两个键；该 fork 垂直 epub 不走 Horizontal。
- **规避**：测试侧绕行 = `_set_reading_mode` 切「左右翻页」后再热开书（应用侧一行修复见 COVERAGE.md 备忘）。工具条按钮可见性判断必须**同时认 id 和 content-desc**（`_HORZ_TOOLBAR_DESCS`），只认 id 会误判隐藏、点中心把已显示的工具条藏掉。
- **补充（FN-49/51 排查）**：**epub 在水平模式下 pagesCountIndicator 不出现**，页码读不到——页码断言类用例（翻页/音量键/跳页）选 **PDF** 作样本，别用 epub。

### 13b. 关键词检索开书会误中同名书
- **现象**：`textContains("big25")` 同时命中 PDF 基准书与 TXT 基准书（都叫 Bench25），FN-49/51 开成 TXT 且停在第 1 页，音量键「回翻方向」在第 1 页永远不动，断言误报「功能失效」。
- **规避**：需要精确控制样本时用 **intent 直开文件路径**（`dev.open_book_via_intent`，注意它会先 force-stop——冷态才生效，内存态偏好会丢）；另外**边界页单向操作不动**（第 1 页测回翻、末页测前进都会误报），先前进到第 3 页起测（`_reader_page_ready`）。
- **连带发现**：本 fork 实测 **VOLUME_UP=回翻、VOLUME_DOWN=下翻**（`isReverseKeys = Dips.isSmallScreen()`，小屏设备反向，与 AppState NEXT_KEYS 常量表相反）——方向类断言一律用「变化→反向键→变化」的方向无关式，别硬编码方向。

### 13c. 中央 tap/双击的 UI 切换在水平模式只切顶栏
- **现象**：中央 tap（或默认双击动作 SHOW_HIDE_UI）后页脚页码指示器（pagesCountIndicator）仍然可见，按「页脚是否可见」断言翻转 → 永远误报「未翻转」。
- **根因**：水平(Book)模式的 UI 切换只作用于顶栏（document_title_bar），页脚页码常驻；垂直模式才是整块工具条一起切。
- **规避**：UI 可见性/切换断言用**「顶栏(imageToolbar) + 页脚(currentSeek/currentPageIndex/pagesCountIndicator)」组合状态**比较（状态变了即切换生效），不单一依赖页脚；`_reader_show_toolbar` 的可见判断必须把 pagesCountIndicator 计入，否则会把已显示的界面当隐藏、点中心反而藏掉。
- **连带发现**：本 fork 存在**阅读器静默退出**的间歇性问题（翻页操作后 app 无声退到桌面/浏览页，无 crash 日志，与 FN-30/46 的远程打开静默失败疑似同族）——用例内做「存活校验 + 重开书重试」（`_reader_alive`）。

### 14. 工具条「点中央」是切换不是显示 —— 幂等点会反向
- **现象**：为确保工具条可见而点屏幕中央，结果把已显示的工具条藏掉。
- **规避**：`_reader_show_toolbar()` 先判可见（id + desc 双重判断），已可见绝不点。

### 15. 远程服务器凭据无法预置进配置文件
- **现象**：想直接往 app-State.json 写服务器行 + 密码实现「零输入」预置，密码写不进去。
- **根因**：行本体（管道分隔串）可预置（`_preset_server_lines`，id 用 `md5("smb|host|port")[:11]` 保证可重复插入/删除），但密码经 AndroidKeyStore AES/GCM 加密存 SharedPreferences，外部无法伪造。
- **规避**：**预置行 + 一次性凭据录入**：删旧行 → 冷启 → UI 添加一次（密码走 ADB 键入，见坑 1）→ 后续运行复用已入库凭据。SFTP startDir 语义是**家目录相对路径**（填 `books` 不填 `/home/howread/books`）。

### 16. AddRemoteDialog「添加」按钮无条件保存，不必先「测试连接」
- **事实**：positive 按钮直接落库不等测试结果；测试连接失败也不拦截。
- **规避**：MIUI 密码框坑（坑 1）导致测试连接按钮不可靠，直接 `input tap` 「添加」，由「目录出现测试书」终判。同样注意：dialogGoToPage **不自动关闭**（输完页码回车后对话框仍在）。

### 17. Pro 功能门控 🔒（isProFeaturesEnabled）
- **现象**：WebDAV/SMB/SFTP/AI 入口置灰带锁，点击无响应。
- **根因**：pro 包需 `BillingManager.isProUnlocked()`；新刷设备未解锁。
- **规避**：debug 包最快路径 `adb shell run-as com.leestudio.howread.pro.reader` 改 `shared_prefs/AppTemp.xml` 的 `iapProUnlocked=true`（须先 force-stop），或应用内「偏好→Pro 卡片」走桩购买。fdroid 包门禁反向利用做 FN-47。

### 18. 书库功能入口常在弹层/折叠容器里，位置随视图模式漂移
- **现象**：单机行为(ConfLineView)弹层绑在 valueView 上；「隐藏文件」开关在浏览视图弹窗(onListGrid)里；抽屉条目、偏好折叠容器不展开就找不到。
- **规避**：先切对视图/容器再找元素；desc「菜单」与浏览 tab 的 onGridList 都会弹**文件夹配置**弹窗，别混用（FN-33 踩过）。

### 19. resourceId 硬编码包名在改包名后全线静默失效
- **现象**：`com.howread.reader:id/...` 前缀对不上新包 `com.leestudio.howread.pro.reader`，元素明明在却定位不到（FN-03/04 曾三机全误报）。
- **规避**：任何 resourceId 选择器一律 `dev.pkg + ":id/..."`（`_rid()`）；改包名/加 flavor 后全局搜旧包名硬编码再跑。

---

## 四、工程与工具链

### 20. Windows cmd.exe / PowerShell 跑 adb、ssh 的转义陷阱
- **现象**：cmd 没有 ls/sleep/grep；`python -c "..."` 内嵌引号被截断成静默无效操作；PowerShell `ErrorActionPreference='Stop'` 把 adb 写到 stderr 的进度行（`1 file pushed`）当终止错误抛 NativeCommandError。
- **规避**：复杂操作一律写脚本文件到 `Z:\opt\Workspace\HowRead\tmp\scripts\` 再执行；PS 封装 adb 时临时 `$ErrorActionPreference='Continue'` 或判 `$LASTEXITCODE`；`.ps1` 含中文需 UTF-8 BOM；等待用 `ping -n N 127.0.0.1 >nul`。

### 21. adb push 中文文件名必乱码且丢扩展名
- **现象**：`297孙子兵法.prc` 推上去变成无扩展名的乱码文件，书库扫描按扩展名匹配即废；adb 仍打印 "1 file pushed" 误导成功。
- **规避**：测试书一律用 **ASCII 远端名**（`adb push <本地中文名> /sdcard/Download/book_xxx.<ext>`）；清理乱码残留不要在 Windows 侧回传乱码文件名（二次错乱），在设备侧按「无扩展名 + 精确大小」删。

### 22. 网络协议排障看服务端日志，别盲试客户端
- **事实**：samba 认证失败默认级别看不见（需 `log level=3`），客户端日志在 `/var/log/samba/log.<client-ip>`；WebDAV 在 `/var/log/apache2/webdav_error.log`。
- **规避**：协议用例失败第一现场去 50.23 查服务端日志（角色隔离：Windows 直连 50.23，不经 50.111 跳板）。

### 23. 编辑 Z: 网络映射上的文件后要 `sync` 落盘
- **规避**：每次经 Windows 编辑 `Z:\opt`（= Ubuntu `/docker/opt/`）代码后，跑一次 `ssh lee@192.168.50.111 "sync"` 强制脏页落盘，避免 Ubuntu 侧编译/执行读到半截文件。

### 24. pm clear 后 Pro 解锁键永不落盘，须预写 AppTemp.xml（2026-09-13 实测）
- **现象**：`--reset`（pm clear + 重装）后跑 Pro 门控用例（FN-22/23/24/26~30/43/44）会整体 FAIL——Pro 锁 `iapProUnlocked=false` 挡住全部 Pro 入口。
- **根因**：`iapProUnlocked` 是 AppSP 内存字段，落盘只发生在 `AppSP.save()`（仅两处调用：存储权限授予后的 `onActivityResult`、`AppProfile.save`）。SDK 30 上权限已被 `grant_setup` 预授（appops MANAGE_EXTERNAL_STORAGE），应用首启 `checkPermissions` 走"已授权"分支直接 `AppProfile.init`，**不触发 save()**，键永不落盘；且 pm clear 后 `shared_prefs/` 目录整个不存在。
- **规避**：`ensure_pro_unlocked`（run_all.py）三态处理：键已 true → 跳过；键为 false → 无引号 sed `/iapProUnlocked/s/false/true/`；**文件/键不存在 → `mkdir -p shared_prefs` + base64 解码写入最小 AppTemp.xml**（含 `iapProUnlocked=true`）。两个转义坑：① printf 的引号在 adb→sh→run-as 多层转义下必坏，base64 是唯一安全写法；② sed 带引号的 `s///` 同样必坏，无引号 `/行匹配/s/false/true/` 才稳。应用侧 `Objects.loadFromSp` 是通用反射加载（boolean 按名读、缺失回退默认值），预写单键文件会被正确读入且不影响其它字段。

### 25. 轮次前置：APK 版本一致性 + --reset/--no-install（2026-09-13 检视新增）
- **现象**：此前 L1 轮次**从不安装 APK**（只有 L0 的 SM-01 装），设备上的包可能是旧版本——整轮结果对的是旧代码且无任何告警（FN-30/46 修复验证时就靠人工核对 APK 时间戳才发现）。
- **规避**：`prepare_device`（run_all.py，worker 内、run_level 前）按 APK 文件名版本号与 `dumpsys package versionName` 比对：一致 → 跳过安装；不一致 → 自动升级安装 + 授权 + Pro 解锁。`--reset` = pm clear + 重装 + 授权 + Pro 解锁 + 重推基准书（全量回归金标准，代价：三协议凭据一次性重新入库，网络用例首轮多 ~3 分钟）；`--no-install` = 跳过安装仅告警（快速单用例调试）。**全量回归一律带 `--reset`**，单用例调试用 `--no-install`。

### 26. pm clear 清不掉外部状态文件 profile.HowRead —— AppState 的唯一持久化在外面（2026-09-13 实测）
- **现象**：`--reset`（pm clear+重装）后首启，"旧配置"原样回来——远程条目（WebDAV/SFTP）、浏览目录、书库文件夹全是旧的。KSA 全量回归 FN-02/07/14/16/34 全灭（书库空、Browse 无 Download），而 MI9 因旧配置恰好含 Download 侥幸全过，掩盖了问题。
- **根因**：`AppState.load` **只读** `/sdcard/HowRead/profile.HowRead/<机型>/app-State.json`（`AppState.save` 也只写它），内部无副本；pm clear 只清 `/data/user/0`，外部状态文件原封不动 → 首启即"恢复出厂备份"。
- **规避**：`prepare_device` 的 `--reset` 分支在 pm clear 后追加 `rm -rf /sdcard/HowRead/profile.HowRead`，让应用真正走 `defaults()`（displayPath=DOWNLOADS_DIR 等）。

### 27. 真出厂态「我的文件」页没有文件列表，Browse 导航必须经"书库文件夹"卡片（2026-09-13 实测）
- **现象**：删外部状态后的真出厂态，「我的文件」Tab 只有 远程区块（OPDS/WebDAV/SMB/SFTP）+ 书库文件夹卡片 + 搜索，**没有 Download 行、没有文件列表**——所有"我的文件→Download→书"的用例（FN-02/04/07/14/16/33/34）直接找 "Download" 文本全失败。
- **根因**：书库文件夹的默认卡片是 `/storage/emulated/0`，按路径末段显示为「0」（desc=`文件夹 0`）；文件列表要点卡片进入 /sdcard 根浏览页后才出现。
- **规避**：统一走 `_browse_dl_row`（tc_function.py）：先找 Download 行，找不到就点 desc 以"文件夹"开头的卡片进入根浏览页再找；6 处 Download 导航（FN-02/04/07/14/16/33）全部收口到该助手。卡片 `clickable=true`，u2 el.click() 可用。

### 28. 页脚 pagesCountIndicator 常显 ≠ 工具条已显示，判定必须用按钮行自身元素（2026-09-13 实测）
- **现象**：`--reset` 全量回归 MI9 上 FN-07/15~18/22/34~36/39 整批 SKIP、FN-41 FAIL，报"工具条未显示/偏好入口不可见"。
- **根因**：页脚（pagesCountIndicator/时间/电量）受 isShowToolBar 控制、常显；顶栏按钮行（imageToolbar/onDocDontext/thumbnail…）由中央点按切换，**两条独立可见性**。开书时"页脚可见+按钮行全隐藏"（FN-17 dump 只剩 pannelBookTitle+pagesCountIndicator），旧谓词把页脚当"已显示"，永远不点中央展开。
- **规避**：`_reader_bar_visible` 只认 `_BAR_IDS`（currentSeek/currentPageIndex/imageToolbar/goToPage1Top/textToSpeachTop/imageMenuArrow/onDocDontext/thumbnail/textToSpeach）+ desc；旧注释"pagesCountIndicator 必须计入"的诉求（别把已显示工具条藏掉）由与语言无关的 imageToolbar id 覆盖，更可靠（FN-50/51 的 bar_state 早已用它作顶栏标记）。

### 29. 出厂态开书停在第 1 页（封面），长按选词类用例要先翻页（2026-09-13 实测）
- **现象**：FN-22 长按正文无 dialogSelectText 弹层；dump 显示阅读器停 "1 ∕ 4" 封面页。
- **根因**："上次阅读位置"随外部状态被删，开书回到封面页——封面没有可选择文本。
- **规避**：fn22 改为"长按→无弹层→右分区翻页→再长按"最多 3 页；同类选词/批注/翻译用例同法。

### 30. 首页统计卡只有 statHours 打开月度统计对话框（2026-09-13 定位）
- **现象**：FN-42 "统计详情页未见月度/区间元素"，MI9/KSA 双机一致，截图停在书库列表页。
- **根因**：`statTotal` 的点击是 `openLibraryWithFilter("")` 跳书库列表，`statToday` 无点击行为；只有 `statHours → showMonthlyReading()`（MonthlyBarsView + 周月年切换）。旧用例按 (statTotal, statHours, statToday) 顺序点中 statTotal。
- **规避**：fn42 只找 statHours。

### 31. 热态开书后文档异步渲染,工具条元素延迟挂上（2026-09-13 实测）
- **现象**：warm-open 后立即找 bookPref/prefTop/currentSeek 全部不存在（"请稍候…"转圈、页面空白），固定短睡后依旧。
- **根因**：`_open_reader_warm` 只等 ViewActivity 出现,文档渲染是异步的——EPUB/PDF 大文件要数秒到数十秒,工具条 id 在渲染完成后才挂进视图树。
- **规避**：对阅读器内任何元素的查找用**轮询等待**（40s 上限 + 2.5s 间隔）,不要"打开→固定睡→单次查找"。

### 32. 目录跳转:选章必须跳过当前章,跳转判定要用指示器完整文本（2026-09-13 双机踩坑）
- **现象**：点章节行后断言"页码未变化"整批 FAIL——截图里其实**跳转成功了**。
- **根因**：①kw 列表首选 CHAPTER II 恰是常驻当前章(目录高亮行),点击当前章是 no-op；②每章章内页码都是「1 ∕ N」,用数字对 (1,N) 比较恒等；③指示器**完整文本**含章节名（「CHAPTER II…– 1 ∕ 17」→「CHAPTER III…– 1 / 17」）,跨章必变；垂直模式顶栏章节副标题（R.id.chapter）同理。
- **规避**：选章前读当前章上下文（chapter 副标题+指示器文本）,kw 命中当前章就顺延到下一候选;变化判定用完整文本,不用数字对。

### 33. EMUI 键盘顶起对话框后,预取坐标全废;应用内导出 chooser 可自动化（2026-09-13 定位）
- **现象**：SMB/SFTP 添加对话框在 KSA 上"凭据入库未成功"（MI9 同代码 PASS）；导出用例误判 SAF 不可自动化。
- **根因**：①「添加」按钮坐标取自**键盘弹出前**的 dump,EMUI 键盘弹出顶起对话框后旧坐标点空（WebDAV 分支因"先收键盘+按文本重找"幸免）;②导出走的是**应用内** chooser（ChooserDialogFragment TYPE_CREATE_FILE,预填文件名）,不是系统 SAF。
- **规避**：密码键入后 `_dismiss_keyboard` → 重 dump 重算确认键坐标再点;导出用例直接点 exportButton → chooser 确认键 → find zip 落盘。

---

## 五、升格为 AGENTS.md 规则的建议分级（2026-09-13 已裁决：A 级全进）

> **落地状态**：A 级 7 条已全部进 AGENTS.md「Known Gotchas」——1→**22**、3→**23**、12→**24**（新增）；5→gotcha 21③、19→gotcha 19、21→gotcha 16、22→gotcha 21⑤（既有规则，确认覆盖）。B/C 级维持测试侧备忘，未入规则。

### 34. 应用布局里 android:maxHeight 写在 ScrollView 上是无效属性（2026-09-13 实锤）
**现象**：小屏机(KSA 720×1520)「我的文件」根页在添加若干远程条目后,书库文件夹卡片整个消失。
**根因**：平台 ScrollView 不支持 android:maxHeight(静默忽略),远程区块无限增高,
把 weight=1 的文件夹列表 RecyclerView 挤成 0px(dumpsys 实测 1150px/0px/230px)。
**规避**：自定义 MaxHeightScrollView(onMeasure 里套 AT_MOST 上限),已在 fragment_browse2 两处生效。
**建议**：应用侧已修复;测试侧写布局相关断言前先 dumpsys 看真实高度,别信 XML 声明。

### 35. 翻页 pager 未接锁定;锁定只禁页内平移/缩放,且状态无 UI 回读（2026-09-13 审计）
**事实**：阅读模式仅 上下翻页/左右翻页/演奏模式,翻页拖动走 VerticalViewPager
(onInterceptTouchEvent/onTouchEvent 只看 isEnableHorizontalSwipe/isSelectTexByTouch);
锁定门控在 PageImaveView(:809 fling/:936 拖动);lockUnlock 仅换图标,desc 静态,
上下翻页文本阅读器底栏没有页码指示器。
**规避**：FN-34 只能做开关冒烟;「锁定禁翻页」不可作为断言。
**建议**：给 pager 接锁属产品决策(与 lockBooksByDefault=true 叠加=每本书开篇即不可拖动),勿单方面改。

### 36. 状态栏位置只移动 bottomPanel;偏好弹窗是可拖拽容器且分区默认折叠（2026-09-13）
**事实**：statusBarPosition 移动的是 bottomPanel(pagesTime/pagesCountIndicator/pagesPower),
currentSeek 永在底部不动;阅读偏好弹窗(DragingPopup)整窗可拖拽,swipe 会拖走弹窗而非滚动内容;
[状态栏]等分区默认折叠;[位置]行只有「值」TextView 带监听,行标签点了无效。
**规避**：全程 resourceId 定位 + 点分区头展开 + 点值不点标签,禁用 swipe 滚动(见 _open_prefs_popup/_open_statusbar_settings)。

### 37. toybox find 两个坑：-newermt 'bad date';MIUI /sdcard 上静默列空（2026-09-13 实锤）
**现象**：导出 zip 已生成,`find -name -newermt '-3 minutes'` 报 bad date(stderr 被 2>/dev/null 吞);
MIUI 11 上 `find /sdcard -name x.zip` 返回空而 `ls` 可见同一文件。
**规避**：文件存在性检查用 `ls <精确路径/名>`(导出文件名含秒级时间戳,天然唯一),别用 find。

### 38. 浏览列表记住滚动位,单向向下找会漏掉上方的书（2026-09-13 实锤）
**现象**：同一台机器 warm-open 时而找不到明明存在的书。
**根因**：浏览页恢复上次滚动位置,_find_text_scrolled 只向下滚。
**规避**：第一遍找不到就滚回顶部再找一遍(见 _open_reader_warm 第二次搜索)。

### 39. 上一用例残留阅读器会让热态复用开错书（2026-09-13 KSA 实锤）
**现象**：fn18 没退出阅读器,fn40 的热态开书直接复用了残留的 Alice,验证读了别的书。
**规避**：用例结束必须真正退出阅读器(只 back 一次关的可能是弹窗);治理在产生残留的一侧——
在下游用例里补 _exit_reader 防御,无阅读器时反而会把应用按退到桌面(round-3 MIUI 桌面现场)。

### 40. 侧边栏日夜切换排查入手点（2026-09-13）
**事实**：抽屉按钮按 appTheme∈{DARK,DARK_OLED} 判向,写 appTheme+isDayNotInvert 后重启;
阅读器日夜切换只翻 isDayNotInvert(退出后外壳主题不变属设计)。3 台真机 11 次混合切换零复现卡夜间。
**规避**：复现时先抓 `logcat -s DayNight`(applyDayNight 已埋探针:点击判向/应用结果两条日志),
再对照 app-State.json 的 appTheme/isDayNotInvert/isSystemThemeColor;另查 WebDAV 三路合并是否回灌旧主题。

### 41. 页码格式百分比只作用于纯数字段（2026-09-13）
**事实**：TxtUtils.deltaPage 只在纯数字路径路由 PAGE_NUMBER_FORMAT_PERCENT;带书签的 PDF/EPUB
指示器是「标题 – N ∕ M」复合格式,切百分比后文本仍不含 %。
**规避**：fn40 断言=阅读器出现 %(强信号)或回读 pageNumberFormat 行值=百分比(兜底)。

### A. 强烈建议入规则（跨会话必踩、不写就会重犯）
| 坑 | 理由 | AGENTS.md 落点 |
|---|---|---|
| 1 密码框打崩 u2 | 耗时最长的坑，任何涉及密码输入的自动化/手工复现都适用 | gotcha 22（新增） |
| 3 息屏杀自动化 | 所有长流程自动化公共前置 | gotcha 23（新增） |
| 5 set_text 追加 | 数据损坏类故障（密码错一位）极难排查 | gotcha 21③（既有） |
| 12 冷启动丢内存偏好 | 应用侧行为，做任何「改设置→验证」流程都适用 | gotcha 24（新增） |
| 19 resourceId 硬编码包名 | 已经造成过三机误报 | gotcha 19（既有） |
| 21 adb push 中文乱码 | 已有 gotcha 16，但建议把「ASCII 远端名」写成正则级强制 | gotcha 16（既有） |
| 22 服务端日志第一现场 | 排障方法学，避免在客户端盲试浪费时间 | gotcha 21⑤（既有） |

### B. 可选入规则（价值高但偏测试框架内部）
| 坑 | 理由 |
|---|---|
| 2 EMUI 键盘遮按钮 | 已有 gotcha 21②，可把「先查 mInputShown 再 back」的细节补进去 |
| 4 toast 不可靠 | 已有 gotcha 21④，可补充「三方证据判定」原则 |
| 7 UiObject 恒真值 | u2 通用陷阱，任何 u2 脚本都适用 |
| 9 非 clickable 节点 click 失效 | 同上 |
| 10 位图书名/顶栏同名 | 只影响本应用 UI 结构 |
| 15/16 凭据加密+添加无条件保存 | 只影响网络用例设计 |
| 20 PS stderr 陷阱 | 已有 gotcha 17①，可补充 $LASTEXITCODE 判法 |

### C. 仅测试侧备忘（不必进 AGENTS.md）
- 坑 6（Android 9 find）、8（JSON-RPC 自愈）、11（选择光标残留）、13/14（水平模式底栏/幂等点击）、17（Pro 解锁路径，已在 gotcha 21①）、18（弹层位置）、23（sync 落盘，已有政策 ④）。

42. **API 34 dumpsys 字段改名（2026-09-15 AVD 实测）**：`dumpsys activity activities` 的
   `mResumedActivity` 在 API 34 改名 `ResumedActivity`/`topResumedActivity`，`grep mResumedActivity`
   在模拟器恒空 → intent 开书/阅读器到达判定全盲（截图里阅读器已渲染仍报失败）。用
   `grep ResumedActivity`（三种形态的公共子串）通吃新旧。已修 driver.py+tc_function.py 共 14 处。
43. **API 34 SELinux 拒绝应用读 /sdcard 符号链接（重要）**：logcat 实证
   `avc: denied { read } for name="sdcard" ... tclass=lnk_file permissive=0`（untrusted_app →
   mnt_sdcard_file）。应用从 /sdcard 起步的文件树整个列不出来（Browse 只剩网络区段）、书库不索引
   Download → 13 个用例失败/跳过。真机 API 9-11 策略允许故从未暴露；**疑似 Android 11+ 全新安装
   场景的真实兼容性问题**，建议应用侧勘探浏览根路径（如改用 /storage/emulated/0 直连）。
44. **AVD 英文 locale 与测试套件冲突**：devices.json 曾约定"英文 locale+用例英文兜底"，但 L1 用例
   的 Tab/抽屉选择器只写中文（click_desc("菜单")/("我的文件")等）→ 首页四 Tab 全不可达。
   解法：`adb shell settings put system system_locales zh-CN` + 重启模拟器（`cmd locale set-locales`
   该镜像不存在；`setprop persist.sys.locale` 被 SELinux 挡，adb root 也不行）。
45. **AVD 冷启动网络坑**：冷启动后可能只有 10/8 直连路由无默认路由（报 Network is unreachable），
   重加 `ip route add default via 10.0.2.2 dev eth0`；ICMP 经模拟器 NAT 经常不通（ping 100% 丢包
   但 TCP 正常），验证连通一律用 `printf 'OPTIONS / HTTP/1.1\r\n\r\n' | nc -w 4 <ip> <port>`。
46. **模拟器启动参数**：本机 `-gpu host` 会挂死（进程在但 adb 永不上线，需杀 Emulator.exe+清理
   *.lock）；已知可用组合 `-no-snapshot -gpu swiftshader_indirect -memory 3072 -no-boot-anim`。
47. **AVD 输入/渲染怪癖（未解，真机不受影响）**：音量键翻页（疑被路由到系统音量）、长按选词弹层、
   点按分区翻页、暗色主题截图像素对比，在 AVD 上失败但同用例 MI9/KSA 全过。判定为模拟器输入
   注入/SwiftShader 差异；AVD 轮报告需注明这些项以真机为准。

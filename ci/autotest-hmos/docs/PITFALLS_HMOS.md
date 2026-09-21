# PITFALLS_HMOS — 鸿蒙自动测试踩坑实录（现象 → 根因 → 规避）

按安卓 PITFALLS.md 的组织方式记录；条目分级：A=升格为规则，B=框架已内置规避，C=知晓即可。

## 一、设备/工具链

1. **hdc 看不到模拟器**（list targets 空）→ 模拟器 hdc 走 TCP，config.ini `hw.hdc.port=notset`
   但实际固定监听 **127.0.0.1:5555** → 每次 `hdc tconn 127.0.0.1:5555`（HdcDevice 构造时自动做）。【B】
2. **hdc 版本要跟镜像对齐** → 模拟器 API24（6.1.1.125），统一用 DevEco 自带
   `D:\Program Files\Huawei\DevEco Studio\sdk\default\openharmony\toolchains\hdc.exe`；
   D:\openHarmonySDK\23（6.1.0.32）也能连但版本旧。支持 HDC 环境变量覆盖。【A】
3. **uitest 不在 PC 侧 SDK** → 它是设备侧 /system/bin/uitest，经 `hdc shell uitest ...` 用。【C】
4. **`hdc shell` 里没有 findstr/grep 特定参数差异** → toybox；管道 OK；`hilog -x`（打印后退出）
   比 `hilog`（流式）适合脚本。【B】
5. **hilog -x 输出被系统日志淹没** → 断言只认 `A00000/HRTEST:` 前缀行（domain 0x0000），
   应用 console.* 日志在 `A03d00/JSAPP`。【B】
6. **file recv 偶发竞态**（dumpLayout 写入未完成时 recv 失败）→ dump() 内置一次重试。【B】
7. **Windows cmd 转义**：复杂操作写脚本文件执行，勿内联一行式（同安卓 17 条）。【A】

## 二、UI 自动化

8. **注入滑动/侧区点击翻不了页**（swipe/dircFling/左右区 tap 全无效，click 正常）→
   Reader 页面 Stack 里全屏手势 Column 盖在 Swiper 上、三个 zone Row 又都居中叠放
   （Reader.ets 3835-3970 结构），触摸事件到不了 Swiper；疑似应用侧布局/手势仲裁问题
   （已报用户）。**规避**：音量键 `uitest uiInput keyEvent 16/17`（inputConsumer 订阅）
   是确定性翻页通道，page_change_done 事件可靠。【A，含应用侧待修线索】
9. **UI 无 resourceId 体系** → dumpLayout 的 text/id/key/hint/bounds 均可用；应用侧已给
   底部 4 tab、退出按钮、跳页输入、TOC/测试按钮补了 `.id()`（P1）；图标类按钮只能按
   坐标（工具条 y≈216 等间距 x=231..1239，见 tc_function.py 头部地图）。【B】
10. **前台判定不要 hidumper** → dumpLayout 树第二个节点带 bundleName/abilityName/pagePath；
    `current_page()` 直接解析。【B】
11. **文本定位歧义**："书库"同时是 tab 名/分区头/书库文件夹行名；"添加"每分区一个 →
    精确匹配（contains=False）或按 section 头 y 坐标选取（`_click_add_after`）。【B】
12. **文本输入用 `uitest uiInput text`**（先点击聚焦再 `text <内容>`）比坐标 inputText 可靠；
    密码/安全框回读不可用，靠结果判定（同安卓经验）。【B】
13. **偏好页 Toggle 不能点行文本** → 行右侧独立 Toggle 控件（type=Toggle，dump 有 checked
    属性）；`_click_toggle_near` 按 label y±80 定位。【B】
14. **事件断言要清缓冲** → wait_event 扫的是 hilog 全缓冲；跨步骤断言前先 `hilog_clear()`，
    否则旧事件（如上一本书的 book_open_done）会误满足断言（SM-04 0/0 书案例）。【A】

## 三、应用侧行为（事实）

15. **冷启动会跑引擎自检**（FMT demo.*、EPUB 冒烟日志），非错误。【C】
16. **重装后书库自愈**：ensureDemoBook 重建 demo 书（8 本），home_ready recent 计数可断言。【B】
17. **bm clean -c 清掉 cache 书**（种子书在 cacheDir）→ --reset 后必须重新种子。【B】
18. **直写沙箱被拒**：shell uid 无权限；应用沙箱也看不到 /data/local/tmp（uri 导入报
    13900002 ENOENT）→ 唯一投放通道 = hrSeedUrl 钩子 http 下载（或 WebDAV UI 下载）。【A】
19. **debug→release 覆盖安装**：先 bm uninstall（PORTING_PLAN 既有坑）。【A】

## 四、稳定性发现（2026-09-21，模拟器 debug v0.9.10 x86_64，已留存 faultlogger）

20. **appfreeze**（20260921003445）：big25.pdf（2600 页）快速连续音量翻页 → 应用冻结被
    踢出前台。SM-04 已改用轻量 demo.cbz 翻页，big25 留给 L2 专项。
21. **cppcrash SIGSEGV**（20260921010408）：`fz_do_always`（libmupdf.so）× libmupdf_napi.so
    的 NativeAsyncWork 线程；触发场景=FN-08 阅读等待期（Alice demo EPUB 打开约 30s 后），
    崩溃时进程 RSS ≈1.16GB。疑似 NAPI 异步任务与文档生命周期竞争 + 内存膨胀。
    → FN-08 在修复前会持续 FAIL（这是用例在正确工作）。
22. **big25 打开后续开书失败**：打开并 Back 大书后，再开其它书 Reader 呈 0/0 空文档
    （book_open_done/fail 均不触发，进程 pid 已变——应用曾重启）。
20-22 均待应用侧排查；CI 侧已把相关路径隔离出冒烟。

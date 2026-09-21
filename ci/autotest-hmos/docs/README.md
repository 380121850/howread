# ci/autotest-hmos — HowRead Pro（HarmonyOS）自动测试 CI

与安卓侧 `ci/autotest/` 平行、按平台区分的黑盒 UI 自动测试框架。
技术路线：**hdc + uitest（dumpLayout/uiInput）+ snapshot_display + hilog**，
事件/耗时断言依赖应用侧 **HRTEST 打桩日志**（`harmony/entry/src/main/ets/model/TestLog.ets`，
`evt=<name> ts=<epoch-ms> k=v`，debug/release 均保留）。

## 运行（Windows 测试宿主，模拟器需先启动）

```bat
cd /d Z:\opt\librera\LibreraReader\ci\autotest-hmos
python run_all.py                :: L0 冒烟（默认 Pura 90 模拟器 127.0.0.1:5555）
python run_all.py --level L1     :: 功能核心子集（自动起 seed/http + ai_mock 服务）
python run_all.py --level L2     :: PF 性能 + ST monkey
python run_all.py --cases SM-04,FN-01        :: 用例过滤
python run_all.py --serial <真机serial>       :: 真机（hdc list targets 取值）
python run_all.py --reset                    :: bm clean + 重装 + 重新种子
```

- 退出码 0 = 全 PASS（SKIP 不计）——CI 门禁信号。
- 结果：`results/<时间戳>_<层级>_ui-hmos-emu|device/{report.md,run.log,evidence/}`，
  `results/LATEST.txt` 指向最新一轮。
- HAP 取 `harmony/dist/debug/HowRead-Pro-*-x86_64-hmos.hap`（最新 mtime），
  可 `--hap` 指定；设备版本一致时跳过安装。

## 架构

```
run_all.py          入口/编排（--level/--serial/--cases/--reset；Tee 日志；门禁退出码）
lib/hdcdriver.py    HdcDevice：hdc/uitest/hilog/snapshot/faultlogger 原语
                    + step()/run_case()（超时线程/重试/心跳/失败取证，安卓 driver 语义平移）
lib/report.py       Markdown 报告
cases/ui/tc_smoke.py      L0 SM-01~07
cases/ui/tc_function.py   L1 FN-01~18（阅读器/书库/浏览/网络/AI/语言）
cases/ui/tc_special.py    L2 PF-01/02 + ST-01
config/devices.json 模拟器/真机档案 + bundle + fixtures（pro 唯一）
config/cases.yaml   case_meta（层级/优先级/超时/重试）+ 阈值 + 50.23 测试服 + ai_mock
tools/ai_mock.py    OpenAI 兼容 mock（自安卓 CI 复制）
```

## 关键机制（详见 docs/PITFALLS_HMOS.md）

- **前台判定**：dumpLayout 树内含 bundleName/abilityName/pagePath，免费获得。
- **翻页通道**：注入滑动到不了 Reader 的 Swiper（手势层遮挡）——统一用音量键
  `uitest uiInput keyEvent 16/17`（inputConsumer 订阅，page_change_done 事件可靠）。
- **测试书投放**：`aa start --ps hrSeedUrl http://<host>:8790/<book>`（应用自身下载入沙箱
  cache 并入库，seed_done 事件确认）；run_all 自动起 http.server。
- **崩溃检测**：`/data/log/faultlog/faultlogger/` 目录新增 `cppcrash/jscrash/appfreeze-<bundle>-*`
  文件扫描（step 粒度增量对比）。
- **取证**：截图=snapshot_display，UI 树=dumpLayout JSON，日志=hilog -x，按
  `evidence/<serial>/<CASE_ID>/` 归档，step 失败自动三件套。

## 用例词汇（zh-CN 模拟器实测）

底部 tab（id `app_tab_0..3`）：首页/书库/我的文件/偏好。
阅读器工具条 y≈216：231=跳页 357=TTS 483=书签 609=目录 987=AI翻译 1113=阅读设置。
偏好页深处的"退出确认对话框"行 + 右侧 Toggle（`_click_toggle_near` 按 y 邻近定位）。

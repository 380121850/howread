# HowRead 自动测试方案（四层体系 · 分层分级）

> v2.0（2026-09-06）　适用：HowRead Android（包名 `com.howread.reader`，品牌"好好读"）
> 位置：`LibreraReader/ci/autotest/`（工程一部分）　相关：ARCHITECTURE.md / ENV_DEPENDENCIES.md

---

## 1. 四层测试体系

| 层 | 名称 | 运行环境 | 触发 | 耗时 | 覆盖内容 |
|---|---|---|---|---|---|
| **L-UNIT** | 单元层（JVM） | CI runner（Ubuntu 构建服务器）`gradlew testXxxDebugUnitTest` | 每次提交/构建 | <1min | 纯 Java 逻辑：数值/字符串/自然排序/MD5/书签 POJO |
| **L-INTEGRATION** | 集成层（JVM+Robolectric） | 同上（Robolectric 提供 Android API 假实现） | 每次提交/构建 | ~2min | AppState JSON 持久化往返、书签存储增查、AI 配置字段 |
| **L-UI-AVD** | UI 层（模拟器） | MedicineAVD（x86_64），`run_all.py --avd` | 每版本 | ~4min | L0 冒烟全集（与真机无关的核心路径） |
| **L-UI-DEVICE** | UI 层（真机） | MI9 / P20 / KSA 三台 | 每版本（门禁） | 15~40min | L0 冒烟 + L1 功能回归 + L2 性能/稳定性专项 + 多 flavor |

**分层原则**：能在 JVM 断言的逻辑不下沉到设备（快、稳、便宜）；Robolectric 覆盖"需要 Android API 但不需要真实渲染"的存储/偏好层；AVD 验证安装-启动-开书主链路的设备无关性；真机承载兼容性、性能与稳定性。

## 2. L0/L1/L2 与用例优先级

### L0 冒烟（全 P0，门禁：全绿才允许提测）
| ID | 用例 | 层 | 优先级 | 超时/重试 |
|---|---|---|---|---|
| SM-01 | 安装/升级安装 + 权限预授权 | UI-AVD + UI-Device | P0 | 300s / 0 |
| SM-02 | 冷启动 + 首启弹窗处理 | UI-AVD + UI-Device | P0 | 300s / 1 |
| SM-03 | 底部 Tab 遍历（首页/书库/我的文件/偏好） | UI-AVD + UI-Device | P0 | 300s / 1 |
| SM-04 | 打开 PDF + 翻页×5 + 退出 | UI-AVD + UI-Device | P0 | 300s / 1 |
| SM-05 | 打开 EPUB + 翻页×5 | UI-AVD + UI-Device | P0 | 300s / 1 |
| SM-06 | 退出重进持久性 | UI-AVD + UI-Device | P0 | 240s / 1 |
| SM-07 | 全程无 crash/ANR | UI-AVD + UI-Device | P0 | 120s / 0 |

### L1 功能回归（P0 100%，P1 ≥90%）
| ID | 用例 | 优先级 | 说明 |
|---|---|---|---|
| FN-08 | intent 打开（冷/热） | P0 | OpenerActivity 正确 MIME 路径 |
| FN-03 | 书签添加→书签笔记可见 | P0 | 阅读器菜单+对话框 |
| FN-04 | 全文搜索命中 | P0 | 搜索页 editSearchText |
| FN-01 | 最近阅读列表 | P1 | |
| FN-02 | 收藏/我的珍藏 | P1 | 书库未扫描设备 SKIP（见遗留） |
| FN-05 | 阅读配置子页 | P1 | |
| FN-06 | 夜间模式切换/还原 | P1 | 截图对比验证 |
| FN-07 | TTS 朗读 | P2 | 入口未定位，当前 SKIP（遗留） |

### L2 专项
| ID | 用例 | 优先级 | 阈值 |
|---|---|---|---|
| ST-01 | 受控 monkey | P0 | 正式 30min：0 crash/0 ANR（试跑 5min） |
| PF-01 | 冷启动耗时（中位数） | P1 | MI9/P20 ≤2000ms，KSA ≤3000ms（Debug 包超阈值为已知发现） |
| PF-03 | 内存趋势（开书后增长） | P1 | 开书→翻 30 页 PSS 增长 ≤30% |

### JVM/Robolectric 层用例（随 gradle 执行）
| 类 | 层 | 断言数 | 覆盖 |
|---|---|---|---|
| MyMathTest | UNIT | 10 | percent、longValueOfNoException 边界 |
| StringUtilsTest | UNIT | 12 | md5、split/merge、自然排序、char[] 分词 |
| TxtUtilsTest | UNIT | 18 | formatInt/标签提取/拼音加粗/进度百分比 |
| AppBookmarkTest | UNIT | 8 | 页码换算与钳制、equals/hashCode 语义 |
| AppStatePersistTest | INTEGRATION | 2 | AI 配置字段 JSON 往返（真实文件 IO） |
| BookmarksDataTest | INTEGRATION | 3 | 书签增查、同毫秒 key 去重、p>1 归零 |

## 3. 门禁标准

- **提测门禁**：L0（真机 google×3）全绿 且 JVM 层全绿。
- **版本发布门禁**：L0 + L1（P0 100%，P1 ≥90%）+ L2 ST-01 30min 无 crash + JVM 层全绿。
- SKIP 不计失败但必须在报告中列明原因。

## 4. 运行方式

```bash
# 单元层 + 集成层（Ubuntu 服务器）
bash ci/autotest/run_unit.sh google

# UI 层 - AVD（先启动模拟器）
python ci/autotest/run_all.py --level L0 --avd

# UI 层 - 真机
python ci/autotest/run_all.py --level L0          # 冒烟（3 机并行）
python ci/autotest/run_all.py --level L1          # 功能回归
python run_all.py --level L2 --serial 48fee174    # 专项

# 一键全量（Windows）
ci\autotest\final_run.bat
```

结果：`ci/autotest/results/<时间戳>_<层级>_<层>/`（report.md + run.log + evidence/）。

## 5. 防卡死机制（全局）

1. 每用例强制超时（默认 300s，cases.yaml 按用例覆盖；ST-01 1900s）。
2. 失败自动重试（默认 1 次），重试通过记 PASS 并标注尝试次数。
3. >30s 操作打印心跳行（`⏳ 心跳 | ... 已运行 Ns`），肉眼判断卡死。
4. 超时用例自动截图 + UI dump + 强停 app，不阻塞后续用例。
5. ADB 命令自身带 subprocess timeout，防止 shell 挂起。

## 6. 实现期确认的关键事实（v2.0 迁移期新增）

1. **intent 仅冷态生效**：VIEW intent 打开书必须在 force-stop 后投递——warm 态时任务栈复用走 `onNewIntent`，OpenerActivity 不处理导致 intent 被静默吞掉（MI9/P20/KSA 一致复现）。driver 已固化冷态投递。
2. **OpenerActivity MIME 白名单**：只接受 application/pdf|epub|mobipocket 等，octet-stream 会被系统静默丢弃。
3. **多包名并存触发选择器**：google 与 fdroid/pro（同包名 `.pro`）并存时 VIEW intent 弹 ResolverActivity，driver 在等待窗口内轮询点"好好读/仅此一次"。
4. **MedicineAVD 环境问题（遗留）**：该 AVD 系统镜像当前损坏——整屏黑屏不渲染、`mCurrentFocus=null`、guest 端工具偶发 Segmentation fault，UI-AVD 层暂无法执行。**框架已就绪**（`run_all.py --avd` + x86_64 选包 + 英文关键词兜底），需对该 AVD 执行 wipe data 或新建 AVD 后即可运行。
5. **书库目录语义**：`BookmarksData.getAll()` 经 `AppProfile.getAllFiles` 扫描 `SYNC_FOLDER_PROFILE` 下 **`device.` 前缀**子目录的 `app-Bookmarks.json`（Robolectric 集成测试按此构造临时环境）。

## 7. 已知遗留（当前版本）

| 项 | 状态 |
|---|---|
| PF-01 Debug 包冷启动 3.3s 超阈 | 真实发现，待 release 包复测 |
| KSA/P20 书库首扫不收录 Download | FN-02 在这两台 SKIP，待排查扫描时机 |
| FN-07 TTS 入口未定位 | 用例 SKIP，待勘探 |
| KSA 阅读器菜单小屏只有顶栏 | 交互层级兼容性观察项 |
| MedicineAVD 镜像损坏 | UI-AVD 层挂起，待 wipe data / 新建 AVD |


## 8. 远程阅读优化专项(2026-09-19)

- **新增 FN-55~60 + PF-04**(用例设计/日志断言矩阵见 COVERAGE.md 同名小节),
  运行方式:`python run_all.py --level L1 --serial 48fee174 --cases FN-55,FN-56,FN-57,FN-58,FN-59,FN-60`
  及 `--level L2 --cases PF-04`。
- **环境依赖**:50.23 WebDAV/SMB/SFTP 三目录的 `big_pdf.pdf`(500 页/268MB,已投放);
  MI9 需装含在线阅读优化的 debug pro 包(≥v1.3.13);远程缓存清理 helper 依赖
  缓存根位于 /sdcard/Download/HowRead/Cache(默认配置,真机已核实)。
- **判定方式**:UI 仅作操作通道,断言全部落在 REMOTE/BENCH 日志(每动作前
  `logcat -c` 防残留);性能阈值在 cases.yaml `remote_firstpaint` 按设备/网络调整。
- **已知边界**:测试书页数 <200 时 FN-55/57/60 SKIP(环境问题);FN-56 的
  `version verified` 为后台异步校验,用例给 20s 窗口。

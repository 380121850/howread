# desktop/ — Windows 桌面版（2026-10-25 起实施；路线：JVM + Compose Multiplatform）

状态：**M0（引擎链路）+ M1（应用骨架）已完成**，工程可构建可运行。

- 结构：`engine/`（MuPDF JNI 封装）/ `core/`（纯 JVM 业务，建设中）/ `app/`（Compose Desktop UI）/ `scripts/`（构建脚本）/ `engine/CMakeLists.txt`（引擎构建，由 Builder/CMakeLists.txt 适配生成后维护）
- 构建（50.111）：`bash scripts/build_engine_cmake.sh all` → `cd desktop && ./gradlew :app:dist`
- 运行：`desktop/build/dist/run.bat`（Windows，需 java 17+）或 `run.sh`
- 无 GUI 冒烟：`java -Djava.library.path=build/dist/engine -cp "build/dist/app/*" howread.app.MainKt --smoke <书...>`
- 版本真源：`desktop/VERSION`（当前 0.1.0，独立序列）
- 已知边界：azw3/djvu/doc(老Word) 走转换层（后续里程碑）；msi 安装包暂缓（便携 dist 先行）
- 历史路线说明（技术对比与决策）见工作区 `HowRead-Windows版本环境与实现方案分析` / `技术路线对比` / `架构与实现方案_v1` 三份文档（2026-10-25）

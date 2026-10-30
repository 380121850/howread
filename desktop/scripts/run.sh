#!/bin/bash
# HowReadPro M1 启动脚本（dist 目录内运行；需 PATH 里有 java 17+）
DIR="$(cd "$(dirname "$0")" && pwd)"
exec java -Djava.library.path="$DIR/engine" -cp "$DIR/app/*" howread.app.MainKt "$@"

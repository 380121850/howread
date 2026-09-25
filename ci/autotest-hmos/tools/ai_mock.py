# -*- coding: utf-8 -*-
"""OpenAI 兼容 AI mock 服务（HowRead 自动测试环境依赖）
用法: python tools/ai_mock.py [port]     # 默认 8770
应用侧配置: AI 大模型 -> 协议 openai / 地址 http://<PC-IP>:8770/v1 / 模型 howread-test-model / key 任意
端点:
  GET  /v1/models                -> 模型列表
  POST /v1/chat/completions      -> 固定回复（含 usage）；stream=True 时按 SSE
                                    分 4 片逐段推送（每片间隔 0.8s，验证增量上屏）
"""
import json
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL = "howread-test-model"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8770


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/").endswith("/models"):
            self._send(200, {"object": "list", "data": [
                {"id": MODEL, "object": "model", "owned_by": "howread-autotest"}]})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            req = json.loads(raw)
        except ValueError:
            req = {}
        prompt = ""
        msgs = req.get("messages") or []
        if msgs:
            prompt = str(msgs[-1].get("content", ""))[:200]
        reply = "[AI-MOCK] 收到请求（模型=%s）。这是 HowRead 自动测试的固定回复。摘要内容：本书为自动化测试样本。" % req.get("model", MODEL)
        # 双语批量翻译请求带 "N. 原文" 编号段落：按编号逐段回复（对齐真实
        # 模型行为），使应用侧的分段解析、注入与缓存复用可被端到端验证。
        user = str(msgs[-1].get("content", "")) if msgs else ""
        segs = re.findall(r"(?m)^\s*(\d+)[\.、\)]\s*(.+)$", user)
        if len(segs) >= 2:
            reply = "\n".join("%s. [AI-MOCK] 译：%s" % (n, t[:40]) for n, t in segs)
        if req.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            pieces = ["[AI-MOCK] 流式", "回复：收到请求（模型=%s）。" % req.get("model", MODEL),
                      "这是 HowRead 自动测试的固定回复。", "摘要内容：本书为自动化测试样本。"]
            for p in pieces:
                chunk = json.dumps({
                    "id": "chatcmpl-howread-mock", "object": "chat.completion.chunk",
                    "created": 0, "model": req.get("model", MODEL),
                    "choices": [{"index": 0, "delta": {"content": p}, "finish_reason": None}],
                }, ensure_ascii=False)
                self.wfile.write(("data: %s\n\n" % chunk).encode("utf-8"))
                self.wfile.flush()
                time.sleep(0.8)
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            return
        self._send(200, {
            "id": "chatcmpl-howread-mock",
            "object": "chat.completion",
            "created": 0,
            "model": req.get("model", MODEL),
            "choices": [{"index": 0, "message": {"role": "assistant", "content": reply},
                         "finish_reason": "stop"}],
            "usage": {"prompt_tokens": len(prompt), "completion_tokens": 32, "total_tokens": len(prompt) + 32},
        })

    def log_message(self, fmt, *args):
        print("[ai_mock]", self.address_string(), fmt % args, flush=True)


if __name__ == "__main__":
    print("[ai_mock] OpenAI 兼容 mock 启动: http://0.0.0.0:%d/v1 (model=%s)" % (PORT, MODEL), flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()

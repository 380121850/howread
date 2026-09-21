# -*- coding: utf-8 -*-
"""OpenAI 兼容 AI mock 服务（HowRead 自动测试环境依赖）
用法: python tools/ai_mock.py [port]     # 默认 8770
应用侧配置: AI 大模型 -> 协议 openai / 地址 http://<PC-IP>:8770/v1 / 模型 howread-test-model / key 任意
端点:
  GET  /v1/models                -> 模型列表
  POST /v1/chat/completions      -> 固定回复（含 usage），支持 stream=False
"""
import json
import sys
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

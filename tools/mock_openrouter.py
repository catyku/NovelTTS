#!/usr/bin/env python3
"""
Mock OpenRouter 伺服器 —— 驗證 TTS 完整成功路徑用（不需真實 API Key）。

模擬兩個端點：
  POST /api/v1/chat/completions  → 回傳符合 spec/02 §1.5 的分析 JSON
  POST /api/v1/audio/speech      → 回傳真實 PCM 位元組（s16le, 24kHz, mono）
  GET  /api/v1/models            → 回傳模型清單

用法：
  python3 mock_openrouter.py --port 18080

產生的 PCM 長度依 request 裡 input 陣列的文字長度計算，
讓每個 turn 的音檔長度不同，方便驗證「章節合併」是否正確串接。
"""
import argparse
import json
import math
import struct
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SAMPLE_RATE = 24000

# 記錄收到的請求，供驗證腳本檢查
REQUESTS = []
LOCK = threading.Lock()


def make_pcm(text, voice):
    """依文字長度產生不同長度的正弦波 PCM。每個 voice 不同頻率。"""
    freq = 220 + (sum(ord(c) for c in (voice or "X")) % 12) * 20
    # 每個字約 180ms，中文語速大致如此
    dur_ms = max(300, min(8000, len(text) * 180))
    n = SAMPLE_RATE * dur_ms // 1000
    out = bytearray()
    for i in range(n):
        v = int(math.sin(2 * math.pi * freq * i / SAMPLE_RATE) * 16000)
        out += struct.pack("<h", v)
    return bytes(out), dur_ms


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print("REQ:", fmt % args, flush=True)  # raw request log

    def _json(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/__requests":
            with LOCK:
                self._json(200, REQUESTS)
        elif self.path.startswith("/api/v1/models"):
            with LOCK:
                REQUESTS.append({"method": "GET", "path": self.path,
                                 "auth": self.headers.get("Authorization")})
            self._json(200, {"data": [{
                "id": "google/gemini-3.8-flash-tts",
                "name": "Mock Gemini TTS",
                "output_modalities": ["speech"],
                "supported_voices": ["Kore", "Aoede", "Leda", "Puck", "Charon", "Fenrir"],
                "pricing": {"prompt": "0", "completion": "0"},
            }]})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            # chunked：逐 chunk 讀取
            chunks = []
            while True:
                size_line = self.rfile.readline().strip()
                size = int(size_line.split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.readline()
            raw = b"".join(chunks)
        else:
            raw = self.rfile.read(length) if length else b""
        try:
            body = json.loads(raw.decode("utf-8"))
        except Exception:
            body = {}

        if self.path.startswith("/api/v1/chat/completions"):
            with LOCK:
                REQUESTS.append({"method": "POST", "path": self.path, "body": body,
                                 "auth": self.headers.get("Authorization")})
            self._handle_chat(body)
        elif self.path.startswith("/api/v1/audio/speech"):
            with LOCK:
                REQUESTS.append({"method": "POST", "path": self.path, "body": body,
                                 "auth": self.headers.get("Authorization")})
            self._handle_speech(body)
        else:
            self._json(404, {"error": "not found"})

    def _handle_chat(self, body):
        """回傳一段涵蓋旁白與兩種角色對話的分析結果。"""
        user_msg = ""
        for m in body.get("messages", []):
            if m.get("role") == "user":
                user_msg = m.get("content", "")

        title = "第一章"
        if "《" in user_msg and "》" in user_msg:
            title = user_msg.split("《")[1].split("》")[0]

        segments = [
            {"seq": 1, "type": "NARRATION", "speaker_name": "旁白",
             "character_name": None,
             "text": f"《{title}》的夜風穿過長街，帶著遠處鐘樓的餘韻。",
             "emotion": "平", "style_hint": "低沉",
             "style_instructions": "以沉穩低沉的語氣朗讀，節奏平緩"},
            {"seq": 2, "type": "DIALOGUE", "speaker_name": "林晚",
             "character_name": "林晚",
             "text": "你終於來了。我等這句話，等了整整三年。",
             "emotion": "喜", "style_hint": "溫柔",
             "style_instructions": "語氣溫柔帶著釋懷"},
            {"seq": 3, "type": "NARRATION", "speaker_name": "旁白",
             "character_name": None,
             "text": "她抬起頭，燈火在眼底晃了一下。",
             "emotion": "平", "style_hint": "輕柔",
             "style_instructions": "輕柔細膩，帶一點氣音"},
            {"seq": 4, "type": "DIALOGUE", "speaker_name": "陳硯",
             "character_name": "陳硯",
             "text": "別站在風裡。我說過多少次了，你從來不聽。",
             "emotion": "怒", "style_hint": "嚴肅",
             "style_instructions": "低沉帶責備，咬字用力"},
            {"seq": 5, "type": "DIALOGUE", "speaker_name": "林晚",
             "character_name": "林晚",
             "text": "因為我知道，這一次之後就沒有人能陪我等下去了。",
             "emotion": "哀", "style_hint": "低沉",
             "style_instructions": "聲音顫抖，語速放慢"},
        ]

        content = json.dumps({"segments": segments}, ensure_ascii=False)
        self._json(200, {
            "id": "mock-chat-1",
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": content}}],
            # 模擬 OpenRouter 實際回應：含 cost 與 cost_details
            "usage": {
                "prompt_tokens": 120,
                "completion_tokens": 480,
                "total_tokens": 600,
                "cost": 0.0015,
                "is_byok": False,
                "cost_details": {"upstream_inference_cost": None},
            },
        })

    def _handle_speech(self, body):
        """回傳 raw PCM。"""
        raw_input = body.get("input")
        if not raw_input:
            self._json(400, {"error": "input empty"})
            return

        # input 有兩種形狀（與 OpenRouter 實際契約一致）：
        #   1. 陣列：multi-speaker，每個 turn 是 {"type":"text","text":...,"voice":...}
        #   2. 字串：單一語音，音色由頂層 voice 指定
        # 陣列內所有 voice 相同時，呼叫端會退回字串 + 頂層 voice。
        if isinstance(raw_input, str):
            text = raw_input
            voice = body.get("voice", "Kore")
        else:
            turns = raw_input
            # multi-speaker：把所有 turn 的文字接起來產生 PCM
            text = "".join(t.get("text", "") for t in turns if isinstance(t, dict))
            voice = turns[0].get("voice", "Kore") if turns and isinstance(turns[0], dict) else "Kore"

        pcm, dur_ms = make_pcm(text, voice)

        self.send_response(200)
        self.send_header("Content-Type", "audio/pcm")
        self.send_header("Content-Length", str(len(pcm)))
        self.send_header("X-Generation-Id", "mock-gen-" + str(abs(hash(text)) % 100000))
        self.end_headers()
        self.wfile.write(pcm)

    def do_GET_DEBUG(self):
        with LOCK:
            self._json(200, REQUESTS)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18080)
    args = ap.parse_args()
    # 綁 0.0.0.0 才能讓同網路的其他容器連上（驗證時 app 跑在容器內）
    srv = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print(f"mock openrouter on http://127.0.0.1:{args.port}/api/v1", flush=True)
    srv.serve_forever()

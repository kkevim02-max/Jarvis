import base64
import ctypes
import difflib
import io
import json
import os
import queue
import re
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unicodedata
import urllib.request
import webbrowser
import zipfile
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import numpy as np
import psutil
import pystray
import requests
import soundfile as sf
import tkinter as tk
from PIL import Image, ImageDraw
from tkinter import messagebox, ttk

try:
    from kokoro_onnx import Kokoro
    from misaki import espeak
    from misaki.espeak import EspeakG2P
except Exception:
    Kokoro = None
    EspeakG2P = None
    espeak = None

APP_NAME = "J.A.R.V.I.S 3.1 Cloud"
VERSION = "3.1"
HTTP_PORT = 8765
DISCOVERY_PORT = 47665
PAIR_SECRET = "__JARVIS_SECRET__"
GROQ_BASE = "https://api.groq.com/openai/v1"
GROQ_CHAT_MODEL = "openai/gpt-oss-20b"
GROQ_STT_MODEL = "whisper-large-v3-turbo"
TTS_VOICE = "pm_alex"  # voz masculina pt-BR
KOKORO_MODEL_URL = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.int8.onnx"
KOKORO_VOICES_URL = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin"
PLATFORM_TOOLS_URL = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"

CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
DETACHED_PROCESS = getattr(subprocess, "DETACHED_PROCESS", 0)


def app_dir() -> Path:
    root = os.environ.get("LOCALAPPDATA") or str(Path.home())
    p = Path(root) / "Jarvis31"
    p.mkdir(parents=True, exist_ok=True)
    return p


DATA_DIR = app_dir()
MODEL_DIR = DATA_DIR / "models"
TOOLS_DIR = DATA_DIR / "tools"
CONFIG_FILE = DATA_DIR / "config.json"
KEY_FILE = DATA_DIR / "groq.key"
DB_FILE = DATA_DIR / "memory.sqlite3"
LOG_FILE = DATA_DIR / "jarvis.log"
MODEL_DIR.mkdir(parents=True, exist_ok=True)
TOOLS_DIR.mkdir(parents=True, exist_ok=True)


def executable_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


def safe_log_line(text: str) -> str:
    text = str(text).replace("\r", " ").replace("\n", " ")
    return text[:500]


class DATA_BLOB(ctypes.Structure):
    _fields_ = [("cbData", ctypes.c_uint), ("pbData", ctypes.POINTER(ctypes.c_byte))]


def _blob(data: bytes):
    buf = ctypes.create_string_buffer(data)
    return DATA_BLOB(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_byte))), buf


def dpapi_encrypt(text: str) -> bytes:
    if os.name != "nt":
        return text.encode("utf-8")
    crypt32 = ctypes.windll.crypt32
    kernel32 = ctypes.windll.kernel32
    src, src_buf = _blob(text.encode("utf-8"))
    out = DATA_BLOB()
    if not crypt32.CryptProtectData(ctypes.byref(src), "JARVIS", None, None, None, 0, ctypes.byref(out)):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        kernel32.LocalFree(out.pbData)


def dpapi_decrypt(data: bytes) -> str:
    if os.name != "nt":
        return data.decode("utf-8")
    crypt32 = ctypes.windll.crypt32
    kernel32 = ctypes.windll.kernel32
    src, src_buf = _blob(data)
    out = DATA_BLOB()
    if not crypt32.CryptUnprotectData(ctypes.byref(src), None, None, None, None, 0, ctypes.byref(out)):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(out.pbData, out.cbData).decode("utf-8")
    finally:
        kernel32.LocalFree(out.pbData)


def load_config() -> dict:
    defaults = {
        "start_with_windows": False,
        "start_minimized": False,
        "cloud_mode": True,
        "voice": TTS_VOICE,
        "assistant_name": "Jarvis",
        "wake_phrases": ["ei mano", "jarvis"],
    }
    try:
        saved = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
        if isinstance(saved, dict):
            defaults.update(saved)
    except Exception:
        pass
    return defaults


def save_config(cfg: dict):
    CONFIG_FILE.write_text(json.dumps(cfg, ensure_ascii=False, indent=2), encoding="utf-8")


def load_groq_key() -> str:
    try:
        if not KEY_FILE.exists():
            return ""
        return dpapi_decrypt(KEY_FILE.read_bytes()).strip()
    except Exception:
        return ""


def save_groq_key(key: str):
    key = key.strip()
    if not key:
        try:
            KEY_FILE.unlink(missing_ok=True)
        except Exception:
            pass
        return
    KEY_FILE.write_bytes(dpapi_encrypt(key))


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFD", text or "")
    text = "".join(ch for ch in text if unicodedata.category(ch) != "Mn")
    text = text.lower()
    text = re.sub(r"[^a-z0-9 ]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def detect_wake(text: str):
    n = normalize(text)
    if not n:
        return False, ""
    patterns = [
        "ei mano", "e mano", "hey mano", "ei man", "ei mano ai",
        "jarvis", "javis", "jarves", "jarviz", "jerves",
    ]
    for p in patterns:
        if n == p:
            return True, ""
        if n.startswith(p + " "):
            return True, n[len(p):].strip()
        if (" " + p + " ") in (" " + n + " "):
            i = n.find(p)
            rest = (n[:i] + " " + n[i + len(p):]).strip()
            return True, rest

    words = n.split()
    windows = []
    for size in (1, 2, 3):
        for i in range(max(0, min(len(words), 5) - size + 1)):
            windows.append((i, size, " ".join(words[i:i + size])))
    best = (0.0, None)
    for i, size, w in windows:
        for p in ("ei mano", "jarvis"):
            score = difflib.SequenceMatcher(None, w, p).ratio()
            if score > best[0]:
                best = (score, (i, size))
    if best[0] >= 0.74 and best[1]:
        i, size = best[1]
        rest = words[:i] + words[i + size:]
        return True, " ".join(rest).strip()
    return False, ""


def split_for_speech(text: str):
    text = re.sub(r"\s+", " ", text or "").strip()
    if not text:
        return []
    chunks = []
    current = ""
    for part in re.split(r"(?<=[.!?;:])\s+", text):
        part = part.strip()
        if not part:
            continue
        if len(current) + len(part) + 1 <= 180:
            current = (current + " " + part).strip()
        else:
            if current:
                chunks.append(current)
            current = part
    if current:
        chunks.append(current)
    out = []
    for c in chunks:
        if len(c) <= 220:
            out.append(c)
        else:
            words = c.split()
            b = ""
            for w in words:
                if len(b) + len(w) + 1 > 180:
                    out.append(b)
                    b = w
                else:
                    b = (b + " " + w).strip()
            if b:
                out.append(b)
    return out


class Memory:
    def __init__(self):
        self.lock = threading.Lock()
        self.conn = sqlite3.connect(DB_FILE, check_same_thread=False)
        self.conn.execute("CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, content TEXT, ts INTEGER)")
        self.conn.commit()

    def add(self, role: str, content: str):
        content = (content or "").strip()
        if not content:
            return
        with self.lock:
            self.conn.execute("INSERT INTO messages(role,content,ts) VALUES(?,?,?)", (role, content[:4000], int(time.time())))
            self.conn.commit()
            self.conn.execute("DELETE FROM messages WHERE id NOT IN (SELECT id FROM messages ORDER BY id DESC LIMIT 80)")
            self.conn.commit()

    def recent(self, limit=10):
        with self.lock:
            rows = self.conn.execute("SELECT role,content FROM messages ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
        return [{"role": r, "content": c} for r, c in reversed(rows)]


class MaleTTS:
    def __init__(self, log):
        self.log = log
        self.lock = threading.Lock()
        self.kokoro = None
        self.g2p = None
        self.ready = False
        self.loading = False
        self.model_path = MODEL_DIR / "kokoro-v1.0.int8.onnx"
        self.voices_path = MODEL_DIR / "voices-v1.0.bin"

    def _download(self, url: str, path: Path):
        tmp = path.with_suffix(path.suffix + ".download")
        self.log(f"Baixando {path.name}...")
        req = urllib.request.Request(url, headers={"User-Agent": "Jarvis/3.1"})
        with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
            shutil.copyfileobj(r, f)
        tmp.replace(path)
        self.log(f"{path.name} pronto.")

    def prepare(self):
        if self.ready or self.loading:
            return
        self.loading = True
        try:
            if Kokoro is None or EspeakG2P is None:
                raise RuntimeError("Componentes Kokoro não foram empacotados")
            if not self.model_path.exists() or self.model_path.stat().st_size < 10_000_000:
                self._download(KOKORO_MODEL_URL, self.model_path)
            if not self.voices_path.exists() or self.voices_path.stat().st_size < 100_000:
                self._download(KOKORO_VOICES_URL, self.voices_path)
            try:
                if espeak is not None:
                    espeak.EspeakFallback(british=False)
            except Exception:
                pass
            self.g2p = EspeakG2P(language="pt-br")
            self.kokoro = Kokoro(str(self.model_path), str(self.voices_path))
            voices = set(self.kokoro.get_voices())
            if TTS_VOICE not in voices:
                raise RuntimeError(f"Voz masculina {TTS_VOICE} não encontrada")
            try:
                phon, _ = self.g2p("Certo.")
                self.kokoro.create(phon, TTS_VOICE, speed=1.06, is_phonemes=True)
            except Exception:
                pass
            self.ready = True
            self.log("Voz masculina pm_alex pronta.")
        except Exception as e:
            self.log(f"Erro na voz: {e}")
        finally:
            self.loading = False

    def wav(self, text: str) -> bytes:
        if not self.ready:
            self.prepare()
        if not self.ready or self.kokoro is None or self.g2p is None:
            raise RuntimeError("Voz ainda não está pronta")
        with self.lock:
            phonemes, _ = self.g2p(text)
            samples, rate = self.kokoro.create(phonemes, TTS_VOICE, speed=1.06, is_phonemes=True)
            bio = io.BytesIO()
            sf.write(bio, samples, rate, format="WAV", subtype="PCM_16")
            return bio.getvalue()


class CloudBrain:
    def __init__(self, log, memory: Memory):
        self.log = log
        self.memory = memory

    def key(self):
        return load_groq_key()

    def headers(self):
        key = self.key()
        if not key:
            raise RuntimeError("Chave Groq não configurada")
        return {"Authorization": f"Bearer {key}"}

    def test(self):
        r = requests.get(GROQ_BASE + "/models", headers=self.headers(), timeout=12)
        if r.status_code != 200:
            raise RuntimeError(f"Groq HTTP {r.status_code}: {r.text[:200]}")
        return True

    def transcribe(self, wav: bytes) -> str:
        prompt = (
            "Português do Brasil. Assistente chamado Jarvis. Frases comuns: Ei mano, Jarvis, "
            "Bluetooth, YouTube, Morphe, Spotify, volume, música, computador, celular. "
            "Transcreva exatamente o que a pessoa disse."
        )
        files = {"file": ("fala.wav", wav, "audio/wav")}
        data = {
            "model": GROQ_STT_MODEL,
            "language": "pt",
            "temperature": "0",
            "response_format": "json",
            "prompt": prompt,
        }
        r = requests.post(GROQ_BASE + "/audio/transcriptions", headers=self.headers(), files=files, data=data, timeout=45)
        if r.status_code != 200:
            raise RuntimeError(f"STT HTTP {r.status_code}: {r.text[:300]}")
        return (r.json().get("text") or "").strip()

    def chat_stream(self, user_text: str):
        system = (
            "Você é JARVIS, assistente pessoal de voz em português brasileiro. "
            "Seja natural, direto e útil. Respostas faladas devem ser curtas: normalmente 1 a 4 frases. "
            "Não invente que executou ações. Se uma ação já foi executada pelo sistema, apenas confirme. "
            "O usuário chama você de Jarvis e também pode ativar dizendo 'Ei, mano'. "
            "Não use listas longas em respostas de voz."
        )
        messages = [{"role": "system", "content": system}] + self.memory.recent(8) + [{"role": "user", "content": user_text}]
        payload = {
            "model": GROQ_CHAT_MODEL,
            "messages": messages,
            "temperature": 0.45,
            "top_p": 0.9,
            "max_completion_tokens": 260,
            "reasoning_effort": "low",
            "include_reasoning": False,
            "stream": True,
        }
        headers = self.headers()
        headers["Content-Type"] = "application/json"
        r = requests.post(GROQ_BASE + "/chat/completions", headers=headers, json=payload, stream=True, timeout=(10, 80))
        if r.status_code != 200:
            raise RuntimeError(f"IA HTTP {r.status_code}: {r.text[:300]}")
        for raw in r.iter_lines(decode_unicode=True):
            if not raw:
                continue
            if raw.startswith("data: "):
                raw = raw[6:]
            if raw.strip() == "[DONE]":
                break
            try:
                obj = json.loads(raw)
                delta = obj.get("choices", [{}])[0].get("delta", {})
                content = delta.get("content") or ""
                if content:
                    yield content
            except Exception:
                continue


class PcActions:
    def __init__(self, log):
        self.log = log

    def handle(self, text: str):
        n = normalize(text)
        if "no pc" not in n and "computador" not in n:
            return None
        try:
            import ctypes as _ct
            user32 = _ct.windll.user32 if os.name == "nt" else None
            VK = {
                "volume_up": 0xAF, "volume_down": 0xAE, "mute": 0xAD,
                "play": 0xB3, "next": 0xB0, "prev": 0xB1,
            }
            def key(vk):
                if user32:
                    user32.keybd_event(vk, 0, 0, 0); user32.keybd_event(vk, 0, 2, 0)
            if "aument" in n and "volume" in n:
                key(VK["volume_up"]); return "Aumentei o volume do PC."
            if ("baix" in n or "diminu" in n) and "volume" in n:
                key(VK["volume_down"]); return "Diminuí o volume do PC."
            if "mudo" in n or ("silenci" in n and "volume" in n):
                key(VK["mute"]); return "Alternei o mudo do PC."
            if "paus" in n or "play" in n or "reprodu" in n:
                key(VK["play"]); return "Controlei a reprodução no PC."
            if "proxima" in n and ("musica" in n or "faixa" in n):
                key(VK["next"]); return "Passei para a próxima faixa no PC."
            if "anterior" in n and ("musica" in n or "faixa" in n):
                key(VK["prev"]); return "Voltei a faixa no PC."
            app_map = {
                "chrome": "chrome.exe", "navegador": "chrome.exe",
                "spotify": "spotify.exe", "notepad": "notepad.exe", "bloco de notas": "notepad.exe",
                "explorador": "explorer.exe", "arquivos": "explorer.exe",
            }
            if "abr" in n:
                for name, exe in app_map.items():
                    if name in n:
                        subprocess.Popen([exe], creationflags=CREATE_NO_WINDOW)
                        return f"Abri {name} no PC."
        except Exception as e:
            self.log(f"Ação PC falhou: {e}")
        return None


def phone_actions(text: str):
    n = normalize(text)
    actions = []
    if "bluetooth" in n and ("liga" in n or "ative" in n or "ativar" in n):
        actions.append({"type": "bluetooth_on"})
    elif "bluetooth" in n and ("desliga" in n or "desative" in n):
        actions.append({"type": "bluetooth_off"})
    if "volume" in n and ("aument" in n or "mais alto" in n):
        actions.append({"type": "volume_up"})
    elif "volume" in n and ("baix" in n or "diminu" in n):
        actions.append({"type": "volume_down"})
    if "proxima" in n and ("musica" in n or "faixa" in n):
        actions.append({"type": "media_next"})
    if "paus" in n and ("musica" in n or "reproduc" in n):
        actions.append({"type": "media_pause"})
    if "continua" in n and ("musica" in n or "reproduc" in n):
        actions.append({"type": "media_play"})
    m = re.search(r"\babr(?:a|e|ir)?\s+(?:o\s+|a\s+)?([a-z0-9 ._-]{2,40})", n)
    if m and "no pc" not in n and "computador" not in n:
        target = m.group(1).strip()
        target = re.sub(r"\s+(?:no|na)\s+(?:celular|telefone).*$", "", target).strip()
        if target:
            actions.append({"type": "open_app", "target": target})
    return actions


class JarvisRuntime:
    def __init__(self, ui_log):
        self.ui_log = ui_log
        self.memory = Memory()
        self.tts = MaleTTS(self.log)
        self.brain = CloudBrain(self.log, self.memory)
        self.pc_actions = PcActions(self.log)
        self.httpd = None
        self.udp_thread = None
        self.running = threading.Event()
        self.running.set()
        self.phone_ip = "—"
        self.last_heard = "—"
        self.last_reply = "—"
        self.last_phone_seen = 0.0

    def log(self, text):
        line = f"[{datetime.now().strftime('%H:%M:%S')}] {safe_log_line(text)}"
        try:
            with open(LOG_FILE, "a", encoding="utf-8") as f:
                f.write(line + "\n")
        except Exception:
            pass
        try:
            self.ui_log(line)
        except Exception:
            pass

    def start(self):
        threading.Thread(target=self._http_loop, daemon=True).start()
        self.udp_thread = threading.Thread(target=self._udp_loop, daemon=True)
        self.udp_thread.start()
        threading.Thread(target=self.tts.prepare, daemon=True).start()
        self.log(f"Núcleo {VERSION} iniciado. Cloud-first, voz masculina {TTS_VOICE}.")

    def stop(self):
        self.running.clear()
        try:
            if self.httpd:
                self.httpd.shutdown()
        except Exception:
            pass

    def cloud_ready(self):
        return bool(load_groq_key())

    def status(self):
        if self.last_phone_seen and time.time() - self.last_phone_seen > 30:
            phone = self.phone_ip + " (ocioso)"
        else:
            phone = self.phone_ip
        return {
            "ok": True,
            "version": VERSION,
            "mode": "groq-cloud" if self.cloud_ready() else "aguardando-chave",
            "model": GROQ_CHAT_MODEL,
            "stt": GROQ_STT_MODEL,
            "voice": TTS_VOICE,
            "male_voice": True,
            "phone": phone,
            "groq": self.cloud_ready(),
            "ollama": self.ollama_available(),
        }

    def ollama_available(self):
        try:
            return requests.get("http://127.0.0.1:11434/api/version", timeout=0.5).status_code == 200
        except Exception:
            return False

    def _http_loop(self):
        runtime = self

        class Handler(BaseHTTPRequestHandler):
            server_version = "Jarvis31/3.1"
            protocol_version = "HTTP/1.1"

            def log_message(self, fmt, *args):
                return

            def _json(self, code, obj):
                data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Connection", "close")
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                runtime.phone_ip = self.client_address[0]
                runtime.last_phone_seen = time.time()
                if self.path in ("/v3/status", "/v2/status", "/health", "/"):
                    self._json(200, runtime.status())
                else:
                    self._json(404, {"error": "not-found"})

            def do_POST(self):
                runtime.phone_ip = self.client_address[0]
                runtime.last_phone_seen = time.time()
                if self.path not in ("/v3/utterance", "/v2/utterance"):
                    self._json(404, {"error": "not-found"})
                    return
                if self.headers.get("X-Jarvis-Secret", "") != PAIR_SECRET:
                    self._json(403, {"error": "forbidden"})
                    runtime.log("Pareamento recusado: segredo diferente.")
                    return
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    if length <= 44 or length > 12 * 1024 * 1024:
                        self._json(400, {"error": "bad-audio"})
                        return
                    wav = self.rfile.read(length)
                    self.send_response(200)
                    self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
                    self.send_header("Connection", "close")
                    self.end_headers()
                    runtime.process_utterance(wav, self.wfile)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                except Exception as e:
                    runtime.log(f"Erro HTTP: {e}")
                    try:
                        runtime._write_event(self.wfile, {"type": "error", "message": str(e)[:180]})
                        runtime._write_event(self.wfile, {"type": "done"})
                    except Exception:
                        pass

        try:
            self.httpd = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), Handler)
            self.httpd.daemon_threads = True
            self.httpd.serve_forever(poll_interval=0.3)
        except Exception as e:
            self.log(f"Falha ao abrir porta {HTTP_PORT}: {e}")

    def _udp_loop(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("0.0.0.0", DISCOVERY_PORT))
            sock.settimeout(1.0)
            while self.running.is_set():
                try:
                    data, addr = sock.recvfrom(512)
                    text = data.decode("utf-8", "ignore").strip()
                    if text in ("JARVIS_DISCOVER_V3", "JARVIS_DISCOVER_V2", "JARVIS_DISCOVER_V1"):
                        if text.endswith("V2"):
                            response = "JARVIS_CORE_V2|8765"
                        elif text.endswith("V1"):
                            response = "JARVIS_CORE_V1|8765"
                        else:
                            response = "JARVIS_CORE_V3|8765"
                        sock.sendto(response.encode("utf-8"), addr)
                except socket.timeout:
                    continue
                except Exception:
                    time.sleep(0.2)
        finally:
            sock.close()

    @staticmethod
    def _write_event(wfile, obj):
        line = (json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
        wfile.write(line)
        wfile.flush()

    def _speak_event(self, wfile, text):
        try:
            wav = self.tts.wav(text)
            self._write_event(wfile, {"type": "audio", "text": text, "audio": base64.b64encode(wav).decode("ascii")})
        except Exception as e:
            self.log(f"TTS falhou: {e}")
            self._write_event(wfile, {"type": "audio", "text": text, "audio": ""})

    def process_utterance(self, wav: bytes, wfile):
        if not self.cloud_ready():
            self._write_event(wfile, {"type": "meta", "heard": "", "ignored": False})
            self._speak_event(wfile, "Abra o Jarvis no computador e configure a chave gratuita da Groq.")
            self._write_event(wfile, {"type": "done"})
            return
        try:
            t0 = time.time()
            heard = self.brain.transcribe(wav)
            self.last_heard = heard or "—"
            self.log(f"Ouvi: {heard or '(vazio)'}")
            awake, command = detect_wake(heard)
            self._write_event(wfile, {"type": "meta", "heard": heard, "ignored": not awake})
            if not awake:
                self.log("Ignorado: não chamou Jarvis/ei mano.")
                self._write_event(wfile, {"type": "done"})
                return
            if not command:
                self.last_reply = "Sim?"
                self._speak_event(wfile, "Sim?")
                self._write_event(wfile, {"type": "done"})
                return

            local_reply = self.pc_actions.handle(command)
            acts = phone_actions(command)
            if acts:
                self._write_event(wfile, {"type": "actions", "actions": acts})
            if local_reply:
                self.last_reply = local_reply
                self.memory.add("user", command)
                self.memory.add("assistant", local_reply)
                self._speak_event(wfile, local_reply)
                self._write_event(wfile, {"type": "done"})
                return

            if acts and len(normalize(command).split()) <= 8:
                reply = "Certo."
                self.last_reply = reply
                self.memory.add("user", command)
                self.memory.add("assistant", reply)
                self._speak_event(wfile, reply)
                self._write_event(wfile, {"type": "done"})
                return

            self.memory.add("user", command)
            full = ""
            pending = ""
            sent_any = False
            for token in self.brain.chat_stream(command):
                full += token
                pending += token
                if len(pending) >= 45 and re.search(r"[.!?;:]\s*$", pending):
                    chunk = pending.strip()
                    pending = ""
                    if chunk:
                        self._speak_event(wfile, chunk)
                        sent_any = True
                elif len(pending) >= 170:
                    cut = max(pending.rfind(". "), pending.rfind(", "), pending.rfind("; "))
                    if cut > 40:
                        chunk, pending = pending[:cut + 1].strip(), pending[cut + 1:].strip()
                        self._speak_event(wfile, chunk)
                        sent_any = True
            if pending.strip():
                self._speak_event(wfile, pending.strip())
                sent_any = True
            full = full.strip() or "Não consegui formular a resposta."
            self.last_reply = full
            self.memory.add("assistant", full)
            self.log(f"Resposta ({time.time()-t0:.2f}s): {full[:220]}")
            if not sent_any:
                self._speak_event(wfile, full)
            self._write_event(wfile, {"type": "done"})
        except Exception as e:
            self.log(f"Falha no processamento: {e}")
            try:
                self._write_event(wfile, {"type": "meta", "heard": self.last_heard if self.last_heard != "—" else "", "ignored": False})
                self._speak_event(wfile, "Tive um problema com a conexão da inteligência. Veja o Jarvis no computador.")
                self._write_event(wfile, {"type": "done"})
            except Exception:
                pass


class JarvisUI:
    def __init__(self):
        self.cfg = load_config()
        self.root = tk.Tk()
        self.root.title(APP_NAME)
        self.root.geometry("850x650")
        self.root.minsize(780, 600)
        self.root.configure(bg="#071018")
        try:
            self.root.iconphoto(True, tk.PhotoImage())
        except Exception:
            pass
        self.log_q = queue.Queue()
        self.runtime = JarvisRuntime(self.enqueue_log)
        self.tray = None
        self.build_ui()
        self.root.protocol("WM_DELETE_WINDOW", self.hide_to_tray)
        self.runtime.start()
        self.root.after(250, self.refresh)
        if "--background" in sys.argv or self.cfg.get("start_minimized"):
            self.root.after(500, self.hide_to_tray)

    def build_ui(self):
        title = tk.Label(self.root, text="J.A.R.V.I.S", font=("Segoe UI Light", 31), fg="#62dcff", bg="#071018")
        title.pack(pady=(18, 0))
        tk.Label(self.root, text="CLOUD INTELLIGENCE • 3.1", font=("Segoe UI", 10, "bold"), fg="#7198aa", bg="#071018").pack()

        status_frame = tk.Frame(self.root, bg="#0d1922", highlightbackground="#23566a", highlightthickness=1)
        status_frame.pack(fill="x", padx=24, pady=18)
        self.status_labels = {}
        for i, (key, label) in enumerate([
            ("cloud", "NUVEM"), ("core", "NÚCLEO"), ("phone", "CELULAR"), ("voice", "VOZ")
        ]):
            f = tk.Frame(status_frame, bg="#0d1922")
            f.grid(row=0, column=i, sticky="nsew", padx=8, pady=12)
            status_frame.grid_columnconfigure(i, weight=1)
            tk.Label(f, text=label, font=("Segoe UI", 9, "bold"), fg="#6ba9c2", bg="#0d1922").pack()
            v = tk.Label(f, text="...", font=("Segoe UI", 13, "bold"), fg="#d8f5ff", bg="#0d1922")
            v.pack(pady=(4, 0))
            self.status_labels[key] = v

        self.big_status = tk.Label(self.root, text="INICIANDO", font=("Segoe UI", 18, "bold"), fg="#67ddff", bg="#071018")
        self.big_status.pack(pady=(2, 10))
        tk.Label(self.root, text="Fale:  “Ei, mano...”  ou  “Jarvis...”", font=("Segoe UI", 12), fg="#7ccce5", bg="#071018").pack()

        buttons = tk.Frame(self.root, bg="#071018")
        buttons.pack(fill="x", padx=24, pady=16)
        self.make_btn(buttons, "Configurar Groq (grátis)", self.configure_groq).grid(row=0, column=0, padx=5, pady=5, sticky="ew")
        self.make_btn(buttons, "Testar inteligência", self.test_groq).grid(row=0, column=1, padx=5, pady=5, sticky="ew")
        self.make_btn(buttons, "Instalar APK no celular", self.install_apk).grid(row=0, column=2, padx=5, pady=5, sticky="ew")
        self.make_btn(buttons, "Segundo plano", self.hide_to_tray).grid(row=1, column=0, padx=5, pady=5, sticky="ew")
        self.make_btn(buttons, "Iniciar com Windows", self.toggle_startup).grid(row=1, column=1, padx=5, pady=5, sticky="ew")
        self.make_btn(buttons, "Abrir página da Groq", lambda: webbrowser.open("https://console.groq.com/keys")).grid(row=1, column=2, padx=5, pady=5, sticky="ew")
        for i in range(3):
            buttons.grid_columnconfigure(i, weight=1)

        tk.Label(self.root, text="ATIVIDADE", font=("Segoe UI", 9, "bold"), fg="#6ba9c2", bg="#071018").pack(anchor="w", padx=28)
        self.log_box = tk.Text(self.root, height=15, bg="#061017", fg="#b9d9e5", insertbackground="#b9d9e5", relief="flat", font=("Consolas", 9), wrap="word")
        self.log_box.pack(fill="both", expand=True, padx=24, pady=(6, 16))
        self.log_box.configure(state="disabled")

    def make_btn(self, parent, text, command):
        return tk.Button(parent, text=text, command=command, bg="#0d202b", fg="#c9f1ff", activebackground="#173848", activeforeground="white", relief="flat", bd=0, padx=10, pady=10, font=("Segoe UI", 10, "bold"), cursor="hand2")

    def enqueue_log(self, line):
        self.log_q.put(line)

    def refresh(self):
        try:
            while True:
                line = self.log_q.get_nowait()
                self.log_box.configure(state="normal")
                self.log_box.insert("end", line + "\n")
                self.log_box.see("end")
                self.log_box.configure(state="disabled")
        except queue.Empty:
            pass
        st = self.runtime.status()
        self.status_labels["cloud"].config(text="ON" if st["groq"] else "SEM CHAVE", fg="#83f5b1" if st["groq"] else "#ffc36b")
        self.status_labels["core"].config(text=f"ON :{HTTP_PORT}", fg="#83f5b1")
        self.status_labels["phone"].config(text=st["phone"][:22], fg="#d8f5ff")
        self.status_labels["voice"].config(text="MASCULINA", fg="#83f5b1")
        self.big_status.config(text="PRONTO" if st["groq"] and self.runtime.tts.ready else ("PREPARANDO VOZ" if st["groq"] else "CONFIGURE A GROQ"))
        self.root.after(500, self.refresh)

    def configure_groq(self):
        win = tk.Toplevel(self.root)
        win.title("Groq Free - JARVIS")
        win.geometry("580x260")
        win.configure(bg="#071018")
        tk.Label(win, text="Chave da Groq", font=("Segoe UI", 16, "bold"), fg="#62dcff", bg="#071018").pack(pady=(18, 6))
        tk.Label(win, text="Use uma chave do plano Free. O JARVIS usa Groq só para entender sua voz e pensar.\nA voz continua masculina e local no seu PC.", font=("Segoe UI", 10), fg="#a8c5d1", bg="#071018").pack(pady=(0, 10))
        entry = tk.Entry(win, show="•", font=("Consolas", 11), bg="#0d202b", fg="white", insertbackground="white", relief="flat")
        entry.pack(fill="x", padx=28, ipady=8)
        old = load_groq_key()
        if old:
            entry.insert(0, old)

        def save():
            key = entry.get().strip()
            if not key:
                messagebox.showwarning("JARVIS", "Cole sua chave da Groq.", parent=win)
                return
            try:
                save_groq_key(key)
                self.runtime.brain.test()
                self.runtime.log("Groq conectada com sucesso.")
                messagebox.showinfo("JARVIS", "Conectado. A inteligência rápida está pronta.", parent=win)
                win.destroy()
            except Exception as e:
                save_groq_key("")
                messagebox.showerror("JARVIS", f"A chave não funcionou:\n{e}", parent=win)

        self.make_btn(win, "Salvar e testar", save).pack(pady=16)

    def test_groq(self):
        def work():
            try:
                self.runtime.log("Testando Groq...")
                self.runtime.brain.test()
                self.runtime.log("Groq OK. Whisper + GPT-OSS prontos.")
                self.root.after(0, lambda: messagebox.showinfo("JARVIS", "Groq funcionando. Agora o Jarvis pode ouvir e responder rápido."))
            except Exception as e:
                self.runtime.log(f"Groq falhou: {e}")
                self.root.after(0, lambda: messagebox.showerror("JARVIS", str(e)))
        threading.Thread(target=work, daemon=True).start()

    def ensure_adb(self) -> Path:
        adb = TOOLS_DIR / "platform-tools" / "adb.exe"
        if adb.exists():
            return adb
        self.runtime.log("Baixando ADB oficial do Google...")
        zpath = TOOLS_DIR / "platform-tools.zip"
        urllib.request.urlretrieve(PLATFORM_TOOLS_URL, zpath)
        with zipfile.ZipFile(zpath, "r") as z:
            z.extractall(TOOLS_DIR)
        zpath.unlink(missing_ok=True)
        if not adb.exists():
            raise RuntimeError("ADB não foi encontrado após download")
        return adb

    def install_apk(self):
        def work():
            try:
                apk = executable_dir() / "Jarvis.apk"
                if not apk.exists():
                    raise RuntimeError("Jarvis.apk precisa estar na mesma pasta do Jarvis.exe")
                adb = self.ensure_adb()
                self.runtime.log("Reiniciando ADB para evitar conflito de versões...")
                if os.name == "nt":
                    subprocess.run(["taskkill", "/F", "/IM", "adb.exe"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=CREATE_NO_WINDOW)
                subprocess.run([str(adb), "start-server"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=CREATE_NO_WINDOW, timeout=15)
                out = subprocess.check_output([str(adb), "devices"], text=True, creationflags=CREATE_NO_WINDOW, timeout=15)
                devices = [ln.split()[0] for ln in out.splitlines()[1:] if "\tdevice" in ln]
                if not devices:
                    raise RuntimeError("Nenhum celular autorizado no USB. Desbloqueie a tela e aceite Depuração USB.")
                self.runtime.log(f"Celular USB: {devices[0]}")
                proc = subprocess.run([str(adb), "install", "-r", str(apk)], text=True, capture_output=True, creationflags=CREATE_NO_WINDOW, timeout=120)
                if proc.returncode != 0 or "Success" not in (proc.stdout + proc.stderr):
                    raise RuntimeError((proc.stdout + proc.stderr)[-700:])
                subprocess.run([str(adb), "shell", "am", "start", "-n", "com.levy.jarvis/.MainActivity"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=CREATE_NO_WINDOW, timeout=20)
                self.runtime.log("Jarvis.apk instalado e aberto.")
            except Exception as e:
                self.runtime.log(f"Instalação APK falhou: {e}")
                self.root.after(0, lambda: messagebox.showerror("JARVIS", str(e)))
        threading.Thread(target=work, daemon=True).start()

    def toggle_startup(self):
        if os.name != "nt":
            return
        import winreg
        key_path = r"Software\Microsoft\Windows\CurrentVersion\Run"
        exe = str(Path(sys.executable).resolve())
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, key_path, 0, winreg.KEY_SET_VALUE) as k:
            if self.cfg.get("start_with_windows"):
                try:
                    winreg.DeleteValue(k, "Jarvis31")
                except FileNotFoundError:
                    pass
                self.cfg["start_with_windows"] = False
                self.runtime.log("Inicialização com Windows desativada.")
            else:
                winreg.SetValueEx(k, "Jarvis31", 0, winreg.REG_SZ, f'"{exe}" --background')
                self.cfg["start_with_windows"] = True
                self.runtime.log("Jarvis vai iniciar com o Windows em segundo plano.")
        save_config(self.cfg)

    def tray_icon(self):
        img = Image.new("RGB", (64, 64), "#071018")
        d = ImageDraw.Draw(img)
        d.ellipse((8, 8, 56, 56), outline="#62dcff", width=4)
        d.ellipse((21, 21, 43, 43), fill="#62dcff")
        return img

    def hide_to_tray(self):
        self.root.withdraw()
        if self.tray is None:
            menu = pystray.Menu(
                pystray.MenuItem("Abrir JARVIS", lambda: self.root.after(0, self.show_window)),
                pystray.MenuItem("Sair", lambda: self.root.after(0, self.quit)),
            )
            self.tray = pystray.Icon("Jarvis31", self.tray_icon(), APP_NAME, menu)
            threading.Thread(target=self.tray.run, daemon=True).start()

    def show_window(self):
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()

    def quit(self):
        self.runtime.stop()
        try:
            if self.tray:
                self.tray.stop()
        except Exception:
            pass
        self.root.destroy()

    def run(self):
        self.root.mainloop()


def single_instance():
    if os.name != "nt":
        return None
    kernel32 = ctypes.windll.kernel32
    handle = kernel32.CreateMutexW(None, False, "Local\\Jarvis31CloudSingleInstance")
    if kernel32.GetLastError() == 183:
        return False
    return handle


def main():
    inst = single_instance()
    if inst is False:
        return
    ui = JarvisUI()
    ui.run()


if __name__ == "__main__":
    main()

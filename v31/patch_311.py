import re
from pathlib import Path

p = Path('pc/jarvis_app.py')
s = p.read_text(encoding='utf-8-sig')


def sub(pattern, repl, count=1, flags=re.S):
    global s
    new, n = re.subn(pattern, repl, s, count=count, flags=flags)
    if n != count:
        raise RuntimeError(f'Patch 3.1.1 falhou: esperado {count} substituicao(oes), obtive {n}: {pattern[:80]}')
    s = new

# Versão e imports
s = s.replace('APP_NAME = "J.A.R.V.I.S 3.1 Cloud"', 'APP_NAME = "J.A.R.V.I.S 3.1.1 Cloud"')
s = s.replace('VERSION = "3.1"', 'VERSION = "3.1.1"')
s = s.replace('import zipfile\n', 'import zipfile\nimport wave\n')
s = s.replace('import numpy as np\n', '')
s = s.replace('import soundfile as sf\n', '')

sub(
    r'try:\n    from kokoro_onnx import Kokoro\n    from misaki import espeak\n    from misaki\.espeak import EspeakG2P\nexcept Exception:\n    Kokoro = None\n    EspeakG2P = None\n    espeak = None\n',
    '''try:\n    from piper import PiperVoice, SynthesisConfig\n    PIPER_IMPORT_ERROR = ""\nexcept Exception as _piper_exc:\n    PiperVoice = None\n    SynthesisConfig = None\n    PIPER_IMPORT_ERROR = str(_piper_exc)\n'''
)

sub(
    r'TTS_VOICE = "pm_alex".*?PLATFORM_TOOLS_URL = "https://dl\.google\.com/android/repository/platform-tools-latest-windows\.zip"',
    '''TTS_VOICE = "pt_BR-cadu-medium"  # voz masculina pt-BR\nPIPER_MODEL_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/cadu/medium/pt_BR-cadu-medium.onnx?download=true"\nPIPER_CONFIG_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/cadu/medium/pt_BR-cadu-medium.onnx.json?download=true"\nPLATFORM_TOOLS_URL = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip"'''
)

# Configuração persistente: volume do Jarvis é independente do volume de mídia do celular.
s = s.replace(
    '        "wake_phrases": ["ei mano", "jarvis"],\n',
    '        "wake_phrases": ["ei mano", "jarvis"],\n        "voice_volume": 65,\n        "voice_muted": False,\n'
)

# Novo TTS local Piper. Mais simples para empacotar e com volume nativo.
sub(
    r'class MaleTTS:.*?\n\nclass CloudBrain:',
    '''class MaleTTS:\n    def __init__(self, log):\n        self.log = log\n        self.lock = threading.Lock()\n        self.voice = None\n        self.ready = False\n        self.loading = False\n        self.model_path = MODEL_DIR / "pt_BR-cadu-medium.onnx"\n        self.config_path = MODEL_DIR / "pt_BR-cadu-medium.onnx.json"\n\n    def _download(self, url: str, path: Path):\n        tmp = path.with_suffix(path.suffix + ".download")\n        self.log(f"Baixando voz masculina {path.name}...")\n        req = urllib.request.Request(url, headers={"User-Agent": "Jarvis/3.1.1"})\n        with urllib.request.urlopen(req, timeout=180) as r, open(tmp, "wb") as f:\n            shutil.copyfileobj(r, f)\n        tmp.replace(path)\n        self.log(f"{path.name} pronto.")\n\n    def prepare(self):\n        if self.ready or self.loading:\n            return\n        self.loading = True\n        try:\n            if PiperVoice is None or SynthesisConfig is None:\n                raise RuntimeError("Piper TTS não foi empacotado: " + (PIPER_IMPORT_ERROR or "erro desconhecido"))\n            if not self.model_path.exists() or self.model_path.stat().st_size < 10_000_000:\n                self._download(PIPER_MODEL_URL, self.model_path)\n            if not self.config_path.exists() or self.config_path.stat().st_size < 1000:\n                self._download(PIPER_CONFIG_URL, self.config_path)\n            self.voice = PiperVoice.load(str(self.model_path))\n            # Aquecimento curto para a primeira resposta não engasgar.\n            try:\n                warm = io.BytesIO()\n                with wave.open(warm, "wb") as wf:\n                    self.voice.synthesize_wav("Certo.", wf, syn_config=SynthesisConfig(volume=0.35, length_scale=0.92))\n            except Exception:\n                pass\n            self.ready = True\n            self.log("Voz masculina pt-BR Cadu pronta.")\n        except Exception as e:\n            self.log(f"Erro na voz: {e}")\n        finally:\n            self.loading = False\n\n    def wav(self, text: str, volume: float = 0.65) -> bytes:\n        if not self.ready:\n            self.prepare()\n        if not self.ready or self.voice is None:\n            raise RuntimeError("Voz ainda não está pronta")\n        volume = max(0.0, min(1.25, float(volume)))\n        if volume <= 0.001:\n            return b""\n        with self.lock:\n            bio = io.BytesIO()\n            cfg = SynthesisConfig(volume=volume, length_scale=0.92, normalize_audio=True)\n            with wave.open(bio, "wb") as wf:\n                self.voice.synthesize_wav(text, wf, syn_config=cfg)\n            return bio.getvalue()\n\n\nclass CloudBrain:'''
)

# Runtime recebe a mesma configuração da UI, para mute/volume valer imediatamente.
s = s.replace(
    'class JarvisRuntime:\n    def __init__(self, ui_log):\n        self.ui_log = ui_log\n',
    'class JarvisRuntime:\n    def __init__(self, ui_log, cfg=None):\n        self.ui_log = ui_log\n        self.cfg = cfg if isinstance(cfg, dict) else load_config()\n'
)
s = s.replace('        self.runtime = JarvisRuntime(self.enqueue_log)\n', '        self.runtime = JarvisRuntime(self.enqueue_log, self.cfg)\n')

# Status da voz para a tela.
s = s.replace(
    '            "male_voice": True,\n            "phone": phone,\n',
    '            "male_voice": True,\n            "voice_volume": int(self.cfg.get("voice_volume", 65)),\n            "voice_muted": bool(self.cfg.get("voice_muted", False)),\n            "phone": phone,\n'
)

# A fala é atenuada antes de ir ao celular. Assim não altera a música/volume do Android.
sub(
    r'    def _speak_event\(self, wfile, text\):.*?\n\n    def process_utterance',
    '''    def _speak_event(self, wfile, text):\n        try:\n            muted = bool(self.cfg.get("voice_muted", False))\n            volume_pct = int(self.cfg.get("voice_volume", 65))\n            if muted or volume_pct <= 0:\n                self._write_event(wfile, {"type": "audio", "text": text, "audio": ""})\n                return\n            wav = self.tts.wav(text, volume=max(0.0, min(100, volume_pct)) / 100.0)\n            self._write_event(wfile, {"type": "audio", "text": text, "audio": base64.b64encode(wav).decode("ascii") if wav else ""})\n        except Exception as e:\n            self.log(f"TTS falhou: {e}")\n            self._write_event(wfile, {"type": "audio", "text": text, "audio": ""})\n\n    def process_utterance'''
)

# Janela um pouco mais alta para os controles da voz.
s = s.replace('self.root.geometry("850x650")', 'self.root.geometry("850x720")')
s = s.replace('self.root.minsize(780, 600)', 'self.root.minsize(780, 660)')
s = s.replace('CLOUD INTELLIGENCE • 3.1', 'CLOUD INTELLIGENCE • 3.1.1')

# Controle de volume independente e mute.
needle = '        tk.Label(self.root, text="Fale:  “Ei, mano...”  ou  “Jarvis...”", font=("Segoe UI", 12), fg="#7ccce5", bg="#071018").pack()\n\n        buttons = tk.Frame(self.root, bg="#071018")\n'
replacement = '''        tk.Label(self.root, text="Fale:  “Ei, mano...”  ou  “Jarvis...”", font=("Segoe UI", 12), fg="#7ccce5", bg="#071018").pack()\n\n        voice_bar = tk.Frame(self.root, bg="#0b1821")\n        voice_bar.pack(fill="x", padx=24, pady=(12, 0))\n        tk.Label(voice_bar, text="VOLUME DO JARVIS", font=("Segoe UI", 9, "bold"), fg="#6ba9c2", bg="#0b1821").pack(side="left", padx=(12, 8), pady=10)\n        self.voice_volume_var = tk.IntVar(value=int(self.cfg.get("voice_volume", 65)))\n        self.voice_scale = tk.Scale(voice_bar, from_=0, to=100, orient="horizontal", variable=self.voice_volume_var, command=self.on_voice_volume, showvalue=True, resolution=5, length=340, bg="#0b1821", fg="#c9f1ff", troughcolor="#173848", activebackground="#62dcff", highlightthickness=0, bd=0)\n        self.voice_scale.pack(side="left", fill="x", expand=True, padx=6)\n        self.mute_btn = self.make_btn(voice_bar, "MUTAR JARVIS", self.toggle_voice_mute)\n        self.mute_btn.pack(side="right", padx=10, pady=7)\n        self.update_mute_button()\n\n        buttons = tk.Frame(self.root, bg="#071018")\n'''
if needle not in s:
    raise RuntimeError('Patch UI 3.1.1: ponto de inserção do controle de voz não encontrado')
s = s.replace(needle, replacement, 1)

# Métodos da UI para salvar volume/mute imediatamente.
needle2 = '    def make_btn(self, parent, text, command):\n'
methods = '''    def on_voice_volume(self, value):\n        try:\n            self.cfg["voice_volume"] = max(0, min(100, int(float(value))))\n            save_config(self.cfg)\n            self.runtime.cfg = self.cfg\n        except Exception:\n            pass\n\n    def toggle_voice_mute(self):\n        self.cfg["voice_muted"] = not bool(self.cfg.get("voice_muted", False))\n        save_config(self.cfg)\n        self.runtime.cfg = self.cfg\n        self.update_mute_button()\n        self.runtime.log("Voz do Jarvis: " + ("MUDO" if self.cfg["voice_muted"] else f"ATIVA em {int(self.cfg.get('voice_volume', 65))}%"))\n\n    def update_mute_button(self):\n        if not hasattr(self, "mute_btn"):\n            return\n        muted = bool(self.cfg.get("voice_muted", False))\n        self.mute_btn.config(text="DESMUTAR JARVIS" if muted else "MUTAR JARVIS", fg="#ffc36b" if muted else "#c9f1ff")\n\n'''
if needle2 not in s:
    raise RuntimeError('Patch UI 3.1.1: método make_btn não encontrado')
s = s.replace(needle2, methods + needle2, 1)

# Indicador de voz mostra mute/volume.
s = s.replace(
    '        self.status_labels["voice"].config(text="MASCULINA", fg="#83f5b1")\n',
    '        voice_text = "MUDO" if st.get("voice_muted") else f"MASC. {st.get(\'voice_volume\', 65)}%"\n        self.status_labels["voice"].config(text=voice_text, fg="#ffc36b" if st.get("voice_muted") else "#83f5b1")\n'
)

# Mensagens de interface.
s = s.replace('A voz continua masculina e local no seu PC.', 'A voz continua masculina, local e com volume independente da música do celular.')
s = s.replace('Núcleo {VERSION} iniciado. Cloud-first, voz masculina {TTS_VOICE}.', 'Núcleo {VERSION} iniciado. Cloud-first, voz masculina pt-BR com volume independente.')

p.write_text(s, encoding='utf-8')
print('Patch JARVIS 3.1.1 aplicado: Piper pt-BR + volume independente + mute.')

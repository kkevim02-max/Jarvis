package com.levy.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class JarvisService extends Service {
    public static volatile boolean running = false;
    public static volatile String serverHost = null;
    public static volatile String lastHeard = "";
    public static volatile String lastReply = "";
    public static volatile String statusText = "Inicializando";
    public static volatile int stateCode = JarvisOrbView.OFFLINE;

    private static final String CHANNEL = "jarvis_core_v2";
    private static final double MIC_GAIN = 6.5;

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);
    private final LinkedBlockingQueue<byte[]> audioQueue = new LinkedBlockingQueue<>();

    private AudioRecord recorder;
    private PowerManager.WakeLock wakeLock;
    private ActionExecutor actions;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        actions = new ActionExecutor(this);
        createChannel();
        setState(JarvisOrbView.OFFLINE, "Procurando o PC");
        startForeground(7, buildNotification(statusText));

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::CoreV2");
            wakeLock.acquire();
        } catch (Exception ignored) {}

        io.submit(this::connectionLoop);
        io.submit(this::listenLoop);
        io.submit(this::playbackLoop);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL, "JARVIS 2.0", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Mantém o Jarvis ouvindo em segundo plano");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private Notification buildNotification(String status) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(com.levy.jarvis.R.drawable.ic_jarvis)
                .setContentTitle("J.A.R.V.I.S 2.0")
                .setContentText(status)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void setState(int code, String text) {
        stateCode = code;
        statusText = text;
        try {
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(7, buildNotification(text));
        } catch (Exception ignored) {}
    }

    private void connectionLoop() {
        String saved = getSharedPreferences("jarvis2", MODE_PRIVATE).getString("server", null);
        if (saved != null && NetworkClient.ping(saved)) {
            serverHost = saved;
            setState(JarvisOrbView.READY, "Pronto • diga: Ei, mano");
        }

        while (running) {
            try {
                if (serverHost == null) {
                    setState(JarvisOrbView.OFFLINE, "Procurando o PC");
                    String found = NetworkClient.discover(this);
                    if (found != null) {
                        serverHost = found;
                        getSharedPreferences("jarvis2", MODE_PRIVATE).edit().putString("server", found).apply();
                        if (!processing.get() && !playing.get()) {
                            setState(JarvisOrbView.READY, "Pronto • diga: Ei, mano");
                        }
                    }
                } else if (!NetworkClient.ping(serverHost)) {
                    serverHost = null;
                }
                Thread.sleep(serverHost == null ? 2200 : 12000);
            } catch (Exception ignored) {
                try { Thread.sleep(1800); } catch (Exception ignored2) {}
            }
        }
    }

    private static double rms(byte[] buffer, int len) {
        if (len < 2) return 0;
        double sum = 0;
        int samples = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int s = (short)((buffer[i] & 0xff) | (buffer[i + 1] << 8));
            sum += (double)s * s;
            samples++;
        }
        return Math.sqrt(sum / Math.max(1, samples));
    }

    private static byte[] boostedCopy(byte[] buffer, int len) {
        byte[] out = Arrays.copyOf(buffer, len);
        for (int i = 0; i + 1 < len; i += 2) {
            int sample = (short)((out[i] & 0xff) | (out[i + 1] << 8));
            double x = sample / 32768.0;
            double y = Math.tanh(x * MIC_GAIN * 0.58) / Math.tanh(MIC_GAIN * 0.58);
            int boosted = (int)Math.round(y * 32767.0);
            if (boosted > 32767) boosted = 32767;
            if (boosted < -32768) boosted = -32768;
            out[i] = (byte)(boosted & 0xff);
            out[i + 1] = (byte)((boosted >> 8) & 0xff);
        }
        return out;
    }

    private void listenLoop() {
        final int rate = 16000;
        final int min = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        final int block = Math.max(3200, min);

        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    block * 3);
            recorder.startRecording();
        } catch (Exception e) {
            setState(JarvisOrbView.OFFLINE, "Erro ao abrir o microfone");
            Log.e("JARVIS2", "microphone", e);
            return;
        }

        byte[] buf = new byte[block];
        double noise = 95.0;
        boolean speaking = false;
        int hotBlocks = 0;
        int silentBlocks = 0;
        int speechBlocks = 0;
        ByteArrayOutputStream speech = new ByteArrayOutputStream();
        ArrayDeque<byte[]> pre = new ArrayDeque<>();

        while (running) {
            try {
                if (playing.get() || processing.get()) {
                    Thread.sleep(55);
                    continue;
                }

                int n = recorder.read(buf, 0, buf.length);
                if (n <= 0) continue;
                double level = rms(buf, n);
                double threshold = Math.max(110.0, noise * 1.48);

                if (!speaking) {
                    noise = noise * 0.975 + Math.min(level, 700) * 0.025;
                    pre.addLast(boostedCopy(buf, n));
                    while (pre.size() > 6) pre.removeFirst();

                    if (level > threshold) hotBlocks++; else hotBlocks = Math.max(0, hotBlocks - 1);
                    if (hotBlocks >= 2) {
                        speaking = true;
                        silentBlocks = 0;
                        speechBlocks = 0;
                        setState(JarvisOrbView.LISTENING, "Ouvindo...");
                        for (byte[] p : pre) speech.write(p);
                        pre.clear();
                    }
                } else {
                    speech.write(boostedCopy(buf, n));
                    speechBlocks++;
                    if (level < threshold * 0.68) silentBlocks++; else silentBlocks = 0;

                    if (silentBlocks >= 9 || speechBlocks >= 110) {
                        byte[] pcm = speech.toByteArray();
                        speech.reset();
                        speaking = false;
                        hotBlocks = 0;
                        silentBlocks = 0;
                        speechBlocks = 0;

                        if (pcm.length >= rate) processUtterance(pcm, rate);
                        else if (serverHost != null) setState(JarvisOrbView.READY, "Pronto • diga: Ei, mano");
                    }
                }
            } catch (Exception e) {
                Log.e("JARVIS2", "listen", e);
                try { Thread.sleep(250); } catch (Exception ignored) {}
            }
        }
    }

    private void processUtterance(byte[] pcm, int rate) {
        if (!processing.compareAndSet(false, true)) return;
        streamOpen.set(true);
        setState(JarvisOrbView.THINKING, "Entendendo...");

        io.submit(() -> {
            try {
                if (serverHost == null || !NetworkClient.ping(serverHost)) {
                    serverHost = NetworkClient.discover(this);
                    if (serverHost == null) throw new Exception("PC não encontrado");
                }
                byte[] wav = WavUtil.fromPcm16(pcm, rate, 1);
                NetworkClient.sendStreaming(serverHost, wav, new NetworkClient.StreamListener() {
                    @Override public void onMeta(String heard, boolean ignored) {
                        lastHeard = heard;
                        if (ignored) setState(JarvisOrbView.READY, "Pronto • diga: Ei, mano");
                        else setState(JarvisOrbView.THINKING, "Pensando...");
                    }

                    @Override public void onActions(JSONArray arr) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject a = arr.optJSONObject(i);
                            if (a != null) actions.execute(a);
                        }
                    }

                    @Override public void onAudio(String text, byte[] audio) {
                        if (text != null && !text.trim().isEmpty()) lastReply = text.trim();
                        if (audio != null && audio.length > 44) audioQueue.offer(audio);
                    }

                    @Override public void onDone() {
                        streamOpen.set(false);
                    }
                });
            } catch (Exception e) {
                Log.e("JARVIS2", "network", e);
                serverHost = null;
                streamOpen.set(false);
                setState(JarvisOrbView.OFFLINE, "Reconectando ao PC...");
            } finally {
                processing.set(false);
                if (!playing.get() && audioQueue.isEmpty() && serverHost != null) {
                    setState(JarvisOrbView.READY, "Pronto • diga: Ei, mano");
                }
            }
        });
    }

    private void playbackLoop() {
        while (running) {
            try {
                byte[] wav = audioQueue.poll(500, TimeUnit.MILLISECONDS);
                if (wav == null) continue;
                playing.set(true);
                setState(JarvisOrbView.SPEAKING, "Falando...");
                playBlocking(wav);
                if (audioQueue.isEmpty()) {
                    playing.set(false);
                    if (!processing.get() && !streamOpen.get() && serverHost != null) {
                        setState(JarvisOrbView.READY, "Pronto • diga: Ei, mano");
                    }
                }
            } catch (Exception e) {
                playing.set(false);
                Log.e("JARVIS2", "playback", e);
            }
        }
    }

    private void playBlocking(byte[] wav) throws Exception {
        File f = new File(getCacheDir(), "reply_" + System.nanoTime() + ".wav");
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(wav); }
        CountDownLatch latch = new CountDownLatch(1);
        MediaPlayer p = new MediaPlayer();
        p.setDataSource(f.getAbsolutePath());
        p.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
        p.setOnCompletionListener(mp -> { try { mp.release(); } catch (Exception ignored) {} latch.countDown(); });
        p.setOnErrorListener((mp, what, extra) -> { try { mp.release(); } catch (Exception ignored) {} latch.countDown(); return true; });
        p.prepare();
        p.start();
        latch.await(90, TimeUnit.SECONDS);
        try { f.delete(); } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }
        } catch (Exception ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

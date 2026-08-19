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

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class JarvisService extends Service {
    public static volatile boolean running = false;
    public static volatile String serverHost = null;
    public static volatile String lastHeard = "";

    private static final String CHANNEL = "jarvis_core";
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final AtomicBoolean playing = new AtomicBoolean(false);

    private AudioRecord recorder;
    private PowerManager.WakeLock wakeLock;
    private ActionExecutor actions;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        actions = new ActionExecutor(this);
        createChannel();
        startForeground(7, buildNotification("Aguardando o PC"));

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::Core");
            wakeLock.acquire();
        } catch (Exception ignored) {}

        io.submit(this::connectionLoop);
        io.submit(this::listenLoop);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL, "JARVIS", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Mantém o Jarvis ativo em segundo plano");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(c);
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
                .setContentTitle("J.A.R.V.I.S ativo")
                .setContentText(status)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String s) {
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
                .notify(7, buildNotification(s));
    }

    private void connectionLoop() {
        while (running) {
            if (serverHost == null) {
                String found = NetworkClient.discover(this);
                if (found != null) {
                    serverHost = found;
                    getSharedPreferences("jarvis", MODE_PRIVATE)
                            .edit().putString("server", found).apply();
                    updateNotification("PC conectado: " + found);
                }
            }
            try { Thread.sleep(serverHost == null ? 5000 : 15000); }
            catch (InterruptedException ignored) {}
        }
    }

    private static double rms(byte[] buffer, int len) {
        if (len < 2) return 0;
        double sum = 0;
        int samples = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int s = (short)((buffer[i] & 0xff) | (buffer[i+1] << 8));
            sum += (double)s * s;
            samples++;
        }
        return Math.sqrt(sum / Math.max(1, samples));
    }

    // O microfone deste aparelho entrega sinal baixo. Amplificamos apenas a cópia
    // enviada ao Whisper; o áudio bruto continua sendo usado pelo detector de voz.
    private static byte[] amplifiedCopy(byte[] buffer, int len) {
        final double gain = 3.0;
        byte[] out = Arrays.copyOf(buffer, len);
        for (int i = 0; i + 1 < len; i += 2) {
            int sample = (short)((out[i] & 0xff) | (out[i + 1] << 8));
            int boosted = (int)Math.round(sample * gain);
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
                    block * 2
            );
            recorder.startRecording();
        } catch (Exception e) {
            updateNotification("Erro no microfone");
            return;
        }

        byte[] buf = new byte[block];
        double noise = 180.0;
        boolean speaking = false;
        int hotBlocks = 0;
        int silentBlocks = 0;
        int speechBlocks = 0;
        ByteArrayOutputStream speech = new ByteArrayOutputStream();
        ArrayDeque<byte[]> pre = new ArrayDeque<>();

        while (running) {
            try {
                if (playing.get() || processing.get()) {
                    Thread.sleep(80);
                    continue;
                }

                int n = recorder.read(buf, 0, buf.length);
                if (n <= 0) continue;

                double level = rms(buf, n);
                double threshold = Math.max(220.0, noise * 1.9);

                if (!speaking) {
                    noise = noise * 0.97 + Math.min(level, 900) * 0.03;
                    byte[] copy = amplifiedCopy(buf, n);
                    pre.addLast(copy);
                    while (pre.size() > 5) pre.removeFirst();

                    if (level > threshold) hotBlocks++; else hotBlocks = 0;

                    if (hotBlocks >= 2) {
                        speaking = true;
                        silentBlocks = 0;
                        speechBlocks = 0;
                        for (byte[] p : pre) speech.write(p);
                        pre.clear();
                    }
                } else {
                    speech.write(amplifiedCopy(buf, n));
                    speechBlocks++;

                    if (level < threshold * 0.75) silentBlocks++;
                    else silentBlocks = 0;

                    if (silentBlocks >= 8 || speechBlocks >= 90) {
                        byte[] pcm = speech.toByteArray();
                        speech.reset();
                        speaking = false;
                        hotBlocks = 0;
                        silentBlocks = 0;
                        speechBlocks = 0;

                        if (pcm.length > rate * 2 / 2) processUtterance(pcm, rate);
                    }
                }
            } catch (Exception e) {
                Log.e("JARVIS", "listen", e);
                try { Thread.sleep(400); } catch (Exception ignored) {}
            }
        }
    }

    private void processUtterance(byte[] pcm, int rate) {
        if (!processing.compareAndSet(false, true)) return;

        io.submit(() -> {
            try {
                if (serverHost == null) {
                    serverHost = NetworkClient.discover(this);
                    if (serverHost == null) return;
                }

                byte[] wav = WavUtil.fromPcm16(pcm, rate, 1);
                NetworkClient.Response r = NetworkClient.send(serverHost, wav);
                lastHeard = r.heard;

                if (r.ignored) return;

                for (JSONObject a : r.actions) {
                    actions.execute(a);
                    try { Thread.sleep(180); } catch (Exception ignored) {}
                }

                if (r.audio != null && r.audio.length > 44) playReply(r.audio);

            } catch (Exception e) {
                Log.e("JARVIS", "network", e);
                serverHost = null;
                updateNotification("Reconectando ao PC...");
            } finally {
                processing.set(false);
            }
        });
    }

    private void playReply(byte[] wav) {
        playing.set(true);
        try {
            File f = new File(getCacheDir(), "jarvis_reply.wav");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(wav);
            }

            MediaPlayer p = new MediaPlayer();
            p.setDataSource(f.getAbsolutePath());
            p.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            p.setOnCompletionListener(mp -> {
                try { mp.release(); } catch (Exception ignored) {}
                try { Thread.sleep(250); } catch (Exception ignored) {}
                playing.set(false);
            });
            p.setOnErrorListener((mp, what, extra) -> {
                try { mp.release(); } catch (Exception ignored) {}
                playing.set(false);
                return true;
            });
            p.prepare();
            p.start();
        } catch (Exception e) {
            playing.set(false);
        }
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
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

package com.audioshare.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class AudioService extends Service {
    public static final String ACTION_STATUS = "com.audioshare.probe.STATUS";
    public static final String ACTION_SERVER = "com.audioshare.probe.SERVER";
    public static final String ACTION_CLIENT = "com.audioshare.probe.CLIENT";
    public static final String ACTION_STOP = "com.audioshare.probe.STOP";
    public static final String ACTION_MUSIC_SET_PLAYLIST = "com.audioshare.probe.MUSIC_SET_PLAYLIST";
    public static final String ACTION_MUSIC_PLAY = "com.audioshare.probe.MUSIC_PLAY";
    public static final String ACTION_MUSIC_PAUSE = "com.audioshare.probe.MUSIC_PAUSE";
    public static final String ACTION_MUSIC_NEXT = "com.audioshare.probe.MUSIC_NEXT";
    public static final String ACTION_MUSIC_PREVIOUS = "com.audioshare.probe.MUSIC_PREVIOUS";
    public static final String ACTION_MUSIC_SHUFFLE = "com.audioshare.probe.MUSIC_SHUFFLE";
    public static final int MODE_STOPPED = 0;
    public static final int MODE_SERVER = 1;
    public static final int MODE_CLIENT = 2;

    private static final String TAG = "AudioShare";
    private static final String CHANNEL_ID = "audio_share_voice";
    private static final int NOTIFICATION_ID = 412;
    private static final int MAGIC = 0x41534831; // ASH1
    private static final int DISCOVERY_PORT = 45870;
    private static final int AUDIO_PORT = 45871;
    private static final int AUDIO_UDP_PORT = 45872;
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = 400; // 25 ms
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;
    private static final int TYPE_AUDIO = 1;
    private static final int TYPE_PEER_LEFT = 2;
    private static final int TYPE_PING = 3;
    private static final int MUSIC_SOURCE_ID = -1;
    private static final String DISCOVERY_REQUEST = "ASHARE_DISCOVER_V1";
    private static final String DISCOVERY_REPLY = "ASHARE_SERVER_V1:" + AUDIO_PORT;

    private static volatile int currentMode = MODE_STOPPED;
    private static volatile String currentStatus = "Parado";
    private static volatile int participantCount = 0;
    private static volatile String currentParticipants = "Nenhum aparelho conectado";
    private static volatile boolean musicActive;
    private static volatile int musicSendVolume = 80;
    private static volatile int voiceSendGain = 100;
    private static volatile boolean musicShuffle;
    private static volatile String musicTrackTitle = "Nenhuma música selecionada";
    private static volatile AudioService activeInstance;

    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Map<Integer, Peer> peers = new ConcurrentHashMap<>();
    private final Map<Integer, AudioTrack> tracks = new ConcurrentHashMap<>();
    private final AtomicInteger nextPeerId = new AtomicInteger(1);
    private volatile boolean running;
    private volatile int localPeerId;
    private volatile Socket clientSocket;
    private volatile DataOutputStream clientOutput;
    private volatile ServerSocket serverSocket;
    private volatile DatagramSocket discoverySocket;
    private volatile DatagramSocket serverAudioSocket;
    private volatile NsdManager.RegistrationListener nsdRegistrationListener;
    private volatile DatagramSocket clientAudioSocket;
    private volatile AudioRecord recorder;
    private volatile MediaPlayer musicPlayer;
    private volatile MusicStreamer musicStreamer;
    private volatile MediaSession mediaSession;
    private volatile boolean musicPrepared;
    private volatile long musicGeneration;
    private final List<Uri> musicPlaylist = Collections.synchronizedList(new ArrayList<>());
    private int musicTrackIndex;
    private volatile boolean scoRequested;
    private volatile boolean scoReceiverRegistered;
    private volatile boolean communicationDeviceSelected;
    private volatile boolean communicationModeActive;
    private volatile PowerManager.WakeLock wakeLock;
    private volatile WifiManager.WifiLock wifiLock;
    private AudioManager audioManager;
    private final BroadcastReceiver scoStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR);
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                Log.i(TAG, "Bluetooth SCO audio connected");
                if (musicActive) {
                    preferBluetoothForMicrophoneRecorder();
                }
                setStatus("Fone Bluetooth conectado para voz; áudio sendo transmitido.");
            } else if (state == AudioManager.SCO_AUDIO_STATE_CONNECTING) {
                setStatus("Conectando o microfone e o áudio do fone Bluetooth…");
            } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED && running) {
                Log.w(TAG, "Bluetooth SCO audio disconnected");
            }
        }
    };
    private final AtomicInteger audioSequence = new AtomicInteger();
    private final AtomicInteger pingSequence = new AtomicInteger();
    private static final AtomicInteger rttMillis = new AtomicInteger(-1);
    private static final AtomicLong receivedAudioPackets = new AtomicLong();
    private static final AtomicLong lostAudioPackets = new AtomicLong();
    private final Map<Integer, Integer> lastReceivedSequence = new ConcurrentHashMap<>();
    private final Map<Integer, Long> pendingPings = new ConcurrentHashMap<>();
    private final AtomicInteger musicSequence = new AtomicInteger();

    public static String getStatus() {
        if (currentMode == MODE_SERVER && participantCount > 0)
            return "Servidor ativo • " + participantCount + (participantCount == 1 ? " participante conectado" : " participantes conectados") + "\n" + currentStatus;
        return currentStatus;
    }

    public static int getMode() { return currentMode; }

    public static String getParticipants() { return currentParticipants; }

    private void updateParticipants() {
        if (peers.isEmpty()) {
            currentParticipants = "Nenhum aparelho conectado";
            return;
        }
        StringBuilder list = new StringBuilder();
        for (Peer peer : peers.values()) {
            if (list.length() > 0) list.append('\n');
            list.append("• ").append(peer.deviceName == null ? "Participante " + peer.id : peer.deviceName);
        }
        currentParticipants = list.toString();
    }

    public static String getMetrics() {
        long received = receivedAudioPackets.get();
        long lost = lostAudioPackets.get();
        String rtt = rttMillis.get() < 0 ? "medindo…" : rttMillis.get() + " ms";
        String loss = received + lost == 0 ? "medindo…" : String.format(java.util.Locale.US, "%.1f%%", (lost * 100.0) / (received + lost));
        return "RTT até o servidor: " + rtt + "\nPerda estimada de áudio: " + loss
                + "\nPacotes recebidos: " + received;
    }

    public static boolean isMusicActive() { return musicActive; }
    public static boolean isMusicShuffle() { return musicShuffle; }
    public static String getMusicTrackTitle() { return musicTrackTitle; }
    public static int getMusicSendVolume() { return musicSendVolume; }
    public static int getVoiceSendGain() { return voiceSendGain; }
    public static void setVoiceSendGain(int gain) { voiceSendGain = Math.max(0, Math.min(200, gain)); }
    public static void setMusicSendVolume(int volume) {
        musicSendVolume = Math.max(0, Math.min(100, volume));
        AudioService service = activeInstance;
        if (service != null) service.applyMusicVolume();
    }
    @Override public void onCreate() {
        super.onCreate();
        activeInstance = this;
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        createMusicSession();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_MUSIC_SET_PLAYLIST.equals(action)) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra("musicUris");
            setMusicPlaylist(uris == null ? new ArrayList<>() : uris);
            return START_STICKY;
        }
        if (ACTION_MUSIC_PLAY.equals(action)) {
            playMusic();
            return START_STICKY;
        }
        if (ACTION_MUSIC_PAUSE.equals(action)) {
            pauseMusic();
            return START_STICKY;
        }
        if (ACTION_MUSIC_NEXT.equals(action)) {
            advanceMusicTrack(true);
            return START_STICKY;
        }
        if (ACTION_MUSIC_PREVIOUS.equals(action)) {
            advanceMusicTrack(false);
            return START_STICKY;
        }
        if (ACTION_MUSIC_SHUFFLE.equals(action)) {
            musicShuffle = !musicShuffle;
            updateMusicSession();
            currentStatus = musicShuffle ? "Reprodução aleatória ativada." : "Reprodução aleatória desativada.";
            sendStatus();
            return START_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopSession();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        int requestedMode = ACTION_CLIENT.equals(action) ? MODE_CLIENT : MODE_SERVER;
        if (requestedMode == MODE_SERVER && intent != null && intent.hasExtra("musicUris")) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra("musicUris");
            setMusicPlaylist(uris == null ? new ArrayList<>() : uris);
        }
        startForegroundCompat(buildNotification("Preparando áudio local"));
        startSession(requestedMode);
        return START_STICKY;
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            int types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            startForeground(NOTIFICATION_ID, notification, types);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private synchronized void setMusicPlaylist(List<Uri> uris) {
        stopMusicPlayerInternal(true);
        musicPlaylist.clear();
        if (uris != null) musicPlaylist.addAll(uris);
        musicTrackIndex = 0;
        musicTrackTitle = musicPlaylist.isEmpty() ? "Nenhuma música na pasta selecionada" : getTrackTitle(musicPlaylist.get(0));
        currentStatus = musicPlaylist.isEmpty() ? "Selecione uma pasta com músicas no servidor." : "Pasta carregada: " + musicPlaylist.size() + " faixas.";
        updateMusicSession();
        sendStatus();
    }

    private synchronized void playMusic() {
        if (currentMode != MODE_SERVER) {
            currentStatus = "Inicie este aparelho como servidor para reproduzir músicas.";
            sendStatus();
            return;
        }
        MediaPlayer player = musicPlayer;
        if (player != null && musicPrepared) {
            if (!player.isPlaying()) player.start();
            musicActive = true;
            MusicStreamer streamer = musicStreamer;
            if (streamer != null) streamer.setPaused(false);
            currentStatus = "Reproduzindo: " + musicTrackTitle;
            updateMusicSession();
            sendStatus();
            return;
        }
        if (musicPlaylist.isEmpty()) {
            currentStatus = "Escolha uma pasta com músicas antes de reproduzir.";
            sendStatus();
            return;
        }
        startMusicTrack(musicTrackIndex);
    }

    private synchronized void startMusicTrack(int index) {
        if (musicPlaylist.isEmpty() || currentMode != MODE_SERVER) return;
        musicTrackIndex = Math.max(0, Math.min(index, musicPlaylist.size() - 1));
        stopMusicPlayerInternal(false);
        long generation = ++musicGeneration;
        Uri uri = musicPlaylist.get(musicTrackIndex);
        musicTrackTitle = getTrackTitle(uri);
        currentStatus = "Abrindo: " + musicTrackTitle;
        sendStatus();
        try {
            MediaPlayer player = new MediaPlayer();
            float volume = musicSendVolume / 100f;
            player.setVolume(volume, volume);
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setOnPreparedListener(prepared -> {
                synchronized (AudioService.this) {
                    if (generation != musicGeneration || prepared != musicPlayer || currentMode != MODE_SERVER) {
                        prepared.release();
                        return;
                    }
                    musicPrepared = true;
                    musicActive = true;
                    prepared.start();
                    startMusicStreamer(uri, generation);
                    currentStatus = "Reproduzindo e compartilhando: " + musicTrackTitle;
                    updateMusicSession();
                    sendStatus();
                }
            });
            player.setOnCompletionListener(completed -> {
                synchronized (AudioService.this) {
                    if (generation == musicGeneration && currentMode == MODE_SERVER) advanceMusicTrack(true);
                }
            });
            player.setOnErrorListener((failed, what, extra) -> {
                if (generation == musicGeneration) {
                    Log.e(TAG, "MusicPlayer error what=" + what + " extra=" + extra);
                    currentStatus = "Não foi possível reproduzir: " + musicTrackTitle;
                    musicActive = false;
                    updateMusicSession();
                    sendStatus();
                }
                return true;
            });
            musicPlayer = player;
            musicPrepared = false;
            musicActive = false;
            player.setDataSource(this, uri);
            player.prepareAsync();
            updateMusicSession();
        } catch (Exception e) {
            Log.e(TAG, "Could not open music track", e);
            stopMusicPlayerInternal(false);
            currentStatus = "Falha ao abrir a música: " + e.getMessage();
            sendStatus();
        }
    }

    private void applyMusicVolume() {
        MediaPlayer player = musicPlayer;
        if (player == null) return;
        float volume = musicSendVolume / 100f;
        try {
            player.setVolume(volume, volume);
        } catch (IllegalStateException e) {
            Log.w(TAG, "Could not apply shared music volume", e);
        }
    }

    private void startMusicStreamer(Uri uri, long generation) {
        MusicStreamer streamer = new MusicStreamer(this, uri, new MusicStreamer.Listener() {
            @Override public int getVolume() { return musicSendVolume; }
            @Override public void onFrame(byte[] pcm, int length) {
                if (generation == musicGeneration && musicActive && currentMode == MODE_SERVER)
                    sendUdpAudio(serverAudioSocket, MUSIC_SOURCE_ID, musicSequence.getAndIncrement(), pcm, length,
                            peers.values().toArray(new Peer[0]));
            }
            @Override public void onEnded() { Log.i(TAG, "Music streaming decoder reached end of track"); }
            @Override public void onError(String message, Exception error) {
                Log.e(TAG, message, error);
                if (generation == musicGeneration) {
                    currentStatus = message + ": " + musicTrackTitle;
                    sendStatus();
                }
            }
        });
        musicStreamer = streamer;
        streamer.start();
    }

    private synchronized void pauseMusic() {
        MediaPlayer player = musicPlayer;
        if (player != null && musicPrepared && player.isPlaying()) player.pause();
        musicActive = false;
        MusicStreamer streamer = musicStreamer;
        if (streamer != null) streamer.setPaused(true);
        currentStatus = "Pausado: " + musicTrackTitle;
        updateMusicSession();
        notifyMusicStopped();
        sendStatus();
    }

    private synchronized void stopMusicPlayer() {
        stopMusicPlayerInternal(true);
        musicActive = false;
        currentStatus = "Reprodutor de música parado.";
        updateMusicSession();
        sendStatus();
    }

    private synchronized void stopMusicPlayerInternal(boolean notifyPeers) {
        boolean wasActive = musicActive;
        musicActive = false;
        musicPrepared = false;
        musicGeneration++;
        MusicStreamer streamer = musicStreamer;
        musicStreamer = null;
        if (streamer != null) streamer.stop();
        MediaPlayer player = musicPlayer;
        musicPlayer = null;
        if (player != null) {
            try { player.setOnPreparedListener(null); player.setOnCompletionListener(null); player.setOnErrorListener(null); } catch (Exception ignored) { }
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
        }
        if (mediaSession != null && !musicPlaylist.isEmpty()) mediaSession.setActive(false);
        if (notifyPeers && wasActive) notifyMusicStopped();
    }

    private void advanceMusicTrack(boolean next) {
        synchronized (this) {
            if (musicPlaylist.isEmpty()) return;
            int newIndex;
            if (musicShuffle && musicPlaylist.size() > 1) {
                do { newIndex = (int) (Math.random() * musicPlaylist.size()); }
                while (newIndex == musicTrackIndex);
            } else {
                newIndex = (musicTrackIndex + (next ? 1 : -1) + musicPlaylist.size()) % musicPlaylist.size();
            }
            startMusicTrack(newIndex);
        }
    }

    private String getTrackTitle(Uri uri) {
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{"_display_name"}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        String last = uri.getLastPathSegment();
        return last == null ? "Faixa de áudio" : last;
    }

    private void notifyMusicStopped() {
        Peer[] connectedPeers = peers.values().toArray(new Peer[0]);
        try {
            io.execute(() -> {
                for (Peer peer : connectedPeers) sendFrame(peer, TYPE_PEER_LEFT, MUSIC_SOURCE_ID, new byte[0], 0);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Log.w(TAG, "Could not notify peers that music playback paused", e);
        }
    }

    private void updateMusicSession() {
        MediaSession session = mediaSession;
        if (session == null) return;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP;
        int state = musicActive ? PlaybackState.STATE_PLAYING : (musicPlayer != null ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED);
        long position = 0;
        MediaPlayer player = musicPlayer;
        if (player != null && musicPrepared) try { position = player.getCurrentPosition(); } catch (Exception ignored) { }
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, musicTrackTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Áudio Share").build());
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(state, position, musicActive ? 1f : 0f).build());
        session.setActive(!musicPlaylist.isEmpty() && currentMode == MODE_SERVER);
        updateNotification(musicActive ? "Reproduzindo: " + musicTrackTitle : currentStatus);
    }

    private void createMusicSession() {
        mediaSession = new MediaSession(this, "AudioShareMusic");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { playMusic(); }
            @Override public void onPause() { pauseMusic(); }
            @Override public void onSkipToNext() { advanceMusicTrack(true); }
            @Override public void onSkipToPrevious() { advanceMusicTrack(false); }
            @Override public void onStop() { stopMusicPlayer(); }
        }, new android.os.Handler(getMainLooper()));
    }

    private void preferBluetoothForMicrophoneRecorder() {
        AudioRecord microphone = recorder;
        if (microphone == null || Build.VERSION.SDK_INT < 23) return;
        for (android.media.AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (device.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                boolean accepted = microphone.setPreferredDevice(device);
                Log.i(TAG, "Bluetooth SCO preferred for mic recorder accepted=" + accepted
                        + " routed=" + describeAudioRoute(microphone));
                return;
            }
        }
        Log.w(TAG, "SCO connected but no Bluetooth SCO input device was listed");
    }

    private String describeAudioRoute(AudioRecord record) {
        if (record == null || Build.VERSION.SDK_INT < 23) return "unavailable";
        try {
            android.media.AudioDeviceInfo device = record.getRoutedDevice();
            return device == null ? "none" : device.getType() + ":" + device.getProductName();
        } catch (Exception e) {
            return "query-failed:" + e.getClass().getSimpleName();
        }
    }

    private synchronized void startSession(int mode) {
        stopSession();
        rttMillis.set(-1);
        receivedAudioPackets.set(0);
        lostAudioPackets.set(0);
        lastReceivedSequence.clear();
        pendingPings.clear();
        currentMode = mode;
        localPeerId = 0;
        running = true;
        currentStatus = mode == MODE_SERVER
                ? "Servidor pronto na rede Wi-Fi. Aguarde os participantes."
                : "Procurando o servidor na rede Wi-Fi conectada.";
        updateNotification(currentStatus);
        acquireLocks();
        prepareCommunicationAudio();

        if (mode == MODE_SERVER) {
            io.execute(this::discoveryServerLoop);
            io.execute(this::tcpServerLoop);
            io.execute(this::serverAudioLoop);
        } else {
            io.execute(this::clientDiscoveryLoop);
        }
        io.execute(this::microphoneLoop);
        updateMusicSession();
        sendStatus();
    }

    private void prepareCommunicationAudio() {
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            communicationModeActive = true;
            if (Build.VERSION.SDK_INT >= 31) {
                for (android.media.AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
                    if (device.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            || device.getType() == 26 /* TYPE_BLE_HEADSET */) {
                        communicationDeviceSelected = audioManager.setCommunicationDevice(device);
                        if (communicationDeviceSelected) {
                            Log.i(TAG, "Communication route selected: " + device.getProductName());
                            setStatus("Fone Bluetooth selecionado para microfone e áudio.");
                        }
                        break;
                    }
                }
            } else {
                IntentFilter filter = new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(scoStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                else registerReceiver(scoStateReceiver, filter);
                scoReceiverRegistered = true;
                scoRequested = true;
                audioManager.startBluetoothSco();
                setStatus("Conectando o microfone e o áudio do fone Bluetooth…");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not select Bluetooth communication route", e);
            setStatus("Bluetooth de voz indisponível; usando o áudio padrão do aparelho.");
        }
    }

    private void stopCommunicationAudio() {
        try {
            if (Build.VERSION.SDK_INT >= 31 && communicationDeviceSelected) {
                audioManager.clearCommunicationDevice();
                communicationDeviceSelected = false;
            }
            if (scoRequested) {
                audioManager.stopBluetoothSco();
                scoRequested = false;
            }
            if (scoReceiverRegistered) {
                unregisterReceiver(scoStateReceiver);
                scoReceiverRegistered = false;
            }
            if (communicationModeActive) {
                audioManager.setMode(AudioManager.MODE_NORMAL);
                communicationModeActive = false;
            }
        } catch (Exception e) { Log.w(TAG, "Could not restore normal audio routing", e); }
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioShare:Voice");
            wakeLock.acquire();
        } catch (Exception e) { Log.w(TAG, "Wake lock unavailable", e); }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AudioShare:WiFi");
            wifiLock.acquire();
        } catch (Exception e) { Log.w(TAG, "Wi-Fi lock unavailable", e); }
    }

    private void microphoneLoop() {
        AudioRecord localRecorder = null;
        try {
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) min = FRAME_BYTES * 4;
            localRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, FRAME_BYTES * 4));
            recorder = localRecorder;
            localRecorder.startRecording();
            if (Build.VERSION.SDK_INT >= 31 && audioManager.getCommunicationDevice() != null)
                setStatus("Microfone e áudio roteados por " + audioManager.getCommunicationDevice().getProductName() + ".");
            else if (Build.VERSION.SDK_INT < 31 && audioManager.isBluetoothScoOn())
                setStatus("Microfone e áudio do fone Bluetooth ativos (SCO).");
            else
                setStatus("Microfone ativo; usando a rota de áudio padrão do Android.");

            byte[] pcm = new byte[FRAME_BYTES];
            while (running) {
                int n = localRecorder.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                if (n > 0) {
                    if ((n & 1) != 0) n--;
                    if (n > 0) {
                        applyVoiceSendGain(pcm, n, voiceSendGain);
                        sendAudio(pcm, n);
                    }
                } else if (n < 0) {
                    Log.w(TAG, "AudioRecord.read returned " + n);
                    Thread.sleep(80);
                }
            }
        } catch (Exception e) {
            if (running) setStatus("Falha ao abrir o microfone: " + e.getMessage());
            Log.e(TAG, "Microphone loop failed", e);
        } finally {
            if (recorder == localRecorder) recorder = null;
            releaseRecorder(localRecorder);
        }
    }

    private static void applyVoiceSendGain(byte[] pcm, int length, int gainPercent) {
        if (gainPercent == 100) return;
        float gain = gainPercent / 100f;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            int amplified = Math.round(sample * gain);
            amplified = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, amplified));
            pcm[i] = (byte) amplified;
            pcm[i + 1] = (byte) (amplified >> 8);
        }
    }

    private void sendAudio(byte[] pcm, int length) {
        int mode = currentMode;
        if (mode == MODE_SERVER) {
            sendUdpAudio(serverAudioSocket, 0, audioSequence.getAndIncrement(), pcm, length,
                    peers.values().toArray(new Peer[0]));
        } else if (mode == MODE_CLIENT) {
            DatagramSocket socket = clientAudioSocket;
            SocketAddress destination = clientAudioDestination;
            if (socket != null && destination != null) sendUdpAudio(socket, destination, localPeerId,
                    audioSequence.getAndIncrement(), pcm, length);
        }
    }

    private volatile SocketAddress clientAudioDestination;

    private void sendUdpAudio(DatagramSocket socket, SocketAddress destination, int sourceId, int sequence,
                              byte[] pcm, int length) {
        if (socket == null || destination == null) return;
        try {
            ByteBuffer frame = ByteBuffer.allocate(13 + length).order(ByteOrder.BIG_ENDIAN);
            frame.put((byte) TYPE_AUDIO).putInt(sourceId).putInt(sequence).putInt(length).put(pcm, 0, length);
            byte[] bytes = frame.array();
            socket.send(new DatagramPacket(bytes, bytes.length, destination));
        } catch (Exception e) { Log.w(TAG, "UDP audio send failed", e); }
    }

    private void sendUdpAudio(DatagramSocket socket, int sourceId, int sequence, byte[] pcm, int length, Peer[] destinations) {
        if (socket == null) return;
        try {
            ByteBuffer frame = ByteBuffer.allocate(13 + length).order(ByteOrder.BIG_ENDIAN);
            frame.put((byte) TYPE_AUDIO).putInt(sourceId).putInt(sequence).putInt(length).put(pcm, 0, length);
            byte[] bytes = frame.array();
            for (Peer peer : destinations) {
                SocketAddress address = peer.udpAddress;
                if (address != null) socket.send(new DatagramPacket(bytes, bytes.length, address));
            }
        } catch (Exception e) { Log.w(TAG, "UDP audio broadcast failed", e); }
    }

    private void discoveryServerLoop() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(DISCOVERY_PORT);
            discoverySocket = socket;
            byte[] buf = new byte[128];
            while (running && currentMode == MODE_SERVER) {
                DatagramPacket request = new DatagramPacket(buf, buf.length);
                socket.receive(request);
                String value = new String(request.getData(), request.getOffset(), request.getLength(), StandardCharsets.UTF_8);
                if (DISCOVERY_REQUEST.equals(value)) {
                    byte[] response = DISCOVERY_REPLY.getBytes(StandardCharsets.UTF_8);
                    socket.send(new DatagramPacket(response, response.length, request.getAddress(), request.getPort()));
                }
            }
        } catch (SocketException e) {
            if (running) setStatus("Falha ao anunciar o servidor na rede local: " + e.getMessage());
        } catch (Exception e) {
            if (running) Log.w(TAG, "Discovery listener stopped", e);
        } finally {
            if (discoverySocket == socket) discoverySocket = null;
            if (socket != null) socket.close();
        }
    }

    private void tcpServerLoop() {
        ServerSocket listener = null;
        try {
            listener = new ServerSocket(AUDIO_PORT, 8, null);
            serverSocket = listener;
            setStatus("Servidor pronto na rede local. Aguardando participantes.");
            startNsdAdvertisement();
            while (running && currentMode == MODE_SERVER) {
                Socket socket = listener.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                int id = nextPeerId.getAndIncrement();
                Peer peer = new Peer(id, socket);
                peer.out.writeInt(MAGIC);
                peer.out.writeInt(id);
                peer.out.writeInt(AUDIO_UDP_PORT);
                peer.out.flush();
                peers.put(id, peer);
                participantCount = peers.size();
                Log.i(TAG, "Accepted participant " + id + " from " + socket.getInetAddress().getHostAddress()
                        + "; total participants=" + participantCount);
                updateParticipants();
                setStatus("Conectado. O servidor está retransmitindo voz em tempo real.");
                io.execute(() -> readPeerLoop(peer));
            }
        } catch (Exception e) {
            if (running) setStatus("Não foi possível abrir o servidor de áudio: " + e.getMessage());
            Log.e(TAG, "TCP server stopped", e);
        } finally {
            stopNsdAdvertisement();
            if (serverSocket == listener) serverSocket = null;
            close(listener);
        }
    }

    private void serverAudioLoop() {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(AUDIO_UDP_PORT);
            serverAudioSocket = socket;
            byte[] buffer = new byte[1500];
            while (running && currentMode == MODE_SERVER) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                if (packet.getLength() < 13) continue;
                ByteBuffer frame = ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength()).order(ByteOrder.BIG_ENDIAN);
                int type = frame.get() & 255;
                int sourceId = frame.getInt();
                int sequence = frame.getInt();
                int length = frame.getInt();
                Peer source = peers.get(sourceId);
                if (source == null || !source.socket.getInetAddress().equals(packet.getAddress())) continue;
                source.udpAddress = packet.getSocketAddress();
                if (type == TYPE_PING && length == 0 && packet.getLength() == 13) {
                    socket.send(new DatagramPacket(packet.getData(), packet.getOffset(), packet.getLength(), packet.getSocketAddress()));
                    continue;
                }
                if (type != TYPE_AUDIO || length <= 0 || length > FRAME_BYTES || packet.getLength() != 13 + length) continue;
                byte[] data = new byte[length];
                frame.get(data);
                for (Peer destination : peers.values()) {
                    if (destination.id != sourceId && destination.udpAddress != null)
                        socket.send(new DatagramPacket(packet.getData(), packet.getOffset(), packet.getLength(), destination.udpAddress));
                }
                playAudio(sourceId, data, length);
            }
        } catch (SocketException e) {
            if (running) setStatus("Falha ao abrir o canal de áudio UDP: " + e.getMessage());
        } catch (Exception e) {
            if (running) Log.e(TAG, "UDP audio listener stopped", e);
        } finally {
            if (serverAudioSocket == socket) serverAudioSocket = null;
            if (socket != null) socket.close();
        }
    }

    private void readPeerLoop(Peer peer) {
        try {
            DataInputStream in = peer.in;
            String deviceName = in.readUTF();
            peer.deviceName = deviceName == null || deviceName.trim().isEmpty() ? "Participante " + peer.id : deviceName.trim();
            updateParticipants();
            setStatus("Conectado: " + peer.deviceName + ". Voz sendo retransmitida em tempo real.");
            while (running && !peer.socket.isClosed()) {
                int type = in.read();
                if (type < 0) break;
                int sourceId = in.readInt();
                int length = in.readInt();
                if (length < 0 || length > 4096) throw new IOException("Invalid audio frame length");
                byte[] data = new byte[length];
                in.readFully(data);
                if (type == TYPE_AUDIO && sourceId == peer.id) {
                    for (Peer destination : peers.values()) {
                        if (destination.id != peer.id) sendFrame(destination, TYPE_AUDIO, sourceId, data, length);
                    }
                    playAudio(sourceId, data, length);
                }
            }
        } catch (Exception e) {
            if (running) Log.i(TAG, "Participant disconnected: " + peer.id);
        } finally {
            if (peers.remove(peer.id) != null) {
                participantCount = peers.size();
                updateParticipants();
                releaseTrack(peer.id);
                for (Peer other : peers.values()) sendFrame(other, TYPE_PEER_LEFT, peer.id, new byte[0], 0);
                setStatus(peers.isEmpty() ? "Servidor pronto. Aguardando participante." : "Servidor ativo. Voz sendo retransmitida.");
            }
            peer.close();
        }
    }

    private void clientDiscoveryLoop() {
        while (running && currentMode == MODE_CLIENT) {
            Socket socket = null;
            try {
                List<Network> wifiNetworks = findWifiNetworks();
                if (wifiNetworks.isEmpty()) {
                    setStatus("Aguardando conexão a uma rede Wi-Fi.");
                    Thread.sleep(1300);
                    continue;
                }
                setStatus("Procurando o servidor automaticamente nesta rede Wi-Fi.");
                InetAddress serverAddress;
                Network wifiNetwork = null;
                serverAddress = null;
                for (Network candidate : wifiNetworks) {
                    try {
                        serverAddress = discoverServer(candidate);
                    } catch (IOException e) {
                        Log.i(TAG, "Discovery failed on Wi-Fi network " + candidate + ": " + e.getMessage());
                    }
                    if (serverAddress != null) {
                        wifiNetwork = candidate;
                        break;
                    }
                }
                if (serverAddress == null) throw new IOException("Servidor ainda não encontrado na rede Wi-Fi");
                socket = wifiNetwork.getSocketFactory().createSocket();
                socket.connect(new InetSocketAddress(serverAddress, AUDIO_PORT), 3500);
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                clientSocket = socket;
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                int magic = in.readInt();
                if (magic != MAGIC) throw new IOException("Servidor incompatível");
                localPeerId = in.readInt();
                int audioPort = in.readInt();
                clientAudioDestination = new InetSocketAddress(serverAddress, audioPort);
                DatagramSocket audio = new DatagramSocket();
                if (wifiNetwork != null) wifiNetwork.bindSocket(audio);
                clientAudioSocket = audio;
                clientOutput = out;
                out.writeUTF(Build.MANUFACTURER + " " + Build.MODEL);
                out.flush();
                io.execute(() -> clientAudioReceiveLoop(audio));
                io.execute(() -> clientPingLoop(audio, clientAudioDestination, localPeerId));
                setStatus("Conectado ao servidor. Microfone aberto e voz sendo enviada.");
                readClientLoop(in);
            } catch (Exception e) {
                if (running) {
                    setStatus("Servidor não encontrado ou conexão interrompida. Procurando novamente na rede local.");
                    Log.i(TAG, "Connection attempt failed", e);
                }
            } finally {
                clientOutput = null;
                clientAudioDestination = null;
                pendingPings.clear();
                close(clientAudioSocket);
                clientAudioSocket = null;
                clientSocket = null;
                close(socket);
                releaseAllTracks();
            }
            if (running) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private void readClientLoop(DataInputStream in) throws IOException {
        while (running && currentMode == MODE_CLIENT) {
            int type = in.read();
            if (type < 0) break;
            int sourceId = in.readInt();
            int length = in.readInt();
            if (length < 0 || length > 4096) throw new IOException("Invalid audio frame length");
            byte[] data = new byte[length];
            in.readFully(data);
            if (type == TYPE_PEER_LEFT) releaseTrack(sourceId);
        }
    }

    private void clientAudioReceiveLoop(DatagramSocket socket) {
        byte[] buffer = new byte[1500];
        try {
            while (running && currentMode == MODE_CLIENT && !socket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                if (packet.getLength() < 13) continue;
                ByteBuffer frame = ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength()).order(ByteOrder.BIG_ENDIAN);
                int type = frame.get() & 255;
                int sourceId = frame.getInt();
                int sequence = frame.getInt();
                int length = frame.getInt();
                if (type == TYPE_PING && length == 0 && packet.getLength() == 13) {
                    Long sentAt = pendingPings.remove(sequence);
                    if (sentAt != null) {
                        int sample = (int) (SystemClock.elapsedRealtime() - sentAt);
                        int previous = rttMillis.get();
                        rttMillis.set(previous < 0 ? sample : (previous * 3 + sample) / 4);
                        sendStatus();
                    }
                    continue;
                }
                if (type != TYPE_AUDIO || length <= 0 || length > FRAME_BYTES || packet.getLength() != 13 + length) continue;
                Integer previousSequence = lastReceivedSequence.put(sourceId, sequence);
                if (previousSequence != null) {
                    int gap = sequence - previousSequence;
                    if (gap > 1 && gap < 1000) lostAudioPackets.addAndGet(gap - 1);
                }
                receivedAudioPackets.incrementAndGet();
                byte[] data = new byte[length];
                frame.get(data);
                playAudio(sourceId, data, length);
            }
        } catch (SocketException e) {
            if (running && currentMode == MODE_CLIENT) Log.i(TAG, "UDP audio receiver closed");
        } catch (Exception e) {
            if (running && currentMode == MODE_CLIENT) Log.w(TAG, "UDP audio receive failed", e);
        }
    }

    private void clientPingLoop(DatagramSocket socket, SocketAddress destination, int peerId) {
        while (running && currentMode == MODE_CLIENT && !socket.isClosed()) {
            try {
                int sequence = pingSequence.incrementAndGet();
                pendingPings.clear();
                pendingPings.put(sequence, SystemClock.elapsedRealtime());
                ByteBuffer ping = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN);
                ping.put((byte) TYPE_PING).putInt(peerId).putInt(sequence).putInt(0);
                byte[] data = ping.array();
                socket.send(new DatagramPacket(data, data.length, destination));
                Thread.sleep(1000);
            } catch (SocketException e) {
                break;
            } catch (Exception e) {
                if (running && currentMode == MODE_CLIENT) Log.w(TAG, "UDP metric ping failed", e);
            }
        }
    }

    private List<Network> findWifiNetworks() {
        List<Network> result = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network[] networks = cm.getAllNetworks();
            for (Network network : networks) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && cm.getLinkProperties(network) != null) result.add(network);
            }
        } catch (Exception e) { Log.w(TAG, "Wi-Fi network lookup failed", e); }
        return result;
    }

    private InetAddress discoverServer(Network wifiNetwork) throws IOException {
        InetAddress nsdAddress = discoverServerNsd();
        if (nsdAddress != null) return nsdAddress;
        Set<InetAddress> destinations = getWifiDiscoveryDestinations(wifiNetwork);
        if (destinations.isEmpty()) return null;
        try (DatagramSocket discovery = new DatagramSocket()) {
            discovery.setBroadcast(true);
            discovery.setSoTimeout(900);
            wifiNetwork.bindSocket(discovery);
            byte[] ask = DISCOVERY_REQUEST.getBytes(StandardCharsets.UTF_8);
            for (InetAddress destination : destinations) {
                try {
                    discovery.send(new DatagramPacket(ask, ask.length, destination, DISCOVERY_PORT));
                    Log.d(TAG, "Discovery request sent to " + destination.getHostAddress());
                } catch (IOException e) {
                    Log.i(TAG, "Discovery send failed to " + destination.getHostAddress() + ": " + e.getMessage());
                }
            }
            long deadline = SystemClock.elapsedRealtime() + 900;
            byte[] replyBytes = new byte[128];
            while (SystemClock.elapsedRealtime() < deadline) {
                discovery.setSoTimeout((int) Math.max(1, deadline - SystemClock.elapsedRealtime()));
                DatagramPacket reply = new DatagramPacket(replyBytes, replyBytes.length);
                try {
                    discovery.receive(reply);
                    String response = new String(reply.getData(), reply.getOffset(), reply.getLength(), StandardCharsets.UTF_8);
                    if (response.startsWith("ASHARE_SERVER_V1:")) {
                        Log.i(TAG, "Server discovered at " + reply.getAddress().getHostAddress());
                        return reply.getAddress();
                    }
                } catch (java.net.SocketTimeoutException timeout) {
                    break;
                }
            }
        }
        return null;
    }

    private void startNsdAdvertisement() {
        try {
            NsdManager manager = (NsdManager) getSystemService(NSD_SERVICE);
            if (manager == null) return;
            NsdServiceInfo service = new NsdServiceInfo();
            service.setServiceName("AudioShare-" + Build.MODEL.replace(' ', '-'));
            service.setServiceType("_audioshare._tcp.");
            service.setPort(AUDIO_PORT);
            NsdManager.RegistrationListener registration = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo info) {
                    Log.i(TAG, "NSD service registered: " + info.getServiceName());
                }
                @Override public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                    Log.w(TAG, "NSD registration failed: " + errorCode);
                }
                @Override public void onServiceUnregistered(NsdServiceInfo info) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
                    Log.w(TAG, "NSD unregistration failed: " + errorCode);
                }
            };
            nsdRegistrationListener = registration;
            manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (Exception e) {
            Log.w(TAG, "Could not advertise server through NSD", e);
        }
    }

    private void stopNsdAdvertisement() {
        NsdManager.RegistrationListener registration = nsdRegistrationListener;
        nsdRegistrationListener = null;
        if (registration == null) return;
        try {
            NsdManager manager = (NsdManager) getSystemService(NSD_SERVICE);
            if (manager != null) manager.unregisterService(registration);
        } catch (Exception e) {
            Log.i(TAG, "NSD advertisement already stopped: " + e.getMessage());
        }
    }

    private InetAddress discoverServerNsd() {
        NsdManager manager = (NsdManager) getSystemService(NSD_SERVICE);
        if (manager == null) return null;
        CountDownLatch resolved = new CountDownLatch(1);
        AtomicReference<InetAddress> address = new AtomicReference<>();
        AtomicReference<NsdManager.ResolveListener> activeResolve = new AtomicReference<>();
        NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.i(TAG, "NSD discovery start failed: " + errorCode);
                resolved.countDown();
            }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.i(TAG, "NSD discovery stop failed: " + errorCode);
            }
            @Override public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "NSD discovery started");
            }
            @Override public void onDiscoveryStopped(String serviceType) { }
            @Override public void onServiceLost(NsdServiceInfo serviceInfo) { }
            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!serviceInfo.getServiceType().contains("_audioshare._tcp")) return;
                if (activeResolve.get() != null) return;
                NsdManager.ResolveListener resolver = new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo service, int errorCode) {
                        Log.i(TAG, "NSD resolve failed: " + errorCode);
                        resolved.countDown();
                    }
                    @Override public void onServiceResolved(NsdServiceInfo service) {
                        address.set(service.getHost());
                        Log.i(TAG, "Server discovered through NSD at " + service.getHost().getHostAddress());
                        resolved.countDown();
                    }
                };
                if (activeResolve.compareAndSet(null, resolver)) {
                    try { manager.resolveService(serviceInfo, resolver); }
                    catch (Exception e) {
                        Log.i(TAG, "NSD resolve could not start: " + e.getMessage());
                        resolved.countDown();
                    }
                }
            }
        };
        try {
            manager.discoverServices("_audioshare._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
            resolved.await(1400, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.i(TAG, "NSD discovery unavailable, using UDP broadcast: " + e.getMessage());
        } finally {
            try { manager.stopServiceDiscovery(discovery); }
            catch (Exception e) { Log.d(TAG, "NSD discovery already stopped"); }
        }
        return address.get();
    }

    private Set<InetAddress> getWifiDiscoveryDestinations(Network wifiNetwork) {
        Set<InetAddress> destinations = new HashSet<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            LinkProperties properties = cm.getLinkProperties(wifiNetwork);
            if (properties == null) return destinations;
            for (android.net.LinkAddress link : properties.getLinkAddresses()) {
                byte[] address = link.getAddress().getAddress();
                int prefix = link.getPrefixLength();
                if (address.length != 4 || prefix < 0 || prefix > 30) continue;
                byte[] broadcast = address.clone();
                for (int bit = prefix; bit < 32; bit++) broadcast[bit / 8] |= (byte) (1 << (7 - bit % 8));
                destinations.add(InetAddress.getByAddress(broadcast));
            }
            // Keep the hotspot gateway probe as a fallback for networks that filter broadcasts.
            for (RouteInfo route : properties.getRoutes()) {
                if (route.isDefaultRoute() && route.getGateway() != null && !route.getGateway().isAnyLocalAddress()
                        && route.getGateway() instanceof java.net.Inet4Address) {
                    destinations.add(route.getGateway());
                    break;
                }
            }
        } catch (Exception e) { Log.w(TAG, "Wi-Fi discovery destinations lookup failed", e); }
        return destinations;
    }

    private void playAudio(int sourceId, byte[] data, int length) {
        if (length == 0) return;
        try {
            AudioTrack track = tracks.get(sourceId);
            if (track == null) {
                AudioAttributes attributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
                AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
                int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int bufferBytes = Math.max(min, FRAME_BYTES * 2);
                AudioTrack.Builder builder = new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
                        .setBufferSizeInBytes(bufferBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM);
                if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
                track = builder.build();
                if (Build.VERSION.SDK_INT >= 24) {
                int effectiveFrames = track.setBufferSizeInFrames(FRAME_SAMPLES * 2);
                    Log.i(TAG, "AudioTrack low-latency buffer=" + effectiveFrames + " frames ("
                            + (effectiveFrames * 1000 / SAMPLE_RATE) + " ms), mode=" + track.getPerformanceMode());
                }
                track.play();
                AudioTrack previous = tracks.putIfAbsent(sourceId, track);
                if (previous != null) {
                    track.release();
                    track = previous;
                }
            }
            track.write(data, 0, length, AudioTrack.WRITE_BLOCKING);
        } catch (Exception e) {
            Log.w(TAG, "Audio playback failed", e);
            releaseTrack(sourceId);
        }
    }

    private void sendFrame(Peer peer, int type, int sourceId, byte[] data, int length) {
        synchronized (peer.writeLock) {
            try {
                peer.out.writeByte(type);
                peer.out.writeInt(sourceId);
                peer.out.writeInt(length);
                if (length > 0) peer.out.write(data, 0, length);
                peer.out.flush();
            } catch (IOException e) {
                close(peer.socket);
            }
        }
    }

    private final Object clientWriteLock = new Object();
    private void sendFrame(DataOutputStream out, Socket socket, int type, int sourceId, byte[] data, int length) {
        synchronized (clientWriteLock) {
            try {
                out.writeByte(type);
                out.writeInt(sourceId);
                out.writeInt(length);
                if (length > 0) out.write(data, 0, length);
                out.flush();
            } catch (IOException e) { close(socket); }
        }
    }

    private void releaseTrack(int id) {
        AudioTrack track = tracks.remove(id);
        if (track != null) {
            try { track.pause(); } catch (Exception ignored) { }
            try { track.flush(); } catch (Exception ignored) { }
            try { track.release(); } catch (Exception ignored) { }
        }
    }

    private void releaseAllTracks() {
        for (Integer id : tracks.keySet()) releaseTrack(id);
    }

    private void setStatus(String value) {
        currentStatus = value;
        sendStatus();
        updateNotification(value);
    }

    private void sendStatus() {
        Intent update = new Intent(ACTION_STATUS).setPackage(getPackageName());
        sendBroadcast(update);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Áudio Share", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Sessão local de intercom por voz");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setContentTitle(musicActive ? musicTrackTitle : "Áudio Share ativo").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pending).setOngoing(true);
        if (mediaSession != null && !musicPlaylist.isEmpty() && currentMode == MODE_SERVER) {
            builder.addAction(android.R.drawable.ic_media_previous, "Anterior", musicActionPendingIntent(ACTION_MUSIC_PREVIOUS, 1));
            builder.addAction(musicActive ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                    musicActive ? "Pausar" : "Reproduzir",
                    musicActionPendingIntent(musicActive ? ACTION_MUSIC_PAUSE : ACTION_MUSIC_PLAY, 2));
            builder.addAction(android.R.drawable.ic_media_next, "Próxima", musicActionPendingIntent(ACTION_MUSIC_NEXT, 3));
            builder.setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }
        return builder.build();
    }

    private PendingIntent musicActionPendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, AudioService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private void updateNotification(String text) {
        if (currentMode == MODE_STOPPED) return;
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification(text)); }
        catch (Exception ignored) { }
    }

    private synchronized void stopSession() {
        stopMusicPlayerInternal(true);
        running = false;
        currentMode = MODE_STOPPED;
        participantCount = 0;
        currentStatus = "Parado";
        currentParticipants = "Nenhum aparelho conectado";
        close(serverSocket);
        close(discoverySocket);
        close(serverAudioSocket);
        close(clientAudioSocket);
        close(clientSocket);
        serverSocket = null;
        discoverySocket = null;
        serverAudioSocket = null;
        clientAudioSocket = null;
        clientAudioDestination = null;
        clientSocket = null;
        clientOutput = null;
        for (Peer peer : peers.values()) peer.close();
        peers.clear();
        releaseAllTracks();
        releaseRecorder(recorder);
        recorder = null;
        stopCommunicationAudio();
        if (mediaSession != null) {
            try { mediaSession.setActive(false); } catch (Exception ignored) { }
        }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        wakeLock = null;
        wifiLock = null;
        sendStatus();
    }

    private void releaseRecorder(AudioRecord value) {
        if (value == null) return;
        try { if (value.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) value.stop(); } catch (Exception ignored) { }
        try { value.release(); } catch (Exception ignored) { }
    }

    private static void close(Socket value) {
        if (value != null) try { value.close(); } catch (IOException ignored) { }
    }
    private static void close(ServerSocket value) {
        if (value != null) try { value.close(); } catch (IOException ignored) { }
    }
    private static void close(DatagramSocket value) { if (value != null) value.close(); }

    @Override public void onDestroy() {
        stopSession();
        if (activeInstance == this) activeInstance = null;
        if (mediaSession != null) {
            try { mediaSession.release(); } catch (Exception ignored) { }
            mediaSession = null;
        }
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static final class Peer {
        final int id;
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;
        final Object writeLock = new Object();
        volatile SocketAddress udpAddress;
        volatile String deviceName;
        Peer(int peerId, Socket value) throws IOException {
            id = peerId;
            socket = value;
            in = new DataInputStream(new BufferedInputStream(value.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(value.getOutputStream()));
        }
        void close() {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}

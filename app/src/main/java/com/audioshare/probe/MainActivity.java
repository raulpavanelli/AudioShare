package com.audioshare.probe;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_MIC = 41;
    private static final int REQ_MUSIC_FOLDER = 44;
    private static final String ACTION_SERVER = "com.audioshare.probe.SERVER";
    private static final String ACTION_CLIENT = "com.audioshare.probe.CLIENT";
    private static final String ACTION_STOP = "com.audioshare.probe.STOP";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView instructions;
    private TextView participantsTitle;
    private TextView participants;
    private TextView metricsTitle;
    private TextView metrics;
    private LinearLayout musicControls;
    private TextView musicVolumeLabel;
    private LinearLayout voiceControls;
    private TextView voiceVolumeLabel;
    private Button musicToggle;
    private Button musicFolderButton;
    private Button musicPreviousButton;
    private Button musicNextButton;
    private Button musicShuffleButton;
    private TextView musicFolderLabel;
    private TextView musicTrackLabel;
    private Uri selectedMusicFolder;
    private final ArrayList<Uri> musicPlaylist = new ArrayList<>();
    private String pendingAction;
    private boolean receiverRegistered;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            renderStatus();
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            renderStatus();
            handler.postDelayed(this, 800);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Áudio Share");
        buildUi();
        loadSavedMusicFolder();
        renderStatus();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(AudioService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver, filter);
        receiverRegistered = true;
        handler.post(refresh);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(refresh);
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    private void buildUi() {
        int pad = (int) (getResources().getDisplayMetrics().density * 18);
        LinearLayout content = new LinearLayout(this);
        content.setPadding(pad, pad, pad, pad);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("Áudio Share");
        title.setTextSize(26);
        content.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Intercom de voz pela rede local. O servidor retransmite a voz para os outros aparelhos.");
        subtitle.setTextSize(16);
        subtitle.setPadding(0, pad / 2, 0, pad);
        content.addView(subtitle);

        Button server = new Button(this);
        server.setText("Usar este aparelho como SERVIDOR");
        server.setOnClickListener(v -> requestMicThen(ACTION_SERVER));
        content.addView(server);

        Button client = new Button(this);
        client.setText("Conectar como PARTICIPANTE");
        client.setOnClickListener(v -> requestMicThen(ACTION_CLIENT));
        content.addView(client);

        Button stop = new Button(this);
        stop.setText("Parar áudio");
        stop.setOnClickListener(v -> sendServiceAction(ACTION_STOP));
        content.addView(stop);

        voiceControls = new LinearLayout(this);
        voiceControls.setOrientation(LinearLayout.VERTICAL);
        TextView voiceTitle = new TextView(this);
        voiceTitle.setText("Volume da minha voz enviada");
        voiceTitle.setTextSize(16);
        voiceTitle.setPadding(0, pad / 2, 0, 0);
        voiceControls.addView(voiceTitle);
        voiceVolumeLabel = new TextView(this);
        voiceControls.addView(voiceVolumeLabel);
        SeekBar voiceVolume = new SeekBar(this);
        voiceVolume.setMax(200);
        voiceVolume.setProgress(AudioService.getVoiceSendGain());
        voiceVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                AudioService.setVoiceSendGain(progress);
                voiceVolumeLabel.setText("Nível de envio: " + progress + "%" + (progress > 100 ? " (amplificado)" : ""));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        voiceVolumeLabel.setText("Nível de envio: " + voiceVolume.getProgress() + "%");
        voiceControls.addView(voiceVolume);
        TextView voiceNotice = new TextView(this);
        voiceNotice.setText("Ajusta apenas a voz enviada por este aparelho. Acima de 100% pode aumentar ruído ou distorção.");
        voiceNotice.setTextSize(13);
        voiceControls.addView(voiceNotice);
        content.addView(voiceControls);

        musicControls = new LinearLayout(this);
        musicControls.setOrientation(LinearLayout.VERTICAL);
        TextView musicTitle = new TextView(this);
        musicTitle.setText("Reprodutor de músicas do servidor");
        musicTitle.setTextSize(18);
        musicTitle.setPadding(0, pad, 0, pad / 3);
        musicControls.addView(musicTitle);

        musicFolderButton = new Button(this);
        musicFolderButton.setText("Escolher pasta de músicas");
        musicFolderButton.setOnClickListener(v -> chooseMusicFolder());
        musicControls.addView(musicFolderButton);

        musicFolderLabel = new TextView(this);
        musicFolderLabel.setText("Nenhuma pasta selecionada");
        musicFolderLabel.setTextSize(14);
        musicControls.addView(musicFolderLabel);

        musicTrackLabel = new TextView(this);
        musicTrackLabel.setText(AudioService.getMusicTrackTitle());
        musicTrackLabel.setTextSize(16);
        musicTrackLabel.setPadding(0, pad / 3, 0, pad / 3);
        musicControls.addView(musicTrackLabel);

        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        musicPreviousButton = new Button(this);
        musicPreviousButton.setText("⏮");
        musicPreviousButton.setOnClickListener(v -> sendMusicAction(AudioService.ACTION_MUSIC_PREVIOUS));
        transport.addView(musicPreviousButton, new LinearLayout.LayoutParams(0, -2, 1));
        musicToggle = new Button(this);
        musicToggle.setOnClickListener(v -> toggleMusicPlayback());
        transport.addView(musicToggle, new LinearLayout.LayoutParams(0, -2, 2));
        musicNextButton = new Button(this);
        musicNextButton.setText("⏭");
        musicNextButton.setOnClickListener(v -> sendMusicAction(AudioService.ACTION_MUSIC_NEXT));
        transport.addView(musicNextButton, new LinearLayout.LayoutParams(0, -2, 1));
        musicControls.addView(transport);

        musicShuffleButton = new Button(this);
        musicShuffleButton.setOnClickListener(v -> sendMusicAction(AudioService.ACTION_MUSIC_SHUFFLE));
        musicControls.addView(musicShuffleButton);

        musicVolumeLabel = new TextView(this);
        musicControls.addView(musicVolumeLabel);
        SeekBar musicVolume = new SeekBar(this);
        musicVolume.setMax(100);
        musicVolume.setProgress(gainToSlider(AudioService.getMusicSendVolume()));
        musicVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int gain = sliderToGain(progress);
                AudioService.setMusicSendVolume(gain);
                musicVolumeLabel.setText("Ganho compartilhado (servidor e transmissão): " + gain + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        musicVolumeLabel.setText("Ganho compartilhado (servidor e transmissão): " + sliderToGain(musicVolume.getProgress()) + "%");
        musicControls.addView(musicVolume);
        TextView playerNotice = new TextView(this);
        playerNotice.setText("A música toca neste aparelho e é transmitida aos participantes. Os controles de mídia do Android, fones e relógios controlam a reprodução.");
        playerNotice.setTextSize(13);
        musicControls.addView(playerNotice);
        content.addView(musicControls);

        status = new TextView(this);
        status.setTextSize(17);
        status.setPadding(0, pad, 0, pad);
        status.setTextIsSelectable(true);

        participantsTitle = new TextView(this);
        participantsTitle.setText("Aparelhos conectados");
        participantsTitle.setTextSize(18);
        participantsTitle.setPadding(0, pad / 2, 0, pad / 3);
        participants = new TextView(this);
        participants.setTextSize(16);
        participants.setPadding(0, 0, 0, pad);

        metricsTitle = new TextView(this);
        metricsTitle.setText("Métricas da conexão");
        metricsTitle.setTextSize(18);
        metricsTitle.setPadding(0, pad / 2, 0, pad / 3);
        metrics = new TextView(this);
        metrics.setTextSize(16);
        metrics.setPadding(0, 0, 0, pad);

        instructions = new TextView(this);
        instructions.setTextSize(14);
        instructions.setPadding(0, pad, 0, pad);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.addView(status);
        details.addView(participantsTitle);
        details.addView(participants);
        details.addView(metricsTitle);
        details.addView(metrics);
        details.addView(instructions);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(details);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(content);
    }

    private static int sliderToGain(int progress) {
        return Math.max(0, Math.min(100, progress));
    }

    private static int gainToSlider(int gain) {
        return Math.max(0, Math.min(100, gain));
    }

    private void requestMicThen(String action) {
        pendingAction = action;
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (missing.isEmpty()) {
            sendServiceAction(action);
        } else {
            requestPermissions(missing.toArray(new String[0]), REQ_MIC);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = requestCode == REQ_MIC && results.length > 0;
        for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (granted && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && pendingAction != null) {
            sendServiceAction(pendingAction);
        } else if (requestCode == REQ_MIC) {
            status.setText("Permissões de microfone e Bluetooth são necessárias para transmitir voz pelo fone.");
        }
        pendingAction = null;
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, AudioService.class).setAction(action);
        if (AudioService.ACTION_SERVER.equals(action) && !musicPlaylist.isEmpty())
            intent.putParcelableArrayListExtra("musicUris", new ArrayList<>(musicPlaylist));
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        renderStatus();
    }

    private void toggleMusicPlayback() {
        if (AudioService.getMode() != AudioService.MODE_SERVER) {
            Toast.makeText(this, "Inicie este aparelho como servidor primeiro.", Toast.LENGTH_SHORT).show();
            return;
        }
        sendMusicAction(AudioService.isMusicActive() ? AudioService.ACTION_MUSIC_PAUSE : AudioService.ACTION_MUSIC_PLAY);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_MUSIC_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        selectedMusicFolder = data.getData();
        int grantFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(selectedMusicFolder, grantFlags); }
        catch (Exception e) { Toast.makeText(this, "Não foi possível guardar o acesso à pasta.", Toast.LENGTH_LONG).show(); }
        getPreferences(MODE_PRIVATE).edit().putString("music_tree_uri", selectedMusicFolder.toString()).apply();
        scanSelectedMusicFolder();
    }

    private void chooseMusicFolder() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= 26 && selectedMusicFolder != null)
            picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, selectedMusicFolder);
        startActivityForResult(picker, REQ_MUSIC_FOLDER);
    }

    private void loadSavedMusicFolder() {
        String saved = getPreferences(MODE_PRIVATE).getString("music_tree_uri", null);
        if (saved == null) return;
        try {
            selectedMusicFolder = Uri.parse(saved);
            scanSelectedMusicFolder();
        } catch (Exception e) {
            selectedMusicFolder = null;
            musicPlaylist.clear();
            musicFolderLabel.setText("Selecione novamente a pasta de músicas");
        }
    }

    private void scanSelectedMusicFolder() {
        musicPlaylist.clear();
        if (selectedMusicFolder != null) {
            try {
                Set<String> visited = new HashSet<>();
                String rootId = DocumentsContract.getTreeDocumentId(selectedMusicFolder);
                addFolderDocuments(selectedMusicFolder, rootId, visited, musicPlaylist);
                Collections.sort(musicPlaylist, (left, right) -> getUriTitle(left).compareToIgnoreCase(getUriTitle(right)));
            } catch (Exception e) {
                musicPlaylist.clear();
                Toast.makeText(this, "Não foi possível ler essa pasta: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
        musicFolderLabel.setText(selectedMusicFolder == null ? "Nenhuma pasta selecionada"
                : getFolderTitle(selectedMusicFolder) + " • " + musicPlaylist.size() + " músicas");
        if (AudioService.getMode() == AudioService.MODE_SERVER) sendMusicPlaylist();
        renderStatus();
    }

    private void addFolderDocuments(Uri tree, String parentId, Set<String> visited, List<Uri> result) {
        if (!visited.add(parentId)) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = getContentResolver().query(children, columns, null, null, null)) {
            if (cursor == null) return;
            int idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                String documentId = cursor.getString(idColumn);
                String name = cursor.getString(nameColumn);
                String mime = cursor.getString(mimeColumn);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    addFolderDocuments(tree, documentId, visited, result);
                } else if (isAudioDocument(name, mime)) {
                    result.add(DocumentsContract.buildDocumentUriUsingTree(tree, documentId));
                }
            }
        }
    }

    private boolean isAudioDocument(String name, String mime) {
        if (mime != null && mime.toLowerCase().startsWith("audio/")) return true;
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac")
                || lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".opus")
                || lower.endsWith(".flac") || lower.endsWith(".amr") || lower.endsWith(".3gp")
                || lower.endsWith(".m4b") || lower.endsWith(".mid") || lower.endsWith(".midi");
    }

    private String getFolderTitle(Uri tree) {
        try {
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            String name = queryDisplayName(document);
            if (name != null && !name.isEmpty()) return name;
        } catch (Exception ignored) { }
        return "Pasta selecionada";
    }

    private String getUriTitle(Uri uri) {
        String name = queryDisplayName(uri);
        if (name != null && !name.isEmpty()) return name;
        String path = uri.getLastPathSegment();
        return path == null ? "Faixa de áudio" : path;
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        return null;
    }

    private void sendMusicPlaylist() {
        sendMusicAction(AudioService.ACTION_MUSIC_SET_PLAYLIST);
    }

    private void sendMusicAction(String action) {
        if (AudioService.getMode() != AudioService.MODE_SERVER) {
            Toast.makeText(this, "Inicie este aparelho como servidor primeiro.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, AudioService.class).setAction(action);
        if (AudioService.ACTION_MUSIC_SET_PLAYLIST.equals(action))
            intent.putParcelableArrayListExtra("musicUris", new ArrayList<>(musicPlaylist));
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        renderStatus();
    }

    private void renderStatus() {
        if (status == null) return;
        status.setText(AudioService.getStatus());
        int mode = AudioService.getMode();
        if (mode == AudioService.MODE_SERVER) {
            voiceControls.setVisibility(TextView.VISIBLE);
            musicControls.setVisibility(TextView.VISIBLE);
            musicToggle.setText(AudioService.isMusicActive() ? "Pausar" : "Reproduzir");
            musicTrackLabel.setText(AudioService.getMusicTrackTitle());
            musicShuffleButton.setText(AudioService.isMusicShuffle() ? "Aleatório: ligado" : "Aleatório: desligado");
            musicToggle.setEnabled(!musicPlaylist.isEmpty());
            musicPreviousButton.setEnabled(musicPlaylist.size() > 1);
            musicNextButton.setEnabled(musicPlaylist.size() > 1);
            musicShuffleButton.setEnabled(musicPlaylist.size() > 1);
            participantsTitle.setVisibility(TextView.VISIBLE);
            participants.setVisibility(TextView.VISIBLE);
            metricsTitle.setVisibility(TextView.GONE);
            metrics.setVisibility(TextView.GONE);
            participants.setText(AudioService.getParticipants());
            instructions.setText("Conecte os participantes à mesma rede Wi‑Fi deste servidor (hotspot ou roteador) e toque em PARTICIPANTE. "
                    + "O app encontra o servidor automaticamente; não precisa informar IP.");
        } else if (mode == AudioService.MODE_CLIENT) {
            voiceControls.setVisibility(TextView.VISIBLE);
            musicControls.setVisibility(TextView.GONE);
            participantsTitle.setVisibility(TextView.GONE);
            participants.setVisibility(TextView.GONE);
            metricsTitle.setVisibility(TextView.VISIBLE);
            metrics.setVisibility(TextView.VISIBLE);
            metrics.setText(AudioService.getMetrics());
            instructions.setText("Conecte este aparelho à mesma rede Wi‑Fi do servidor. "
                    + "O app procura e reconecta automaticamente na rede local.");
        } else {
            voiceControls.setVisibility(TextView.GONE);
            musicControls.setVisibility(TextView.GONE);
            participantsTitle.setVisibility(TextView.GONE);
            participants.setVisibility(TextView.GONE);
            metricsTitle.setVisibility(TextView.GONE);
            metrics.setVisibility(TextView.GONE);
            instructions.setText("Escolha SERVIDOR em um aparelho. Conecte os outros à mesma rede Wi‑Fi e escolha PARTICIPANTE.");
        }
    }

}

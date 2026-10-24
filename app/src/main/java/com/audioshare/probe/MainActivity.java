package com.audioshare.probe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_MIC = 41;
    private static final int REQ_MUSIC_FOLDER = 44;
    private static final String ACTION_SERVER = "com.audioshare.probe.SERVER";
    private static final String ACTION_CLIENT = "com.audioshare.probe.CLIENT";
    private static final String ACTION_STOP = "com.audioshare.probe.STOP";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView micLevelLabel;
    private Button serverButton, clientButton, stopButton;
    private final ExecutorService folderExecutor = Executors.newSingleThreadExecutor();
    private int folderScanGeneration;
    private TextView instructions;
    private TextView participantsTitle;
    private TextView participants;
    private TextView metricsTitle;
    private TextView metrics;
    private LinearLayout musicControls;
    private LinearLayout musicLibrary;
    private LinearLayout participantsCard;
    private TextView musicLibraryTitle;
    private LinearLayout playerDock;
    private TextView musicVolumeLabel;
    private LinearLayout voiceControls;
    private TextView voiceVolumeLabel;
    private Button musicToggle;

    private Button musicPreviousButton;
    private Button musicNextButton;
    private Button musicShuffleButton;
    private TextView musicFolderLabel;
    private TextView musicTrackLabel;
    private ListView musicList;
    private BaseAdapter musicListAdapter;
    private SeekBar musicVolumeSlider;
    private Uri selectedMusicFolder;
    private final ArrayList<Uri> musicPlaylist = new ArrayList<>();
    private final Map<Uri, String> musicTitles = new HashMap<>();
    private String lastListTrackTitle;
    private String pendingAction;
    private boolean receiverRegistered;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioService.ACTION_SERVER_ALREADY_ONLINE.equals(intent.getAction()))
                Toast.makeText(MainActivity.this, "Já existe um servidor ativo nesta rede.", Toast.LENGTH_LONG).show();
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
        applySavedTheme();
        super.onCreate(state);
        setTitle("Áudio Share");
        buildUi();
        loadSavedMusicFolder();
        renderStatus();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(AudioService.ACTION_STATUS);
        filter.addAction(AudioService.ACTION_SERVER_ALREADY_ONLINE);
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

    @Override protected void onDestroy() {
        folderScanGeneration++;
        folderExecutor.shutdownNow();
        super.onDestroy();
    }
    private void applySavedTheme() {
        String choice = getPreferences(MODE_PRIVATE).getString("theme", "system");
        boolean dark = "dark".equals(choice) || ("system".equals(choice) && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
    }
    private void buildUi() {
        int pad = dp(12);
        LinearLayout content = new LinearLayout(this);
        content.setPadding(pad, dp(6), pad, dp(8));
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(themeColor(android.R.attr.colorBackground, Color.WHITE));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Áudio Share");
        title.setTextSize(22);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = new Button(this);
        settings.setText("⚙");
        settings.setMinWidth(dp(48));
        settings.setMinHeight(dp(44));
        settings.setPadding(dp(8), 0, dp(8), 0);
        settings.setContentDescription("Configurações");
        settings.setOnClickListener(v -> showSettings());
        header.addView(settings);
        content.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        LinearLayout scrollContent = new LinearLayout(this);
        scrollContent.setOrientation(LinearLayout.VERTICAL);

        TextView subtitle = new TextView(this);
        subtitle.setText("Intercom de voz pela rede local. O servidor retransmite a voz para os outros aparelhos.");
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(1), 0, dp(4));
        scrollContent.addView(subtitle);

        serverButton = new Button(this);
        serverButton.setText("Usar este aparelho como SERVIDOR");
        compactButton(serverButton);
        serverButton.setOnClickListener(v -> requestMicThen(ACTION_SERVER));
        scrollContent.addView(serverButton);

        clientButton = new Button(this);
        clientButton.setText("Conectar como PARTICIPANTE");
        compactButton(clientButton);
        clientButton.setOnClickListener(v -> requestMicThen(ACTION_CLIENT));
        scrollContent.addView(clientButton);

        stopButton = new Button(this);
        stopButton.setText("Parar áudio");
        compactButton(stopButton);
        stopButton.setOnClickListener(v -> sendServiceAction(ACTION_STOP));
        stopButton.setVisibility(View.GONE);
        scrollContent.addView(stopButton);

        voiceControls = new LinearLayout(this);
        voiceControls.setOrientation(LinearLayout.VERTICAL);
        TextView voiceTitle = new TextView(this);
        voiceTitle.setText("Volume da minha voz enviada");
        voiceTitle.setTextSize(15);
        voiceTitle.setPadding(0, dp(8), 0, 0);
        voiceControls.addView(voiceTitle);
        voiceVolumeLabel = new TextView(this);
        voiceVolumeLabel.setTextSize(13);
        voiceControls.addView(voiceVolumeLabel);
        SeekBar voiceVolume = new SeekBar(this);
        voiceVolume.setMax(200);
        voiceVolume.setThumbTintList(android.content.res.ColorStateList.valueOf(0xff36b7ad));
        voiceVolume.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff36b7ad));
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
        micLevelLabel = new TextView(this);
        micLevelLabel.setTextSize(12);
        voiceControls.addView(micLevelLabel);
        TextView voiceNotice = new TextView(this);
        voiceNotice.setText("Ajusta apenas a voz enviada por este aparelho. Acima de 100% pode aumentar ruído ou distorção.");
        voiceNotice.setTextSize(12);
        voiceControls.addView(voiceNotice);
        scrollContent.addView(voiceControls);

        musicControls = new LinearLayout(this);
        musicControls.setOrientation(LinearLayout.VERTICAL);
        musicControls.setPadding(0, dp(8), 0, dp(4));
        TextView musicTitle = new TextView(this);
        musicTitle.setText("Biblioteca musical");
        musicTitle.setTextSize(14);
        musicTitle.setTypeface(null, Typeface.BOLD);
        musicTitle.setTextColor(primaryAppText());
        musicTitle.setPadding(0, dp(3), 0, dp(4));
        musicControls.addView(musicTitle);

        musicFolderLabel = new TextView(this);
        musicFolderLabel.setText("Nenhuma pasta selecionada");
        musicFolderLabel.setTextSize(12);
        musicFolderLabel.setPadding(0, dp(2), 0, dp(2));
        musicFolderLabel.setTextColor(secondaryAppText());
        musicControls.addView(musicFolderLabel);

        musicLibrary = new LinearLayout(this);
        musicLibrary.setOrientation(LinearLayout.VERTICAL);
        musicLibrary.setPadding(dp(2), dp(3), dp(2), 0);
        musicLibrary.addView(musicControls);
        musicLibraryTitle = new TextView(this);
        musicLibraryTitle.setText("Faixas");
        musicLibraryTitle.setTextSize(13);
        musicLibraryTitle.setTypeface(null, Typeface.BOLD);
        musicLibraryTitle.setPadding(dp(4), dp(4), dp(4), dp(5));
        musicLibraryTitle.setTextColor(secondaryAppText());
        musicLibrary.addView(musicLibraryTitle);

        musicList = new ListView(this);
        musicList.setDivider(new ColorDrawable(isDarkPalette() ? 0xff484848 : 0xffdedede));
        musicList.setDividerHeight(dp(1));
        musicList.setSelector(android.R.color.transparent);
        musicList.setVerticalScrollBarEnabled(true);
        musicList.setPadding(0, 0, 0, 0);
        musicList.setOnItemClickListener((parent, view, position, id) -> selectMusicTrack(position));
        musicListAdapter = new BaseAdapter() {
            @Override public int getCount() { return musicPlaylist.size(); }
            @Override public Object getItem(int position) { return musicPlaylist.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row = convertView instanceof LinearLayout ? (LinearLayout) convertView : new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(44));
                row.setPadding(dp(5), dp(2), dp(6), dp(2));
                View marker;
                TextView line;
                if (row.getChildCount() == 0) {
                    marker = new View(MainActivity.this);
                    row.addView(marker, new LinearLayout.LayoutParams(dp(3), dp(26)));
                    line = new TextView(MainActivity.this);
                    line.setTextSize(13);
                    line.setGravity(Gravity.CENTER_VERTICAL);
                    line.setMaxLines(1);
                    line.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    line.setPadding(dp(9), 0, 0, 0);
                    row.addView(line, new LinearLayout.LayoutParams(0, -1, 1));
                } else {
                    marker = row.getChildAt(0);
                    line = (TextView) row.getChildAt(1);
                }
                Uri uri = musicPlaylist.get(position);
                String name = musicTitles.get(uri);
                if (name == null || name.trim().isEmpty()) name = getUriTitle(uri);
                boolean selected = name.equals(AudioService.getMusicTrackTitle());
                line.setText((position + 1) + ".  " + name);
                line.setTextColor(primaryAppText());
                marker.setBackgroundColor(selected ? 0xff36b7ad : Color.TRANSPARENT);
                row.setBackgroundColor(selected ? (isDarkPalette() ? 0x1b36b7ad : 0x12308f89) : Color.TRANSPARENT);
                return row;
            }
        };
        musicList.setAdapter(musicListAdapter);
        musicLibrary.addView(musicList, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView emptyMusic = new TextView(this);
        emptyMusic.setText("Nenhuma música carregada");
        emptyMusic.setTextSize(12);
        emptyMusic.setTextColor(secondaryAppText());
        emptyMusic.setGravity(Gravity.CENTER);
        musicLibrary.addView(emptyMusic, new LinearLayout.LayoutParams(-1, 0, 1));
        musicList.setEmptyView(emptyMusic);

        status = new TextView(this);
        status.setTextSize(13);
        status.setPadding(0, dp(4), 0, dp(4));
        status.setTextIsSelectable(true);
        scrollContent.addView(status);

        participantsCard = new LinearLayout(this);
        participantsCard.setOrientation(LinearLayout.VERTICAL);
        participantsCard.setPadding(dp(10), dp(7), dp(10), dp(8));
        GradientDrawable participantsBackground = new GradientDrawable();
        participantsBackground.setColor(cardSurfaceColor());
        participantsBackground.setCornerRadius(dp(8));
        participantsBackground.setStroke(dp(1), cardStrokeColor());
        participantsCard.setBackground(participantsBackground);
        participantsTitle = new TextView(this);
        participantsTitle.setText("Aparelhos conectados");
        participantsTitle.setTextSize(13);
        participantsTitle.setTypeface(null, Typeface.BOLD);
        participantsTitle.setTextColor(primaryAppText());
        participantsTitle.setPadding(0, 0, 0, dp(3));
        participantsCard.addView(participantsTitle);
        participants = new TextView(this);
        participants.setTextSize(12);
        participants.setTextColor(secondaryAppText());
        participantsCard.addView(participants);
        LinearLayout.LayoutParams participantsParams = new LinearLayout.LayoutParams(-1, -2);
        participantsParams.topMargin = dp(2);
        scrollContent.addView(participantsCard, participantsParams);

        metricsTitle = new TextView(this);
        metricsTitle.setText("Métricas da conexão");
        metricsTitle.setTextSize(15);
        metricsTitle.setTypeface(null, Typeface.BOLD);
        metricsTitle.setPadding(0, dp(7), 0, dp(3));
        scrollContent.addView(metricsTitle);
        metrics = new TextView(this);
        metrics.setTextSize(13);
        metrics.setPadding(0, 0, 0, dp(6));
        scrollContent.addView(metrics);

        instructions = new TextView(this);
        instructions.setTextSize(12);
        instructions.setPadding(0, dp(7), 0, dp(10));
        scrollContent.addView(instructions);

        scroll.addView(scrollContent);
        // Keep the controls at the top; let the track list consume the flexible middle area.
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 0.6f));
        content.addView(musicLibrary, new LinearLayout.LayoutParams(-1, 0, 1.4f));

        playerDock = new LinearLayout(this);
        playerDock.setOrientation(LinearLayout.VERTICAL);
        playerDock.setPadding(dp(10), dp(5), dp(10), dp(3));
        playerDock.setElevation(dp(4));
        GradientDrawable dockBackground = new GradientDrawable();
        dockBackground.setColor(cardSurfaceColor());
        dockBackground.setCornerRadius(dp(12));
        dockBackground.setStroke(dp(1), cardStrokeColor());
        playerDock.setBackground(dockBackground);
        musicTrackLabel = new TextView(this);
        musicTrackLabel.setText(AudioService.getMusicTrackTitle());
        musicTrackLabel.setTextSize(13);
        musicTrackLabel.setTypeface(null, Typeface.BOLD);
        musicTrackLabel.setTextColor(primaryAppText());
        musicTrackLabel.setMaxLines(1);
        musicTrackLabel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        playerDock.addView(musicTrackLabel);

        LinearLayout transport = new LinearLayout(this);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        musicPreviousButton = new Button(this);
        musicPreviousButton.setText("⏮");
        compactButton(musicPreviousButton);
        musicPreviousButton.setOnClickListener(v -> sendMusicAction(AudioService.ACTION_MUSIC_PREVIOUS));
        transport.addView(musicPreviousButton, new LinearLayout.LayoutParams(0, dp(42), 1));
        musicToggle = new Button(this);
        musicToggle.setTextSize(19);
        musicToggle.setContentDescription("Reproduzir música");
        compactButton(musicToggle);
        musicToggle.setOnClickListener(v -> toggleMusicPlayback());
        transport.addView(musicToggle, new LinearLayout.LayoutParams(0, dp(42), 2));
        musicNextButton = new Button(this);
        musicNextButton.setText("⏭");
        compactButton(musicNextButton);
        musicNextButton.setOnClickListener(v -> sendMusicAction(AudioService.ACTION_MUSIC_NEXT));
        transport.addView(musicNextButton, new LinearLayout.LayoutParams(0, dp(42), 1));
        musicShuffleButton = new Button(this);
        musicShuffleButton.setText("⇄");
        musicShuffleButton.setTextSize(15);
        musicShuffleButton.setMinWidth(dp(38));
        musicShuffleButton.setMinHeight(dp(38));
        musicShuffleButton.setPadding(0, 0, 0, 0);
        musicShuffleButton.setContentDescription("Alternar aleatório");
        musicShuffleButton.setOnClickListener(v -> sendMusicAction(AudioService.ACTION_MUSIC_SHUFFLE));
        transport.addView(musicShuffleButton, new LinearLayout.LayoutParams(dp(42), dp(42)));
        playerDock.addView(transport);

        musicVolumeLabel = new TextView(this);
        musicVolumeLabel.setTextSize(11);
        musicVolumeLabel.setTextColor(secondaryAppText());
        playerDock.addView(musicVolumeLabel);
        musicVolumeSlider = new SeekBar(this);
        musicVolumeSlider.setMax(100);
        musicVolumeSlider.setThumbTintList(android.content.res.ColorStateList.valueOf(0xff36b7ad));
        musicVolumeSlider.setProgressTintList(android.content.res.ColorStateList.valueOf(0xff36b7ad));
        int savedProgress = getPreferences(MODE_PRIVATE).getInt("music_slider_progress", 10);
        musicVolumeSlider.setProgress(savedProgress);
        AudioService.setMusicSendVolume(sliderToGain(savedProgress));
        musicVolumeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float gain = sliderToGain(progress);
                AudioService.setMusicSendVolume(gain);
                if (fromUser) getPreferences(MODE_PRIVATE).edit().putInt("music_slider_progress", progress).apply();
                updateMusicVolumeLabel(progress, gain);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        updateMusicVolumeLabel(savedProgress, sliderToGain(savedProgress));
        playerDock.addView(musicVolumeSlider);
        LinearLayout.LayoutParams dockParams = new LinearLayout.LayoutParams(-1, -2);
        dockParams.topMargin = dp(6);
        dockParams.bottomMargin = dp(14);
        content.addView(playerDock, dockParams);
        setContentView(content);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int themeColor(int attribute, int fallback) {
        TypedValue value = new TypedValue();
        if (!getTheme().resolveAttribute(attribute, value, true)) return fallback;
        if (value.resourceId != 0) {
            try { return getResources().getColor(value.resourceId); }
            catch (Exception ignored) { }
        }
        return value.data;
    }

    private boolean isDarkPalette() {
        int background = themeColor(android.R.attr.colorBackground, Color.WHITE);
        double luminance = 0.2126 * Color.red(background) + 0.7152 * Color.green(background) + 0.0722 * Color.blue(background);
        return luminance < 128;
    }

    private int primaryAppText() { return isDarkPalette() ? 0xffe6e6e6 : 0xff242424; }
    private int secondaryAppText() { return isDarkPalette() ? 0xffb5b5b5 : 0xff666666; }
    private int cardSurfaceColor() { return isDarkPalette() ? 0xff393939 : 0xfff4f5f5; }
    private int cardStrokeColor() { return isDarkPalette() ? 0xff4a4a4a : 0xffdedede; }

    private void compactButton(Button button) {
        button.setMinHeight(dp(42));
        button.setPadding(dp(7), 0, dp(7), 0);
        button.setTextSize(12);
        button.setMaxLines(2);
    }

    private void updateMusicVolumeLabel(int progress, float gain) {
        musicVolumeLabel.setText("Música: " + progress + "% da barra • envio "
                + String.format(java.util.Locale.getDefault(), "%.1f", gain) + "% (máximo 25%)");
    }

    private static float sliderToGain(int progress) {
        return Math.max(0f, Math.min(25f, Math.max(0, Math.min(100, progress)) * 0.25f));
    }

    private static int gainToSlider(int gain) {
        return Math.max(0, Math.min(100, Math.round(Math.max(0, Math.min(25, gain)) * 4f)));
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
        final int generation = ++folderScanGeneration;
        final Uri folder = selectedMusicFolder;
        musicPlaylist.clear();
        musicTitles.clear();
        refreshMusicLibrary();
        musicFolderLabel.setText(folder == null ? "Nenhuma pasta selecionada" : "Lendo pasta de músicas…");
        if (folder == null) {
            refreshMusicLibrary();
            renderStatus();
            return;
        }
        folderExecutor.execute(() -> {
            ArrayList<Uri> found = new ArrayList<>();
            Map<Uri, String> titles = new HashMap<>();
            final String title = getFolderTitle(folder);
            String error = null;
            try {
                Set<String> visited = new HashSet<>();
                String rootId = DocumentsContract.getTreeDocumentId(folder);
                addFolderDocuments(folder, rootId, visited, found, titles, new boolean[]{false}, uri ->
                        runOnUiThread(() -> {
                            if (isFinishing() || generation != folderScanGeneration) return;
                            musicPlaylist.clear();
                            musicPlaylist.add(uri);
                            musicTitles.clear();
                            musicTitles.put(uri, titles.get(uri));
                            refreshMusicLibrary();
                            musicFolderLabel.setText(title + " • música encontrada; lendo restante…");
                            if (AudioService.getMode() == AudioService.MODE_SERVER) sendMusicPlaylist();
                            renderStatus();
                        }));
                Collections.sort(found, (left, right) -> {
                    String leftTitle = titles.get(left);
                    String rightTitle = titles.get(right);
                    return (leftTitle == null ? "" : leftTitle).compareToIgnoreCase(rightTitle == null ? "" : rightTitle);
                });
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String folderTitle = title;
            final String scanError = error;
            final ArrayList<Uri> result = found;
            runOnUiThread(() -> {
                if (isFinishing() || generation != folderScanGeneration) return;
                musicPlaylist.clear();
                musicPlaylist.addAll(result);
                musicTitles.clear();
                musicTitles.putAll(titles);
                refreshMusicLibrary();
                musicFolderLabel.setText(scanError == null ? folderTitle + " • " + musicPlaylist.size() + " músicas"
                        : "Não foi possível ler a pasta selecionada");
                if (scanError != null) Toast.makeText(MainActivity.this, "Não foi possível ler essa pasta: " + scanError, Toast.LENGTH_LONG).show();
                if (AudioService.getMode() == AudioService.MODE_SERVER) sendMusicPlaylist();
                renderStatus();
            });
        });
    }
    private interface FirstMusicFoundListener {
        void onFound(Uri uri);
    }

    private void refreshMusicLibrary() {
        if (musicLibraryTitle != null)
            musicLibraryTitle.setText("Faixas");
        if (musicListAdapter != null) musicListAdapter.notifyDataSetChanged();
    }

    private void addFolderDocuments(Uri tree, String parentId, Set<String> visited, List<Uri> result,
                                    Map<Uri, String> titles, boolean[] firstFound, FirstMusicFoundListener listener) {
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
                    addFolderDocuments(tree, documentId, visited, result, titles, firstFound, listener);
                } else if (isAudioDocument(name, mime)) {
                    Uri audio = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
                    result.add(audio);
                    titles.put(audio, name == null ? "" : name);
                    if (!firstFound[0]) {
                        firstFound[0] = true;
                        listener.onFound(audio);
                    }
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

    private void selectMusicTrack(int index) {
        if (AudioService.getMode() != AudioService.MODE_SERVER) {
            Toast.makeText(this, "Inicie este aparelho como servidor para selecionar uma música.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, AudioService.class)
                .setAction(AudioService.ACTION_MUSIC_SELECT_TRACK)
                .putExtra("trackIndex", index);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    private void showSettings() {
        new AlertDialog.Builder(this).setTitle("Configurações")
                .setItems(new String[]{"Selecionar pasta de músicas", "Tema"}, (dialog, which) -> {
                    if (which == 0) chooseMusicFolder(); else showThemeSettings();
                }).show();
    }
    private void showThemeSettings() {
        String[] themes = {"Seguir sistema", "Claro", "Escuro"};
        String[] values = {"system", "light", "dark"};
        String current = getPreferences(MODE_PRIVATE).getString("theme", "system");
        int checked = "light".equals(current) ? 1 : "dark".equals(current) ? 2 : 0;
        new AlertDialog.Builder(this).setTitle("Tema").setSingleChoiceItems(themes, checked, (dialog, which) -> {
            getPreferences(MODE_PRIVATE).edit().putString("theme", values[which]).apply();
            dialog.dismiss(); recreate();
        }).setNegativeButton("Cancelar", null).show();
    }
    private void renderStatus() {
        if (status == null) return;
        int mode = AudioService.getMode();
        String currentStatus = AudioService.getStatus();
        if (mode == AudioService.MODE_SERVER) {
            int detailLine = currentStatus.indexOf('\n');
            if (detailLine >= 0) currentStatus = currentStatus.substring(0, detailLine);
            if (currentStatus.startsWith("Reproduzindo")) currentStatus = "Servidor ativo";
        }
        status.setText(currentStatus);
        stopButton.setVisibility(mode == AudioService.MODE_STOPPED ? android.view.View.GONE : android.view.View.VISIBLE);
        serverButton.setVisibility(mode == AudioService.MODE_CLIENT ? android.view.View.GONE : android.view.View.VISIBLE);
        clientButton.setVisibility(mode == AudioService.MODE_SERVER ? android.view.View.GONE : android.view.View.VISIBLE);
        serverButton.setEnabled(mode != AudioService.MODE_SERVER);
        clientButton.setEnabled(mode != AudioService.MODE_CLIENT);
        micLevelLabel.setText(AudioService.getMicLevelDescription());
        if (mode == AudioService.MODE_SERVER) {
            voiceControls.setVisibility(TextView.VISIBLE);
            musicControls.setVisibility(TextView.VISIBLE);
            musicLibrary.setVisibility(View.VISIBLE);
            playerDock.setVisibility(View.VISIBLE);
            boolean playing = AudioService.isMusicActive();
            musicToggle.setText(playing ? "Ⅱ" : "▶");
            musicToggle.setContentDescription(playing ? "Pausar música" : "Reproduzir música");
            String currentTrack = AudioService.getMusicTrackTitle();
            musicTrackLabel.setText(currentTrack);
            if (!currentTrack.equals(lastListTrackTitle) && musicListAdapter != null) {
                lastListTrackTitle = currentTrack;
                musicListAdapter.notifyDataSetChanged();
            }
            musicShuffleButton.setText(AudioService.isMusicShuffle() ? "⇄" : "↔");
            musicShuffleButton.setAlpha(AudioService.isMusicShuffle() ? 1f : 0.55f);
            musicToggle.setEnabled(!musicPlaylist.isEmpty());
            musicPreviousButton.setEnabled(musicPlaylist.size() > 1);
            musicNextButton.setEnabled(musicPlaylist.size() > 1);
            musicShuffleButton.setEnabled(musicPlaylist.size() > 1);
            participantsCard.setVisibility(View.VISIBLE);
            metricsTitle.setVisibility(TextView.GONE);
            metrics.setVisibility(TextView.GONE);
            participants.setText(AudioService.getParticipants());
            instructions.setVisibility(View.GONE);
        } else if (mode == AudioService.MODE_CLIENT) {
            voiceControls.setVisibility(TextView.VISIBLE);
            musicControls.setVisibility(TextView.GONE);
            musicLibrary.setVisibility(View.GONE);
            playerDock.setVisibility(View.GONE);
            participantsCard.setVisibility(View.GONE);
            metricsTitle.setVisibility(TextView.VISIBLE);
            metrics.setVisibility(TextView.VISIBLE);
            metrics.setText(AudioService.getMetrics());
            instructions.setText("Conecte este aparelho à mesma rede Wi‑Fi do servidor. "
                    + "O app procura e reconecta automaticamente na rede local.");
            instructions.setVisibility(View.VISIBLE);
        } else {
            voiceControls.setVisibility(TextView.GONE);
            musicControls.setVisibility(TextView.GONE);
            musicLibrary.setVisibility(View.GONE);
            playerDock.setVisibility(View.GONE);
            participantsCard.setVisibility(View.GONE);
            metricsTitle.setVisibility(TextView.GONE);
            metrics.setVisibility(TextView.GONE);
            instructions.setText("Escolha SERVIDOR em um aparelho. Conecte os outros à mesma rede Wi‑Fi e escolha PARTICIPANTE.");
            instructions.setVisibility(View.VISIBLE);
        }
    }

}

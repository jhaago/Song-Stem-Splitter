package com.jhaago.vocalreducer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class MainActivity extends Activity implements ProcessingService.Listener {
    private static final int PICK_AUDIO = 1, SAVE_AUDIO = 2;
    private static final int BACKGROUND = Color.rgb(17, 26, 37), CARD = Color.rgb(28, 41, 57);
    private static final int TEXT = Color.rgb(241, 245, 249), MUTED = Color.rgb(169, 185, 202);
    private static final int MINT = Color.rgb(133, 227, 203), BORDER = Color.rgb(50, 68, 86);
    private final Handler main = new Handler(Looper.getMainLooper());
    private ProcessingService service;
    private ProcessingService.State current;
    private boolean bound, active, seeking, updating;
    private StemPlayer player;
    private TrackSession playingSession;
    private int level = 25, pendingSaveLevel = 25;
    private boolean compareOriginal;
    private String restoredId;
    private long restoredFrame;
    private LinearLayout intro, progressCard, resultCard;
    private Button choose, play, save, share, cancel;
    private ToggleButton compare;
    private TextView modelHint, status, progressTitle, progressDetail, progressNumbers, trackName, trackDetail, time, vocalValue;
    private SeekBar timeline, vocal;
    private ProgressBar progress;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ProcessingService.LocalBinder) binder).service();
            service.addListener(MainActivity.this);
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; choose.setEnabled(false); }
    };
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { if (player != null) player.pause(); }
    };
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateTime();
            if (active) main.postDelayed(this, 200);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        level = Math.max(0, Math.min(100, getPreferences(MODE_PRIVATE).getInt("vocal-level", 25)));
        if (saved != null) {
            level = saved.getInt("level", level);
            pendingSaveLevel = saved.getInt("pending-save-level", level);
            compareOriginal = saved.getBoolean("compare", false);
            restoredId = saved.getString("track-id");
            restoredFrame = saved.getLong("frame", 0L);
        }
        buildScreen();
    }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        bound = bindService(new Intent(this, ProcessingService.class), connection, BIND_AUTO_CREATE);
        ContextCompat.registerReceiver(this, noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED);
        main.post(ticker);
    }
    @Override protected void onStop() {
        active = false;
        main.removeCallbacks(ticker);
        releasePlayer();
        getPreferences(MODE_PRIVATE).edit().putInt("vocal-level", level).apply();
        unregisterReceiver(noisy);
        if (service != null) service.removeListener(this);
        if (bound) unbindService(connection);
        bound = false; service = null;
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        rememberPosition();
        out.putInt("level", level); out.putInt("pending-save-level", pendingSaveLevel);
        out.putBoolean("compare", compareOriginal); out.putString("track-id", restoredId);
        out.putLong("frame", restoredFrame);
        super.onSaveInstanceState(out);
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BACKGROUND); scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(18), dp(22), dp(28));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(scroll);

        LinearLayout brand = row();
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_notification); mark.setImageTintList(ColorStateList.valueOf(MINT));
        mark.setPadding(dp(10), dp(10), dp(10), dp(10)); mark.setBackground(shape(CARD, 14, 0));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout brandText = new LinearLayout(this); brandText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, -2, 1); brandParams.setMargins(dp(12), 0, 0, 0);
        brand.addView(brandText, brandParams);
        brandText.addView(text("Vocal Reducer", 20, TEXT, true));
        TextView badge = text("AD-FREE  ·  ON YOUR PHONE", 10, MINT, true); badge.setLetterSpacing(0.08f);
        brandText.addView(badge);
        Button about = button("About", false); about.setTextSize(12); about.setContentDescription("About Vocal Reducer and open source credits");
        brand.addView(about, new LinearLayout.LayoutParams(dp(68), dp(48))); about.setOnClickListener(v -> about());
        page.addView(brand); space(page, 26);

        intro = new LinearLayout(this); intro.setOrientation(LinearLayout.VERTICAL);
        TextView heading = text("Turn vocals down.\nKeep the music.", 31, TEXT, true); heading.setLineSpacing(dp(2), 1f);
        intro.addView(heading); space(intro, 12);
        intro.addView(text("Create a backing track, or leave a little vocal to sing along with.", 16, MUTED, false));
        space(intro, 24); page.addView(intro);

        LinearLayout importCard = card();
        importCard.addView(text("Start with a track", 16, TEXT, true)); space(importCard, 5);
        importCard.addView(text("MP3, M4A or WAV · up to 15 minutes", 13, MUTED, false)); space(importCard, 15);
        choose = button("Choose a track", true); choose.setEnabled(false);
        importCard.addView(choose, fullButton()); choose.setOnClickListener(v -> pick()); space(importCard, 12);
        modelHint = text("First use downloads an 80 MB model. After setup, you can process tracks offline.", 12, MUTED, false);
        importCard.addView(modelHint); page.addView(importCard); space(page, 16);

        status = text("", 14, MINT, false); status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status); space(page, 8);

        progressCard = card(); progressCard.setVisibility(View.GONE);
        progressTitle = text("Preparing your track", 20, TEXT, true); progressCard.addView(progressTitle); space(progressCard, 8);
        progressDetail = text("", 14, MUTED, false); progressCard.addView(progressDetail); space(progressCard, 17);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000); progress.setProgressTintList(ColorStateList.valueOf(MINT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(BORDER));
        progressCard.addView(progress, new LinearLayout.LayoutParams(-1, dp(8))); space(progressCard, 10);
        progressNumbers = text("", 12, MUTED, false); progressCard.addView(progressNumbers); space(progressCard, 16);
        cancel = button("Cancel", false); progressCard.addView(cancel, fullButton());
        cancel.setOnClickListener(v -> { if (service != null) service.cancelProcessing(); });
        page.addView(progressCard);

        resultCard = card(); resultCard.setVisibility(View.GONE);
        TextView ready = text("READY TO MIX", 11, MINT, true); ready.setLetterSpacing(0.1f); resultCard.addView(ready); space(resultCard, 10);
        trackName = text("", 22, TEXT, true); trackName.setMaxLines(3); resultCard.addView(trackName); space(resultCard, 6);
        trackDetail = text("", 12, MUTED, false); resultCard.addView(trackDetail); space(resultCard, 18);
        LinearLayout transport = row();
        play = button("Play", true); transport.addView(play, new LinearLayout.LayoutParams(dp(88), dp(54)));
        time = text("0:00 / 0:00", 14, TEXT, false); time.setTypeface(Typeface.MONOSPACE); time.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(0, -1, 1); timeParams.setMargins(dp(8), 0, 0, 0);
        transport.addView(time, timeParams); resultCard.addView(transport);
        play.setOnClickListener(v -> { if (player != null) { if (player.isPlaying()) player.pause(); else player.play(); updateTime(); } });
        timeline = new SeekBar(this); timeline.setMax(10000); timeline.setContentDescription("Playback position");
        resultCard.addView(timeline, new LinearLayout.LayoutParams(-1, dp(48)));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && playingSession != null) time.setText(format(Math.round(playingSession.frames * value / 10000d)) + " / " + format(playingSession.frames));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (player != null && playingSession != null) player.seek(Math.round(playingSession.frames * bar.getProgress() / 10000d));
                seeking = false; updateTime();
            }
        });
        View line = new View(this); line.setBackgroundColor(BORDER); resultCard.addView(line, new LinearLayout.LayoutParams(-1, dp(1))); space(resultCard, 20);
        LinearLayout vocalRow = row(); vocalRow.addView(text("Vocal level", 17, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        vocalValue = text(level + "%", 25, MINT, true); vocalRow.addView(vocalValue); resultCard.addView(vocalRow);
        vocal = new SeekBar(this); vocal.setMax(100); vocal.setProgress(level); vocal.setContentDescription("Vocal level, from instrumental to original");
        resultCard.addView(vocal, new LinearLayout.LayoutParams(-1, dp(56)));
        vocal.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (updating) return;
                level = value;
                if (fromUser && compareOriginal) { compareOriginal = false; compare.setChecked(false); }
                updateMix();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) { getPreferences(MODE_PRIVATE).edit().putInt("vocal-level", level).apply(); }
        });
        LinearLayout ends = row(); ends.addView(text("0% · Instrumental", 11, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView end = text("100% · Original", 11, MUTED, false); end.setGravity(Gravity.END); ends.addView(end); resultCard.addView(ends); space(resultCard, 17);
        compare = new ToggleButton(this); compare.setTextOff("Compare original"); compare.setTextOn("Listening to original");
        compare.setTextColor(TEXT); compare.setTextSize(14); compare.setAllCaps(false); compare.setBackground(shape(BACKGROUND, 12, BORDER));
        compare.setChecked(compareOriginal); resultCard.addView(compare, fullButton());
        compare.setOnCheckedChangeListener((button, checked) -> { compareOriginal = checked; updateMix(); }); space(resultCard, 10);
        resultCard.addView(text("Changing the level is instant. Some vocals or echoes may remain.", 12, MUTED, false)); space(resultCard, 22);
        LinearLayout exports = row(); save = button("Save WAV", true); share = button("Share", false);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, dp(54), 1); saveParams.setMargins(0, 0, dp(8), 0);
        exports.addView(save, saveParams); exports.addView(share, new LinearLayout.LayoutParams(0, dp(54), 1)); resultCard.addView(exports);
        save.setOnClickListener(v -> save()); share.setOnClickListener(v -> startExport(null, true, effectiveLevel()));
        space(resultCard, 11); resultCard.addView(text("Saves the mix you're hearing as a WAV file.", 12, MUTED, false));
        page.addView(resultCard); space(page, 20);
        TextView footer = text("TEST BUILD 0.1\nTry a short clip first. Processing speed depends on your phone.", 12, MUTED, false);
        footer.setLineSpacing(dp(5), 1f); page.addView(footer);
    }

    @Override public void onState(ProcessingService.State state) {
        if (!active) return;
        current = state;
        boolean busy = state.busy;
        choose.setEnabled(!busy); choose.setAlpha(busy ? 0.5f : 1f);
        choose.setText(state.session == null ? "Choose a track" : "Choose another track");
        intro.setVisibility(state.session == null && !busy ? View.VISIBLE : View.GONE);
        progressCard.setVisibility(busy ? View.VISIBLE : View.GONE);
        resultCard.setVisibility(!busy && state.session != null ? View.VISIBLE : View.GONE);
        status.setText(state.message); status.setTextColor(state.error ? Color.rgb(255, 178, 178) : MINT);
        status.setVisibility(state.message.isEmpty() ? View.GONE : View.VISIBLE);
        if (service != null && service.hasModel()) modelHint.setText("Offline model ready. Your songs are processed on this phone.");
        if (busy) {
            releasePlayer();
            progressTitle.setText(state.title); progressDetail.setText(state.detail);
            progress.setIndeterminate(state.cancelling); progress.setProgress(Math.round(state.progress * 1000));
            cancel.setEnabled(!state.cancelling); cancel.setText(state.cancelling ? "Stopping…" : "Cancel");
        } else if (state.session != null) {
            boolean different = restoredId != null && !restoredId.equals(state.session.id);
            if (different) { restoredFrame = 0; compareOriginal = false; compare.setChecked(false); }
            trackName.setText(state.session.name);
            trackDetail.setText(format(state.session.frames) + " · Stereo audio · Ready to save");
            if (player == null) {
                try {
                    playingSession = state.session;
                    player = new StemPlayer(this, state.session, error -> main.post(() -> showError(error)));
                    if (state.session.id.equals(restoredId)) player.seek(restoredFrame);
                    restoredId = state.session.id;
                    updateMix();
                } catch (Exception error) { showError(error.getMessage()); }
            }
            play.setEnabled(player != null); save.setEnabled(player != null); share.setEnabled(player != null);
        }
        updateTime();
        if (!busy && service != null) {
            ProcessingService.ExportResult result = service.takeExport();
            if (result != null) {
                if (result.share) showShare(result);
                else {
                    status.setText("Saved: " + result.name); status.setTextColor(MINT); status.setVisibility(View.VISIBLE);
                }
            }
        }
    }

    private void updateMix() {
        vocalValue.setText(compareOriginal ? "100%" : level + "%");
        if (player != null) player.setLevel(level, compareOriginal);
        save.setText(compareOriginal ? "Save original" : "Save WAV");
        share.setContentDescription(compareOriginal ? "Share original audio" : "Share mix with vocals at " + level + " percent");
    }
    private int effectiveLevel() { return compareOriginal ? 100 : level; }
    private void updateTime() {
        if (current != null && current.busy) {
            long seconds = (SystemClock.elapsedRealtime() - current.started) / 1000;
            progressNumbers.setText(Math.round(current.progress * 100) + "%  ·  " + clock(seconds) + " elapsed");
        }
        if (player == null || playingSession == null) return;
        long frame = player.framePosition();
        if (!seeking) {
            timeline.setProgress((int) Math.round(frame * 10000d / playingSession.frames));
            time.setText(format(frame) + " / " + format(playingSession.frames));
        }
        play.setText(player.isPlaying() ? "Pause" : "Play");
        play.setContentDescription(player.isPlaying() ? "Pause preview" : "Play preview");
    }

    private void pick() {
        if (current != null && current.busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
        try { startActivityForResult(intent, PICK_AUDIO); }
        catch (Exception unavailable) { showError("Android's file picker is unavailable on this phone."); }
    }
    private void save() {
        if (playingSession == null || (current != null && current.busy)) return;
        pendingSaveLevel = effectiveLevel();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/wav")
                .putExtra(Intent.EXTRA_TITLE, PcmFiles.exportName(playingSession.name, pendingSaveLevel));
        try { startActivityForResult(intent, SAVE_AUDIO); }
        catch (Exception unavailable) { showError("Android's save picker is unavailable on this phone."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == PICK_AUDIO) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException notPersistable) { /* The temporary grant also covers a live processing job. */ }
            compareOriginal = false; compare.setChecked(false);
            startJob(new Intent(this, ProcessingService.class).setAction(ProcessingService.ACTION_PROCESS)
                    .setData(uri).putExtra("name", displayName(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } else if (request == SAVE_AUDIO) startExport(uri, false, pendingSaveLevel);
    }
    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (Exception ignored) {}
        return "Track";
    }
    private void startExport(Uri destination, boolean sharing, int atLevel) {
        if (player != null) player.pause();
        Intent intent = new Intent(this, ProcessingService.class).setAction(ProcessingService.ACTION_EXPORT)
                .putExtra("level", atLevel).putExtra("share", sharing);
        if (destination != null) intent.setData(destination).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startJob(intent);
    }
    private void startJob(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notification-asked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notification-asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
        }
        try { startForegroundService(intent); choose.setEnabled(false); }
        catch (RuntimeException error) { showError("Android could not start this task. Please reopen the app and try again."); }
    }
    private void showShare(ProcessingService.ExportResult result) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM, result.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        shareIntent.setClipData(ClipData.newRawUri(result.name, result.uri));
        try { startActivity(Intent.createChooser(shareIntent, "Share your mix")); }
        catch (Exception unavailable) { showError("No sharing app is available. Use Save WAV to keep a copy instead."); }
    }
    private void rememberPosition() {
        if (player != null && playingSession != null) { restoredFrame = player.framePosition(); restoredId = playingSession.id; }
    }
    private void releasePlayer() {
        rememberPosition();
        if (player != null) { player.close(); player = null; }
        playingSession = null;
    }
    private void showError(String message) {
        String detail = message == null ? "Something interrupted this action. Please try again." : message;
        status.setText(detail); status.setTextColor(Color.rgb(255, 178, 178)); status.setVisibility(View.VISIBLE);
        Toast.makeText(this, detail, Toast.LENGTH_LONG).show();
    }
    private void about() {
        String notices;
        try (InputStream input = getAssets().open("licenses.txt"); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            notices = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        catch (Exception unavailable) { notices = "demucs.cpp — MIT license\nEigen — MPL 2.0\nAndroidX — Apache 2.0"; }
        TextView content = text("Vocal Reducer · 0.1.0 alpha\n\nBuilt from the Song Stem Splitter work. No ads, accounts or analytics. Audio separation happens on your phone; only the model download needs the internet.\n\nThis test version needs a modern 64-bit Android phone and substantial free memory. Start with a short clip. WAV export keeps the current mix; it does not modify your source file.\n\nOpen source credits\n\n" + notices, 13, TEXT, false);
        content.setPadding(dp(22), dp(12), dp(22), dp(20));
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("About Vocal Reducer").setView(scroll).setPositiveButton("Close", null).show();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout row() { LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(20), dp(18), dp(20)); card.setBackground(shape(CARD, 20, 0)); return card;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        text.setLineSpacing(dp(2), 1f); return text;
    }
    private Button button(String value, boolean primary) {
        Button button = new Button(this); button.setText(value); button.setAllCaps(false); button.setTextSize(15);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); button.setTextColor(primary ? BACKGROUND : TEXT);
        button.setBackground(shape(primary ? MINT : CARD, 12, primary ? 0 : BORDER));
        button.setMinHeight(dp(48)); button.setMinimumWidth(0); button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }
    private GradientDrawable shape(int fill, int radius, int border) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(radius));
        if (border != 0) shape.setStroke(dp(1), border); return shape;
    }
    private LinearLayout.LayoutParams fullButton() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)); }
    private void space(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private static String format(long frames) { return clock(frames / PcmFiles.RATE); }
    private static String clock(long seconds) { return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
}

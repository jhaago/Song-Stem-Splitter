package com.jhaago.vocalreducer;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProcessingService extends Service {
    public static final String ACTION_PROCESS = "com.jhaago.vocalreducer.PROCESS";
    public static final String ACTION_EXPORT = "com.jhaago.vocalreducer.EXPORT";
    public static final String ACTION_CANCEL = "com.jhaago.vocalreducer.CANCEL";
    private static final String CHANNEL = "track-processing";
    private static final int NOTIFICATION = 10;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final LocalBinder binder = new LocalBinder();
    private volatile boolean cancelled;
    private volatile boolean inNative;
    private volatile TrackSession session;
    private volatile State state;
    private volatile ExportResult exportResult;
    private SessionStore store;
    private PowerManager.WakeLock wakeLock;
    private long started;
    private long lastNotification;

    public interface Listener { void onState(State state); }
    public final class LocalBinder extends Binder { public ProcessingService service() { return ProcessingService.this; } }
    public static final class State {
        public final boolean busy, cancelling, error;
        public final String title, detail, message;
        public final float progress;
        public final long started;
        public final TrackSession session;
        State(boolean busy, boolean cancelling, boolean error, String title, String detail,
              String message, float progress, long started, TrackSession session) {
            this.busy = busy; this.cancelling = cancelling; this.error = error;
            this.title = title; this.detail = detail; this.message = message;
            this.progress = progress; this.started = started; this.session = session;
        }
    }
    public static final class ExportResult {
        public final Uri uri;
        public final String name;
        public final boolean share;
        ExportResult(Uri uri, String name, boolean share) { this.uri = uri; this.name = name; this.share = share; }
    }

    @Override public void onCreate() {
        super.onCreate();
        store = new SessionStore(getFilesDir());
        session = store.load();
        store.cleanExcept(session);
        state = new State(false, false, false, "", "", "", 0f, 0L, session);
        String pendingUri = getSharedPreferences("pending-export", MODE_PRIVATE).getString("uri", null);
        if (pendingUri != null) {
            exportResult = new ExportResult(Uri.parse(pendingUri),
                    getSharedPreferences("pending-export", MODE_PRIVATE).getString("name", "Your mix"),
                    getSharedPreferences("pending-export", MODE_PRIVATE).getBoolean("share", false));
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Track processing", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progress while a track is being prepared or saved");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VocalReducer:processing");
        wakeLock.setReferenceCounted(false);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
    public State state() { return state; }
    public void addListener(Listener listener) { listeners.add(listener); listener.onState(state); }
    public void removeListener(Listener listener) { listeners.remove(listener); }
    public synchronized ExportResult takeExport() {
        ExportResult result = exportResult;
        exportResult = null;
        if (result != null) getSharedPreferences("pending-export", MODE_PRIVATE).edit().clear().apply();
        return result;
    }
    public boolean hasModel() { return ModelStore.isDownloaded(new File(getFilesDir(), "models")); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_CANCEL.equals(action)) { cancelProcessing(); return START_NOT_STICKY; }
        if (state.busy) return START_NOT_STICKY;
        if (ACTION_PROCESS.equals(action)) {
            Uri source = intent.getData();
            if (source == null) return START_NOT_STICKY;
            String supplied = intent.getStringExtra("name");
            String name = supplied == null || supplied.trim().isEmpty() ? "Track" : supplied;
            if (begin("Preparing your track", name)) worker.execute(() -> process(source, name));
        } else if (ACTION_EXPORT.equals(action) && session != null) {
            TrackSession toSave = session;
            int level = intent.getIntExtra("level", 25);
            if (level < 0 || level > 100) return START_NOT_STICKY;
            boolean share = intent.getBooleanExtra("share", false);
            Uri destination = intent.getData();
            if (!share && destination == null) return START_NOT_STICKY;
            if (begin("Saving your mix", PcmFiles.exportName(toSave.name, level))) {
                worker.execute(() -> export(toSave, level, destination, share));
            }
        }
        return START_NOT_STICKY;
    }

    private boolean begin(String title, String detail) {
        cancelled = false;
        started = SystemClock.elapsedRealtime();
        state = new State(true, false, false, title, detail, "", 0f, started, session);
        exportResult = null;
        getSharedPreferences("pending-export", MODE_PRIVATE).edit().clear().apply();
        try {
            Notification notification = notification();
            if (Build.VERSION.SDK_INT >= 35) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
            else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIFICATION, notification);
            wakeLock.acquire(3 * 60 * 60 * 1000L);
            publish(true);
            return true;
        } catch (RuntimeException error) {
            finish("Android could not start background processing. Reopen the app and try again.", true);
            return false;
        }
    }

    private void process(Uri uri, String name) {
        File job = null;
        boolean committed = false;
        try {
            checkCancelled();
            checkMemory();
            job = store.directory(UUID.randomUUID().toString());
            if (job.getUsableSpace() < 128L * 1024 * 1024) throw new IOException("Free some storage on your phone, then try again.");
            File original = new File(job, "original.pcm");
            report("Opening your track", "Reading audio on your phone", 0f);
            long frames = AudioDecoder.decode(this, uri, original, new AudioDecoder.Callback() {
                @Override public boolean isCancelled() { return cancelled; }
                @Override public void onProgress(float fraction, String ignored) {
                    report("Opening your track", "Reading audio on your phone", fraction);
                }
            });
            checkCancelled();
            if (job.getUsableSpace() < frames * 8 + ModelStore.MODEL_BYTES + 64L * 1024 * 1024) {
                throw new IOException("There is not enough free storage to finish this track. Free some space or try a shorter track.");
            }
            File model = ModelStore.ensure(new File(getFilesDir(), "models"), new ModelStore.Progress() {
                @Override public boolean isCancelled() { return cancelled; }
                @Override public void onProgress(float fraction, String stage) {
                    report(stage, "This download is needed once. Your song stays on your phone.", fraction);
                }
            });
            checkCancelled();
            checkMemory();
            report("Reducing vocals", "This can take several minutes. You can leave this screen.", 0f);
            inNative = true;
            try {
                NativeSeparator.separate(model.getAbsolutePath(), original.getAbsolutePath(),
                        new File(job, "instrumental.pcm").getAbsolutePath(), frames, (fraction, ignored) -> {
                            checkCancelled();
                            report("Reducing vocals", "This can take several minutes. You can leave this screen.", fraction);
                        });
            } finally { inNative = false; }
            checkCancelled();
            TrackSession ready = new TrackSession(job.getName(), name, frames, job);
            PcmFiles.validatePair(ready.original(), ready.instrumental(), frames);
            store.save(ready);
            committed = true;
            session = ready;
            store.cleanExcept(ready);
            finish("Ready. Adjust the vocal level, then listen or save a copy.", false);
        } catch (Exception error) {
            finish(cancelled || error instanceof CancellationException ? cancellationMessage() : message(error),
                    !cancelled && !(error instanceof CancellationException));
        } catch (OutOfMemoryError exhausted) {
            finish("This phone ran out of working memory. Close other apps and try a short clip.", true);
        } finally {
            inNative = false;
            if (!committed && job != null) SessionStore.deleteTree(job);
            try { getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException notPersisted) { /* Some file providers grant only temporary access. */ }
        }
    }

    private void export(TrackSession ready, int level, Uri destination, boolean share) {
        File sharedDirectory = null;
        File partial = null;
        boolean complete = false;
        try {
            checkCancelled();
            String name = PcmFiles.exportName(ready.name, level);
            File shared = null;
            if (share) {
                File root = new File(getCacheDir(), "shared");
                cleanOldShares(root);
                sharedDirectory = new File(root, UUID.randomUUID().toString());
                if (!sharedDirectory.mkdirs()) throw new IOException("Could not create the shared audio file.");
                if (sharedDirectory.getUsableSpace() < ready.frames * 4 + 16L * 1024 * 1024) {
                    throw new IOException("Free some storage before sharing this track.");
                }
                shared = new File(sharedDirectory, name);
                partial = new File(sharedDirectory, "export.partial");
            }
            try (OutputStream output = share ? new FileOutputStream(partial) : getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException("Could not open the chosen save location.");
                PcmFiles.exportWav(ready.original(), ready.instrumental(), ready.frames, level, output, new PcmFiles.Progress() {
                    @Override public boolean isCancelled() { return cancelled; }
                    @Override public void onProgress(float fraction) { report("Saving your mix", name, fraction); }
                });
            }
            checkCancelled();
            if (share) {
                if (!partial.renameTo(shared)) throw new IOException("Could not finish the shared audio file.");
                destination = FileProvider.getUriForFile(this, getPackageName() + ".files", shared);
            }
            exportResult = new ExportResult(destination, name, share);
            // Keep the explicit Share action available when the user returns after backgrounding.
            getSharedPreferences("pending-export", MODE_PRIVATE).edit().putString("uri", destination.toString())
                    .putString("name", name).putBoolean("share", share).commit();
            complete = true;
            finish(share ? "Your mix is ready to share." : "Saved: " + name, false);
        } catch (Exception error) {
            finish(cancelled || error instanceof CancellationException ? "Save cancelled." : message(error),
                    !cancelled && !(error instanceof CancellationException));
        } finally {
            if (!complete) {
                if (sharedDirectory != null) SessionStore.deleteTree(sharedDirectory);
                else if (destination != null) {
                    try { DocumentsContract.deleteDocument(getContentResolver(), destination); } catch (Exception ignored) {}
                }
            }
        }
    }

    public synchronized void cancelProcessing() {
        if (state == null || !state.busy) return;
        cancelled = true;
        if (inNative) NativeSeparator.cancel();
        State old = state;
        state = new State(true, true, false, "Stopping safely", "Please wait while temporary audio is cleared.", "", old.progress, started, session);
        publish(true);
    }

    private void checkCancelled() { if (cancelled) throw new CancellationException("Cancelled"); }
    private void checkMemory() throws IOException {
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        getSystemService(ActivityManager.class).getMemoryInfo(memory);
        if (memory.lowMemory || memory.availMem < 1536L * 1024 * 1024) {
            throw new IOException("This test engine needs about 1.5 GB of free working memory. Close other apps and try again.");
        }
    }
    private String cancellationMessage() {
        return session == null ? "Cancelled. You can choose a track when you're ready." : "Cancelled. Your previous track is still available.";
    }
    private String message(Exception error) {
        String detail = error.getMessage();
        return detail == null || detail.trim().isEmpty() ? "Could not finish this track. Please try again." : detail;
    }

    private synchronized void report(String title, String detail, float fraction) {
        if (cancelled) return;
        state = new State(true, false, false, title, detail, "", Math.max(0f, Math.min(1f, fraction)), started, session);
        publish(false);
    }
    private void publish(boolean immediately) {
        if (immediately) { main.post(this::notifyListeners); return; }
        if (refreshPending.compareAndSet(false, true)) main.postDelayed(() -> {
            refreshPending.set(false); notifyListeners();
        }, 150);
    }
    private void notifyListeners() {
        State snapshot = state;
        for (Listener listener : listeners) listener.onState(snapshot);
        long now = SystemClock.elapsedRealtime();
        if (snapshot.busy && now - lastNotification > 900) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
            lastNotification = now;
        }
    }
    private void finish(String message, boolean error) {
        main.post(() -> {
            state = new State(false, false, error, "", "", message, 0f, 0L, session);
            if (wakeLock.isHeld()) wakeLock.release();
            stopForeground(STOP_FOREGROUND_REMOVE);
            notifyListeners();
            stopSelf();
        });
    }
    private Notification notification() {
        State current = state;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel = PendingIntent.getService(this, 1, new Intent(this, ProcessingService.class).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(current.title).setContentText(current.detail).setContentIntent(open)
                .setOnlyAlertOnce(true).setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(100, Math.round(current.progress * 100), current.cancelling)
                .addAction(new Notification.Action.Builder(null, "Cancel", cancel).build()).build();
    }
    private static void cleanOldShares(File root) {
        File[] files = root.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        for (File file : files) if (file.lastModified() < cutoff) SessionStore.deleteTree(file);
    }
    @Override public void onTimeout(int startId, int foregroundServiceType) {
        cancelProcessing();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
    }
    @Override public void onDestroy() {
        cancelled = true;
        if (inNative) NativeSeparator.cancel();
        worker.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        listeners.clear();
        super.onDestroy();
    }
}

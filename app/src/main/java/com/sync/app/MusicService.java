package com.sync.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.session.MediaSessionCompat;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

/**
 * Foreground Service that keeps the app process alive while music is playing in the background.
 *
 * Responsibilities:
 *  1. Call startForeground() with a MediaStyle notification → prevents system kill
 *  2. Keep the notification in sync when track/play state changes
 *  3. Expose IBinder so MainActivity can call update() / stopForeground()
 *
 * Audio is NOT produced here — it still comes from the WebView (YouTube IFrame API).
 * This service exists purely to satisfy Android's background process requirements.
 */
public class MusicService extends Service {

    static final String CHANNEL_ID  = "sync_music_playback";
    static final int    NOTIF_ID    = 1001;

    static final String ACTION_PLAY  = "com.sync.app.PLAY";
    static final String ACTION_PAUSE = "com.sync.app.PAUSE";
    static final String ACTION_NEXT  = "com.sync.app.NEXT";
    static final String ACTION_PREV  = "com.sync.app.PREV";
    static final String ACTION_STOP  = "com.sync.app.STOP";

    // ── Binder for MainActivity ──────────────────────────────────────────────
    public class LocalBinder extends Binder {
        public MusicService getService() { return MusicService.this; }
    }
    private final IBinder binder = new LocalBinder();

    // ── State ────────────────────────────────────────────────────────────────
    private String  title    = "SYNC";
    private String  artist   = "";
    private boolean playing  = false;
    private MediaSessionCompat.Token sessionToken = null;

    // ── Lifecycle ────────────────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            // Notification action buttons forward their intents to MainActivity via broadcast
            // We just need to keep the service running; MainActivity handles the actual command
            // via the existing __mediaCmd JS bridge.
            final String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        // Show the foreground notification immediately so we survive background
        startForeground(NOTIF_ID, buildNotification());
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public boolean onUnbind(Intent intent) { return true; }  // allow rebind

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(1 /* STOP_FOREGROUND_REMOVE */);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
    }

    // ── Public API called by MainActivity ───────────────────────────────────

    /**
     * Update the notification with current playback state.
     * Called by MainActivity whenever a mediaState message arrives from JS.
     */
    public void updateNotification(String title, String artist, boolean playing,
                                   MediaSessionCompat.Token token) {
        this.title   = (title  != null && !title.isEmpty())  ? title  : "SYNC";
        this.artist  = (artist != null && !artist.isEmpty()) ? artist : "";
        this.playing = playing;
        this.sessionToken = token;

        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification());
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "음악 재생",
                    NotificationManager.IMPORTANCE_LOW   // no sound/vibration
            );
            ch.setDescription("SYNC 백그라운드 음악 재생");
            ch.setShowBadge(false);
            NotificationManager nm =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        // ── Action intents (sent to MainActivity via PendingIntent) ──────────
        final int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent piPrev  = actionPI(ACTION_PREV,  0, piFlags);
        PendingIntent piPlay  = actionPI(playing ? ACTION_PAUSE : ACTION_PLAY, 1, piFlags);
        PendingIntent piNext  = actionPI(ACTION_NEXT,  2, piFlags);

        // Tap notification → open MainActivity
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPI = PendingIntent.getActivity(this, 10, openIntent, piFlags);

        // ── Play/pause icon ──────────────────────────────────────────────────
        int playIcon = playing
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play;
        String playLabel = playing ? "일시정지" : "재생";

        // ── MediaStyle ───────────────────────────────────────────────────────
        MediaStyle style = new MediaStyle()
                .setShowActionsInCompactView(0, 1, 2);  // prev, play, next in compact
        if (sessionToken != null) {
            style.setMediaSession(sessionToken);
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(contentPI)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)          // swipe-to-dismiss only when paused
                .setStyle(style)
                .addAction(android.R.drawable.ic_media_previous, "이전", piPrev)
                .addAction(playIcon, playLabel, piPlay)
                .addAction(android.R.drawable.ic_media_next,     "다음", piNext);

        return b.build();
    }

    private PendingIntent actionPI(String action, int requestCode, int flags) {
        Intent i = new Intent(this, MainActivity.class);
        i.setAction(action);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, requestCode, i, flags);
    }
}

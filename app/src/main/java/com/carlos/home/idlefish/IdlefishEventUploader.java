package com.carlos.home.idlefish;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class IdlefishEventUploader {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int DEFAULT_MAX_BATCH = 20;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private IdlefishEventUploader() {
    }

    public static UploadResult uploadPending(Context context) {
        String endpoint = IdlefishSyncConfig.getEndpoint(context);
        String deviceSecret = IdlefishSyncConfig.getDeviceSecret(context);
        return uploadPending(context, endpoint, deviceSecret, DEFAULT_MAX_BATCH);
    }

    public static UploadResult uploadSingleEvent(
            Context context,
            String endpoint,
            String deviceSecret,
            String eventJson
    ) {
        if (TextUtils.isEmpty(endpoint)) {
            return UploadResult.skipped("missing endpoint");
        }
        try {
            JSONObject event = new JSONObject(eventJson);
            String deviceId = extractDeviceId(event);
            HttpResult result = postJson(endpoint, event.toString(), deviceId, deviceSecret);
            if (result.isSuccessful()) {
                return UploadResult.success(1, result.statusCode, result.body);
            }
            return UploadResult.failed("http " + result.statusCode + ": " + result.body);
        } catch (Throwable throwable) {
            return UploadResult.failed(throwable.toString());
        }
    }

    public static UploadResult uploadPending(
            Context context,
            String endpoint,
            String deviceSecret,
            int maxBatch
    ) {
        if (TextUtils.isEmpty(endpoint)) {
            return UploadResult.skipped("missing endpoint");
        }

        List<File> pending = IdlefishEventStore.listPending(context, maxBatch);
        if (pending.isEmpty()) {
            return UploadResult.skipped("no pending events");
        }

        try {
            JSONObject payload = buildPayload(pending);
            HttpResult result = postJson(endpoint, payload.toString(), payload.optString("device_id", ""), deviceSecret);
            if (result.isSuccessful()) {
                for (File file : pending) {
                    IdlefishEventStore.markSent(context, file);
                }
                return UploadResult.success(pending.size(), result.statusCode, result.body);
            }
            return UploadResult.failed("http " + result.statusCode + ": " + result.body);
        } catch (Throwable throwable) {
            return UploadResult.failed(throwable.toString());
        }
    }

    private static JSONObject buildPayload(List<File> files) throws IOException, JSONException {
        JSONArray events = new JSONArray();
        String deviceId = "";

        for (File file : files) {
            JSONObject event = new JSONObject(IdlefishEventStore.readUtf8(file));
            events.put(event);
            if (deviceId.length() == 0) {
                deviceId = extractDeviceId(event);
            }
        }

        JSONObject payload = new JSONObject();
        payload.put("device_id", deviceId);
        payload.put("events", events);
        return payload;
    }

    private static String extractDeviceId(JSONObject event) {
        String deviceId = event.optString("device_id", "");
        if (deviceId.length() > 0) {
            return deviceId;
        }
        JSONObject account = event.optJSONObject("account");
        if (account != null) {
            deviceId = account.optString("device_id", "");
            if (deviceId.length() == 0) {
                deviceId = account.optString("device_serial", "");
            }
        }
        return deviceId;
    }

    private static HttpResult postJson(String endpoint, String body, String deviceId, String deviceSecret)
            throws IOException, NoSuchAlgorithmException, InvalidKeyException {
        byte[] bodyBytes = body.getBytes(UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");

        if (!TextUtils.isEmpty(deviceSecret)) {
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000L);
            connection.setRequestProperty("x-device-id", deviceId);
            connection.setRequestProperty("x-timestamp", timestamp);
            connection.setRequestProperty("x-signature", hmacSha256Hex(deviceSecret, timestamp + "." + body));
        }

        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(bodyBytes);
            outputStream.flush();
        }

        int statusCode = connection.getResponseCode();
        InputStream stream = statusCode >= 200 && statusCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String responseBody = readStream(stream);
        connection.disconnect();
        return new HttpResult(statusCode, responseBody);
    }

    private static String hmacSha256Hex(String secret, String message)
            throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(message.getBytes(UTF_8));
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private static String readStream(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private static final class HttpResult {
        final int statusCode;
        final String body;

        HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        boolean isSuccessful() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    public static final class UploadResult {
        public final boolean success;
        public final boolean skipped;
        public final int uploadedCount;
        public final int statusCode;
        public final String message;

        private UploadResult(boolean success, boolean skipped, int uploadedCount, int statusCode, String message) {
            this.success = success;
            this.skipped = skipped;
            this.uploadedCount = uploadedCount;
            this.statusCode = statusCode;
            this.message = message;
        }

        static UploadResult success(int uploadedCount, int statusCode, String message) {
            return new UploadResult(true, false, uploadedCount, statusCode, message);
        }

        static UploadResult skipped(String message) {
            return new UploadResult(false, true, 0, 0, message);
        }

        static UploadResult failed(String message) {
            return new UploadResult(false, false, 0, 0, message);
        }
    }
}

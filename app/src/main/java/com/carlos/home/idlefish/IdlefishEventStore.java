package com.carlos.home.idlefish;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class IdlefishEventStore {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String ROOT_DIR = "idlefish_events";
    private static final String PENDING_DIR = "pending";
    private static final String SENT_DIR = "sent";
    private static final String FAILED_DIR = "failed";

    private IdlefishEventStore() {
    }

    public static File enqueue(Context context, String eventJson) throws IOException, JSONException {
        JSONObject event = new JSONObject(eventJson);
        String eventId = event.optString("event_id", "");
        if (eventId.length() == 0) {
            eventId = UUID.randomUUID().toString();
            event.put("event_id", eventId);
        }

        File dir = ensureDir(context, PENDING_DIR);
        File file = uniqueEventFile(dir, eventId);
        writeUtf8(file, event.toString());
        return file;
    }

    public static List<File> listPending(Context context, int maxCount) {
        File[] files = ensureDir(context, PENDING_DIR).listFiles();
        if (files == null || files.length == 0) {
            return new ArrayList<>();
        }

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        List<File> result = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".json")) {
                result.add(file);
                if (result.size() >= maxCount) {
                    break;
                }
            }
        }
        return result;
    }

    public static void markSent(Context context, File file) throws IOException {
        moveTo(context, file, SENT_DIR);
    }

    public static void markFailed(Context context, File file) throws IOException {
        moveTo(context, file, FAILED_DIR);
    }

    public static String readUtf8(File file) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try (FileInputStream inputStream = new FileInputStream(file)) {
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        }
        return new String(outputStream.toByteArray(), UTF_8);
    }

    public static File getRootDir(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), ROOT_DIR);
    }

    private static File ensureDir(Context context, String name) {
        File dir = new File(getRootDir(context), name);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Unable to create " + dir.getAbsolutePath());
        }
        return dir;
    }

    private static File uniqueEventFile(File dir, String eventId) {
        String safeId = eventId.replaceAll("[^a-zA-Z0-9._-]", "_");
        return new File(dir, System.currentTimeMillis() + "-" + safeId + ".json");
    }

    private static void writeUtf8(File file, String value) throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(value.getBytes(UTF_8));
            outputStream.flush();
        }
    }

    private static void moveTo(Context context, File file, String targetDirName) throws IOException {
        File targetDir = ensureDir(context, targetDirName);
        File target = new File(targetDir, file.getName());
        if (target.exists() && !target.delete()) {
            throw new IOException("Unable to replace " + target.getAbsolutePath());
        }
        if (!file.renameTo(target)) {
            String body = readUtf8(file);
            writeUtf8(target, body);
            if (!file.delete()) {
                throw new IOException("Unable to delete " + file.getAbsolutePath());
            }
        }
    }
}

package com.market346.monitor;

import android.app.*;
import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.*;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import androidx.annotation.Nullable;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MonitorService extends Service {

    public static final String ACTION_MONITOR =
            "com.market346.monitor.MONITOR";

    public static final String ACTION_RECEIVER =
            "com.market346.monitor.RECEIVER";

    private static final int NOTIFY_ID = 346;
    private static final int PORT = 8765;

    private SharedPreferences prefs;

    private volatile String currentState = "UNKNOWN";
    private volatile long lastChange =
            System.currentTimeMillis();
    private volatile String detail =
            "Waiting for detection";

    private MediaProjection projection;
    private ImageReader reader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private VirtualDisplay display;

    private TextRecognizer recognizer;

    private ServerSocket serverSocket;
    private Thread serverThread;

    private ScheduledExecutorService receiverExecutor;

    private String receiverIp;
    private String lastReceiverState = "UNKNOWN";

    private final AtomicBoolean processing =
            new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();

        prefs = getSharedPreferences(
                "346",
                MODE_PRIVATE
        );

        currentState = prefs.getString(
                "current_state",
                "UNKNOWN"
        );

        lastChange = prefs.getLong(
                "last_change",
                System.currentTimeMillis()
        );

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        if (intent == null) {
            return START_STICKY;
        }

        String action = intent.getAction();

        startForeground(
                NOTIFY_ID,
                buildServiceNotification(
                        "346 Monitor is running"
                )
        );

        if (ACTION_MONITOR.equals(action)) {

            startMonitor(intent);

        } else if (ACTION_RECEIVER.equals(action)) {

            startReceiver(
                    intent.getStringExtra("ip")
            );
        }

        return START_STICKY;
    }

    private void startMonitor(Intent intent) {

        stopReceiverLoop();

        int resultCode = intent.getIntExtra(
                "resultCode",
                Activity.RESULT_CANCELED
        );

        Intent data = intent.getParcelableExtra("data");

        if (data == null ||
                resultCode != Activity.RESULT_OK) {

            return;
        }

        try {

            MediaProjectionManager mpm =
                    (MediaProjectionManager)
                            getSystemService(
                                    MEDIA_PROJECTION_SERVICE
                            );

            projection =
                    mpm.getMediaProjection(
                            resultCode,
                            data
                    );

            if (projection == null) {
                return;
            }

            recognizer =
                    TextRecognition.getClient(
                            TextRecognizerOptions.DEFAULT_OPTIONS
                    );

            startCapture();
            startHttpServer();

        } catch (Exception e) {

            detail =
                    "Monitor start failed: "
                            + e.getMessage();
        }
    }

    private void startCapture() {

        if (captureThread != null) {
            return;
        }

        DisplayMetricsCompat dm =
                new DisplayMetricsCompat(this);

        int width = dm.width;
        int height = dm.height;
        int density = dm.density;

        reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
        );

        captureThread =
                new HandlerThread("346Capture");

        captureThread.start();

        captureHandler =
                new Handler(
                        captureThread.getLooper()
                );

        display =
                projection.createVirtualDisplay(
                        "346Monitor",
                        width,
                        height,
                        density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(),
                        null,
                        captureHandler
                );

        reader.setOnImageAvailableListener(
                r -> {

                    Image image = null;

                    try {

                        if (!processing.compareAndSet(
                                false,
                                true
                        )) {
                            return;
                        }

                        image =
                                r.acquireLatestImage();

                        if (image == null) {
                            processing.set(false);
                            return;
                        }

                        Bitmap bitmap =
                                imageToBitmap(image);

                        if (bitmap != null) {
                            analyze(bitmap);
                        }

                    } catch (Exception ignored) {

                    } finally {

                        if (image != null) {
                            image.close();
                        }

                        processing.set(false);
                    }

                },
                captureHandler
        );
    }

    private Bitmap imageToBitmap(Image image) {

        Image.Plane plane =
                image.getPlanes()[0];

        ByteBuffer buffer =
                plane.getBuffer();

        buffer.rewind();

        int width = image.getWidth();
        int height = image.getHeight();

        int pixelStride =
                plane.getPixelStride();

        int rowStride =
                plane.getRowStride();

        int rowPadding =
                rowStride -
                        pixelStride * width;

        Bitmap full =
                Bitmap.createBitmap(
                        width +
                                rowPadding /
                                        pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                );

        full.copyPixelsFromBuffer(buffer);

        if (rowPadding == 0) {
            return full;
        }

        return Bitmap.createBitmap(
                full,
                0,
                0,
                width,
                height
        );
    }

    private void analyze(Bitmap full) {

        int l = prefs.getInt(
                "left",
                10
        );

        int t = prefs.getInt(
                "top",
                20
        );

        int r = prefs.getInt(
                "right",
                95
        );

        int b = prefs.getInt(
                "bottom",
                78
        );

        int left =
                Math.max(
                        0,
                        full.getWidth() * l / 100
                );

        int top =
                Math.max(
                        0,
                        full.getHeight() * t / 100
                );

        int right =
                Math.min(
                        full.getWidth(),
                        full.getWidth() * r / 100
                );

        int bottom =
                Math.min(
                        full.getHeight(),
                        full.getHeight() * b / 100
                );

        if (right <= left ||
                bottom <= top) {

            return;
        }

        Bitmap crop =
                Bitmap.createBitmap(
                        full,
                        left,
                        top,
                        right - left,
                        bottom - top
                );

        float[] colors =
                colorRatios(crop);

        InputImage input =
                InputImage.fromBitmap(
                        crop,
                        0
                );

        recognizer
                .process(input)
                .addOnSuccessListener(result -> {

                    String text =
                            result
                                    .getText()
                                    .toUpperCase(
                                            Locale.US
                                    );

                    String state = null;

                    if (text.contains("LOCKED") ||
                            text.contains("SUSPENDED")) {

                        state = "LOCKED";

                    } else if (
                            text.contains("UNLOCKED") ||
                            text.contains("ODDS")
                    ) {

                        state = "UNLOCKED";

                    } else if (
                            colors[0] > 0.012f &&
                            colors[0] >
                                    colors[1] * 1.4f
                    ) {

                        state = "LOCKED";

                    } else if (
                            colors[1] > 0.012f &&
                            colors[1] >
                                    colors[0] * 1.4f
                    ) {

                        state = "UNLOCKED";
                    }

                    if (state != null) {

                        detail =
                                state.equals("LOCKED")
                                        ? "Market is SUSPENDED"
                                        : "Odds are available";

                        setState(
                                state,
                                detail
                        );
                    }

                    crop.recycle();

                })
                .addOnFailureListener(
                        e -> crop.recycle()
                );
    }

    private float[] colorRatios(Bitmap b) {

        int step =
                Math.max(
                        2,
                        Math.min(
                                b.getWidth(),
                                b.getHeight()
                        ) / 80
                );

        int total = 0;
        int red = 0;
        int green = 0;

        for (
                int y = 0;
                y < b.getHeight();
                y += step
        ) {

            for (
                    int x = 0;
                    x < b.getWidth();
                    x += step
            ) {

                int c =
                        b.getPixel(x, y);

                int rr = Color.red(c);
                int gg = Color.green(c);
                int bb = Color.blue(c);

                total++;

                if (
                        rr > 150 &&
                        rr > gg * 1.25f &&
                        rr > bb * 1.25f
                ) {

                    red++;
                }

                if (
                        gg > 120 &&
                        gg > rr * 1.25f &&
                        gg > bb * 1.1f
                ) {

                    green++;
                }
            }
        }

        return new float[]{
                red / (float) Math.max(1, total),
                green / (float) Math.max(1, total)
        };
    }

    private void setState(
            String state,
            String why
    ) {

        if (!state.equals(currentState)) {

            currentState = state;

            lastChange =
                    System.currentTimeMillis();

            prefs.edit()
                    .putString(
                            "current_state",
                            state
                    )
                    .putLong(
                            "last_change",
                            lastChange
                    )
                    .apply();
        }

        detail = why;
    }

    private void startHttpServer() {

        if (serverThread != null) {
            return;
        }

        serverThread =
                new Thread(
                        () -> {

                            try {

                                serverSocket =
                                        new ServerSocket(
                                                PORT
                                        );

                                while (
                                        !serverSocket.isClosed()
                                ) {

                                    Socket s =
                                            serverSocket
                                                    .accept();

                                    new Thread(
                                            () ->
                                                    handleClient(s)
                                    ).start();
                                }

                            } catch (Exception ignored) {
                            }

                        },
                        "346HttpServer"
                );

        serverThread.start();
    }

    private void handleClient(
            Socket socket
    ) {

        try (
                Socket s = socket;

                BufferedReader in =
                        new BufferedReader(
                                new InputStreamReader(
                                        s.getInputStream()
                                )
                        );

                OutputStream out =
                        s.getOutputStream()
        ) {

            String line =
                    in.readLine();

            if (line == null) {
                return;
            }

            while (in.ready()) {
                in.readLine();
            }

            JSONObject obj =
                    new JSONObject();

            obj.put(
                    "market",
                    prefs.getString(
                            "market",
                            "346"
                    )
            );

            obj.put(
                    "state",
                    currentState
            );

            obj.put(
                    "detail",
                    detail
            );

            obj.put(
                    "lastChange",
                    lastChange
            );

            byte[] body =
                    obj.toString()
                            .getBytes("UTF-8");

            String header =
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: " +
                    body.length +
                    "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";

            out.write(
                    header.getBytes("UTF-8")
            );

            out.write(body);

            out.flush();

        } catch (Exception ignored) {
        }
    }

    private void startReceiver(
            String ip
    ) {

        stopMonitorCapture();

        receiverIp = ip;

        stopReceiverLoop();

        receiverExecutor =
                Executors
                        .newSingleThreadScheduledExecutor();

        receiverExecutor.scheduleAtFixedRate(
                this::pollReceiver,
                0,
                1,
                TimeUnit.SECONDS
        );
    }

    private void pollReceiver() {

        if (
                receiverIp == null ||
                receiverIp.trim().isEmpty()
        ) {

            return;
        }

        HttpURLConnection c = null;

        try {

            URL url =
                    new URL(
                            "http://" +
                                    receiverIp.trim() +
                                    ":" +
                                    PORT +
                                    "/state"
                    );

            c =
                    (HttpURLConnection)
                            url.openConnection();

            c.setConnectTimeout(1200);
            c.setReadTimeout(1200);
            c.setRequestMethod("GET");

            BufferedReader br =
                    new BufferedReader(
                            new InputStreamReader(
                                    c.getInputStream()
                            )
                    );

            StringBuilder sb =
                    new StringBuilder();

            String line;

            while (
                    (line = br.readLine()) != null
            ) {

                sb.append(line);
            }

            JSONObject o =
                    new JSONObject(
                            sb.toString()
                    );

            String state =
                    o.optString(
                            "state",
                            "UNKNOWN"
                    );

            String d =
                    o.optString(
                            "detail",
                            ""
                    );

            long change =
                    o.optLong(
                            "lastChange",
                            0
                    );

            if (
                    !state.equals(
                            lastReceiverState
                    ) &&
                    !"UNKNOWN".equals(state)
            ) {

                alert(
                        state,
                        d,
                        change
                );

                lastReceiverState = state;
            }

        } catch (Exception ignored) {

        } finally {

            if (c != null) {
                c.disconnect();
            }
        }
    }

    private void alert(
            String state,
            String detail,
            long when
    ) {

        String market =
                prefs.getString(
                        "market",
                        "346"
                );

        String title =
                market + " " + state;

        NotificationManager nm =
                (NotificationManager)
                        getSystemService(
                                NOTIFICATION_SERVICE
                        );

        Notification.Builder b;

        if (Build.VERSION.SDK_INT >= 26) {

            b =
                    new Notification.Builder(
                            this,
                            "346alerts"
                    );

        } else {

            b =
                    new Notification.Builder(
                            this
                    );
        }

        b.setSmallIcon(
                android.R.drawable
                        .ic_dialog_alert
        );

        b.setContentTitle(title);

        b.setContentText(detail);

        b.setAutoCancel(true);

        b.setWhen(when);

        b.setStyle(
                new Notification.BigTextStyle()
                        .bigText(detail)
        );

        if (Build.VERSION.SDK_INT >= 21) {

            b.setCategory(
                    Notification.CATEGORY_ALARM
            );

            b.setPriority(
                    Notification.PRIORITY_HIGH
            );
        }

        nm.notify(
                (int)
                        (System.currentTimeMillis()
                                % 100000),
                b.build()
        );

        Vibrator v =
                (Vibrator)
                        getSystemService(
                                VIBRATOR_SERVICE
                        );

        if (v != null) {

            if (Build.VERSION.SDK_INT >= 26) {

                v.vibrate(
                        VibrationEffect
                                .createWaveform(
                                        new long[]{
                                                0,
                                                250,
                                                150,
                                                250
                                        },
                                        -1
                                )
                );

            } else {

                v.vibrate(
                        new long[]{
                                0,
                                250,
                                150,
                                250
                        },
                        -1
                );
            }
        }

        appendHistory(
                title +
                        " — " +
                        detail +
                        "\n" +
                        time(when)
        );
    }

    private void appendHistory(
            String s
    ) {

        String old =
                prefs.getString(
                        "history",
                        ""
                );

        String n =
                s +
                        (
                                old.isEmpty()
                                        ? ""
                                        : "\n\n" + old
                        );

        if (n.length() > 4000) {

            n = n.substring(
                    0,
                    4000
            );
        }

        prefs.edit()
                .putString(
                        "history",
                        n
                )
                .apply();
    }

    private String time(long ms) {

        return new SimpleDateFormat(
                "dd MMM yyyy, hh:mm:ss a",
                Locale.getDefault()
        ).format(
                new Date(ms)
        );
    }

    private Notification buildServiceNotification(
            String text
    ) {

        Notification.Builder b;

        if (Build.VERSION.SDK_INT >= 26) {

            b =
                    new Notification.Builder(
                            this,
                            "346service"
                    );

        } else {

            b =
                    new Notification.Builder(
                            this
                    );
        }

        return b
                .setSmallIcon(
                        android.R.drawable
                                .ic_menu_view
                )
                .setContentTitle(
                        "346 Monitor"
                )
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            NotificationManager nm =
                    getSystemService(
                            NotificationManager.class
                    );

            nm.createNotificationChannel(
                    new NotificationChannel(
                            "346service",
                            "346 Monitor Service",
                            NotificationManager
                                    .IMPORTANCE_LOW
                    )
            );

            nm.createNotificationChannel(
                    new NotificationChannel(
                            "346alerts",
                            "346 Alerts",
                            NotificationManager
                                    .IMPORTANCE_HIGH
                    )
            );
        }
    }

    private void stopReceiverLoop() {

        if (receiverExecutor != null) {

            receiverExecutor.shutdownNow();

            receiverExecutor = null;
        }
    }

    private void stopMonitorCapture() {

        try {

            if (display != null) {
                display.release();
            }

        } catch (Exception ignored) {
        }

        display = null;

        try {

            if (reader != null) {
                reader.close();
            }

        } catch (Exception ignored) {
        }

        reader = null;

        try {

            if (projection != null) {
                projection.stop();
            }

        } catch (Exception ignored) {
        }

        projection = null;

        if (captureThread != null) {

            captureThread.quitSafely();

            captureThread = null;
        }

        if (recognizer != null) {

            recognizer.close();

            recognizer = null;
        }
    }

    @Override
    public void onDestroy() {

        stopReceiverLoop();

        stopMonitorCapture();

        try {

            if (serverSocket != null) {
                serverSocket.close();
            }

        } catch (Exception ignored) {
        }

        serverSocket = null;
        serverThread = null;

        super.onDestroy();
    }

    @Nullable
    @Override
    public android.os.IBinder onBind(
            Intent intent
    ) {

        return null;
    }

    private static class DisplayMetricsCompat {

        int width;
        int height;
        int density;

        DisplayMetricsCompat(Context c) {

            android.util.DisplayMetrics m =
                    c.getResources()
                            .getDisplayMetrics();

            width = m.widthPixels;
            height = m.heightPixels;
            density = m.densityDpi;
        }
    }
    }

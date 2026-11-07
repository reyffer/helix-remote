package com.reyffer.helixremote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Base64;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Helix Remote: a thin Android shell around the web page in assets/index.html.
 *
 * The page holds the screen, the setlist and the Wing OSC logic. This class only does the
 * parts a web page can't:
 *   - Helix: USB MIDI through Android's MIDI service, with precisely timed tap-tempo taps.
 *   - Wing: plain UDP packets on the Wi-Fi network (browsers can't send UDP at all).
 *   - Keeps the screen on while the app is open.
 *
 * The page calls us through window.Native, and we answer through
 * window.__native(type, text) and window.__wingPacket(ip, port, base64).
 */
public class MainActivity extends Activity {

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());

    // ---- Helix (MIDI)
    private MidiManager midi;
    private MidiDevice midiDevice;
    private MidiInputPort helixIn;          // "input" = the port we send INTO the Helix
    private String helixName = "";
    private final Object midiLock = new Object();
    private final ExecutorService helixQueue = Executors.newSingleThreadExecutor();
    private final AtomicInteger setGeneration = new AtomicInteger();
    private volatile long lastTapNs = 0;            // when the last tap-tempo tap was due (System.nanoTime)

    private static final long TAP_RESET_NS = 2_200_000_000L;   // > 2 s = slowest tap gap the Helix accepts (30 BPM)
    private static final long TAP_LEAD_NS = 4_000_000L;        // hand taps to Android 4 ms before they're due

    // ---- Wing (UDP)
    private ConnectivityManager cm;
    private volatile DatagramSocket sock;
    private volatile Network wifi;
    private final ExecutorService netQueue = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The screen must never sleep during a service.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WebView.setWebContentsDebuggingEnabled(true);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setBackgroundColor(0xFF0B0D10);
        web.setWebChromeClient(new WebChromeClient());   // lets the page use confirm() dialogs
        web.addJavascriptInterface(new Bridge(), "Native");

        // Android 15+ draws apps underneath the status bar, navigation bar and keyboard.
        // Pad the page by those areas so nothing hides behind the clock or the keyboard.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0D10);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets b = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(b.left, b.top, b.right, b.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        setContentView(root);
        web.loadUrl("file:///android_asset/index.html");

        midi = (MidiManager) getSystemService(Context.MIDI_SERVICE);
        if (midi != null) {
            midi.registerDeviceCallback(midiCallback, main);
        }

        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        cm.registerNetworkCallback(req, wifiCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        event("resume", "");
    }

    @Override
    protected void onDestroy() {
        try { if (midi != null) midi.unregisterDeviceCallback(midiCallback); } catch (Exception ignored) {}
        try { cm.unregisterNetworkCallback(wifiCallback); } catch (Exception ignored) {}
        closeHelix(false);
        closeSocket();
        helixQueue.shutdownNow();
        netQueue.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);   // never close the app by accident mid-service
    }

    // ================================================================ talking to the page

    private void event(String type, String text) {
        final String js = "window.__native && window.__native(" + quote(type) + "," + quote(text) + ")";
        main.post(() -> web.evaluateJavascript(js, null));
    }

    private static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'";
    }

    // ================================================================ Helix: USB MIDI

    private final MidiManager.DeviceCallback midiCallback = new MidiManager.DeviceCallback() {
        @Override public void onDeviceAdded(MidiDeviceInfo info) {
            event("log", "MIDI device added: " + describe(info));
            if (helixIn == null) connectHelix();
        }
        @Override public void onDeviceRemoved(MidiDeviceInfo info) {
            event("log", "MIDI device removed: " + describe(info));
            synchronized (midiLock) {
                if (midiDevice != null && midiDevice.getInfo().getId() == info.getId()) closeHelix(true);
            }
        }
    };

    private static String describe(MidiDeviceInfo info) {
        Bundle p = info.getProperties();
        String name = p.getString(MidiDeviceInfo.PROPERTY_NAME);
        if (name == null) name = p.getString(MidiDeviceInfo.PROPERTY_PRODUCT);
        if (name == null) name = "MIDI device";
        String type = info.getType() == MidiDeviceInfo.TYPE_USB ? "USB" :
                info.getType() == MidiDeviceInfo.TYPE_BLUETOOTH ? "Bluetooth" : "virtual";
        return name + " (" + type + ", " + info.getInputPortCount() + " in)";
    }

    /** Pick the Helix if we can see it by name; otherwise the first USB MIDI device we can send to. */
    private MidiDeviceInfo findHelix() {
        if (midi == null) return null;
        MidiDeviceInfo fallback = null;
        for (MidiDeviceInfo info : midi.getDevices()) {
            if (info.getInputPortCount() < 1) continue;
            Bundle p = info.getProperties();
            String all = (p.getString(MidiDeviceInfo.PROPERTY_NAME) + " " + p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
                    + " " + p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)).toLowerCase();
            if (all.contains("helix") || all.contains("line 6") || all.contains("line6")) return info;
            if (fallback == null && info.getType() == MidiDeviceInfo.TYPE_USB) fallback = info;
        }
        return fallback;
    }

    private void connectHelix() {
        if (midi == null) { event("helix", "nomidi"); return; }
        final MidiDeviceInfo info = findHelix();
        if (info == null) { event("helix", "none"); return; }
        event("log", "Opening " + describe(info));
        midi.openDevice(info, device -> {
            if (device == null) { event("helix", "none"); event("log", "Android couldn't open the MIDI device"); return; }
            synchronized (midiLock) {
                closeHelix(false);
                MidiInputPort port = device.openInputPort(0);
                if (port == null) {
                    try { device.close(); } catch (Exception ignored) {}
                    event("helix", "none");
                    event("log", "The MIDI device's input port is busy (another app may be using it)");
                    return;
                }
                midiDevice = device;
                helixIn = port;
                Bundle p = info.getProperties();
                String name = p.getString(MidiDeviceInfo.PROPERTY_PRODUCT);
                if (name == null) name = p.getString(MidiDeviceInfo.PROPERTY_NAME);
                helixName = name == null ? "Helix" : name;
            }
            event("helix", "connected:" + helixName);
        }, main);
    }

    private void closeHelix(boolean notify) {
        synchronized (midiLock) {
            try { if (helixIn != null) helixIn.close(); } catch (Exception ignored) {}
            try { if (midiDevice != null) midiDevice.close(); } catch (Exception ignored) {}
            helixIn = null;
            midiDevice = null;
        }
        if (notify) event("helix", "none");
    }

    private boolean sendMidi(int status, int d1, int d2) { return sendMidiAt(status, d1, d2, 0); }

    /** timestampNs = 0: send now. Otherwise ask Android's MIDI service to deliver it at that System.nanoTime(). */
    private boolean sendMidiAt(int status, int d1, int d2, long timestampNs) {
        synchronized (midiLock) {
            if (helixIn == null) return false;
            byte[] msg = { (byte) status, (byte) (d1 & 0x7F), (byte) (d2 & 0x7F) };
            try {
                if (timestampNs > 0) helixIn.send(msg, 0, 3, timestampNs);
                else helixIn.send(msg, 0, 3);
                return true;
            } catch (Exception e) {
                event("log", "MIDI send failed: " + e.getMessage());
                return false;
            }
        }
    }

    /** Sleep until a System.nanoTime() target with ~0.1 ms accuracy (sleep most of it, spin the end). */
    private static void waitUntil(long target) throws InterruptedException {
        while (true) {
            long left = target - System.nanoTime();
            if (left <= 0) return;
            if (left > 3_000_000L) Thread.sleep((left - 2_000_000L) / 1_000_000L);
            else Thread.yield();
        }
    }

    /**
     * One song change: snapshot (CC69), then delay start level (EXP2 = CC2), then tap tempo (CC64).
     * Runs on its own thread so the taps are evenly spaced. A newer SET cancels an older one.
     */
    private void runSet(final int channel, final int snapshot, final int expValue, final double bpm, final int taps) {
        final int gen = setGeneration.incrementAndGet();
        helixQueue.execute(() -> {
            final int cc = 0xB0 | ((channel - 1) & 0x0F);
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Exception ignored) {}
            try {
                if (gen != setGeneration.get()) return;
                if (!sendMidi(cc, 69, snapshot)) { event("set", "fail:Helix not connected"); return; }
                // give the snapshot a moment to load before touching its parameters
                Thread.sleep(60);
                if (expValue >= 0 && gen == setGeneration.get()) sendMidi(cc, 2, expValue);
                if (bpm > 0 && taps >= 2) {
                    long interval = Math.round(60_000_000_000.0 / bpm);
                    // The Helix measures the gap between taps. If the previous song's last tap was less than
                    // ~2 s ago, our first tap would pair with it and show a random tempo, so wait it out.
                    long t0 = Math.max(System.nanoTime() + 40_000_000L, lastTapNs + TAP_RESET_NS);
                    for (int i = 0; i < taps; i++) {
                        if (gen != setGeneration.get()) return;   // a newer song was pressed
                        long due = t0 + i * interval;
                        // Hand each tap to Android a few ms early with its exact due time, so the MIDI
                        // service (not this thread) does the final timing. If a device ignores the time,
                        // every tap is early by the same amount, so the gaps (the tempo) stay the same.
                        waitUntil(due - TAP_LEAD_NS);
                        sendMidiAt(cc, 64, 127, due);
                        lastTapNs = due;
                    }
                }
                if (gen == setGeneration.get()) event("set", "done");
            } catch (InterruptedException ignored) {
            }
        });
    }

    // ================================================================ Wing: UDP on Wi-Fi

    private Network findWifi() {
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    private final ConnectivityManager.NetworkCallback wifiCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            event("log", "Wi-Fi connected");
            netQueue.execute(() -> openSocket(true));
        }
        @Override public void onLost(Network network) {
            event("log", "Wi-Fi lost");
            netQueue.execute(() -> openSocket(true));
        }
    };

    /**
     * (Re)open our UDP socket, tied to the Wi-Fi network. Tying it to Wi-Fi stops Android from
     * sending Wing traffic over mobile data when it thinks the Wi-Fi is "bad".
     */
    private synchronized void openSocket(boolean announce) {
        closeSocket();
        try {
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(0));
            Network w = findWifi();
            if (w != null) {
                try { w.bindSocket(s); } catch (Exception e) { event("log", "Couldn't tie to Wi-Fi: " + e.getMessage()); }
            }
            s.setBroadcast(true);
            s.setSoTimeout(500);
            sock = s;
            wifi = w;
            startReceiving(s);
            // Only tell the page when Android reported a Wi-Fi change. (Announcing every reopen made the
            // page reconnect, which reopened the socket again: an endless loop.)
            if (announce) event("wifi", w != null ? "up" : "down");
        } catch (Exception e) {
            event("log", "Network socket failed: " + e.getMessage());
            if (announce) event("wifi", "down");
        }
    }

    private synchronized void closeSocket() {
        DatagramSocket s = sock;
        sock = null;
        if (s != null) s.close();
    }

    private void startReceiving(final DatagramSocket s) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[33000];
            while (s == sock && !s.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    final String ip = p.getAddress().getHostAddress();
                    final int port = p.getPort();
                    final String b64 = Base64.encodeToString(p.getData(), 0, p.getLength(), Base64.NO_WRAP);
                    main.post(() -> web.evaluateJavascript(
                            "window.__wingPacket && window.__wingPacket(" + quote(ip) + "," + port + ",'" + b64 + "')", null));
                } catch (java.net.SocketTimeoutException ignored) {
                } catch (Exception e) {
                    if (s == sock) event("log", "Receive error: " + e.getMessage());
                }
            }
        }, "wing-receive");
        t.setDaemon(true);
        t.start();
    }

    private void udpSend(String host, int port, byte[] data) {
        netQueue.execute(() -> {
            DatagramSocket s = sock;
            if (s == null) { openSocket(false); s = sock; }
            if (s == null) return;
            try {
                s.send(new DatagramPacket(data, data.length, InetAddress.getByName(host), port));
            } catch (Exception e) {
                event("log", "Send to " + host + " failed: " + e.getMessage());
            }
        });
    }

    /** Ask every Wing on the network to say where it is ("WING?" to UDP port 2222). */
    private void discover() {
        netQueue.execute(() -> {
            DatagramSocket s = sock;
            if (s == null) { openSocket(false); s = sock; }
            if (s == null) return;
            byte[] q = "WING?".getBytes();
            try {
                s.send(new DatagramPacket(q, q.length, InetAddress.getByName("255.255.255.255"), 2222));
            } catch (Exception e) {
                event("log", "Broadcast failed: " + e.getMessage());
            }
            // Also the Wi-Fi subnet's own broadcast address (some routers drop 255.255.255.255)
            Network w = wifi;
            LinkProperties lp = w != null ? cm.getLinkProperties(w) : null;
            if (lp != null) {
                for (LinkAddress la : lp.getLinkAddresses()) {
                    if (!(la.getAddress() instanceof Inet4Address)) continue;
                    byte[] a = la.getAddress().getAddress();
                    int ip = ((a[0] & 0xFF) << 24) | ((a[1] & 0xFF) << 16) | ((a[2] & 0xFF) << 8) | (a[3] & 0xFF);
                    int mask = la.getPrefixLength() == 0 ? 0 : (-1 << (32 - la.getPrefixLength()));
                    int bc = ip | ~mask;
                    String bcs = ((bc >>> 24) & 0xFF) + "." + ((bc >>> 16) & 0xFF) + "." + ((bc >>> 8) & 0xFF) + "." + (bc & 0xFF);
                    try {
                        s.send(new DatagramPacket(q, q.length, InetAddress.getByName(bcs), 2222));
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    // ================================================================ what the page can call

    private class Bridge {
        @JavascriptInterface public String version() { return BuildConfig.VERSION_NAME; }

        /** True when Android thinks a physical keyboard is plugged in (the Helix's USB can look like one),
            which makes it hide the on-screen keyboard. */
        @JavascriptInterface public boolean hardKeyboard() {
            Configuration c = getResources().getConfiguration();
            return c.keyboard != Configuration.KEYBOARD_NOKEYS && c.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
        }
        @JavascriptInterface public void openKeyboardSettings() {
            main.post(() -> {
                try { startActivity(new Intent(android.provider.Settings.ACTION_HARD_KEYBOARD_SETTINGS)); }
                catch (Exception e) { startActivity(new Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)); }
            });
        }

        // Helix
        @JavascriptInterface public void helixConnect() { main.post(MainActivity.this::connectHelix); }
        @JavascriptInterface public boolean helixConnected() { return helixIn != null; }
        @JavascriptInterface public boolean midi(int status, int d1, int d2) { return sendMidi(status, d1, d2); }
        @JavascriptInterface public void setSong(int channel, int snapshot, int expValue, double bpm, int taps) {
            runSet(channel, snapshot, expValue, bpm, taps);
        }

        // Wing
        /** Open the socket if there isn't one (never reopens a working one). */
        @JavascriptInterface public void wingOpen() { netQueue.execute(() -> { if (sock == null) openSocket(false); }); }
        /** Force a fresh socket ("Search for the Wing again"). */
        @JavascriptInterface public void wingReopen() { netQueue.execute(() -> openSocket(false)); }
        @JavascriptInterface public void wingDiscover() { discover(); }
        @JavascriptInterface public void wingSend(String host, int port, String base64) {
            udpSend(host, port, Base64.decode(base64, Base64.NO_WRAP));
        }
        @JavascriptInterface public boolean wifiUp() { return findWifi() != null; }
    }
}

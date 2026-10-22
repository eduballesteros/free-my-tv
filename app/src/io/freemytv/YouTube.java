package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The YouTube tab: the newest videos of the channels you watch most. YouTube shares no one's home feed or history, so
 * this signs in to your account with Google's code-on-another-screen flow (youtube_client_id and youtube_client_secret
 * in config.properties: an OAuth client of type "TVs and Limited Input devices"), reads your subscriptions in YouTube's
 * own order of relevance (1 quota unit a page, once a day), and takes each channel's newest uploads from its public
 * feed (no quota). Shorts are left out. The sign-in stays in this app's private storage; Original doesn't touch it.
 */
final class YouTube {
    static final String PKG = "com.google.android.youtube.tv";
    private static final String SCOPE = "https://www.googleapis.com/auth/youtube.readonly";
    private static final int CHANNELS = 25, KEEP = 12;
    private static final long VIDEOS_EVERY = 30 * 60_000L, CHANNELS_EVERY = 24 * 3600_000L, WINDOW = 14 * 24 * 3600_000L;
    /** The card sizes (the wide one and the tall ones of the home screen, at 1080p): images are cut to them once. */
    private static final int WIDE_W = 1040, WIDE_H = 442, TALL_W = 320, TALL_H = 442;

    static final class Video {
        String id, title, channel;
        long published;
        volatile Bitmap wide, tall;
        volatile boolean loading;
    }

    interface Listener { void onYouTube(); }

    static boolean configured() {
        return !Config.YOUTUBE_CLIENT_ID.trim().isEmpty();
    }

    private static YouTube instance;

    static synchronized YouTube get(Context c) {
        if (instance == null) instance = new YouTube(c.getApplicationContext());
        return instance;
    }

    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService auth = Executors.newSingleThreadExecutor();   // waits up to 30 min for your OK
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    volatile List<Video> videos = new ArrayList<>();
    private volatile long videosAt, failedAt;
    private volatile boolean fetching;

    // Signing in: the code to type at the address, until it expires. linkError: what went wrong, for the card.
    volatile String linkCode, linkUrl, linkError;
    volatile boolean linking;

    private YouTube(Context c) {
        prefs = c.getSharedPreferences("youtube", Context.MODE_PRIVATE);
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onYouTube(); });
    }

    boolean linked() {
        return !prefs.getString("refresh", "").isEmpty();
    }

    // MARK: - Signing in (OAuth 2.0 for TVs and limited-input devices)

    /** Asks Google for a code, shows it, and waits (in the background) for you to confirm it on your phone. */
    void startLink() {
        if (linking || !configured()) return;
        linking = true;
        linkError = null;
        linkCode = null;
        notifyListeners();
        auth.execute(() -> {
            try {
                JSONObject code = post("https://oauth2.googleapis.com/device/code",
                        "client_id=" + enc(Config.YOUTUBE_CLIENT_ID.trim()) + "&scope=" + enc(SCOPE));
                linkCode = code.getString("user_code");
                linkUrl = code.optString("verification_url", "google.com/device").replaceFirst("^https?://(www\\.)?", "");
                notifyListeners();
                long until = System.currentTimeMillis() + code.optLong("expires_in", 1800) * 1000;
                long every = Math.max(5, code.optLong("interval", 5)) * 1000;
                while (System.currentTimeMillis() < until) {
                    Thread.sleep(every);
                    JSONObject token;
                    try {
                        token = post("https://oauth2.googleapis.com/token", "client_id=" + enc(Config.YOUTUBE_CLIENT_ID.trim())
                                + "&client_secret=" + enc(Config.YOUTUBE_CLIENT_SECRET.trim()) + "&device_code=" + enc(code.getString("device_code"))
                                + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code"));
                    } catch (OAuthError e) {
                        if (e.code.equals("authorization_pending")) continue;
                        if (e.code.equals("slow_down")) { every += 5000; continue; }
                        throw e;
                    }
                    prefs.edit().putString("refresh", token.getString("refresh_token")).putString("access", token.getString("access_token"))
                            .putLong("accessUntil", System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000 - 60_000).apply();
                    AppLog.w("youtube: signed in");
                    linkCode = null;
                    videosAt = 0;
                    failedAt = 0;
                    return;
                }
                linkCode = null;
                linkError = Ui.tr("The code expired. Press OK for a new one.");
            } catch (OAuthError e) {
                linkCode = null;
                linkError = e.code.equals("access_denied") ? Ui.tr("You said no on your phone. Press OK to try again.")
                        : Ui.trf("Couldn't sign in: {error}", "error", e.code);
                AppLog.w("youtube sign-in: " + e.code);
            } catch (Exception e) {
                linkCode = null;
                linkError = Ui.trf("Couldn't sign in: {error}", "error", e.getClass().getSimpleName());
                AppLog.w("youtube sign-in: " + e.getClass().getSimpleName());
            } finally {
                linking = false;
                notifyListeners();
                if (linked()) refreshIfStale();
            }
        });
    }

    /** A valid access token: the saved one, or a new one from the refresh token. Background only. */
    private String access() throws IOException {
        if (System.currentTimeMillis() < prefs.getLong("accessUntil", 0)) return prefs.getString("access", "");
        try {
            JSONObject token = post("https://oauth2.googleapis.com/token", "client_id=" + enc(Config.YOUTUBE_CLIENT_ID.trim())
                    + "&client_secret=" + enc(Config.YOUTUBE_CLIENT_SECRET.trim()) + "&refresh_token=" + enc(prefs.getString("refresh", ""))
                    + "&grant_type=refresh_token");
            String access = token.getString("access_token");
            prefs.edit().putString("access", access)
                    .putLong("accessUntil", System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000 - 60_000).apply();
            return access;
        } catch (OAuthError e) {
            // The access was taken back (in your Google account) or expired: sign in again.
            if (e.code.equals("invalid_grant")) {
                prefs.edit().clear().apply();
                videos = new ArrayList<>();
                linkError = Ui.tr("YouTube asked to sign in again. Press OK.");
                notifyListeners();
            }
            throw new IOException(e.code);
        } catch (org.json.JSONException e) {
            throw new IOException("bad token answer");
        }
    }

    // MARK: - Videos

    /** Fetches the videos if they are older than 30 min. The screen calls this when it appears and every 15 s. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (!linked() || fetching || (now - videosAt < VIDEOS_EVERY && now >= videosAt) || (failedAt != 0 && now - failedAt < 5 * 60_000L)) return;
        fetching = true;
        io.execute(() -> {
            try {
                List<String[]> channels = channels();
                List<Video> all = new ArrayList<>();
                for (String[] ch : channels) {
                    try {
                        all.addAll(feed(ch[0], ch[1]));
                    } catch (IOException | RuntimeException e) {
                        // one channel's feed failing doesn't hide the others
                    }
                }
                Collections.sort(all, (a, b) -> Long.compare(b.published, a.published));
                List<Video> keep = new ArrayList<>(all.subList(0, Math.min(KEEP, all.size())));
                // Images already loaded for videos still on the list are kept.
                Map<String, Video> old = new HashMap<>();
                for (Video v : videos) old.put(v.id, v);
                for (Video v : keep) {
                    Video o = old.get(v.id);
                    if (o != null) { v.wide = o.wide; v.tall = o.tall; }
                }
                videos = keep;
                videosAt = System.currentTimeMillis();
                notifyListeners();
                AppLog.w("youtube: " + keep.size() + " videos from " + channels.size() + " channels");
                for (int i = 0; i < keep.size() && i < 3; i++) images(keep.get(i));
                notifyListeners();
            } catch (Exception e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("youtube: " + e.getClass().getSimpleName() + " " + (e instanceof IOException ? e.getMessage() : ""));
            } finally {
                fetching = false;
            }
        });
    }

    /** The cards of this hour: three of the newest, the newest always first. Makes the missing images in the background. */
    List<Video> now() {
        List<Video> list = videos;
        List<Video> out = new ArrayList<>();
        for (int i = 0; i < list.size() && out.size() < 3; i++) out.add(list.get(i));
        for (Video v : out) {
            if (v.wide != null || v.loading) continue;
            v.loading = true;
            io.execute(() -> {
                images(v);
                v.loading = false;
                if (v.wide != null) notifyListeners();
            });
        }
        return out;
    }

    /** Your subscriptions, the ones you watch most first (YouTube's "relevance"): [channel id, name]. Saved for a day. */
    private List<String[]> channels() throws Exception {
        List<String[]> out = new ArrayList<>();
        String saved = prefs.getString("channels", "");
        if (!saved.isEmpty() && System.currentTimeMillis() - prefs.getLong("channelsAt", 0) < CHANNELS_EVERY) {
            JSONArray a = new JSONArray(saved);
            for (int i = 0; i < a.length(); i++) out.add(new String[] {a.getJSONArray(i).getString(0), a.getJSONArray(i).getString(1)});
            return out;
        }
        String page = "";
        JSONArray save = new JSONArray();
        while (out.size() < CHANNELS) {
            JSONObject o = new JSONObject(get("https://www.googleapis.com/youtube/v3/subscriptions?part=snippet&mine=true&order=relevance&maxResults=50"
                    + (page.isEmpty() ? "" : "&pageToken=" + enc(page)), access()));
            JSONArray items = o.optJSONArray("items");
            for (int i = 0; items != null && i < items.length() && out.size() < CHANNELS; i++) {
                JSONObject s = items.getJSONObject(i).getJSONObject("snippet");
                String id = s.getJSONObject("resourceId").optString("channelId"), name = s.optString("title");
                if (id.isEmpty()) continue;
                out.add(new String[] {id, name});
                save.put(new JSONArray().put(id).put(name));
            }
            page = o.optString("nextPageToken", "");
            if (page.isEmpty()) break;
        }
        prefs.edit().putString("channels", save.toString()).putLong("channelsAt", System.currentTimeMillis()).apply();
        return out;
    }

    /** A channel's newest uploads from its public feed (the last two weeks, no Shorts). */
    private static List<Video> feed(String channelId, String channelName) throws Exception {
        String xml = get("https://www.youtube.com/feeds/videos.xml?channel_id=" + enc(channelId), null);
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(new StringReader(xml));
        List<Video> out = new ArrayList<>();
        Video v = null;
        boolean shorts = false;
        long since = System.currentTimeMillis() - WINDOW;
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
            if (e == XmlPullParser.START_TAG) {
                String tag = p.getName();
                if (tag.equals("entry")) {
                    v = new Video();
                    v.channel = channelName;
                    shorts = false;
                } else if (v != null && tag.equals("yt:videoId")) {
                    v.id = p.nextText();
                } else if (v != null && tag.equals("title") && v.title == null) {
                    v.title = p.nextText();
                } else if (v != null && tag.equals("published")) {
                    v.published = iso.parse(p.nextText()).getTime();
                } else if (v != null && tag.equals("link")) {
                    String href = p.getAttributeValue(null, "href");
                    if (href != null && href.contains("/shorts/")) shorts = true;
                }
            } else if (e == XmlPullParser.END_TAG && p.getName().equals("entry") && v != null) {
                if (v.id != null && v.title != null && !shorts && v.published >= since) out.add(v);
                v = null;
            }
        }
        return out;
    }

    /** The video's image, cut once to the wide card and fitted into the tall ones (on the image's own dark edge). */
    private static void images(Video v) {
        if (v.wide != null) return;
        Bitmap src = null;
        for (String name : new String[] {"maxresdefault", "hqdefault"}) {
            try {
                byte[] data = bytes("https://i.ytimg.com/vi/" + v.id + "/" + name + ".jpg");
                src = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (src != null) break;
            } catch (IOException ignored) {
            }
        }
        if (src == null) return;
        // hqdefault is 4:3 with black bands: only its 16:9 middle is the picture.
        Rect pic = new Rect(0, 0, src.getWidth(), src.getHeight());
        if (src.getWidth() * 9 < src.getHeight() * 16) {
            int h = src.getWidth() * 9 / 16;
            pic.set(0, (src.getHeight() - h) / 2, src.getWidth(), (src.getHeight() + h) / 2);
        }
        Paint smooth = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        Bitmap wide = Bitmap.createBitmap(WIDE_W, WIDE_H, Bitmap.Config.ARGB_8888);
        // Cover: the picture's middle band, as wide as the card.
        int bandH = pic.width() * WIDE_H / WIDE_W;
        Rect band = new Rect(pic.left, pic.centerY() - bandH / 2, pic.right, pic.centerY() + bandH / 2);
        new Canvas(wide).drawBitmap(src, band, new Rect(0, 0, WIDE_W, WIDE_H), smooth);
        Bitmap tall = Bitmap.createBitmap(TALL_W * 2, TALL_H * 2, Bitmap.Config.ARGB_8888);
        Canvas t = new Canvas(tall);
        t.drawColor(0xFF0F0F0F);
        float h = TALL_W * 2 * pic.height() / (float) pic.width();
        t.drawBitmap(src, pic, new RectF(0, (TALL_H * 2 - h) / 2, TALL_W * 2, (TALL_H * 2 + h) / 2), smooth);
        src.recycle();
        v.wide = wide;
        v.tall = tall;
    }

    // MARK: - HTTP

    static final class OAuthError extends IOException {
        final String code;
        OAuthError(String code) { super(code); this.code = code; }
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static JSONObject post(String url, String form) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(form.getBytes(StandardCharsets.UTF_8));
            }
            int status = c.getResponseCode();
            String body = read(status < 400 ? c.getInputStream() : c.getErrorStream());
            JSONObject o = new JSONObject(body.isEmpty() ? "{}" : body);
            if (status >= 400 || o.has("error")) throw new OAuthError(o.optString("error", "HTTP " + status));
            return o;
        } catch (org.json.JSONException e) {
            throw new IOException("bad answer");
        } finally {
            c.disconnect();
        }
    }

    private static String get(String url, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static byte[] bytes(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = s.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}

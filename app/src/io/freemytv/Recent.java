package io.freemytv;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The apps used last, for the "Recent" tab. Google TV's "Continue watching" can't be copied: other apps' Watch Next
 * rows need a permission only system apps get, and most of it lives on Google's servers. So the tab lists apps (which
 * usually open on their own "continue watching"). Reading app use needs GET_USAGE_STATS, which HomeMode allows with
 * the TV's ADB in Free My TV mode.
 */
final class Recent {
    private Recent() {}

    static final int MAX = 3;
    private static final long WINDOW = 14 * 24 * 3600_000L;

    static final class App {
        String pkg, label;
        long at;
        Bitmap tile;
        int color;      // the card's background: the color at the art's corner, so the art seems to fill the card
    }

    static boolean allowed(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        // checkOpNoThrow: Android 8 and 9 have no unsafeCheckOpNoThrow.
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /** Up to MAX apps from "All Apps" and the shelf, the last used first. Slow: background only. */
    static List<App> load(Context c) {
        List<App> out = new ArrayList<>();
        if (!allowed(c)) return out;
        UsageStatsManager usage = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usage == null) return out;
        Map<String, String> labels = new HashMap<>();
        for (String[] app : HomeApps.launchable(c)) labels.put(app[0], app[1]);
        Set<String> skip = new HashSet<>();
        skip.add(c.getPackageName());
        skip.addAll(java.util.Arrays.asList(HomeApps.LIVE_TV_APPS));
        Map<String, Long> last = new HashMap<>();
        long now = System.currentTimeMillis();
        UsageEvents events = usage.queryEvents(now - WINDOW, now);
        UsageEvents.Event e = new UsageEvents.Event();
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(e);
            // MOVE_TO_FOREGROUND is ACTIVITY_RESUMED from Android 10 on (the same value).
            if (e.getEventType() != UsageEvents.Event.MOVE_TO_FOREGROUND) continue;
            String pkg = e.getPackageName();
            if (labels.containsKey(pkg) && !skip.contains(pkg) && !HomeApps.hidden(pkg)) last.put(pkg, e.getTimeStamp());
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(last.entrySet());
        Collections.sort(sorted, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Long> entry : sorted) {
            if (out.size() == MAX) break;
            App a = new App();
            a.pkg = entry.getKey();
            a.label = labels.get(a.pkg);
            a.at = entry.getValue();
            a.tile = HomeApps.tile(c, a.pkg);
            a.color = a.tile == null ? 0xFF15161C : a.tile.getPixel(2, 2) | 0xFF000000;
            out.add(a);
        }
        return out;
    }

    /** "5 min ago", "3 h ago", "2 days ago". */
    static String ago(long at) {
        long minutes = Math.max(1, (System.currentTimeMillis() - at) / 60_000);
        if (minutes < 60) return Ui.trf("{n} min ago", "n", minutes);
        if (minutes < 24 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
        return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / (24 * 60));
    }
}

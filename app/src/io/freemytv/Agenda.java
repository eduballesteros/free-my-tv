package io.freemytv;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * The next week of a calendar, from its iCal address (calendar_ics in config.properties: Google Calendar's "Secret
 * address in iCal format", or any public .ics). A TV can't sign in to Google, so this is the way in. Repeating
 * events (daily, weekly on some days, monthly on a date, yearly) are expanded; moved and cancelled ones are honored.
 */
final class Agenda {
    private Agenda() {}

    static final long DAYS = 7;
    private static final int MAX_EVENTS = 20, MAX_STEPS = 4000;

    static final class Event {
        String title;
        long start, end;
        boolean allDay;
    }

    static boolean on() {
        return !Config.CALENDAR_ICS.trim().isEmpty();
    }

    /** The events that haven't ended and start within DAYS, the soonest first. */
    static List<Event> parse(String ics, long now) {
        long until = startOfDay(now, TimeZone.getDefault()) + (DAYS + 1) * 86_400_000L;
        List<Map<String, String[]>> events = new ArrayList<>();
        Map<String, String[]> current = null;
        List<String> exdates = null;
        for (String line : unfold(ics)) {
            if (line.equals("BEGIN:VEVENT")) {
                current = new HashMap<>();
                exdates = new ArrayList<>();
            } else if (line.equals("END:VEVENT") && current != null) {
                current.put("EXDATE", exdates.toArray(new String[0]));
                events.add(current);
                current = null;
            } else if (current != null) {
                int colon = colon(line);
                if (colon < 0) continue;
                String head = line.substring(0, colon), value = line.substring(colon + 1);
                int semi = head.indexOf(';');
                String name = semi < 0 ? head : head.substring(0, semi), params = semi < 0 ? "" : head.substring(semi + 1);
                if (name.equals("EXDATE")) {
                    for (String v : value.split(",")) exdates.add(params + "\n" + v);
                } else {
                    current.put(name, new String[] {params, value});
                }
            }
        }
        // A moved or cancelled instance of a repeating event comes as its own VEVENT with RECURRENCE-ID: the original
        // time is left out of the repetition.
        Map<String, Set<Long>> moved = new HashMap<>();
        for (Map<String, String[]> e : events) {
            String[] id = e.get("RECURRENCE-ID"), uid = e.get("UID");
            if (id == null || uid == null) continue;
            try {
                Set<Long> set = moved.get(uid[1]);
                if (set == null) moved.put(uid[1], set = new HashSet<>());
                set.add(time(id[0], id[1]));
            } catch (ParseException ignored) {}
        }
        List<Event> out = new ArrayList<>();
        for (Map<String, String[]> e : events) {
            try {
                add(e, moved, now, until, out);
            } catch (ParseException | RuntimeException ignored) {
                // one odd event doesn't hide the others
            }
        }
        Collections.sort(out, (a, b) -> a.start != b.start ? Long.compare(a.start, b.start) : Boolean.compare(!a.allDay, !b.allDay));
        return out.size() > MAX_EVENTS ? new ArrayList<>(out.subList(0, MAX_EVENTS)) : out;
    }

    private static void add(Map<String, String[]> e, Map<String, Set<Long>> moved, long now, long until, List<Event> out)
            throws ParseException {
        String[] start = e.get("DTSTART");
        if (start == null) return;
        String[] status = e.get("STATUS");
        if (status != null && status[1].equalsIgnoreCase("CANCELLED")) return;
        boolean allDay = start[0].contains("VALUE=DATE") || start[1].length() == 8;
        TimeZone zone = zone(start[0], start[1]);
        long first = time(start[0], start[1]);
        String[] end = e.get("DTEND");
        long length = end != null ? time(end[0], end[1]) - first : allDay ? 86_400_000L : 0;
        String[] summary = e.get("SUMMARY");
        String title = summary == null ? "" : text(summary[1]);
        Set<Long> skip = new HashSet<>();
        for (String x : e.get("EXDATE")) {
            int nl = x.indexOf('\n');
            skip.add(time(x.substring(0, nl), x.substring(nl + 1)));
        }
        String[] uid = e.get("UID");
        if (uid != null && e.get("RECURRENCE-ID") == null && moved.containsKey(uid[1])) skip.addAll(moved.get(uid[1]));
        String[] rule = e.get("RRULE");
        if (rule == null) {
            keep(title, first, length, allDay, now, until, out);
            return;
        }
        Map<String, String> r = new HashMap<>();
        for (String part : rule[1].split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) r.put(part.substring(0, eq), part.substring(eq + 1));
        }
        String freq = r.containsKey("FREQ") ? r.get("FREQ") : "";
        int interval = r.containsKey("INTERVAL") ? Math.max(1, Integer.parseInt(r.get("INTERVAL"))) : 1;
        int count = r.containsKey("COUNT") ? Integer.parseInt(r.get("COUNT")) : Integer.MAX_VALUE;
        long last = r.containsKey("UNTIL") ? time("", r.get("UNTIL")) : Long.MAX_VALUE;
        List<Integer> days = new ArrayList<>();
        if (freq.equals("WEEKLY") && r.containsKey("BYDAY")) {
            for (String d : r.get("BYDAY").split(",")) {
                int day = weekday(d.replaceAll("^[+-]?\\d+", ""));
                if (day > 0) days.add(day);
            }
        }
        int field = freq.equals("DAILY") ? Calendar.DAY_OF_MONTH : freq.equals("WEEKLY") ? Calendar.WEEK_OF_YEAR
                : freq.equals("MONTHLY") ? Calendar.MONTH : freq.equals("YEARLY") ? Calendar.YEAR : -1;
        // "The second Tuesday", "the last day": rules we don't follow show their first time only, never a wrong date.
        boolean other = !freq.equals("WEEKLY") && (r.containsKey("BYDAY") || r.containsKey("BYMONTHDAY") || r.containsKey("BYSETPOS"));
        if (field < 0 || other) {
            keep(title, first, length, allDay, now, until, out);
            return;
        }
        Calendar step = Calendar.getInstance(zone);
        step.setTimeInMillis(first);
        Calendar at = Calendar.getInstance(zone);
        int made = 0;
        for (int i = 0; i < MAX_STEPS && made < count; i++) {
            long base = step.getTimeInMillis();
            if (base > until || base > last) break;
            if (days.isEmpty()) {
                if (!skip.contains(base)) keep(title, base, length, allDay, now, until, out);
                made++;
            } else {
                // Weekly on some days: each of them in this week (weeks start on Monday, as WKST usually says).
                for (int d = 0; d < 7 && made < count; d++) {
                    at.setTimeInMillis(base);
                    at.add(Calendar.DAY_OF_MONTH, d - ((at.get(Calendar.DAY_OF_WEEK) + 5) % 7));
                    long t = at.getTimeInMillis();
                    if (t < first || !days.contains(at.get(Calendar.DAY_OF_WEEK))) continue;
                    if (t > until || t > last) break;
                    if (!skip.contains(t)) keep(title, t, length, allDay, now, until, out);
                    made++;
                }
            }
            step.add(field, interval);
        }
    }

    private static void keep(String title, long start, long length, boolean allDay, long now, long until, List<Event> out) {
        if (start + Math.max(length, 1) <= now || start >= until) return;
        Event e = new Event();
        e.title = title;
        e.start = start;
        e.end = start + length;
        e.allDay = allDay;
        out.add(e);
    }

    /** Long lines are folded: a line starting with a space or a tab continues the one before. */
    private static List<String> unfold(String ics) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = null;
        for (String raw : ics.split("\r?\n")) {
            if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t') && line != null) {
                line.append(raw, 1, raw.length());
                continue;
            }
            if (line != null) lines.add(line.toString());
            line = new StringBuilder(raw);
        }
        if (line != null) lines.add(line.toString());
        return lines;
    }

    /** The colon that ends the name and parameters (a quoted parameter may hold one). */
    private static int colon(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (ch == ':' && !quoted) return i;
        }
        return -1;
    }

    private static String text(String v) {
        return v.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim();
    }

    private static TimeZone zone(String params, String value) {
        if (value.endsWith("Z")) return TimeZone.getTimeZone("UTC");
        for (String p : params.split(";")) {
            if (p.startsWith("TZID=")) return TimeZone.getTimeZone(p.substring(5).replace("\"", ""));
        }
        return TimeZone.getDefault();   // floating times and whole days: the TV's own
    }

    /** 20261022T180000Z (UTC), 20261022T180000 with TZID= (or floating), 20261022 (a whole day). */
    private static long time(String params, String value) throws ParseException {
        value = value.trim();
        boolean date = value.length() == 8;
        SimpleDateFormat f = new SimpleDateFormat(date ? "yyyyMMdd" : value.endsWith("Z") ? "yyyyMMdd'T'HHmmss'Z'" : "yyyyMMdd'T'HHmmss", Locale.US);
        f.setTimeZone(date ? TimeZone.getDefault() : zone(params, value));
        return f.parse(value).getTime();
    }

    private static int weekday(String d) {
        switch (d) {
            case "MO": return Calendar.MONDAY;
            case "TU": return Calendar.TUESDAY;
            case "WE": return Calendar.WEDNESDAY;
            case "TH": return Calendar.THURSDAY;
            case "FR": return Calendar.FRIDAY;
            case "SA": return Calendar.SATURDAY;
            case "SU": return Calendar.SUNDAY;
            default: return 0;
        }
    }

    static long startOfDay(long t, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    // MARK: - Words

    /** "Today", "Tomorrow", or the short weekday ("Fri"). */
    static String day(long at, long now) {
        long days = (startOfDay(at, TimeZone.getDefault()) - startOfDay(now, TimeZone.getDefault()) + 3_600_000L) / 86_400_000L;
        if (days <= 0) return Ui.tr("Today");
        if (days == 1) return Ui.tr("Tomorrow");
        return Ui.datePattern("format|EEE").format(at);
    }

    /** "18:00", "All day", or "Now" while it's on. */
    static String when(Event e, long now) {
        if (e.allDay) return Ui.tr("All day");
        if (e.start <= now) return Ui.tr("calendar|Now");
        return Ui.time(e.start);
    }

    /** The line at the top of the home screen: "Today 18:00 · Dentist". */
    static String line(Event e, long now) {
        String when = e.allDay ? day(e.start, now) : e.start <= now ? Ui.tr("calendar|Now") : day(e.start, now) + " " + Ui.time(e.start);
        return when + " · " + e.title;
    }
}

package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.Random;

/**
 * Optional (idle_pikachu=true in config.properties): after a few minutes with no key pressed, a pixel-art Pikachu
 * crosses the home screen, and keeps coming back every 20 to 40 s while nobody touches the remote. Each time a
 * different way: either direction, running or hopping, or stopping halfway to jump and run back; along the top of the
 * shelf or of the cards. Any key sends it away. Its frames are drawn here, cell by cell, and scaled up without
 * smoothing, so it stays pixelated. Personal: not for the shared project.
 */
final class Runner {
    static final long IDLE_MS = 3 * 60_000L;
    private static final long AGAIN_MS = 20_000L, AGAIN_SPREAD = 20_000L, FRAME_MS = 110, PAUSE_MS = 900;
    private static final float CELL = 4;        // one sprite pixel, in the 1920×1080 space
    private static final float SPEED = 330;     // in the 1920×1080 space, per second
    private static final int RUN = 0, HOP = 1, TURN = 2;

    // Facing right; two frames (legs apart, legs gathered and a row higher). D outline, Y yellow, O shade, K black,
    // R cheek, B brown, W eye highlight.
    private static final String[][] FRAMES = {{
        "....................................",
        ".DDDDDDDD...........................",
        "DYYYYYYYYD..........................",
        "DYYYYYYYYD..........................",
        ".DYYYYYYYD..........KKK.............",
        "..DDDDYYYDD....KKK..KKKK............",
        "..DYYYYYYYYD...KKKKDDKKKD...........",
        "..DYYYYYYYYD..DDKKKYYDYYYD..........",
        "...DYYYYYYYD...DDDYYYYDYYYD.........",
        "....DDDDYYYD....DDDDYYYDYYYD........",
        ".......DYYYYD..DBDYBDYYYDYYYD.......",
        "........DDBBDDDYBBYBBDDYYDYYDD......",
        ".........DBBBYYYYBYYBYYDYYDYYYD.....",
        "..........DBYYYYYYYYYYYYYYYKWYYD....",
        "...........DYYYYYYYYYYYYYYYKKYYD....",
        "..........DYYYYYYYYYYYYYYYYYYYYK....",
        "..........DYYYYYYYYYYYYYYYYYYYYYD...",
        "..........DYYYYYYYYYYYYYYYRRYYYYD...",
        "..........DOOOYYYYYYYYYYYRRRRYYYD...",
        "...........DOOOYYYYYYYYYYRRRRYYD....",
        "...........DOOOOYYYYYYYYYYRRYYYD....",
        "............DOOOOOYYYYYYYYYYYYD.....",
        "...........DDDDOOOOOOOYYYYYYDD......",
        "..........DOOD.DDDOOOOYYYDDYYD......",
        "...........DYYD...DDDDDDD..DYYD.....",
        "............DD..............DD......",
        "....................................",
    }, {
        ".DDDDDDDD...........................",
        "DYYYYYYYYD..........................",
        "DYYYYYYYYD..........................",
        ".DYYYYYYYD..........KKK.............",
        "..DDDDYYYDD....KKK..KKKK............",
        "..DYYYYYYYYD...KKKKDDKKKD...........",
        "..DYYYYYYYYD..DDKKKYYDYYYD..........",
        "...DYYYYYYYD...DDDYYYYDYYYD.........",
        "....DDDDYYYD....DDDDYYYDYYYD........",
        ".......DYYYYD..DBDYBDYYYDYYYD.......",
        "........DDBBDDDYBBYBBDDYYDYYDD......",
        ".........DBBBYYYYBYYBYYDYYDYYYD.....",
        "..........DBYYYYYYYYYYYYYYYKWYYD....",
        "...........DYYYYYYYYYYYYYYYKKYYD....",
        "..........DYYYYYYYYYYYYYYYYYYYYK....",
        "..........DYYYYYYYYYYYYYYYYYYYYYD...",
        "..........DYYYYYYYYYYYYYYYRRYYYYD...",
        "..........DOOOYYYYYYYYYYYRRRRYYYD...",
        "...........DOOOYYYYYYYYYYRRRRYYD....",
        "...........DOOOOYYYYYYYYYYRRYYYD....",
        "............DOOOOOYYYYYYYYYYYYD.....",
        ".............DDOOOOOOOYYYYYYDD......",
        "...............DOOOOOOYYYDDD........",
        "................DYYDDDDYYD..........",
        ".................DD....DD...........",
        "....................................",
        "....................................",
    }};

    private final Bitmap[] bitmaps = new Bitmap[FRAMES.length];
    private final Paint pixels = new Paint();   // no FILTER_BITMAP_FLAG: each cell stays a sharp square
    private final RectF dst = new RectF();
    private final Random random = new Random();
    private long startedAt = -1, endedAt, nextAt = -1, runMs;
    private int dir, way;
    private float ground, turnAt;

    Runner() {
        for (int f = 0; f < FRAMES.length; f++) {
            String[] rows = FRAMES[f];
            int w = rows[0].length(), h = rows.length;
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) px[y * w + x] = color(rows[y].charAt(x));
            }
            bitmaps[f] = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        }
        pixels.setFilterBitmap(false);
        pixels.setAntiAlias(false);
    }

    private static int color(char c) {
        switch (c) {
            case 'D': return 0xFF281E0F;
            case 'K': return 0xFF141414;
            case 'Y': return 0xFFFFDE1E;
            case 'O': return 0xFFE1AF0A;
            case 'R': return 0xFFE12D23;
            case 'B': return 0xFF825019;
            case 'W': return 0xFFFFFFFF;
            default: return 0;
        }
    }

    boolean active(long up) {
        if (startedAt < 0) return false;
        if (up - startedAt < runMs) return true;
        finish(up);
        return false;
    }

    private void finish(long up) {
        startedAt = -1;
        endedAt = up;
        nextAt = up + AGAIN_MS + (long) (random.nextFloat() * AGAIN_SPREAD);
    }

    /**
     * Starts a run when the screen has been left alone long enough: the first after IDLE_MS, then every 20 to 40 s.
     * `grounds`: where it can run (the y of the surface it runs on, screen pixels); `width`: the screen's.
     */
    boolean maybeStart(long up, long lastKeyAt, float[] grounds, float width, float k) {
        if (startedAt >= 0 || up - lastKeyAt < IDLE_MS) return false;
        if (nextAt >= 0 && endedAt > lastKeyAt && up < nextAt) return false;
        dir = random.nextBoolean() ? 1 : -1;
        way = random.nextInt(3);
        ground = grounds[random.nextInt(grounds.length)];
        turnAt = 0.3f + random.nextFloat() * 0.4f;
        float sw = spriteWidth(k);
        float across = (width + sw) / (SPEED * k) * 1000;
        runMs = way == TURN ? (long) (2 * (turnAt * width + sw) / (SPEED * k) * 1000) + PAUSE_MS : (long) across;
        startedAt = up;
        return true;
    }

    /** A key: it leaves at once. True if it was running (its layer must be redrawn without it). */
    boolean stop(long up) {
        if (startedAt < 0) return false;
        finish(up);
        nextAt = -1;    // the next one waits for the screen to be idle again
        return true;
    }

    private float spriteWidth(float k) {
        return bitmaps[0].getWidth() * CELL * k;
    }

    void draw(Canvas c, long up, float width, float k) {
        if (startedAt < 0) return;
        long t = up - startedAt;
        float cell = CELL * k, sw = spriteWidth(k), speed = SPEED * k / 1000;
        int frame = (int) (t / FRAME_MS) % bitmaps.length;
        int facing = dir;
        float x, lift = 0;
        if (way == TURN) {
            // In from its side to turnAt, a pause with a jump, then back out the way it came.
            float in = turnAt * width + sw;
            long inMs = (long) (in / speed);
            if (t < inMs) {
                x = -sw + t * speed;
            } else if (t < inMs + PAUSE_MS) {
                x = -sw + in;
                float p = (t - inMs) / (float) PAUSE_MS;
                lift = (float) Math.sin(Math.PI * p) * 22 * cell;
                frame = 1;
                if (p > 0.5f) facing = -dir;
            } else {
                x = -sw + in - (t - inMs - PAUSE_MS) * speed;
                facing = -dir;
            }
        } else {
            x = -sw + t * speed;
            if (way == HOP) {
                float p = (t % 700) / 700f;
                lift = (float) Math.sin(Math.PI * p) * 14 * cell;
                frame = lift > 2 * cell ? 1 : 0;
            }
        }
        if (dir < 0) x = width - sw - x;   // the same path, from the right
        // Whole cells: it moves on its own pixel grid, like in the games.
        x = Math.round(x / cell) * cell;
        float y = Math.round((ground - lift) / cell) * cell;
        Bitmap b = bitmaps[frame];
        dst.set(x, y - b.getHeight() * cell, x + sw, y);
        if (facing < 0) {
            c.save();
            c.scale(-1, 1, dst.centerX(), dst.centerY());
            c.drawBitmap(b, null, dst, pixels);
            c.restore();
        } else {
            c.drawBitmap(b, null, dst, pixels);
        }
    }
}

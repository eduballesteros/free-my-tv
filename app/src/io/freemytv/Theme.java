package io.freemytv;

/**
 * The Glass design system (design/glass.html, its "tokens" block), value for value. Sizes are in the 1920×1080 space
 * the home screen draws in (multiply by k); 1 dp = 2 px there.
 */
final class Theme {
    private Theme() {}

    // Safe area: 5% of the screen on every side.
    static final float SAFE_X = 96, SAFE_Y = 54;

    // Type: tvOS's text styles at 1080p, three sizes; figures (temperatures, dates, the clock) in a thin weight.
    static final float TITLE = 76, HEADLINE = 38, BODY = 29, FIGURES = 108;
    static final float TITLE_TRACK = -0.012f, HEADLINE_TRACK = -0.008f, BODY_TRACK = -0.004f, FIGURES_TRACK = -0.02f;

    // iOS's semantic colours; the secondary label at 80% (iOS's 60% is under 4.5:1 on white).
    static final int LABEL = 0xFF000000, LABEL_2 = 0xCC3C3C43, WIDGET = 0xFFFFFFFF, RED = 0xFFD70015;
    static final int LABEL_DARK = 0xFFFFFFFF, LABEL_2_DARK = 0x99EBEBF5, WIDGET_DARK = 0xFF1C1C1E, RED_DARK = 0xFFFF453A;
    static final int[] SKY_DAY = {0xFF2F6FD0, 0xFF1F55B0}, SKY_NIGHT = {0xFF1F3366, 0xFF0E1A38};
    static final int[] EVENTS = {0xFF5E5CE6, 0xFF30B0C7, 0xFFFF9F0A};
    static final int CLOCK_HAND = 0xFFFF9500;

    // Materials: thin (the dock) and regular (panels with text); a 1 px inner edge and a specular highlight on top.
    static final int THIN = 0x4DFFFFFF, THIN_DARK = 0x5728282E, REGULAR = 0xB8FFFFFF, REGULAR_DARK = 0xB31E1E22;
    static final int EDGE = 0x33FFFFFF, EDGE_DARK = 0x29FFFFFF, SPECULAR_LINE = 0x59FFFFFF, SPECULAR_TOP = 0x38FFFFFF;
    static final float SPECULAR_TO = 0.42f;

    // Shapes.
    static final float ICON_RADIUS = 0.22f, WIDGET_RADIUS = 56, DOCK_RADIUS = 64;
    static final float WIDGET_S = 336, WIDGET_M = 712, WIDGET_H = 336, WIDGET_PAD_X = 34, WIDGET_PAD_Y = 30;
    static final float ICON_GRID = 150, ICON_GRID_GAP = 66, ICON_DOCK = 132, ICON_DOCK_GAP = 36;

    // Focus (tvOS): zoom, a raised shadow, a white rim and a diagonal sheen; the rest of the row at 80%.
    static final float FOCUS_SCALE = 1.08f, FOCUS_DIM = 0.8f, FOCUS_SHADOW_Y = 34, FOCUS_SHADOW_BLUR = 60;
    static final float FOCUS_SHADOW_ALPHA = 0.38f, RIM = 1.5f, SHEEN = 0.42f;
    static final int RIM_COLOR = 0x8CFFFFFF;

    // Springs (response s, damping), integrated like Spring: they settle in 264 to 308 ms.
    static Spring focus() { return new Spring(0.34f, 0.9f, 0); }
    static Spring dim() { return new Spring(0.24f, 1f, 0); }
    static Spring move() { return new Spring(0.28f, 1f, 0); }

    /** The rounded-rectangle radius closest to an icon's continuous corner (for its shadow, rim and sheen). */
    static float squircleRadius(float size) {
        return size * ICON_RADIUS;
    }
}

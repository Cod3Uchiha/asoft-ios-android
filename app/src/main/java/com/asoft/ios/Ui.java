package com.asoft.ios;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The home screen's look for the setup page: its colours, type sizes and its two kinds of action (the light capsule and
 * the plain coloured text), so both read as one app. MainActivity keeps its own copies of the same values.
 */
final class Ui {
    private Ui() {}

    static final int BG = 0xff0b1020, LINE = 0xff27334d, TEXT = 0xffedf2ff, DIM = 0xffa0aec6, FAINT = 0xff71809a,
            LINK = 0xff63d9e8, RED = 0xffff7185, GREEN = 0xff5ee0a0, ORANGE = 0xffffbd66;
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static TextView text(Context c, LinearLayout to, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        to.addView(t);
        return t;
    }

    static LinearLayout.LayoutParams top(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px;
        return lp;
    }

    /** The main action of a section: a light capsule, or (@outline) only its edge for the quieter one. */
    static TextView capsule(Context c, String s, boolean outline, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(15);
        t.setTypeface(MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 38));
        t.setMinWidth(dp(c, 92));
        t.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), dp(c, 8));
        t.setOnClickListener(l);
        GradientDrawable g = round(c, outline ? Color.TRANSPARENT : LINK, 19);
        if (outline) {
            g.setStroke(dp(c, 1.2f), 0xff6e6e73);
        }
        t.setTextColor(outline ? TEXT : BG);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(outline ? 0x30ffffff : 0x30000000), g, round(c, Color.WHITE, 19)));
        return t;
    }

    /** Plain coloured text that acts. */
    static TextView link(Context c, String s, int color, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(14.5f);
        t.setTextColor(color);
        t.setPadding(dp(c, 14), dp(c, 9), dp(c, 8), dp(c, 9));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x28ffffff), null, round(c, Color.WHITE, 14)));
        t.setOnClickListener(l);
        return t;
    }

    /** A hairline across a column. */
    static void line(Context c, LinearLayout to, int topDp, int bottomDp) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 0.75f)));
        lp.topMargin = dp(c, topDp);
        lp.bottomMargin = dp(c, bottomDp);
        to.addView(v, lp);
    }

    /** The status dot of a state line: grey off, amber at work, green there. */
    static void dot(Context c, View dot, int color) {
        dot.setBackground(round(c, color, 4));
    }
}

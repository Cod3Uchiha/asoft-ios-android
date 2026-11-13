package com.asoft.ios;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * One system (or one block) of the setup page, built like a panel of the home screen without its frame: its small drawn
 * device, its name, one state line, a line of what it needs, its action, and "Details" that opens what is long (the
 * description, the downloaded files, every step). The details stay closed unless the system's setup runs or the user
 * opens them; the user's choice holds until the run starts or stops.
 */
final class SetupSection {
    static final int OFF = 0, WORKING = 1, THERE = 2;

    private final Context c;
    final LinearLayout root;
    final DeviceView device;
    final TextView title, state, info, extra, error;
    private final View dot;
    /** The system's actions: its main capsule and plain links, side by side. */
    final LinearLayout actions;
    /** Below the actions: what a section wants to keep in view (the GPU line and its buttons). */
    final LinearLayout main;
    /** The details, closed or open. */
    final LinearLayout body;
    private final TextView toggle;
    /** True: the user opened or closed the details; null: they follow the run. */
    private Boolean forced;
    private boolean wasRunning;

    /** @kind is a DeviceView.ISLAND, HOME or WATCH; -1: no device (the block under the systems, the PC). */
    SetupSection(Context c, String name, int kind, int[] tint) {
        this.c = c;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row);
        if (kind >= 0) {
            device = new DeviceView(c, kind, tint);
            row.addView(device, new LinearLayout.LayoutParams(Ui.dp(c, 46), Ui.dp(c, kind == DeviceView.WATCH ? 76 : 94)));
        } else {
            device = null;
        }
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
        clp.leftMargin = kind >= 0 ? Ui.dp(c, 16) : 0;
        row.addView(col, clp);

        title = Ui.text(c, col, name, 21, Ui.TEXT);
        title.setTypeface(Ui.MEDIUM);

        LinearLayout st = new LinearLayout(c);
        st.setOrientation(LinearLayout.HORIZONTAL);
        st.setGravity(Gravity.CENTER_VERTICAL);
        dot = new View(c);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(c, 8), Ui.dp(c, 8));
        dlp.rightMargin = Ui.dp(c, 8);
        st.addView(dot, dlp);
        state = Ui.text(c, st, "", 14.5f, Ui.DIM);
        col.addView(st, Ui.top(Ui.dp(c, 2)));

        info = quiet(col, 12.5f, Ui.DIM, 6);
        extra = quiet(col, 12.5f, Ui.DIM, 4);
        error = quiet(col, 14, Ui.RED, 8);
        main = new LinearLayout(c);
        main.setOrientation(LinearLayout.VERTICAL);
        col.addView(main);

        actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(actions, Ui.top(Ui.dp(c, 12)));

        toggle = Ui.link(c, "Details", Ui.LINK, v -> flip());
        toggle.setTextSize(13.5f);
        toggle.setPadding(0, Ui.dp(c, 9), Ui.dp(c, 8), Ui.dp(c, 4));
        col.addView(toggle, new LinearLayout.LayoutParams(-2, -2));

        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        root.addView(body, Ui.top(Ui.dp(c, 4)));
    }

    private void flip() {
        forced = body.getVisibility() != View.VISIBLE;
        apply(wasRunning);
    }

    private TextView quiet(LinearLayout to, float sp, int color, int topDp) {
        TextView t = Ui.text(c, to, "", sp, color);
        t.setLineSpacing(Ui.dp(c, 1.5f), 1f);
        t.setVisibility(View.GONE);
        ((LinearLayout.LayoutParams) t.getLayoutParams()).topMargin = Ui.dp(c, topDp);
        return t;
    }

    /** The state line: @how colours its dot (grey, amber at work, green there); "" hides the line. */
    void setState(int how, String s) {
        state.setText(s);
        state.setTextColor(how == THERE ? Ui.GREEN : how == WORKING ? Ui.ORANGE : Ui.DIM);
        Ui.dot(c, dot, how == THERE ? Ui.GREEN : how == WORKING ? Ui.ORANGE : 0xff48484a);
    }

    /** Sets a quiet line under the state; "" hides it. */
    static void say(TextView t, String s, int color) {
        if (!s.contentEquals(t.getText())) {
            t.setText(s);
        }
        t.setTextColor(color);
        t.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** The details follow @running (open while a setup runs) unless the user chose; the run's start or stop gives the choice back. */
    void follow(boolean running) {
        if (running != wasRunning) {
            forced = null;
        }
        wasRunning = running;
        apply(running);
    }

    private void apply(boolean running) {
        boolean open = forced != null ? forced : running;
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        toggle.setText(open ? "Hide details" : "Details");
    }

    /** No details at all (a block whose body is empty): the link goes. */
    void noDetails(boolean none) {
        toggle.setVisibility(none ? View.GONE : View.VISIBLE);
        if (none) {
            body.setVisibility(View.GONE);
        }
    }

    /** Opens the details until the user closes them (a block that has something waiting). */
    void open() {
        if (forced == null) {
            forced = true;
            apply(wasRunning);
        }
    }

    // -- pieces for the details

    /** A small heading inside the details. */
    TextView heading(String s) {
        TextView t = Ui.text(c, body, s, 13, Ui.DIM);
        t.setTypeface(Ui.MEDIUM);
        t.setPadding(0, Ui.dp(c, 14), 0, Ui.dp(c, 6));
        return t;
    }

    /** A paragraph of the details. */
    TextView paragraph(String s, int color) {
        TextView t = Ui.text(c, body, s, 13.5f, color);
        t.setLineSpacing(Ui.dp(c, 1.5f), 1f);
        t.setPadding(0, Ui.dp(c, 6), 0, 0);
        return t;
    }

    /** A thin progress bar, white on the line's grey. */
    ProgressBar bar(LinearLayout to, int heightDp) {
        ProgressBar b = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        b.setMax(1000);
        b.setProgressTintList(ColorStateList.valueOf(Ui.TEXT));
        b.setProgressBackgroundTintList(ColorStateList.valueOf(0xff3a3a3f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, heightDp));
        lp.topMargin = Ui.dp(c, 6);
        to.addView(b, lp);
        return b;
    }

    /** A file's row in the downloads: its name, its state on the right, its bar under. */
    TextView[] fileRow(LinearLayout to, String name, boolean first) {
        if (!first) {
            Ui.line(c, to, 10, 8);
        }
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView n = new TextView(c);
        n.setTextSize(14.5f);
        n.setTextColor(Ui.TEXT);
        n.setText(name);
        TextView st = new TextView(c);
        st.setTextSize(13);
        st.setTextColor(Ui.DIM);
        st.setGravity(Gravity.END);
        head.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(st, new LinearLayout.LayoutParams(-2, -2));
        to.addView(head);
        return new TextView[] {n, st};
    }
}

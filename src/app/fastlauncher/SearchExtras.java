package app.fastlauncher;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Results under the drawer's search field, besides the matching apps: a calculator answer,
 * up to three contacts, and "Search the web". Contacts are read off the main thread.
 */
final class SearchExtras extends LinearLayout {

    interface Host {
        void start(Intent i, View from);
        void snack(String msg);
        void onExtrasHeight(int px);
        int hiddenCount();
        void openHidden(View from);
    }

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Host host;
    private int gen;
    private String query = "", maths;
    private String shownContacts = "";

    // Built once and reused while typing: a keystroke only changes text and visibility, and the
    // contact rows are rebuilt only when the set of matching contacts actually changes.
    private final View mathsRow, webRow, hiddenRow;
    private final TextView mathsTitle, webTitle, hiddenTitle;
    private boolean hiddenShown;
    private final LinearLayout contactBox;

    SearchExtras(Context c, Host h) {
        super(c);
        host = h;
        setOrientation(VERTICAL);
        mathsRow = row(c, panelIcon(c, PanelIcons.CALC), "", "Tap to copy", v -> { if (maths != null) copy(maths); });
        mathsTitle = (TextView) mathsRow.getTag();
        hiddenRow = row(c, glyph(c, Ui.G_SEARCH), "Hidden apps", "Tap to see them", v -> host.openHidden(v));
        hiddenTitle = (TextView) hiddenRow.getTag();
        contactBox = new LinearLayout(c);
        contactBox.setOrientation(VERTICAL);
        webRow = row(c, glyph(c, Ui.G_SEARCH), "", null, this::web);
        webTitle = (TextView) webRow.getTag();
        addView(hiddenRow, rowLp());
        addView(mathsRow, rowLp());
        addView(contactBox, new LinearLayout.LayoutParams(-1, -2));
        addView(webRow, rowLp());
        setVisibility(GONE);
        // rows fade in and out and the others slide to make room; LayoutTransition copes with
        // changes arriving faster than the animation (typing), unlike a scene transition
        setLayoutTransition(Ui.rows());
        contactBox.setLayoutTransition(Ui.rows());
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dpi(getContext(), 52));
        lp.bottomMargin = Ui.dpi(getContext(), 6);
        return lp;
    }

    void update(String raw) {
        final String q = raw.trim();
        query = q;
        final int g = ++gen;
        if (q.isEmpty()) {
            showContacts(new ArrayList<>());
            setVisibility(GONE);
            host.onExtrasHeight(0);
            return;
        }
        maths = Calc.answer(q);
        if (maths != null) mathsTitle.setText("= " + maths);
        setVis(mathsRow, maths != null);
        // the hidden apps live here, out of the drawer: type "hidden" (or the start of it)
        int hidden = q.length() >= 3 && "hidden".startsWith(q.toLowerCase(Locale.ROOT)) ? host.hiddenCount() : 0;
        hiddenShown = hidden > 0;
        if (hiddenShown) hiddenTitle.setText(hidden == 1 ? "1 hidden app" : hidden + " hidden apps");
        setVis(hiddenRow, hiddenShown);
        webTitle.setText("Search the web for \u201C" + q + "\u201D");
        setVis(this, true);
        boolean lookup = q.length() >= 2
                && getContext().checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        // the previous contacts stay up while the lookup runs (a few ms), so the grid doesn't jump twice
        if (!lookup) showContacts(new ArrayList<>());
        reportHeight();
        if (!lookup) return;
        final Context app = getContext().getApplicationContext();
        worker.execute(() -> {
            if (g != gen) return;
            final List<Contact> found = contacts(app, q);
            main.post(() -> {
                if (g != gen) return;
                showContacts(found);
                reportHeight();
            });
        });
    }

    private static void setVis(View v, boolean on) {
        int vis = on ? VISIBLE : GONE;
        if (v.getVisibility() != vis) v.setVisibility(vis);
    }

    private static String keyOf(List<Contact> found) {
        StringBuilder key = new StringBuilder();
        for (Contact k : found) key.append(k.id).append(',');
        return key.toString();
    }

    private void showContacts(List<Contact> found) {
        String key = keyOf(found);
        if (key.equals(shownContacts)) return;
        shownContacts = key;
        contactBox.removeAllViews();
        Context c = getContext();
        for (final Contact k : found) {
            contactBox.addView(row(c, avatar(c, k), k.name, k.number, v -> host.start(new Intent(Intent.ACTION_VIEW,
                    ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, k.id)), v)), rowLp());
        }
    }

    /** Fixed-height rows, so the height is arithmetic: no measure pass while typing. */
    private void reportHeight() {
        int rows = 1 + (maths != null ? 1 : 0) + (hiddenShown ? 1 : 0) + contactBox.getChildCount();
        host.onExtrasHeight(rows * Ui.dpi(getContext(), 52 + 6) - Ui.dpi(getContext(), 6));
    }

    /** Enter on the keyboard with no app match: the best extra (maths result copied, else the web). */
    void runDefault() {
        if (query.isEmpty()) return;
        if (maths != null) { copy(maths); return; }
        web(this);
    }

    private void web(View v) {
        host.start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))), v);
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("result", s));
        host.snack("Copied " + s);
    }

    private View avatar(Context c, Contact k) {
        int s = Ui.dpi(c, 38);
        if (k.photo != null) {
            Ui.RoundImage img = new Ui.RoundImage(c, s / 2f);
            img.setBitmap(k.photo);
            return img;
        }
        TextView t = Ui.text(c, k.name.substring(0, 1).toUpperCase(Locale.getDefault()), 16, Color.WHITE, true);
        t.setTypeface(Ui.font(c, 650));
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(0xFF3D7FFF, s / 2f));
        return t;
    }

    private View glyph(Context c, int type) {
        Ui.Glyph g = new Ui.Glyph(c, type, Color.WHITE);
        int p = Ui.dpi(c, 9);
        g.setPadding(p, p, p, p);
        return g;
    }

    private View panelIcon(Context c, int type) {
        PanelIcons g = new PanelIcons(c, type, Color.WHITE);
        int p = Ui.dpi(c, 8);
        g.setPadding(p, p, p, p);
        return g;
    }

    private View row(Context c, View icon, String title, String sub, OnClickListener click) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dpi(c, 8), 0, Ui.dpi(c, 16), 0);
        r.setBackground(Ui.round(0x26FFFFFF, Ui.dp(c, 26)));
        int s = Ui.dpi(c, 38);
        r.addView(icon, new LinearLayout.LayoutParams(s, s));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        col.setPadding(Ui.dpi(c, 12), 0, 0, 0);
        TextView t = Ui.text(c, title, 15, Color.WHITE, true);
        t.setTypeface(Ui.font(c, 600));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);
        r.setTag(t);
        if (sub != null && !sub.isEmpty()) {
            TextView st = Ui.text(c, sub, 12, 0xB3FFFFFF, false);
            st.setSingleLine(true);
            col.addView(st);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        r.setClickable(true);
        Ui.pressable(r);
        r.setOnClickListener(click);
        return r;
    }

    // ---------------------------------------------------------------- contacts

    static final class Contact {
        long id;
        String name, number;
        Bitmap photo;
    }

    private static List<Contact> contacts(Context c, String q) {
        List<Contact> out = new ArrayList<>();
        Uri uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q));
        String[] cols = {ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI, ContactsContract.Contacts.HAS_PHONE_NUMBER};
        try (Cursor cur = c.getContentResolver().query(uri, cols, null, null, ContactsContract.Contacts.TIMES_CONTACTED + " DESC")) {
            while (cur != null && cur.moveToNext() && out.size() < 3) {
                Contact k = new Contact();
                k.id = cur.getLong(0);
                k.name = cur.getString(1);
                if (k.name == null || k.name.isEmpty()) continue;
                String thumb = cur.getString(2);
                if (thumb != null) {
                    try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(thumb))) {
                        k.photo = BitmapFactory.decodeStream(in);
                    } catch (Exception ignored) {}
                }
                if (cur.getInt(3) > 0) k.number = number(c, k.id);
                out.add(k);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String number(Context c, long id) {
        try (Cursor p = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?", new String[]{String.valueOf(id)}, null)) {
            if (p != null && p.moveToFirst()) return p.getString(0);
        } catch (Exception ignored) {}
        return null;
    }

    // ---------------------------------------------------------------- calculator

    /** A small calculator: + - * / x × ÷ % ^, brackets and decimals. Null unless it's clearly maths. */
    static final class Calc {
        private final String s;
        private int i;

        private Calc(String s) { this.s = s; }

        static String answer(String q) {
            String t = q.replace(" ", "").replace("×", "*").replace("÷", "/").replace(",", "");
            t = t.replaceAll("(?<=[0-9).])x(?=[0-9(.])", "*");
            if (!t.matches("[0-9.+\\-*/%^()]+") || !t.matches(".*[0-9].*")) return null;
            if (!t.matches(".*[0-9)][+\\-*/%^].*")) return null; // needs an operator after a number
            try {
                Calc c = new Calc(t);
                double v = c.sum();
                if (c.i != t.length() || Double.isNaN(v) || Double.isInfinite(v)) return null;
                if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
                String r = String.format(Locale.US, "%.8f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
                return r;
            } catch (Exception e) {
                return null;
            }
        }

        private double sum() {
            double v = product();
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch == '+') { i++; v += product(); }
                else if (ch == '-') { i++; v -= product(); }
                else break;
            }
            return v;
        }

        private double product() {
            double v = power();
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch == '*') { i++; v *= power(); }
                else if (ch == '/') { i++; v /= power(); }
                else if (ch == '%') {
                    i++;
                    // "50%" alone is 0.5; "10%3" is the remainder
                    if (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '(' || s.charAt(i) == '.')) v %= power();
                    else v /= 100;
                }
                else break;
            }
            return v;
        }

        private double power() {
            double v = unary();
            if (i < s.length() && s.charAt(i) == '^') { i++; v = Math.pow(v, power()); }
            return v;
        }

        private double unary() {
            if (i < s.length() && s.charAt(i) == '-') { i++; return -unary(); }
            if (i < s.length() && s.charAt(i) == '+') { i++; return unary(); }
            if (i < s.length() && s.charAt(i) == '(') {
                i++;
                double v = sum();
                if (i >= s.length() || s.charAt(i) != ')') throw new IllegalArgumentException();
                i++;
                return v;
            }
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (start == i) throw new IllegalArgumentException();
            return Double.parseDouble(s.substring(start, i));
        }
    }
}

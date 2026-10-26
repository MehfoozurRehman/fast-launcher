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
    private final LinearLayout settingsBox;
    private String shownSettings = "";
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
        settingsBox = new LinearLayout(c);
        settingsBox.setOrientation(VERTICAL);
        settingsBox.setLayoutTransition(Ui.rows());
        // look up which settings screens exist once, off the main thread, before the first keystroke
        final Context app = c.getApplicationContext();
        worker.execute(() -> Setting.all(app));
        addView(settingsBox, new LinearLayout.LayoutParams(-1, -2));
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
        showSettings(Setting.match(getContext(), q));
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
        int rows = 1 + (maths != null ? 1 : 0) + (hiddenShown ? 1 : 0) + contactBox.getChildCount() + settingsBox.getChildCount();
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
        t.setBackground(Ui.round(Ui.ACCENT, s / 2f));
        return t;
    }

    private View glyph(Context c, int type) {
        Ui.Glyph g = new Ui.Glyph(c, type, Color.WHITE);
        int p = Ui.dpi(c, 9);
        g.setPadding(p, p, p, p);
        return g;
    }

    private void showSettings(List<Setting> found) {
        StringBuilder key = new StringBuilder();
        for (Setting st : found) key.append(st.title).append(',');
        if (key.toString().equals(shownSettings)) return;
        shownSettings = key.toString();
        settingsBox.removeAllViews();
        Context c = getContext();
        for (final Setting st : found) {
            settingsBox.addView(row(c, panelIcon(c, PanelIcons.GEAR), st.title, "Settings", v -> host.start(st.intent(), v)), rowLp());
        }
    }

    /**
     * Settings screens you can jump to from search ("wifi", "battery", "display"...). Each is
     * listed only if the phone actually has that screen.
     */
    static final class Setting {
        final String title, action;
        final String[] words;

        Setting(String title, String action, String... words) { this.title = title; this.action = action; this.words = words; }

        Intent intent() { return new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); }

        private static List<Setting> all;

        private static List<Setting> all(Context c) {
            if (all != null) return all;
            Setting[] list = {
                new Setting("Wi-Fi", android.provider.Settings.ACTION_WIFI_SETTINGS, "wifi", "wi-fi", "wireless", "internet", "network"),
                new Setting("Bluetooth", android.provider.Settings.ACTION_BLUETOOTH_SETTINGS, "bluetooth", "earbuds", "headphones", "pair"),
                new Setting("Mobile network", android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS, "mobile", "data", "sim", "roaming", "network", "cellular"),
                new Setting("Data usage", android.provider.Settings.ACTION_DATA_USAGE_SETTINGS, "data usage", "usage", "data"),
                new Setting("Hotspot & tethering", android.provider.Settings.ACTION_WIRELESS_SETTINGS, "hotspot", "tethering", "more connections"),
                new Setting("Aeroplane mode", android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS, "aeroplane", "airplane", "flight"),
                new Setting("VPN", android.provider.Settings.ACTION_VPN_SETTINGS, "vpn"),
                new Setting("NFC", android.provider.Settings.ACTION_NFC_SETTINGS, "nfc", "tap to pay"),
                new Setting("Display & brightness", android.provider.Settings.ACTION_DISPLAY_SETTINGS, "display", "brightness", "dark mode", "screen", "font", "eye comfort", "refresh rate"),
                new Setting("Sounds & vibration", android.provider.Settings.ACTION_SOUND_SETTINGS, "sound", "volume", "ringtone", "vibration", "vibrate"),
                new Setting("Notifications", "android.settings.NOTIFICATION_SETTINGS", "notifications", "notification"),
                new Setting("Do not disturb", "android.settings.ZEN_MODE_SETTINGS", "do not disturb", "dnd", "silent", "focus"),
                new Setting("Battery", Intent.ACTION_POWER_USAGE_SUMMARY, "battery", "power", "charging"),
                new Setting("Battery saver", android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS, "battery saver", "power saving", "saver"),
                new Setting("Storage", android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "storage", "space", "memory"),
                new Setting("Apps", android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, "apps", "applications", "uninstall"),
                new Setting("Default apps", android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, "default apps", "default", "home app", "browser"),
                new Setting("Location", android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS, "location", "gps"),
                new Setting("Security", android.provider.Settings.ACTION_SECURITY_SETTINGS, "security", "lock", "fingerprint", "face", "password", "pin", "biometrics"),
                new Setting("Privacy", android.provider.Settings.ACTION_PRIVACY_SETTINGS, "privacy", "permissions"),
                new Setting("Accessibility", android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS, "accessibility"),
                new Setting("Accounts", android.provider.Settings.ACTION_SYNC_SETTINGS, "accounts", "sync", "google account"),
                new Setting("Date & time", android.provider.Settings.ACTION_DATE_SETTINGS, "date", "time", "clock", "timezone"),
                new Setting("Language & keyboard", android.provider.Settings.ACTION_LOCALE_SETTINGS, "language", "keyboard", "input"),
                new Setting("Developer options", android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "developer", "usb debugging", "debugging"),
                new Setting("About phone", android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS, "about", "phone info", "android version", "model"),
                new Setting("Cast", android.provider.Settings.ACTION_CAST_SETTINGS, "cast", "screen mirroring", "mirror"),
            };
            List<Setting> ok = new ArrayList<>();
            PackageManager pm = c.getPackageManager();
            for (Setting st : list) if (st.intent().resolveActivity(pm) != null) ok.add(st);
            all = ok;
            return all;
        }

        /** Up to two screens whose name or a keyword starts with what was typed (2+ letters). */
        static List<Setting> match(Context c, String q) {
            List<Setting> out = new ArrayList<>();
            String t = q.trim().toLowerCase(Locale.ROOT);
            if (t.length() < 2) return out;
            for (Setting st : all(c)) {
                boolean hit = st.title.toLowerCase(Locale.ROOT).startsWith(t);
                for (String w : st.words) if (w.startsWith(t) || (t.length() >= 4 && w.contains(t))) hit = true;
                if (hit) out.add(st);
                if (out.size() == 2) break;
            }
            return out;
        }
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

package com.phone.dialer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 10;
    private static final int REQ_ROLE = 11;

    private static final String[] PERMS = new String[]{
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECORD_AUDIO
    };

    /** Avatar palette, allocated once instead of once per contact and per call-log row. */
    private static final String[] PALETTES = {
            "#ff7a9c", "#ff3d71", "#6aa8ff", "#3a5bff", "#ffb347", "#ff7a1a",
            "#59e0b4", "#14a37f", "#c58bff", "#7b4dff", "#7fd8ff", "#2aa4d6",
            "#9be15d", "#00a86b", "#ffd166", "#ef8a17", "#8f9bff", "#4b5bd6"
    };

    /**
     * The avatar pairs, built once. There are only nine of them, but colorsFor()
     * ran for every contact on every read and for every call-log row, and handed
     * back a freshly allocated JSONArray (plus its two Strings) each time: a
     * thousand-contact address book meant a thousand throwaway arrays per
     * hydrate. The array is never mutated — it is put into a JSONObject and
     * serialised — so the same instance can be shared.
     */
    private static final JSONArray[] AVATAR_PAIRS = buildAvatarPairs();

    private static JSONArray[] buildAvatarPairs() {
        JSONArray[] pairs = new JSONArray[PALETTES.length / 2];
        for (int i = 0; i < pairs.length; i++) {
            JSONArray pair = new JSONArray();
            pair.put(PALETTES[i * 2]);
            pair.put(PALETTES[i * 2 + 1]);
            pairs[i] = pair;
        }
        return pairs;
    }

    /** Digits kept from a stored number when building the de-duplication key. */
    private static final Pattern NON_DIAL = Pattern.compile("[^0-9+]");

    private WebView web;

    /**
     * A number a tel: link brought in, waiting for the page to be able to show
     * it. The bridge call can only be made once the asset page has loaded, and
     * a second link can arrive while the first one is still queued.
     */
    private String pendingDial;
    /** Whether the asset page has finished loading, so evaluateJavascript lands. */
    private boolean pageReady;

    /**
     * A full re-read of contacts and the call log that is owed to the page but
     * that the page cannot run yet. evaluateJavascript() against a page that has
     * not finished loading is evaluated in the empty document and thrown away,
     * and two paths made that call without checking pageReady: the permission
     * result (which can arrive before a slow first load finishes — the read was
     * then lost and the app kept its empty lists until it was backgrounded and
     * resumed) and the renderer-restart path below (where the new page comes up
     * with nothing at all). Queued here and delivered by onPageFinished(), the
     * same rule deliverPendingDial() already follows for a number from a link.
     */
    private boolean pendingHydrate;

    /**
     * Whether contacts and the call log were readable the last time we looked,
     * tracked separately: access can be lost for one of them while the other is
     * still granted, and a single combined flag never noticed that.
     */
    private boolean hadContacts, hadCallLog;

    /**
     * A full hydrate — which reads the call log as well as the contacts — has
     * just been asked for, so the log-only refresh onResume() would run is the
     * same query a second time, milliseconds later.
     */
    private boolean logFresh;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        WebView view = null;
        try {
            view = createWebView();
        } catch (Throwable t) {
            /* Constructing a WebView throws when the system WebView is missing,
               disabled, or half-way through an update (WebViewFactory's
               missing-package and resource errors). That exception used to
               escape onCreate() and take the whole app down before anything
               was drawn. There is no page to show without a WebView, so say
               so instead of crashing. */
        }
        if (view == null) {
            /* Nothing was ever installed as the content view, so this activity
               was left on screen as an empty black window: no page, no keypad,
               nothing to tap — and because everything below this line is
               skipped, not even the permission request that could have brought
               the app to life. It sat in Recents until the user backed out of
               it. The renderer-restart path below already says the same thing
               and then closes; say it and close here too. */
            toast("Phone needs the Android System WebView — enable or reinstall it in Settings");
            finish();
            return;
        }
        setContentView(view);
        /* a number carried in by a tel: link is shown once the page is up */
        takeDialExtra(getIntent());

        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        rememberAccess();
        askPermissions();
    }

    /**
     * Builds the single page this app shows and installs it as {@link #web}.
     * Split out of onCreate() so a dead renderer can be replaced with a fresh
     * WebView instead of taking the whole process down (see onRenderProcessGone).
     */
    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView() {
        WebView w = new WebView(this);
        w.setBackgroundColor(Color.BLACK);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);

        pageReady = false;

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);

        w.addJavascriptInterface(new Bridge(), "PhoneNative");
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handoff(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handoff(request.getUrl().toString());
            }

            /**
             * The renderer is a separate process and the system kills it when
             * memory runs low — a large contact list and a live call at the same
             * time is enough. With no handler here the app process was taken down
             * with it, so the dialer simply vanished mid-use. Tear the dead view
             * down and put a fresh one in its place; only the page state (a
             * half-typed number) is lost.
             */
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (view != web) return;
                pageReady = true;
                deliverPendingDial();
                deliverPendingHydrate();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                /* A renderer can also die for a view this activity has already
                   thrown away — the one an earlier restart tore down. Nothing
                   on screen belonged to it, yet the code below still built a
                   replacement and installed it: the healthy page was replaced,
                   the number being typed on it was lost and every contact was
                   read again, for a view nobody was looking at. Decide first,
                   then clean up. */
                final boolean current = (view == web);
                destroyWebView(view);
                if (!current) return true;   /* handled: not the page on screen */
                web = null;
                /* The page that comes back is a brand-new document: the contact
                   list, the call log and the cached index all went with the dead
                   renderer, and nothing re-read them for the new one. The app
                   then showed "No Contacts" and "No Calls" until the process
                   was killed and started again. Ask for a full read once the
                   fresh page reports that it has loaded. */
                pendingHydrate = true;
                if (!isFinishing() && !isDestroyed()) {
                    /* Constructing a WebView throws when the system WebView is
                       missing, disabled or half-way through an update — the
                       exact case onCreate() already guards. Here it was called
                       bare, so a renderer that died *because* the WebView
                       package was broken came straight back through this
                       callback and took the process down from an exception
                       thrown inside a framework callback, which is the one
                       thing returning true here is meant to prevent. Build it
                       the way onCreate() does: with no page to be had, say so
                       and close. */
                    WebView fresh = null;
                    try {
                        fresh = createWebView();
                    } catch (Throwable t) {
                        web = null;
                    }
                    if (fresh != null) {
                        setContentView(fresh);
                    } else {
                        toast("Phone needs the Android System WebView — enable or reinstall it in Settings");
                        finish();
                    }
                }
                return true;   /* handled: do not let the system kill the app */
            }
        });

        w.loadUrl("file:///android_asset/index.html");
        web = w;
        return w;
    }

    /**
     * Asks the page to re-read contacts and the call log, now if it can and
     * otherwise as soon as it has finished loading.
     */
    private void requestHydrate() {
        if (web != null && pageReady) {
            try {
                web.evaluateJavascript("window.__phoneHydrate&&window.__phoneHydrate()", null);
            } catch (Exception ignored) { }
            return;
        }
        pendingHydrate = true;
    }

    /** Runs a read that was requested before the page could take it. */
    private void deliverPendingHydrate() {
        if (!pendingHydrate || !pageReady || web == null) return;
        pendingHydrate = false;
        try {
            web.evaluateJavascript("window.__phoneHydrate&&window.__phoneHydrate()", null);
        } catch (Exception ignored) { }
    }

    /**
     * Puts a number that arrived by tel: link on the keypad. Called when the page
     * finishes loading and again if a link arrives later; the field is cleared
     * before the call so a number is never handed over twice.
     */
    private void deliverPendingDial() {
        final String number = pendingDial;
        if (number == null || !pageReady || web == null) return;
        pendingDial = null;
        String arg;
        try {
            arg = JSONObject.quote(number);
        } catch (Exception e) {
            return;
        }
        try {
            web.evaluateJavascript("window.__phoneNumber&&window.__phoneNumber(" + arg + ")", null);
        } catch (Exception ignored) { }
    }

    /**
     * A tel: link that arrives while the app is already open comes here — the
     * activity is singleTop, so the page and any half-typed number survive it.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        takeDialExtra(intent);
        deliverPendingDial();
    }

    /** Remembers the number a DIAL intent carried, if any. */
    private void takeDialExtra(Intent intent) {
        if (intent == null) return;
        try {
            String number = intent.getStringExtra(DialerActivity.EXTRA_DIAL);
            if (number != null && !number.trim().isEmpty()) pendingDial = number.trim();
        } catch (Exception ignored) { }
    }

    /** Tears a WebView down without leaving its renderer, bridge or parent behind. */
    private void destroyWebView(WebView w) {
        if (w == null) return;
        try {
            w.stopLoading();
            w.removeJavascriptInterface("PhoneNative");
            w.setWebViewClient(null);
            ViewParent parent = w.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(w);
            w.destroy();
        } catch (Exception ignored) { }
    }

    private void askPermissions() {
        List<String> missing = new ArrayList<>();
        for (String p : PERMS) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERMS || web == null) return;
        /* the page was reloaded after every permission dialog, throwing away the
           number that had already been typed even when nothing was granted;
           only reload when there is actually new data to read */
        /* "gained" used to mean "any of the four permissions came back granted",
           and the four include CALL_PHONE and RECORD_AUDIO. Granting only those
           — or only one of them — re-read both providers, re-parsed the JSON,
           re-sorted every contact and rebuilt every list, none of which can
           change without contacts or call-log access. */
        /* Re-read contacts and the call log in place. A full reload replayed
           every entrance animation and threw away a number that had already
           been typed — which is exactly what happened on the first launch,
           because the dialog result arrives after the page has loaded. */
        if (accessChanged()) {
            requestHydrate();
            /* requestHydrate() re-reads the contacts *and* the call log.
               onResume() runs again right after this result arrives and, with
               the access flags already remembered, took its "nothing changed"
               branch — which asks for the call log on its own. That was the
               same provider query, the same JSON, the same parse and the same
               Recents re-render a second time, milliseconds after the full
               read had already done all of it. Skip it for that one resume. */
            logFresh = true;
        }
        /* onResume() runs again right after this result arrives; without this the
           very first grant re-read the whole contact list twice */
        rememberAccess();
    }

    /**
     * The WebView was never paused or torn down. While the app sat in the
     * background its page timers kept firing and its renderer kept a graphics
     * context alive; on every destroy the WebView, its renderer and the
     * JavaScript bridge (which holds this Activity) were leaked.
     */
    @Override
    protected void onPause() {
        if (web != null) {
            web.onPause();
            web.pauseTimers();
        }
        super.onPause();
    }

    private boolean hasContacts() {
        return checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCallLog() {
        return checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
    }

    /** Records what is readable now, so the next resume can tell what changed. */
    private void rememberAccess() {
        hadContacts = hasContacts();
        hadCallLog = hasCallLog();
    }

    /** Access was granted or taken away since the last time we looked. */
    private boolean accessChanged() {
        return hadContacts != hasContacts() || hadCallLog != hasCallLog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.resumeTimers();
            web.onResume();
        }
        /* Granting contacts or call-log access from the system Settings screen and
           coming back left the app showing its empty states until it was killed
           and reopened: nothing re-read the data when it came back to the front.
           Losing it was worse — the list stayed on screen, and a row from it
           still dialled a number the app could no longer read. Re-read on any
           change, in either direction. */
        if (web != null) {
            if (accessChanged()) {
                requestHydrate();
            } else if (pageReady && !logFresh) {
                /* The call log was read at boot and when the permission state
                   changed, and never again. A call that arrived while the app
                   was open — or one placed from any other app — was missing
                   from Recents until the process was killed and reopened, and
                   a missed call is the one row a dialer must not lose. Ask for
                   the log alone on the way back to the front: one provider
                   query and forty rows, where re-reading contacts as well
                   would be the expensive half of the hydrate for no reason. */
                /* Guarded like every other bridge call in this class. The
                   renderer can be gone between the null check above and this
                   line — it died while the app was in the background, or the
                   view was destroyed under us — and an exception thrown out of
                   onResume takes the app down on the way back to the
                   foreground, which is the one moment a dialer must survive.
                   A full hydrate is queued instead: it is the read this call
                   was only the cheap half of. */
                try {
                    web.evaluateJavascript("window.__phoneLog&&window.__phoneLog()", null);
                } catch (Exception e) {
                    requestHydrate();
                }
            }
        }
        /* the flag only ever covers the resume that follows the permission
           result that set it */
        logFresh = false;
        rememberAccess();
    }

    /**
     * The WebView was never told when the system ran short of memory. Its
     * renderer keeps a compiled-script cache, decoded images and a code cache
     * alive for the life of the process, and on a low-memory device that is
     * what gets the renderer killed — the one crash onRenderProcessGone exists
     * to recover from. Only the levels that mean the page is no longer on
     * screen are answered, so nothing visible is ever dropped and re-decoded.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level != TRIM_MEMORY_BACKGROUND && level != TRIM_MEMORY_MODERATE
                && level != TRIM_MEMORY_COMPLETE) {
            return;
        }
        if (web == null) return;
        try {
            web.freeMemory();
        } catch (Exception ignored) { }
    }

    @Override
    protected void onDestroy() {
        pageReady = false;
        destroyWebView(web);
        web = null;
        super.onDestroy();
    }

    /**
     * tel:/sms:/mailto: links leave the app for the system dialer; every other
     * URL is refused. This WebView only ever shows the bundled asset page, so
     * returning false for anything else let a link or a redirect load a web page
     * (or, with file access enabled, another file:// path) over the dialer, with
     * no way back except relaunching the app.
     */
    private boolean handoff(String url) {
        if (url == null) return true;
        if (url.startsWith("tel:") || url.startsWith("sms:") || url.startsWith("smsto:")
                || url.startsWith("mailto:")) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) { }
            return true;
        }
        return !url.startsWith("file:///android_asset/") && !url.startsWith("about:");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /* ------------------------------------------------------------------ */
    /*  Bridge exposed to the HTML as window.PhoneNative                   */
    /* ------------------------------------------------------------------ */
    private class Bridge {

        @JavascriptInterface
        public String contacts() {
            if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                return "[]";
            Map<String, JSONObject> byKey = new LinkedHashMap<>();
            Cursor c = null;
            boolean failed = false;
            try {
                c = getContentResolver().query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{
                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER
                        }, null, null, null);
                if (c != null) {
                    while (c.moveToNext()) {
                        String name = c.getString(0);
                        String number = c.getString(1);
                        /* a Phone row can exist with no number at all — some
                           sync adapters write one — and it was kept: the page
                           drew a contact with a blank subtitle that dialled
                           nothing when it was tapped, because placeCall() has
                           no digits to hand to telecom. There is no such thing
                           as a dialable contact without a number. */
                        if (number == null || number.trim().isEmpty()) continue;
                        /* A Phone row whose DISPLAY_NAME comes back null or blank
                           — some providers answer that for a number saved without
                           a name — was dropped from the list altogether: the
                           contact existed, was dialable, and simply never
                           appeared. callLog() has always fallen back to the
                           number for exactly this case; do the same here. */
                        if (name == null || name.trim().isEmpty()) name = number;
                        String key = name + "|" + NON_DIAL.matcher(number).replaceAll("");
                        if (byKey.containsKey(key)) continue;
                        JSONObject o = new JSONObject();
                        o.put("n", name);
                        o.put("t", number);
                        o.put("c", colorsFor(name));
                        byKey.put(key, o);
                    }
                } else {
                    failed = true;
                }
            } catch (Exception ignored) {
                failed = true;
            } finally {
                if (c != null) c.close();
            }
            /* A read that blew up is not an empty address book. Answering "[]"
               here told the page the device had no contacts, and the page then
               replaced the list it was already showing with nothing — the rows
               vanished and every one of them stopped being dialable until the
               next successful read. "null" parses to null, which the page
               already treats as "keep what you have". */
            if (failed) return "null";
            List<JSONObject> list = new ArrayList<>(byKey.values());
            Collections.sort(list, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    return a.optString("n").compareToIgnoreCase(b.optString("n"));
                }
            });
            return new JSONArray(list).toString();
        }

        @JavascriptInterface
        public String callLog() {
            if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED)
                return "[]";
            JSONArray out = new JSONArray();
            /* one "now" and one scratch Calendar for the whole query, not a fresh
               pair of them for every row */
            Calendar now = Calendar.getInstance();
            Calendar rowCal = Calendar.getInstance();
            Cursor c = null;
            boolean failed = false;
            try {
                c = getContentResolver().query(
                        CallLog.Calls.CONTENT_URI,
                        new String[]{CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                                CallLog.Calls.TYPE, CallLog.Calls.DATE},
                        null, null, CallLog.Calls.DATE + " DESC");
                int n = 0;
                if (c != null) {
                    /* the bound was tested after the cursor had already moved,
                       so every refresh read one row past the forty it keeps.
                       Test it first. */
                    while (n < 40 && c.moveToNext()) {
                        String number = c.getString(0);
                        String name = c.getString(1);
                        int type = c.getInt(2);
                        long when = c.getLong(3);
                        /* same as contacts(): a row with no number drew an
                           empty Recents entry that dialled nothing */
                        if (number == null || number.trim().isEmpty()) continue;
                        if (name == null || name.trim().isEmpty()) name = number;

                        String kind = type == CallLog.Calls.MISSED_TYPE ? "missed"
                                : type == CallLog.Calls.OUTGOING_TYPE ? "out" : "in";
                        String label = type == CallLog.Calls.MISSED_TYPE ? "Missed call"
                                : type == CallLog.Calls.OUTGOING_TYPE ? "Outgoing" : "Incoming";

                        JSONObject person = new JSONObject();
                        person.put("n", name);
                        person.put("t", number);
                        person.put("c", colorsFor(name));

                        JSONObject row = new JSONObject();
                        row.put("p", person);
                        row.put("when", shortTime(when, now, rowCal));
                        row.put("kind", kind);
                        row.put("label", label);
                        out.put(row);
                        n++;
                    }
                } else {
                    failed = true;
                }
            } catch (Exception ignored) {
                failed = true;
            } finally {
                if (c != null) c.close();
            }
            /* same rule as contacts(): a failed read must not look like an empty
               call log, or the Recents list on screen is wiped by a transient
               provider error */
            if (failed) return "null";
            return out.toString();
        }

        @JavascriptInterface
        public void dial(final String number) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String clean = number == null ? "" : number.trim();
                    if (clean.isEmpty()) return;
                    /* Uri.fromParts() left a '#' in the number as a URI fragment,
                       so *123# and *#06# were handed to telecom truncated at the '#'. */
                    Uri uri = Uri.parse("tel:" + Uri.encode(clean, "+*"));
                    boolean canCall = checkSelfPermission(Manifest.permission.CALL_PHONE)
                            == PackageManager.PERMISSION_GRANTED;
                    Intent i = new Intent(canCall ? Intent.ACTION_CALL : Intent.ACTION_DIAL, uri);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        toast("Could not start the call");
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean isDefaultDialer() {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager rm = getSystemService(RoleManager.class);
                return rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER);
            }
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            return tm != null && getPackageName().equals(tm.getDefaultDialerPackage());
        }

        @JavascriptInterface
        public void requestDefaultDialer() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (Build.VERSION.SDK_INT >= 29) {
                            RoleManager rm = getSystemService(RoleManager.class);
                            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)
                                    && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), REQ_ROLE);
                                return;
                            }
                        }
                        Intent i = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
                        i.putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
                        startActivity(i);
                    } catch (Exception e) {
                        toast("Set Phone as the default dialer in Settings");
                    }
                }
            });
        }

        /**
         * The clipboard, for the page's Paste item. navigator.clipboard is not
         * implemented by the Android WebView, so that item asked for the clip,
         * threw, and did nothing at all inside the app. Read it here instead;
         * the page only calls this from the tap that opened the menu.
         */
        @JavascriptInterface
        public String clipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip()) return "";
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return "";
                CharSequence text = clip.getItemAt(0).coerceToText(MainActivity.this);
                return text == null ? "" : text.toString();
            } catch (Exception e) {
                return "";
            }
        }

        /* A version() bridge method used to sit here. Nothing in index.html ever
           asked PhoneNative for a version, so it was an unused entry point on the
           interface the page can reach; removed rather than left to rot. */
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private static final ThreadLocal<SimpleDateFormat> FMT_TIME =
            new ThreadLocal<SimpleDateFormat>() {
                @Override protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("h:mm a", Locale.US);
                }
            };
    private static final ThreadLocal<SimpleDateFormat> FMT_MD =
            new ThreadLocal<SimpleDateFormat>() {
                @Override protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("MMM d", Locale.US);
                }
            };
    private static final ThreadLocal<SimpleDateFormat> FMT_MDY =
            new ThreadLocal<SimpleDateFormat>() {
                @Override protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("MMM d, yyyy", Locale.US);
                }
            };
    /** One Date per thread, reused: shortTime() ran for every call-log row and
     *  allocated a fresh Date for each of them — forty per refresh. */
    private static final ThreadLocal<Date> DATE_SCRATCH =
            new ThreadLocal<Date>() {
                @Override protected Date initialValue() { return new Date(0); }
            };

    /**
     * Same output as before, without building four formatters for every call-log
     * row — and without building two Calendars for each of them either, which
     * used to mean eighty of them per forty-row refresh. The scratch Calendar is
     * now owned by the caller and reused across the whole query.
     */
    private static String shortTime(long millis, Calendar now, Calendar d) {
        d.setTimeInMillis(millis);
        boolean thisYear = d.get(Calendar.YEAR) == now.get(Calendar.YEAR);
        Date at = DATE_SCRATCH.get();
        at.setTime(millis);
        if (thisYear && d.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) {
            return FMT_TIME.get().format(at);
        }
        return (thisYear ? FMT_MD.get() : FMT_MDY.get()).format(at);
    }

    private static JSONArray colorsFor(String name) {
        /* & 0x7fffffff, not Math.abs: abs(Integer.MIN_VALUE) is still negative and threw. */
        int h = (name == null ? 0 : name.hashCode()) & 0x7fffffff;
        return AVATAR_PAIRS[h % AVATAR_PAIRS.length];
    }
}

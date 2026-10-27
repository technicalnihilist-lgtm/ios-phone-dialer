package com.phone.dialer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.role.RoleManager;
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
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 10;
    private static final int REQ_ROLE = 11;

    private static final String[] PERMS = new String[]{
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECORD_AUDIO
    };

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "PhoneNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handoff(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handoff(request.getUrl().toString());
            }
        });

        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);

        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        askPermissions();
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
        if (code == REQ_PERMS) web.reload();
    }

    /** tel:/sms:/mailto: links leave the app for the system dialer. */
    private boolean handoff(String url) {
        if (url == null) return false;
        if (url.startsWith("tel:") || url.startsWith("sms:") || url.startsWith("smsto:")
                || url.startsWith("mailto:")) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) { }
            return true;
        }
        return false;
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
                        if (name == null || number == null) continue;
                        String key = name + "|" + number.replaceAll("[^0-9+]", "");
                        if (byKey.containsKey(key)) continue;
                        JSONObject o = new JSONObject();
                        o.put("n", name);
                        o.put("t", number);
                        o.put("c", colorsFor(name));
                        byKey.put(key, o);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }
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
            Cursor c = null;
            try {
                c = getContentResolver().query(
                        CallLog.Calls.CONTENT_URI,
                        new String[]{CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                                CallLog.Calls.TYPE, CallLog.Calls.DATE},
                        null, null, CallLog.Calls.DATE + " DESC");
                int n = 0;
                if (c != null) {
                    while (c.moveToNext() && n < 40) {
                        String number = c.getString(0);
                        String name = c.getString(1);
                        int type = c.getInt(2);
                        long when = c.getLong(3);
                        if (number == null) continue;
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
                        row.put("when", shortTime(when));
                        row.put("kind", kind);
                        row.put("label", label);
                        out.put(row);
                        n++;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }
            return out.toString();
        }

        @JavascriptInterface
        public void dial(final String number) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String clean = number == null ? "" : number.trim();
                    if (clean.isEmpty()) return;
                    Uri uri = Uri.fromParts("tel", clean, null);
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

        @JavascriptInterface
        public String version() {
            return "2.0";
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private static String shortTime(long millis) {
        Date d = new Date(millis);
        Date now = new Date();
        boolean today = new SimpleDateFormat("yyyyDDD", Locale.US).format(d)
                .equals(new SimpleDateFormat("yyyyDDD", Locale.US).format(now));
        if (today) return new SimpleDateFormat("h:mm a", Locale.US).format(d);
        boolean thisYear = new SimpleDateFormat("yyyy", Locale.US).format(d)
                .equals(new SimpleDateFormat("yyyy", Locale.US).format(now));
        return new SimpleDateFormat(thisYear ? "MMM d" : "MMM d, yyyy", Locale.US).format(d);
    }

    private static JSONArray colorsFor(String name) {
        String[] palettes = {
                "#ff7a9c", "#ff3d71", "#6aa8ff", "#3a5bff", "#ffb347", "#ff7a1a",
                "#59e0b4", "#14a37f", "#c58bff", "#7b4dff", "#7fd8ff", "#2aa4d6",
                "#9be15d", "#00a86b", "#ffd166", "#ef8a17", "#8f9bff", "#4b5bd6"
        };
        int h = Math.abs(name == null ? 0 : name.hashCode());
        int i = (h % (palettes.length / 2)) * 2;
        JSONArray a = new JSONArray();
        a.put(palettes[i]);
        a.put(palettes[i + 1]);
        return a;
    }
}

package com.phone.dialer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Transparent trampoline that makes Phone eligible for the default-dialer role.
 *
 * The system requires an activity that answers ACTION_DIAL before an app may
 * hold the role, which is the only reason this one exists. It used to finish
 * without looking at the intent at all, so the number a tel: link carried was
 * thrown away: tapping a phone number in a message, a browser or a contact card
 * closed this screen and opened nothing, and the caller had to retype the number
 * by hand. Hand the number to the real screen instead.
 */
public class DialerActivity extends Activity {

    /** Number carried by a DIAL intent, for MainActivity to put on the keypad. */
    static final String EXTRA_DIAL = "com.phone.dialer.DIAL_NUMBER";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String number = numberFrom(getIntent());
        /* start the dialer before finishing this trampoline: finishing first can
           tear the task down under the activity that was just launched */
        try {
            Intent out = new Intent(this, MainActivity.class);
            /* REORDER_TO_FRONT, not CLEAR_TOP. CLEAR_TOP reuses the running
               MainActivity by closing every activity above it in the task — and
               the one activity that can be above it is CallActivity, the in-call
               screen. So tapping a number in a message, a browser or a contact
               card *during a call* tore that screen down: the call stayed up,
               but this app is excludeFromRecents and posts no ongoing-call
               notification, so its mute, speaker, hold, keypad and hang-up were
               gone and the call could only be ended from the other side. The
               screen also has no way back of its own — only telecom's "return to
               call", which this app's InCallService now answers. Bring the
               dialer to the front of the task instead and leave what is under it
               alive: the number still arrives through onNewIntent(), and when
               the call ends finishIfOpen() closes the in-call screen as usual. */
            out.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (number != null && !number.isEmpty()) out.putExtra(EXTRA_DIAL, number);
            startActivity(out);
        } catch (Exception ignored) {
            /* nothing to show: do not leave a dead trampoline on screen */
        }
        finish();
    }

    /**
     * The dialled number from a tel: URI, or null when the intent carries none —
     * ACTION_DIAL with no data (a plain "open the dialer" request) is legitimate.
     */
    private static String numberFrom(Intent intent) {
        if (intent == null) return null;
        Uri data = intent.getData();
        if (data == null) return null;
        /* A tel: URI is opaque, and a "#" in it is part of the number, not a
           fragment: *123# runs a code, #31# hides the caller's ID, *#06# shows
           the IMEI. Uri does not know that — it splits the fragment at the
           first "#" for every URI, opaque or not — so the scheme-specific part
           of "tel:*123#" is "*123", and the code arrived on the keypad with
           its last character missing and dialled something else entirely.
           Read the URI as it was written instead (getDataString() keeps the
           whole string) and take everything after the scheme: the escapes are
           decoded below, the "#" survives, and the "//" of a "tel://" form is
           stripped by the page along with every other character that cannot
           belong to a number. */
        String number = null;
        try {
            number = intent.getDataString();
        } catch (Exception e) {
            number = null;
        }
        String scheme = null;
        try {
            scheme = data.getScheme();
        } catch (Exception ignored) { }
        if (number != null && scheme != null && number.length() > scheme.length() + 1
                && number.charAt(scheme.length()) == ':'
                && number.regionMatches(true, 0, scheme, 0, scheme.length())) {
            number = number.substring(scheme.length() + 1);
        } else {
            /* no readable URI string: fall back to what Uri itself parsed, which
               is the same answer for every URI that carries no "#" */
            try {
                number = data.getSchemeSpecificPart();
            } catch (Exception e) {
                return null;
            }
        }
        if (number == null) return null;
        /* A tel: URI built by another app is almost always percent-encoded —
           Uri.encode() writes "*" as %2A and "#" as %23 — and the
           scheme-specific part is handed over still encoded. The keypad keeps
           only digits, "+", "*" and "#", so "tel:%2A123%23" reached it as
           "212323": the "%" was dropped as junk, "2A" lost its letter and the
           "23" of "%23" was kept as digits. A USSD code therefore arrived on
           the keypad as a completely different, ordinary number, and tapping
           Call dialled that number instead of running the code. Decode the
           escapes before anything reads the digits. Uri.decode() is on every
           API level this app supports. */
        try {
            String decoded = Uri.decode(number);
            if (decoded != null) number = decoded;
        } catch (Exception ignored) {
            /* malformed escape: keep the part exactly as it arrived */
        }
        /* "tel:12345;ext=6" and "tel:12345;postd=1" style parameters are not part
           of the number as far as the keypad is concerned */
        int semi = number.indexOf(';');
        if (semi >= 0) number = number.substring(0, semi);
        return number.trim();
    }
}

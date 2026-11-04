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
            out.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
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
        String number;
        try {
            number = data.getSchemeSpecificPart();
        } catch (Exception e) {
            return null;
        }
        if (number == null) return null;
        /* "tel:12345;ext=6" and "tel:12345#;postd=1" style suffixes are not part
           of the number as far as the keypad is concerned */
        int semi = number.indexOf(';');
        if (semi >= 0) number = number.substring(0, semi);
        return number.trim();
    }
}

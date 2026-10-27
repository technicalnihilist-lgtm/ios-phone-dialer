package com.phone.dialer;

import android.app.Activity;
import android.os.Bundle;

/** Transparent trampoline that makes Phone eligible for the default-dialer role. */
public class DialerActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        finish();
    }
}

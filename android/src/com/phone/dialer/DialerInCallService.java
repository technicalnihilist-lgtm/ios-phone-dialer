package com.phone.dialer;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.InCallService;

/**
 * Owns the in-call experience. This service is only bound by the system when
 * Phone holds the default-dialer role, which is what makes the speaker,
 * mute, hold and record controls work on a live call.
 */
public class DialerInCallService extends InCallService {

    public static Call current;
    public static DialerInCallService instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        current = call;

        String number = "";
        try {
            if (call.getDetails() != null && call.getDetails().getHandle() != null) {
                number = call.getDetails().getHandle().getSchemeSpecificPart();
            }
        } catch (Exception ignored) { }

        Intent i = new Intent(this, CallActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("number", number);
        try {
            startActivity(i);
        } catch (Exception ignored) { }
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        if (call == current) current = null;
        CallActivity.finishIfOpen();
    }
}

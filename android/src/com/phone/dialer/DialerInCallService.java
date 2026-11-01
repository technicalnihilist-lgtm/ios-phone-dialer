package com.phone.dialer;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.InCallService;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Owns the in-call experience. This service is only bound by the system when
 * Phone holds the default-dialer role, which is what makes the speaker,
 * mute, hold and record controls work on a live call.
 */
public class DialerInCallService extends InCallService {

    public static Call current;
    public static DialerInCallService instance;

    /** Every call the system still has open, not just the most recent one. */
    private final Set<Call> live = Collections.newSetFromMap(new IdentityHashMap<Call, Boolean>());

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        live.clear();
        instance = null;
        current = null;          /* was left pointing at a dead Call after the service died */
    }

    /** The dialled number behind a Call, or "" when the handle is not readable. */
    private static String numberOf(Call call) {
        String number = "";
        try {
            if (call != null && call.getDetails() != null && call.getDetails().getHandle() != null) {
                number = call.getDetails().getHandle().getSchemeSpecificPart();
            }
        } catch (Exception ignored) { }
        return number == null ? "" : number;
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        live.add(call);
        current = call;

        String number = numberOf(call);

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
        live.remove(call);
        if (call == current) {
            /* with a second call still up, the in-call screen belongs to that
               one now — it used to be closed as if every call had ended */
            current = live.isEmpty() ? null : live.iterator().next();
            /* the screen was never told: after the second call hung up the header
               still showed the caller that had just gone, not the one still up */
            if (current != null) CallActivity.showIfOpen(numberOf(current));
        }
        if (live.isEmpty()) CallActivity.finishIfOpen();
    }
}

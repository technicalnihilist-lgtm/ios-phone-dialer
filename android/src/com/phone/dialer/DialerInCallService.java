package com.phone.dialer;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the in-call experience. This service is only bound by the system when
 * Phone holds the default-dialer role, which is what makes the speaker,
 * mute, hold and record controls work on a live call.
 */
public class DialerInCallService extends InCallService {

    /* Written on the telecom binder thread, read on the UI thread (and the
       reverse). Without volatile the in-call screen could read a stale null —
       or a Call that had already been replaced — and paint nothing at all. */
    public static volatile Call current;
    public static volatile DialerInCallService instance;

    /**
     * The audio route telecom last reported. The in-call screen used to read
     * AudioManager.isSpeakerphoneOn() instead, which does not follow a route set
     * through setAudioRoute(): the speaker button un-highlighted itself about a
     * second after being tapped, on every call, whenever the app held the
     * default-dialer role.
     */
    public static volatile int audioRoute = CallAudioState.ROUTE_EARPIECE;

    /**
     * Whether the microphone was muted by the last request the in-call screen
     * made. That screen keeps its own copy for the button, but it is re-created
     * on a display-size or font-size change (and by the system under memory
     * pressure), and the new instance always started with muted = false: the
     * button claimed a call that was muted was not, and — worse —
     * clearMuteOnService() then decided there was nothing to undo when the next
     * call arrived, so that call came up muted behind a button that said
     * "mute". The state lives with the service, which outlives the screen.
     */
    public static volatile boolean muted;

    /**
     * Every call the system still has open, not just the most recent one, kept in
     * the order they were added. This used to be a Set built on an IdentityHashMap,
     * whose iteration order is arbitrary: when the call on screen ended and another
     * one was still up, the screen fell back to whichever call the hash table
     * happened to reach first rather than to the one added last.
     */
    private final List<Call> live = new ArrayList<Call>();

    /** Removes by identity — Call does not define equals(), and we want no surprises. */
    private boolean drop(Call call) {
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i) == call) { live.remove(i); return true; }
        }
        return false;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        /* audioRoute started at earpiece and was only ever written by
           onCallAudioStateChanged(), so the in-call screen read "earpiece" until
           telecom next *changed* the route. This service is often bound in the
           middle of a call — it was re-created, or the call was already up and on
           speaker — and the speaker button then showed itself as off over a call
           that was on speaker, and the first tap sent ROUTE_EARPIECE: the button
           in the wrong state also asked for the wrong thing. Read the route that
           is actually in force when the service comes up. */
        try {
            CallAudioState state = getCallAudioState();
            if (state != null) audioRoute = state.getRoute();
        } catch (Exception ignored) { }
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState state) {
        super.onCallAudioStateChanged(state);
        if (state != null) audioRoute = state.getRoute();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        live.clear();
        instance = null;
        current = null;          /* was left pointing at a dead Call after the service died */
        audioRoute = CallAudioState.ROUTE_EARPIECE;
        muted = false;           /* the line it described is gone with the service */
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
        /* a Set ignored a repeat add for free; a List would keep the duplicate and
           then hand it back as "the most recent call" after it had ended */
        if (!live.contains(call)) live.add(call);
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
        drop(call);
        if (call == current) {
            /* with a second call still up, the in-call screen belongs to that
               one now — it used to be closed as if every call had ended */
            current = live.isEmpty() ? null : live.get(live.size() - 1);
            /* the screen was never told: after the second call hung up the header
               still showed the caller that had just gone, not the one still up */
            if (current != null) CallActivity.showIfOpen(numberOf(current));
        }
        if (live.isEmpty()) CallActivity.finishIfOpen();
    }
}

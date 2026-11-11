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

    /**
     * Every access to {@link #live} is made while holding its own monitor. The
     * list is written from the telecom binder thread (onCallAdded,
     * onCallRemoved) and cleared from the main thread in onDestroy(), and an
     * ArrayList is not safe against that: a call arriving as the service was
     * being torn down could add to the list while it was being emptied, which
     * leaves the array in an inconsistent state — a size that no longer matches
     * its contents, an index that reads past the end, or a call that survives a
     * clear and is then handed back as "the most recent call" by a later
     * onCallRemoved(). The fields beside this one are volatile for the same
     * reason; the list needs a lock, not just visibility.
     */
    private final Object liveLock = new Object();

    /** Removes by identity — Call does not define equals(), and we want no surprises. */
    private boolean drop(Call call) {
        synchronized (liveLock) {
            for (int i = 0; i < live.size(); i++) {
                if (live.get(i) == call) { live.remove(i); return true; }
            }
        }
        return false;
    }

    /** Whether any call is still up, read under the same lock as every write. */
    private boolean noCallsLeft() {
        synchronized (liveLock) { return live.isEmpty(); }
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

    /**
     * The system asking for the in-call screen — the "return to call" chip, a
     * headset button, TelecomManager.showInCallScreen(). Nothing was ever done
     * with it, and this screen is excludeFromRecents with no ongoing-call
     * notification behind it: once the user pressed Home mid-call there was no
     * way back to the mute, speaker, keypad or even the hang-up button, and the
     * call could only be ended from the other side. Hand it to the screen.
     */
    @Override
    public void onBringToForeground(boolean showDialpad) {
        super.onBringToForeground(showDialpad);
        Call call = current;
        if (call == null) return;
        CallActivity.bringToFront(this, numberOf(call), showDialpad);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        synchronized (liveLock) { live.clear(); }
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
        synchronized (liveLock) {
            if (!live.contains(call)) live.add(call);
        }
        current = call;

        String number = numberOf(call);

        Intent i = new Intent(this, CallActivity.class);
        /* REORDER_TO_FRONT, not CLEAR_TOP, for the same reason the trampoline
           uses it: CLEAR_TOP reuses the running in-call screen by closing every
           activity above it — and the activity above it can be the dialer. A
           number tapped in a message during a call brings MainActivity to the
           front (see DialerActivity), and the next call to arrive then cleared
           it off the stack: the WebView, its lists and any half-typed number
           went with it and had to be read again from scratch. Bring the call
           screen to the front and leave what is under it alone; onNewIntent()
           still gets the new caller, and the call screen keeps its own state
           (mute, hold, keypad) across the reorder. */
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
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
            synchronized (liveLock) {
                current = live.isEmpty() ? null : live.get(live.size() - 1);
            }
            /* the screen was never told: after the second call hung up the header
               still showed the caller that had just gone, not the one still up */
            if (current != null) CallActivity.showIfOpen(this, numberOf(current));
        }
        if (noCallsLeft()) {
            /* The call session is over, and with it the two pieces of state the
               service was holding for it. Telecom drops the mute and the audio
               route when the last call goes, but these fields are this app's own
               record of what it last asked for, and they outlived the call: the
               next call then opened with its mute button reading "unmute" over a
               line nobody had muted — so the first tap on it only put the label
               back — and with the speaker button lit for a route the new call was
               not on. onDestroy() already resets both for exactly this reason;
               do it at the moment the state they describe stops existing. */
            muted = false;
            audioRoute = CallAudioState.ROUTE_EARPIECE;
            CallActivity.finishIfOpen();
        }
    }
}

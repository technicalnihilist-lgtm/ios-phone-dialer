package com.phone.dialer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

public class CallActivity extends Activity {

    /* set on the UI thread, read from the telecom binder thread in
       showIfOpen()/finishIfOpen(); volatile so that read cannot go stale */
    private static volatile CallActivity open;

    private TextView nameView, statusView;
    private ImageView muteBtn, speakerBtn, holdBtn, recordBtn;
    private TextView muteLabel, speakerLabel, holdLabel, recordLabel;
    private View keypadPanel;

    private AudioManager audio;
    private MediaRecorder recorder;
    /**
     * The file the current recording is being written to. stop() throws when it
     * has no valid data to finalise, and the file prepare() already created is
     * then left behind empty — keeping the handle is what lets it be removed.
     */
    private File recordFile;
    private boolean recording;
    private boolean muted;
    private boolean onHold;
    /** When hold was last asked for, so a request that never took is not undone mid-flight. */
    private long holdAskedAt;
    private long startedAt;
    private boolean answered;
    /** Number currently painted in the call header, so a repeated intent does not reset the timer. */
    private String shownNumber = "";

    /**
     * One background thread for contact lookups. The lookup used to run on the
     * UI thread from onCreate() and onNewIntent(), so a slow or large contacts
     * provider stalled the in-call screen while it was being opened.
     */
    private static final java.util.concurrent.ExecutorService LOOKUPS =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /**
     * Recording teardown gets a thread of its own. stop() blocks while it
     * finalises the MP4 — on some encoders for several hundred milliseconds — and
     * it was queued on LOOKUPS, the same single thread the caller-name lookup
     * runs on: hanging up and then receiving the next call put that call's
     * PhoneLookup behind the old recording's teardown, so the new caller's name
     * appeared late over a screen that had already opened.
     */
    private static final java.util.concurrent.ExecutorService RECORDER =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Guards against an out-of-order lookup painting the previous caller's name. */
    private int lookupSeq;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                refreshStatus();
            } catch (Exception ignored) {
                /* refreshStatus() returns early when a binder read fails, and the
                   re-post below used to be skipped with it: one exception from
                   getState() stopped the ticker for good, so the counter froze
                   and the screen never closed when the call ended. */
            }
            /* ... and the re-post itself used to run even after refreshStatus()
               had asked the ticker to stop, undoing removeCallbacks() and waking
               the main thread every 500 ms behind a finished screen. */
            if (isFinishing() || isDestroyed()) return;
            /* Every pass makes two binder calls, one into telecom and one into the
               audio service. Only a call that is still connecting needs a fast
               poll; once it is up and counting, one pass per second is enough.
               The delay is lined up with the second boundary so the counter
               never skips a value the way a plain 1000 ms poll would. */
            long delay = 500;
            if (answered) delay = 1000 - (System.currentTimeMillis() % 1000) + 5;
            ticker.postDelayed(this, delay);
        }
    };

    /**
     * A single pending DTMF stop. Each key used to post its own stop 160 ms
     * later, so pressing two keys quickly had the first key's stop cut the
     * second tone off almost immediately.
     */
    private final Runnable stopTone = new Runnable() {
        @Override
        public void run() {
            try {
                Call call = DialerInCallService.current;
                if (call != null) call.stopDtmfTone();
            } catch (Exception ignored) { }
        }
    };

    /** Repaints the header when the call on screen changes without a new intent. */
    public static void showIfOpen(final String number) {
        final CallActivity a = open;
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    /* the screen now belongs to a different call than the one the
                       clock was started for: drop the answer state so the ticker
                       takes the remaining call's own connect time instead of
                       counting on from the call that just ended */
                    a.answered = false;
                    a.startedAt = 0;
                    /* the mute, hold and recording state on screen belonged to
                       the call that just ended. onNewIntent() already drops
                       them when a new call takes the screen; this path — the
                       screen falling back to a call that is still up — did not,
                       so the labels kept the finished call's state and a
                       recording started on it kept running, unwatched, under
                       the other caller's name. */
                    a.onHold = false;
                    a.clearMuteOnService();
                    a.muted = false;
                    if (a.keypadPanel != null) a.keypadPanel.setVisibility(View.GONE);
                    if (a.holdBtn != null) { a.holdBtn.setSelected(false); a.holdLabel.setText("hold"); }
                    if (a.muteBtn != null) { a.muteBtn.setSelected(false); a.muteLabel.setText("mute"); }
                    a.releaseRecorderOffThread();
                    a.showCaller(number);
                } catch (Exception ignored) { }
            }
        });
    }

    public static void finishIfOpen() {
        if (open != null) {
            open.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        open.releaseRecorderOffThread();
                        open.finish();
                    } catch (Exception ignored) { }
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        open = this;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(R.layout.activity_call);

        nameView = findViewById(R.id.call_name);
        statusView = findViewById(R.id.call_status);
        keypadPanel = findViewById(R.id.keypad_panel);

        muteBtn = findViewById(R.id.btn_mute);
        speakerBtn = findViewById(R.id.btn_speaker);
        holdBtn = findViewById(R.id.btn_hold);
        recordBtn = findViewById(R.id.btn_record);
        muteLabel = findViewById(R.id.lbl_mute);
        speakerLabel = findViewById(R.id.lbl_speaker);
        holdLabel = findViewById(R.id.lbl_hold);
        recordLabel = findViewById(R.id.lbl_record);

        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        /* volume keys adjusted the media stream during a call, not the call stream */
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);

        /* This screen is re-created mid-call by a display-size or font-size
           change, and the new instance used to start with muted = false whatever
           the line was doing. Take the state the service is holding, so the
           button matches the microphone and the next call still gets its mute
           cleared. */
        muted = DialerInCallService.muted;
        if (muteBtn != null) muteBtn.setSelected(muted);
        if (muteLabel != null) muteLabel.setText(muted ? "unmute" : "mute");

        showCaller(getIntent() == null ? "" : getIntent().getStringExtra("number"));

        muteBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleMute(); }
        });
        speakerBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleSpeaker(); }
        });
        holdBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleHold(); }
        });
        recordBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleRecord(); }
        });
        findViewById(R.id.btn_end).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { endCall(); }
        });
        findViewById(R.id.btn_keypad).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                keypadPanel.setVisibility(keypadPanel.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE);
            }
        });
        findViewById(R.id.btn_addcall).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Toast.makeText(CallActivity.this, "Add call needs a second SIM line",
                        Toast.LENGTH_SHORT).show();
            }
        });

        int[] digits = {R.id.k1, R.id.k2, R.id.k3, R.id.k4, R.id.k5, R.id.k6,
                R.id.k7, R.id.k8, R.id.k9, R.id.kstar, R.id.k0, R.id.khash};
        String chars = "123456789*0#";
        for (int i = 0; i < digits.length; i++) {
            final char c = chars.charAt(i);
            Button b = findViewById(digits[i]);
            if (b != null) {
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { playTone(c); }
                });
            }
        }
    }

    /**
     * The 500 ms status ticker kept waking the main thread for as long as the
     * activity existed, including while it sat stopped behind the home screen
     * or another app. It only has a reason to run while the screen is visible.
     */
    @Override
    protected void onStart() {
        super.onStart();
        ticker.removeCallbacks(tick);
        ticker.post(tick);
    }

    @Override
    protected void onStop() {
        ticker.removeCallbacks(tick);
        super.onStop();
    }

    /**
     * Back used to finish this screen while the call stayed up. The activity is
     * excludeFromRecents and nothing in the app posts an ongoing-call
     * notification, so once it was gone there was no way back to the controls
     * and no way to hang up from the app at all — the call could only be ended
     * from the system's own UI or by the other side hanging up. While a call is
     * live the screen stays put; with no call left it closes as any screen does.
     */
    @Override
    public void onBackPressed() {
        if (DialerInCallService.current != null) return;
        super.onBackPressed();
    }

    /** Paints the caller for a number; the lookup runs only when the number actually changed. */
    private void showCaller(String number) {
        final String n = number == null ? "" : number;
        if (n.equals(shownNumber)) return;
        shownNumber = n;
        final int seq = ++lookupSeq;
        if (n.isEmpty()) {
            nameView.setText("");
            return;
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            /* no contacts access: the number is all there is, paint it straight away */
            nameView.setText(n);
            return;
        }
        LOOKUPS.execute(new Runnable() {
            @Override
            public void run() {
                final String looked = lookupName(n);
                ticker.post(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != lookupSeq || !n.equals(shownNumber)) return;
                        nameView.setText(looked != null ? looked : n);
                    }
                });
            }
        });
    }

    /**
     * A second call arrives as a new intent on this singleTop activity, but the
     * activity ignored it: the header kept the previous caller's name and the
     * duration carried on counting from the previous call.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String number = intent == null ? null : intent.getStringExtra("number");
        /* This used to bail out when the number matched the one already painted,
           but an intent only ever arrives here from onCallAdded(): it always
           means a *new* call, even when the same person is calling back. Bailing
           left answered/startedAt from the previous call, so the second call's
           duration carried on counting from the first one's connect time. */

        answered = false;
        startedAt = 0;
        onHold = false;
        clearMuteOnService();
        muted = false;
        /* the DTMF keypad belongs to the call that was on screen: left open it
           was already showing for a brand-new call, the same state leak the
           mute, hold and recording state above are reset for */
        if (keypadPanel != null) keypadPanel.setVisibility(View.GONE);
        /* a recording started on the previous call kept running, and its button
           still said "stop" over the new caller's name. Tearing it down inline
           meant stop() finalising the MP4 and release() shutting the encoder
           down on the UI thread — tens of milliseconds of jank while the new
           caller's screen was opening — so it takes the same off-thread path
           the end-of-call code already uses. */
        releaseRecorderOffThread();
        if (holdBtn != null) { holdBtn.setSelected(false); holdLabel.setText("hold"); }
        if (muteBtn != null) { muteBtn.setSelected(false); muteLabel.setText("mute"); }
        if (statusView != null) setTextIfChanged(statusView, "connecting…");
        showCaller(number);
    }

    /* ---------------- call controls ---------------- */

    private void toggleMute() {
        Call call = DialerInCallService.current;
        if (call == null) return;
        /* without the call service there is nothing that can mute the line, and
           the request below used to be skipped silently: the label still flipped
           to "unmute" over a call that was never muted. */
        if (DialerInCallService.instance == null) return;
        boolean next = !muted;
        try {
            DialerInCallService.instance.setMuted(next);
        } catch (Exception e) {
            /* The label was flipped before telecom was asked, and nothing syncs
               mute back afterwards: a rejected request left "unmute" over a call
               that was never muted for the rest of the call. */
            return;
        }
        muted = next;
        DialerInCallService.muted = next;
        muteBtn.setSelected(muted);
        muteLabel.setText(muted ? "unmute" : "mute");
    }

    /**
     * Where the call audio is actually going. AudioManager.isSpeakerphoneOn()
     * only reports a route this app set itself, so once the default-dialer role
     * hands routing to telecom it kept answering "earpiece" — and the 1 Hz
     * refresh below then un-highlighted the speaker button about a second after
     * it was tapped. Ask the call service, which is told the real route.
     */
    private boolean speakerOn() {
        if (DialerInCallService.instance != null)
            return DialerInCallService.audioRoute == CallAudioState.ROUTE_SPEAKER;
        return audio != null && audio.isSpeakerphoneOn();
    }

    private void toggleSpeaker() {
        boolean next = !speakerOn();
        /* neither route is available: do not claim the speaker was switched */
        if (DialerInCallService.instance == null && audio == null) return;
        try {
            if (DialerInCallService.instance != null) {
                DialerInCallService.instance.setAudioRoute(
                        next ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_EARPIECE);
            } else {
                audio.setSpeakerphoneOn(next);
            }
        } catch (Exception e) {
            /* same as mute: the 1 Hz refresh would eventually repaint it, but not
               before the user had already seen the button claim a route it never got */
            return;
        }
        speakerBtn.setSelected(next);
        speakerLabel.setText(next ? "speaker on" : "speaker");
    }

    private void toggleHold() {
        Call call = DialerInCallService.current;
        if (call == null) return;
        boolean next = !onHold;
        try {
            if (next) call.hold();
            else call.unhold();
        } catch (Exception e) {
            /* hold() throws on a call that does not advertise CAPABILITY_HOLD, and
               unlike the speaker route nothing ever re-synced the button: it sat on
               "resume" over a call that was still live on the line. */
            return;
        }
        holdAskedAt = System.currentTimeMillis();
        setHoldUi(next);
    }

    /** Paints the hold control; shared by the tap and the 1 Hz sync below. */
    private void setHoldUi(boolean held) {
        onHold = held;
        if (holdBtn != null) holdBtn.setSelected(held);
        if (holdLabel != null) holdLabel.setText(held ? "resume" : "hold");
    }

    /**
     * Mute lives on the InCallService, not on the call: dropping the label alone
     * left the microphone muted for the next call while its button said "mute",
     * so the first tap on it only put the label back where it already was.
     */
    private void clearMuteOnService() {
        if (!muted) return;
        DialerInCallService svc = DialerInCallService.instance;
        if (svc == null) return;
        try { svc.setMuted(false); } catch (Exception ignored) { }
        /* the service's copy is what a re-created screen reads back */
        DialerInCallService.muted = false;
    }

    /** TextView.setText() always requests layout, even for the same string. */
    private static void setTextIfChanged(TextView v, CharSequence s) {
        if (v == null || s == null) return;
        CharSequence cur = v.getText();
        if (cur != null && s.toString().contentEquals(cur)) return;
        v.setText(s);
    }

    private void playTone(char c) {
        Call call = DialerInCallService.current;
        if (call == null) return;
        try {
            ticker.removeCallbacks(stopTone);
            call.playDtmfTone(c);
            ticker.postDelayed(stopTone, 160);
        } catch (Exception ignored) { }
    }

    private void endCall() {
        Call call = DialerInCallService.current;
        try {
            if (call != null) call.disconnect();
        } catch (Exception ignored) { }
        releaseRecorderOffThread();
        finish();
    }

    /* ---------------- recording ---------------- */

    private void toggleRecord() {
        if (recording) {
            /* stop() finalises the MP4 and release() tears the encoder down, and
               both block the caller for tens of milliseconds. This path ran them
               on the UI thread — the very work the end-of-call path already hands
               to the background executor — so the frame that should have shown
               "record" again was dropped. Same teardown, off the main thread. */
            releaseRecorderOffThread();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 20);
            return;
        }
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        File out = new File(dir, "call-" + System.currentTimeMillis() + ".m4a");
        /* the constructor sat outside the try: a MediaRecorder that failed to be
           created (media server busy or dead, encoder unavailable) threw straight
           out of the click handler and took the app down, where every other
           failure on this path is caught and explained with a toast */
        MediaRecorder r = null;
        try {
            r = new MediaRecorder();
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioEncodingBitRate(64000);
            r.setAudioSamplingRate(22050);
            r.setOutputFile(out.getAbsolutePath());
            r.prepare();
            r.start();
            recorder = r;
            recordFile = out;
            recording = true;
            recordBtn.setSelected(true);
            recordLabel.setText("stop");
            Toast.makeText(this, "Recording to " + out.getName(), Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            if (r != null) { try { r.release(); } catch (Exception ignored) { } }
            /* prepare() creates the file before the recorder can fail, so every
               blocked attempt left another empty .m4a behind in the app's files
               directory; nothing was ever written to it */
            deleteRecordFile(out);
            recorder = null;
            recordFile = null;
            recording = false;
            recordBtn.setSelected(false);
            recordLabel.setText("record");
            Toast.makeText(this,
                    "This Android version blocks call recording for third-party apps",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Tapping Record with the microphone permission denied asked for it and then
     * did nothing at all: no result handler existed, so the user had to come back
     * and press Record a second time before anything was captured.
     */
    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != 20 || isFinishing()) return;
        boolean granted = results != null && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && !recording) toggleRecord();
    }

    /**
     * The one teardown for every path that ends a recording: the Record button
     * itself, onDestroy(), a hang-up and a remote disconnect. stop() finalises
     * the MP4 and release() tears the encoder down, and both block for tens of
     * milliseconds, so they are handed to the background executor instead of the
     * UI thread. The handle is dropped here before the work is queued, so a new
     * recording started straight afterwards is a new MediaRecorder on a
     * single-threaded executor, and cannot be torn down by this one.
     */
    private void releaseRecorderOffThread() {
        final MediaRecorder r = recorder;
        final File f = recordFile;
        recorder = null;
        recordFile = null;
        recording = false;
        resetRecordButton();
        if (r == null) return;
        RECORDER.execute(new Runnable() {
            @Override
            public void run() {
                boolean stopped = true;
                try { r.stop(); } catch (Exception e) { stopped = false; }
                try { r.release(); } catch (Exception ignored) { }
                /* same empty leftover as the stop path above */
                if (!stopped) deleteRecordFile(f);
            }
        });
    }

    /** Removes a recording file that was created but never written to. */
    private static void deleteRecordFile(File f) {
        if (f == null) return;
        try { f.delete(); } catch (Exception ignored) { }
    }

    private void resetRecordButton() {
        if (recordBtn != null) recordBtn.setSelected(false);
        if (recordLabel != null) recordLabel.setText("record");
    }

    /* ---------------- state ---------------- */

    private void refreshStatus() {
        Call call = DialerInCallService.current;
        if (call == null) {
            /* the service went away: stop the 500 ms ticker and close instead of
               showing "call ended" and polling for ever */
            ticker.removeCallbacks(tick);
            finish();
            return;
        }
        int state;
        try {
            state = call.getState();
        } catch (Exception e) {
            return;
        }
        switch (state) {
            case Call.STATE_RINGING:
                setTextIfChanged(statusView, "incoming call");
                break;
            case Call.STATE_DIALING:
                setTextIfChanged(statusView, "calling…");
                break;
            case Call.STATE_ACTIVE:
                if (!answered) {
                    answered = true;
                    startedAt = connectTimeOf(call);
                }
                long secs = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);
                setTextIfChanged(statusView, String.format(Locale.US, "%02d:%02d", secs / 60, secs % 60));
                /* the button said "resume" over a call that is not on hold — when
                   it was resumed from somewhere else, or when hold() never took.
                   A fresh tap gets a moment before it is believed. */
                if (onHold && System.currentTimeMillis() - holdAskedAt > 1500) setHoldUi(false);
                break;
            case Call.STATE_HOLDING:
                setTextIfChanged(statusView, "on hold");
                /* the call really is held. This screen is often created mid-call
                   — screen off, activity recreated — with onHold still false, so
                   the control offered to hold a call that already was. */
                if (!onHold) setHoldUi(true);
                break;
            case Call.STATE_DISCONNECTED:
                releaseRecorderOffThread();
                finish();
                /* the audio-route sync below is a binder call into the audio
                   service and two view writes, all on a screen that is already
                   closing */
                return;
            default:
                setTextIfChanged(statusView, "connecting…");
                break;
        }
        try {
            boolean sp = speakerOn();
            /* setSelected() refreshes the drawable state and invalidates even when
               the value is unchanged, and this ran on every tick of the clock */
            if (speakerBtn.isSelected() != sp) speakerBtn.setSelected(sp);
            setTextIfChanged(speakerLabel, sp ? "speaker on" : "speaker");
        } catch (Exception ignored) { }
    }

    /**
     * When the call actually connected. This screen is often created mid-call —
     * the screen was off, or the activity was recreated — and starting the clock
     * at "now" in those cases showed 00:00 over a call that had been running for
     * minutes. Falls back to now when telecom does not know (API 24).
     */
    private static long connectTimeOf(Call call) {
        try {
            if (Build.VERSION.SDK_INT >= 25 && call.getDetails() != null) {
                long t = call.getDetails().getConnectTimeMillis();
                if (t > 0) return t;
            }
        } catch (Exception ignored) { }
        return System.currentTimeMillis();
    }

    private String lookupName(String number) {
        if (number == null || number.isEmpty()) return null;
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return null;
        Cursor c = null;
        try {
            android.net.Uri uri = android.net.Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    android.net.Uri.encode(number));
            c = getContentResolver().query(uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        lookupSeq++;             /* a lookup still in flight must not paint into a dead screen */
        ticker.removeCallbacks(tick);
        /* cancelling the pending stop without running it left a key tone
           sounding until the call itself ended; stop it now */
        ticker.removeCallbacks(stopTone);
        stopTone.run();
        releaseRecorderOffThread();
        if (open == this) open = null;
        super.onDestroy();
    }
}

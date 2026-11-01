package com.phone.dialer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaRecorder;
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

    private static CallActivity open;

    private TextView nameView, statusView;
    private ImageView muteBtn, speakerBtn, holdBtn, recordBtn;
    private TextView muteLabel, speakerLabel, holdLabel, recordLabel;
    private View keypadPanel;

    private AudioManager audio;
    private MediaRecorder recorder;
    private boolean recording;
    private boolean muted;
    private boolean onHold;
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

    /** Guards against an out-of-order lookup painting the previous caller's name. */
    private int lookupSeq;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            ticker.postDelayed(this, 500);
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
                try { a.showCaller(number); } catch (Exception ignored) { }
            }
        });
    }

    public static void finishIfOpen() {
        if (open != null) {
            open.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        open.stopRecordingIfAny();
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
        if (number != null && number.equals(shownNumber)) return;

        answered = false;
        startedAt = 0;
        onHold = false;
        muted = false;
        /* a recording started on the previous call kept running, and its button
           still said "stop" over the new caller's name */
        stopRecordingIfAny();
        if (holdBtn != null) { holdBtn.setSelected(false); holdLabel.setText("hold"); }
        if (muteBtn != null) { muteBtn.setSelected(false); muteLabel.setText("mute"); }
        if (statusView != null) setTextIfChanged(statusView, "connecting…");
        showCaller(number);
    }

    /* ---------------- call controls ---------------- */

    private void toggleMute() {
        Call call = DialerInCallService.current;
        if (call == null) return;
        muted = !muted;
        try {
            if (DialerInCallService.instance != null) DialerInCallService.instance.setMuted(muted);
        } catch (Exception ignored) { }
        muteBtn.setSelected(muted);
        muteLabel.setText(muted ? "unmute" : "mute");
    }

    private void toggleSpeaker() {
        boolean on = audio != null && audio.isSpeakerphoneOn();
        boolean next = !on;
        try {
            if (DialerInCallService.instance != null) {
                DialerInCallService.instance.setAudioRoute(
                        next ? CallAudioState.ROUTE_SPEAKER : CallAudioState.ROUTE_EARPIECE);
            } else if (audio != null) {
                audio.setSpeakerphoneOn(next);
            }
        } catch (Exception ignored) { }
        speakerBtn.setSelected(next);
        speakerLabel.setText(next ? "speaker on" : "speaker");
    }

    private void toggleHold() {
        Call call = DialerInCallService.current;
        if (call == null) return;
        onHold = !onHold;
        try {
            if (onHold) call.hold();
            else call.unhold();
        } catch (Exception ignored) { }
        holdBtn.setSelected(onHold);
        holdLabel.setText(onHold ? "resume" : "hold");
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
        stopRecordingIfAny();
        finish();
    }

    /* ---------------- recording ---------------- */

    private void toggleRecord() {
        if (recording) {
            stopRecordingIfAny();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 20);
            return;
        }
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        File out = new File(dir, "call-" + System.currentTimeMillis() + ".m4a");
        MediaRecorder r = new MediaRecorder();
        try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioEncodingBitRate(64000);
            r.setAudioSamplingRate(22050);
            r.setOutputFile(out.getAbsolutePath());
            r.prepare();
            r.start();
            recorder = r;
            recording = true;
            recordBtn.setSelected(true);
            recordLabel.setText("stop");
            Toast.makeText(this, "Recording to " + out.getName(), Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            try { r.release(); } catch (Exception ignored) { }
            recorder = null;
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

    private void stopRecordingIfAny() {
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) { }
            try { recorder.release(); } catch (Exception ignored) { }
            recorder = null;
        }
        recording = false;
        if (recordBtn != null) {
            recordBtn.setSelected(false);
            recordLabel.setText("record");
        }
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
                    startedAt = System.currentTimeMillis();
                }
                long secs = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);
                setTextIfChanged(statusView, String.format(Locale.US, "%02d:%02d", secs / 60, secs % 60));
                break;
            case Call.STATE_HOLDING:
                setTextIfChanged(statusView, "on hold");
                break;
            case Call.STATE_DISCONNECTED:
                stopRecordingIfAny();
                finish();
                break;
            default:
                setTextIfChanged(statusView, "connecting…");
                break;
        }
        try {
            if (audio != null) {
                boolean sp = audio.isSpeakerphoneOn();
                speakerBtn.setSelected(sp);
                setTextIfChanged(speakerLabel, sp ? "speaker on" : "speaker");
            }
        } catch (Exception ignored) { }
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
        ticker.removeCallbacks(stopTone);
        stopRecordingIfAny();
        if (open == this) open = null;
        super.onDestroy();
    }
}

package com.elotouch.devicetester.modules.audio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Audio module (PRD §3.8): play tones through the earpiece and the speaker via
 * audio-route switching, and record from the mic then play it back.
 */
public class AudioActivity extends BaseTestActivity {

    private static final long RECORD_DURATION_MS = 60_000;
    private static final String MIC_STOP_LABEL = "Stop / 停止";
    private static final String MIC_PLAYING_LABEL = "Playing / 回放中";
    private static final String MIC_PAUSED_LABEL = "Resume / 已暂停，点击继续";
    private static final long EXT_MIC_RECORD_DURATION_MS = 10_000;
    private static final long STRESS_TEST_DURATION_MS = 2L * 60 * 60 * 1000;
    private static final long CHANNEL_TONE_DURATION_MS = 30_000;
    private static final String CHANNEL_LEFT_LABEL = "Verify Left Channel / 验证左声道";
    private static final String CHANNEL_RIGHT_LABEL = "Verify Right Channel / 验证右声道";
    private static final String CHANNEL_STOP_LABEL = "Stop / 停止";
    private static final String STRESS_START_LABEL = "Start 2h Stress Test / 开始 2 小时压力测试";
    private static final String STRESS_STOP_LABEL = "Stop / 停止";

    private static final int SWEEP_SAMPLE_RATE = 48_000;
    private static final double SWEEP_START_HZ = 20;
    private static final double SWEEP_END_HZ = 20_000;
    private static final long SWEEP_DURATION_MS = 15_000;
    private static final String SWEEP_START_LABEL = "Play Frequency Sweep / 播放扫频音";
    private static final String SWEEP_STOP_LABEL = "Stop / 停止";

    private static final long DISCRETE_TONE_DURATION_MS = 3_000;
    private static final String FREQ_POINT_STOP_LABEL = "Stop / 停止";

    /** Critical/threshold points (see Audio_freq.txt) chosen to cover 20Hz-20kHz with few tests. */
    private static final class FreqPoint {
        final int hz;
        final String label;
        FreqPoint(int hz, String label) { this.hz = hz; this.label = label; }
    }

    private static final FreqPoint[] FREQ_POINTS = {
            new FreqPoint(20, "20 Hz"),
            new FreqPoint(40, "40 Hz"),
            new FreqPoint(100, "100 Hz"),
            new FreqPoint(1000, "1 kHz"),
            new FreqPoint(8000, "8 kHz"),
            new FreqPoint(12000, "12 kHz"),
            new FreqPoint(15000, "15 kHz"),
            new FreqPoint(20000, "20 kHz"),
    };

    /** Heuristic offset mapping an uncalibrated mic's dBFS reading into a plausible dB(A) range. */
    private static final double DBA_CALIBRATION_OFFSET_DB = 100.0;

    /** PRD §3.5: one scenario per button; file name = key + "_" + device model. */
    private static final class MicScenario {
        final String key;
        final String label;
        MicScenario(String key, String label) { this.key = key; this.label = label; }
    }

    private static final MicScenario[] MIC_SCENARIOS = {
            new MicScenario("1.above_10cm", "Above 10cm / 正上方 10cm"),
            new MicScenario("2.above_50cm", "Above 50cm / 正上方 50cm"),
            new MicScenario("3.left_10cm", "Left 10cm / 左上方 10cm"),
            new MicScenario("4.left_50cm", "Left 50cm / 左上方 50cm"),
            new MicScenario("5.right_10cm", "Right 10cm / 右上方 10cm"),
            new MicScenario("6.right_50cm", "Right 50cm / 右上方 50cm"),
    };

    private AudioManager audioManager;
    private ToneGenerator toneGen;
    private MediaRecorder recorder;
    private MediaPlayer player;
    private File recordFile;
    private TextView freqStatusText;
    private Button sweepButton;
    private volatile boolean sweepRunning = false;
    private AudioTrack toneTrack;
    private final Runnable toneFinishRunnable = this::finishDiscreteTone;
    private Button[] freqPointButtons;
    private boolean tonePlaying = false;
    private int playingToneIdx = -1;
    private Button[] micButtons;
    private int recordingScenarioIdx = -1;
    private boolean micRecording = false;
    private boolean micPlaybackActive = false;
    private long micSecondsLeft;
    private final Runnable micTick = new Runnable() {
        @Override public void run() {
            micText.setText("Recording " + MIC_SCENARIOS[recordingScenarioIdx].label + "… "
                    + micSecondsLeft + "s left 录音中…剩余 " + micSecondsLeft + " 秒");
            if (micSecondsLeft <= 0) {
                finishScenarioRecording(recordingScenarioIdx);
                return;
            }
            micSecondsLeft--;
            main.postDelayed(this, 1000);
        }
    };
    private TextView micText;
    private TextView channelText;
    private Button channelLeftButton;
    private Button channelRightButton;
    private AudioTrack channelTrack;
    private boolean channelRunning = false;
    private boolean channelRunningLeft;
    private final Runnable channelFinishRunnable = this::finishChannelTest;
    private TextView noiseText;
    private TextView extMicText;
    private Button extMicButton;
    private File extMicRecordFile;
    private TextView stressText;
    private Button stressButton;
    private File stressRecordFile;
    private boolean stressRunning = false;
    private long stressSecondsLeft;
    private final Runnable stressTick = new Runnable() {
        @Override public void run() {
            stressText.setText(formatDuration(stressSecondsLeft) + " left 剩余 "
                    + formatDuration(stressSecondsLeft));
            if (stressSecondsLeft <= 0) {
                finishStressTest();
                return;
            }
            stressSecondsLeft--;
            main.postDelayed(this, 1000);
        }
    };

    /** Registered as a field (before STARTED) per the ActivityResultLauncher contract. */
    private final ActivityResultLauncher<Intent> openTreeLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> { });

    @Override
    protected String title() {
        return "Audio 音频测试";
    }

    @Override
    protected void buildUi() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        addSectionTitle("Speaker & Earpiece / 扬声器与听筒");
        addInfo("Plays a tone via the bottom speaker and the top earpiece to verify output "
                + "quality and channels.\n分别通过底部扬声器和顶部听筒播放提示音，验证放音质量与声道。");
        addButton("Speaker / 扬声器播放", this::playSpeaker);
        addButton("Earpiece / 听筒播放", this::playEarpiece);

        addSectionTitle("Frequency Test / 频率测试");
        freqStatusText = addInfo("Play an audio file that has a frequency which is between "
                + "20Hz ~ 20kHz, verify the voice varies by frequency and no other noises are "
                + "heard.\n播放 20Hz ~ 20kHz 范围内的音频，确认音调随频率变化，且未听到其他杂音。");
        sweepButton = addButton(SWEEP_START_LABEL, this::onSweepClicked);
        addView(buildFreqPointRows());

        addSectionTitle("Ambient Noise / 环境分贝检测");
        noiseText = addInfo("Tap to measure the current ambient level from the mic (indicative dB(A), "
                + "uncalibrated — not a certified SPL reading).\n"
                + "点击通过麦克风测量当前环境音量（dB(A) 估算值，麦克风未经声压级校准，仅供参考）。");
        addButton("Measure dB(A) / 测量环境分贝", this::measureAmbientNoise);

        addSectionTitle("Microphone / 麦克风测试");
        micText = addInfo("Each button records 1 minute at the labeled mic position, then "
                + "auto-stops, saves the recording, and plays it back (file name + device model "
                + "shown below). Tap again anytime to stop early — it still saves before playback.\n"
                + "每个按钮在对应麦克风位置录音 1 分钟，自动停止后先保存再回放（文件名见下方保存路径提示）。"
                + "录制过程中可再次点击提前停止，同样会先保存再回放。");
        micButtons = new Button[MIC_SCENARIOS.length];
        addView(buildScenarioGrid());
        addButton("Open Recordings Folder / 打开录音文件夹", this::openRecordingsFolder);

        addSectionTitle("External Microphone / 外部麦克风测试");
        extMicText = addInfo("Plug in a Type-A, Type-C, or 3.5mm headset with a mic, then tap to "
                + "record 10 seconds; it plays back automatically when done.\n"
                + "插入 Type-A、Type-C 或 3.5mm 带麦克风的耳机后点击录音，录制 10 秒，完成后自动回放。");
        extMicButton = addButton("Record 10s & Play / 录音 10 秒并回放", this::onExtMicClicked);

        addSectionTitle("Microphone Stress Test / 麦克风压力测试");
        stressText = addInfo("Continuously records for 2 hours, then auto-saves as Mic_stress_2h "
                + "(no playback). Keep this screen in the foreground the whole time — leaving the "
                + "app pauses/aborts the recording. Tap again anytime to stop early and save.\n"
                + "连续录音 2 小时，完成后自动保存为 Mic_stress_2h（不自动回放）。请全程保持此界面在前台，"
                + "切出应用会暂停/中止录音。测试过程中可再次点击按钮提前停止并保存。");
        stressButton = addButton(STRESS_START_LABEL, this::onStressTestClicked);

        addSectionTitle("Channels / 声道验证");
        channelText = addInfo("Each button plays a tone on only that channel for 30 seconds — "
                + "confirm you hear it solely from the corresponding side.\n"
                + "每个按钮仅在对应声道播放提示音 30 秒，请确认声音仅从对应一侧发出。");
        channelLeftButton = addButton(CHANNEL_LEFT_LABEL, () -> onChannelButtonClicked(true));
        channelRightButton = addButton(CHANNEL_RIGHT_LABEL, () -> onChannelButtonClicked(false));
    }

    private void playSpeaker() {
        stopTone();
        audioManager.setMode(AudioManager.MODE_NORMAL);
        toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        toneGen.startTone(ToneGenerator.TONE_DTMF_0, 1500);
        toast("Playing on speaker 扬声器播放中");
    }

    private void playEarpiece() {
        stopTone();
        // Route to the earpiece: communication mode + speakerphone off.
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(false);
        toneGen = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100);
        toneGen.startTone(ToneGenerator.TONE_DTMF_0, 1500);
        toast("Playing on earpiece (hold to ear) 听筒播放中（请贴近耳朵）");
    }

    private void onSweepClicked() {
        if (sweepRunning) {
            stopTests();
            return;
        }
        playFrequencySweep();
    }

    /**
     * Plays a logarithmic (equal-time-per-octave) 20Hz-20kHz sine sweep through the speaker so
     * the tester can confirm pitch rises smoothly and listen for rattling/buzzing at any point
     * in the range. Uses a continuous-phase chirp so there's no clicking between buffers.
     */
    private void playFrequencySweep() {
        sweepRunning = true;
        sweepButton.setText(SWEEP_STOP_LABEL);
        audioManager.setMode(AudioManager.MODE_NORMAL);
        runAsync(() -> {
            int frames = 2048;
            int minBuf = AudioTrack.getMinBufferSize(SWEEP_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SWEEP_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf, frames * 2 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            double durationSec = SWEEP_DURATION_MS / 1000.0;
            double k = durationSec / Math.log(SWEEP_END_HZ / SWEEP_START_HZ);
            long totalFrames = (long) (durationSec * SWEEP_SAMPLE_RATE);
            short[] buf = new short[frames];
            long written = 0;
            long lastUiFrame = 0;
            try {
                track.play();
                while (written < totalFrames && !isStopped()) {
                    int n = (int) Math.min(frames, totalFrames - written);
                    for (int i = 0; i < n; i++) {
                        double t = (written + i) / (double) SWEEP_SAMPLE_RATE;
                        double phase = 2 * Math.PI * SWEEP_START_HZ * k * (Math.exp(t / k) - 1);
                        buf[i] = (short) (Short.MAX_VALUE * 0.6 * Math.sin(phase));
                    }
                    track.write(buf, 0, n);
                    written += n;
                    if (written - lastUiFrame > SWEEP_SAMPLE_RATE / 5) {
                        double curFreq = SWEEP_START_HZ * Math.exp(written / (double) SWEEP_SAMPLE_RATE / k);
                        lastUiFrame = written;
                        ui(() -> freqStatusText.setText(String.format(Locale.US,
                                "Playing sweep… now ~%.0f Hz 正在播放…当前约 %.0f Hz", curFreq, curFreq)));
                    }
                }
            } finally {
                track.stop();
                track.release();
            }
            boolean stoppedEarly = isStopped();
            ui(() -> {
                sweepRunning = false;
                sweepButton.setText(SWEEP_START_LABEL);
                freqStatusText.setText(stoppedEarly ? "Stopped. 已停止。" : "Sweep complete. 扫频完成。");
            });
        });
    }

    private void onFreqPointClicked(int idx) {
        if (tonePlaying) {
            if (idx == playingToneIdx) finishDiscreteTone();
            return;
        }
        playDiscreteTone(idx);
    }

    /**
     * Plays a steady tone at a single critical frequency (see FREQ_POINTS) for
     * DISCRETE_TONE_DURATION_MS unless stopped early by tapping the button again. Uses a
     * 1-second static buffer looped via setLoopPoints — every chosen frequency is a whole number
     * of Hz, so the buffer boundary always lands on a full cycle with no click at the seam.
     */
    private void playDiscreteTone(int idx) {
        tonePlaying = true;
        playingToneIdx = idx;
        setFreqPointButtonsEnabledExcept(idx, false);
        freqPointButtons[idx].setText(FREQ_POINT_STOP_LABEL);

        int hz = FREQ_POINTS[idx].hz;
        releaseToneTrack();
        audioManager.setMode(AudioManager.MODE_NORMAL);
        int sampleRate = 48_000;
        int numSamples = sampleRate;
        short[] mono = new short[numSamples];
        for (int i = 0; i < numSamples; i++) {
            mono[i] = (short) (Short.MAX_VALUE * 0.6 * Math.sin(2 * Math.PI * hz * i / sampleRate));
        }
        toneTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(mono.length * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        toneTrack.write(mono, 0, mono.length);
        toneTrack.setLoopPoints(0, numSamples, -1);
        toneTrack.play();
        String label = FREQ_POINTS[idx].label;
        freqStatusText.setText("Playing " + label + "… Tap again to stop early.\n正在播放 " + label
                + "…可再次点击提前停止。");
        main.removeCallbacks(toneFinishRunnable);
        main.postDelayed(toneFinishRunnable, DISCRETE_TONE_DURATION_MS);
    }

    private void finishDiscreteTone() {
        main.removeCallbacks(toneFinishRunnable);
        releaseToneTrack();
        freqStatusText.setText(freqStatusText.getText() + "\nDone. 完成。");
        resetFreqPointButtons();
    }

    private void resetFreqPointButtons() {
        tonePlaying = false;
        playingToneIdx = -1;
        if (freqPointButtons == null) return;
        for (int i = 0; i < freqPointButtons.length; i++) {
            if (freqPointButtons[i] == null) continue;
            freqPointButtons[i].setEnabled(true);
            freqPointButtons[i].setText(FREQ_POINTS[i].label);
        }
    }

    private void setFreqPointButtonsEnabledExcept(int keepIdx, boolean enabled) {
        for (int i = 0; i < freqPointButtons.length; i++) {
            if (i != keepIdx && freqPointButtons[i] != null) freqPointButtons[i].setEnabled(enabled);
        }
    }

    private void releaseToneTrack() {
        if (toneTrack != null) {
            try { toneTrack.stop(); } catch (Exception ignored) { }
            toneTrack.release();
            toneTrack = null;
        }
    }

    /** Lays the frequency-point buttons out as 2 even rows of 4 slots (any leftover slots are
     *  invisible spacers) so every button ends up the same width and default text size. */
    private LinearLayout buildFreqPointRows() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int columns = 4;
        freqPointButtons = new Button[FREQ_POINTS.length];
        FreqPoint[] row1 = Arrays.copyOfRange(FREQ_POINTS, 0, columns);
        FreqPoint[] row2 = Arrays.copyOfRange(FREQ_POINTS, columns, FREQ_POINTS.length);
        container.addView(buildFreqButtonRow(row1, columns, 0));
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = dp(6);
        container.addView(buildFreqButtonRow(row2, columns, columns), row2Lp);
        return container;
    }

    private LinearLayout buildFreqButtonRow(FreqPoint[] points, int slots, int startIdx) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < slots; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(6);
            if (i < points.length) {
                int idx = startIdx + i;
                Button b = new Button(this);
                b.setAllCaps(false);
                b.setText(points[i].label);
                b.setOnClickListener(v -> onFreqPointClicked(idx));
                row.addView(b, lp);
                freqPointButtons[idx] = b;
            } else {
                row.addView(new View(this), lp);
            }
        }
        return row;
    }

    /** Lays the 6 scenario buttons out as 3 columns (above/left/right), 2 rows (10cm/50cm) each. */
    private LinearLayout buildScenarioGrid() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        int columns = MIC_SCENARIOS.length / 2;
        for (int col = 0; col < columns; col++) {
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (col > 0) colLp.leftMargin = dp(8);
            grid.addView(column, colLp);
            for (int row = 0; row < 2; row++) {
                final int idx = col * 2 + row;
                Button b = new Button(this);
                b.setAllCaps(false);
                b.setText(MIC_SCENARIOS[idx].label);
                b.setOnClickListener(v -> onMicScenarioClicked(idx));
                LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                btnLp.topMargin = dp(6);
                column.addView(b, btnLp);
                micButtons[idx] = b;
            }
        }
        return grid;
    }

    private void onMicScenarioClicked(int idx) {
        if (micRecording) {
            if (idx == recordingScenarioIdx) stopScenarioRecordingEarly();
            return;
        }
        if (micPlaybackActive) {
            if (idx == recordingScenarioIdx) toggleScenarioPlayback(idx);
            return;
        }
        requirePermission(Manifest.permission.RECORD_AUDIO,
                () -> requireStorageThenRecord(idx),
                () -> micText.setText("Permission denied. 权限受限：未授予麦克风权限。"));
    }

    private void requireStorageThenRecord(int idx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            requirePermission(Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    () -> startScenarioRecording(idx),
                    () -> micText.setText("Storage permission denied. 权限受限：未授予存储权限。"));
        } else {
            startScenarioRecording(idx);
        }
    }

    private void startScenarioRecording(int idx) {
        recordFile = new File(getCacheDir(), "elo_mic_scenario.m4a");
        try {
            startRecordingTo(recordFile);
            micRecording = true;
            recordingScenarioIdx = idx;
            setMicButtonsEnabledExcept(idx, false);
            micButtons[idx].setText(MIC_STOP_LABEL);
            micSecondsLeft = RECORD_DURATION_MS / 1000;
            main.removeCallbacks(micTick);
            main.post(micTick);
        } catch (IOException | IllegalStateException e) {
            setMicButtonsEnabled(true);
            micText.setText("Recording failed 录音失败：" + e.getMessage());
        }
    }

    private void stopScenarioRecordingEarly() {
        main.removeCallbacks(micTick);
        finishScenarioRecording(recordingScenarioIdx);
    }

    /** Shared MediaRecorder setup for both the mic-scenario and external-mic recordings. */
    private void startRecordingTo(File target) throws IOException {
        releaseRecorder();
        releasePlayer();
        recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new MediaRecorder(this) : new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setOutputFile(target.getAbsolutePath());
        recorder.prepare();
        recorder.start();
    }

    /** Stops recording and saves the file immediately — saving before playback ensures the
     *  file already exists if the tester jumps to the recordings folder mid-playback. Leaves
     *  micRecording false and the button disabled for the brief save, so a stray tap can't
     *  re-enter recording or restart the save. */
    private void finishScenarioRecording(int idx) {
        main.removeCallbacks(micTick);
        micRecording = false;
        micButtons[idx].setEnabled(false);
        releaseRecorder();
        micText.setText("Saving… 保存中…");
        saveScenarioRecording(idx);
    }

    private void playback(Runnable onDone) {
        playFile(recordFile, micText, onDone);
    }

    /** Shared MediaPlayer playback for both the mic-scenario and external-mic recordings. */
    private void playFile(File file, TextView statusText, Runnable onDone) {
        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setDataSource(file.getAbsolutePath());
            player.setOnCompletionListener(mp -> ui(() -> {
                releasePlayer();
                if (onDone != null) onDone.run();
            }));
            player.prepare();
            player.start();
        } catch (IOException e) {
            statusText.setText("Playback failed 回放失败：" + e.getMessage());
            if (onDone != null) onDone.run();
        }
    }

    private void onExtMicClicked() {
        requirePermission(Manifest.permission.RECORD_AUDIO, this::startExtMicRecording,
                () -> extMicText.setText("Permission denied. 权限受限：未授予麦克风权限。"));
    }

    private void startExtMicRecording() {
        extMicButton.setEnabled(false);
        extMicRecordFile = new File(getCacheDir(), "elo_ext_mic_test.m4a");
        extMicText.setText(describeExternalMicStatus() + "\nRecording… (10s) 录音中…（10 秒）");
        try {
            startRecordingTo(extMicRecordFile);
            main.postDelayed(this::finishExtMicRecording, EXT_MIC_RECORD_DURATION_MS);
        } catch (IOException | IllegalStateException e) {
            extMicButton.setEnabled(true);
            extMicText.setText("Recording failed 录音失败：" + e.getMessage());
        }
    }

    private void finishExtMicRecording() {
        releaseRecorder();
        extMicText.setText("Playing back… 回放中…");
        playFile(extMicRecordFile, extMicText, () -> {
            extMicText.setText("Playback done. 回放完成。");
            extMicButton.setEnabled(true);
        });
    }

    /** Reports whether an external (non-built-in) mic input is currently active, for the
     *  Type-A / Type-C / 3.5mm headset scenarios named in the PRD. */
    private String describeExternalMicStatus() {
        AudioDeviceInfo[] inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        for (AudioDeviceInfo d : inputs) {
            String label = externalMicLabel(d.getType());
            if (label != null) return "Detected 已检测到：" + label;
        }
        return "No external mic detected — using built-in mic. 未检测到外部麦克风，将使用内置麦克风。";
    }

    @Nullable
    private static String externalMicLabel(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "3.5mm headset mic 3.5mm 耳机麦克风";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB headset mic (Type-A/Type-C) USB 耳机麦克风（Type-A/Type-C）";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return "USB audio device (Type-A/Type-C) USB 音频设备（Type-A/Type-C）";
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return "USB accessory mic (Type-A/Type-C) USB 配件麦克风（Type-A/Type-C）";
            default:
                return null;
        }
    }

    private void saveScenarioRecording(int idx) {
        MicScenario sc = MIC_SCENARIOS[idx];
        String name = sc.key + "_" + sanitizeFileName(Build.MODEL) + ".m4a";
        saveRecordingToPublicStorage(recordFile, name, msg -> startScenarioPlayback(idx, msg));
    }

    /** Auto-starts playback right after saving. While playing, the button doubles as a
     *  pause/resume toggle (see toggleScenarioPlayback) instead of a re-record trigger. */
    private void startScenarioPlayback(int idx, String savedMsg) {
        micPlaybackActive = true;
        micText.setText(savedMsg + "\nPlaying back… 回放中…");
        micButtons[idx].setText(MIC_PLAYING_LABEL);
        micButtons[idx].setEnabled(true);
        playback(() -> {
            micPlaybackActive = false;
            micText.setText(savedMsg + "\nPlayback done. 回放完成。");
            resetMicScenarioButtons();
        });
    }

    private void toggleScenarioPlayback(int idx) {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            micButtons[idx].setText(MIC_PAUSED_LABEL);
        } else {
            player.start();
            micButtons[idx].setText(MIC_PLAYING_LABEL);
        }
    }

    private void resetMicScenarioButtons() {
        micRecording = false;
        micPlaybackActive = false;
        if (micButtons == null) return;
        for (int i = 0; i < micButtons.length; i++) {
            micButtons[i].setEnabled(true);
            micButtons[i].setText(MIC_SCENARIOS[i].label);
        }
    }

    private void setMicButtonsEnabledExcept(int keepIdx, boolean enabled) {
        if (micButtons == null) return;
        for (int i = 0; i < micButtons.length; i++) {
            if (i != keepIdx) micButtons[i].setEnabled(enabled);
        }
    }

    /** Shared save-to-MediaStore logic for every recording feature in this Activity. */
    private void saveRecordingToPublicStorage(File src, String name, Consumer<String> onDone) {
        new Thread(() -> {
            String msg;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentResolver cr = getContentResolver();
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
                    cv.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
                    cv.put(MediaStore.Audio.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_MUSIC + "/EloDeviceTester");
                    cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
                    Uri uri = cr.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new IOException("MediaStore insert failed");
                    try (OutputStream os = cr.openOutputStream(uri);
                         FileInputStream is = new FileInputStream(src)) {
                        if (os == null) throw new IOException("openOutputStream failed");
                        copy(is, os);
                    }
                    cv.clear();
                    cv.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    cr.update(uri, cv, null, null);
                    msg = "Saved to Music/EloDeviceTester/" + name;
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_MUSIC), "EloDeviceTester");
                    if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed");
                    File out = new File(dir, name);
                    try (FileInputStream is = new FileInputStream(src);
                         FileOutputStream fos = new FileOutputStream(out)) {
                        copy(is, fos);
                    }
                    MediaScannerConnection.scanFile(this,
                            new String[]{out.getAbsolutePath()}, null, null);
                    msg = "Saved to " + out.getAbsolutePath();
                }
            } catch (Exception ex) {
                msg = "Save failed 保存失败：" + ex.getMessage();
            }
            final String finalMsg = msg;
            ui(() -> onDone.accept(finalMsg));
        }).start();
    }

    private void onStressTestClicked() {
        if (stressRunning) {
            stopStressTestEarly();
            return;
        }
        requirePermission(Manifest.permission.RECORD_AUDIO, this::startStressTest,
                () -> stressText.setText("Permission denied. 权限受限：未授予麦克风权限。"));
    }

    private void startStressTest() {
        stressRecordFile = new File(getCacheDir(), "elo_mic_stress.m4a");
        try {
            startRecordingTo(stressRecordFile);
        } catch (IOException | IllegalStateException e) {
            stressText.setText("Recording failed 录音失败：" + e.getMessage());
            return;
        }
        stressRunning = true;
        stressButton.setText(STRESS_STOP_LABEL);
        stressSecondsLeft = STRESS_TEST_DURATION_MS / 1000;
        main.removeCallbacks(stressTick);
        main.post(stressTick);
    }

    private void stopStressTestEarly() {
        main.removeCallbacks(stressTick);
        finishStressTest();
    }

    private void finishStressTest() {
        main.removeCallbacks(stressTick);
        releaseRecorder();
        stressRunning = false;
        stressButton.setText(STRESS_START_LABEL);
        stressText.setText("Saving… 保存中…");
        saveRecordingToPublicStorage(stressRecordFile, "Mic_stress_2h.m4a", stressText::setText);
    }

    private static String formatDuration(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s);
    }

    private static void copy(InputStream is, OutputStream os) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
    }

    private static String sanitizeFileName(String s) {
        return s == null ? "unknown" : s.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void setMicButtonsEnabled(boolean enabled) {
        if (micButtons == null) return;
        for (Button b : micButtons) b.setEnabled(enabled);
    }

    /**
     * PRD: jump to the real folder the scenario recordings are saved into
     * (/sdcard/Music/EloDeviceTester), not a by-type media grouping. ACTION_VIEW on a raw
     * content:// document URI throws SecurityException without a prior SAF grant, so instead
     * use ACTION_OPEN_DOCUMENT_TREE (the API Android itself points to for this) with
     * EXTRA_INITIAL_URI hinting at that exact folder — it's a standard system picker intent,
     * always resolvable, and opens browsing right there.
     */
    private void openRecordingsFolder() {
        String path = recordingsFolderPath();
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Uri initialUri = DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents", "primary:Music/EloDeviceTester");
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
            }
            openTreeLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            toast("No file manager found. 未找到文件管理器。\nPath 路径：" + path);
        }
    }

    private String recordingsFolderPath() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC), "EloDeviceTester").getAbsolutePath();
    }

    private void stopTone() {
        if (toneGen != null) {
            toneGen.stopTone();
            toneGen.release();
            toneGen = null;
        }
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) { }
            recorder.release();
            recorder = null;
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            player.release();
            player = null;
        }
    }

    private void onChannelButtonClicked(boolean left) {
        if (channelRunning) {
            if (left == channelRunningLeft) finishChannelTest();
            return;
        }
        verifyChannel(left);
    }

    private void verifyChannel(boolean left) {
        channelRunning = true;
        channelRunningLeft = left;
        (left ? channelLeftButton : channelRightButton).setText(CHANNEL_STOP_LABEL);
        (left ? channelRightButton : channelLeftButton).setEnabled(false);
        int sampleRate = 44100;
        int numSamples = sampleRate; // 1 second tone, looped for the full test duration
        short[] mono = new short[numSamples];
        for (int i = 0; i < numSamples; i++) {
            mono[i] = (short) (Short.MAX_VALUE * 0.5 * Math.sin(2 * Math.PI * 440 * i / sampleRate));
        }
        short[] stereo = new short[numSamples * 2];
        for (int i = 0; i < numSamples; i++) {
            stereo[i * 2] = left ? mono[i] : 0;
            stereo[i * 2 + 1] = left ? 0 : mono[i];
        }
        channelTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(stereo.length * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        channelTrack.write(stereo, 0, stereo.length);
        channelTrack.setLoopPoints(0, numSamples, -1);
        channelTrack.play();
        String channel = left ? "left 左" : "right 右";
        channelText.setText("Playing " + channel + " channel for 30s — confirm you hear it only "
                + "from that side. Tap Stop to end early.\n仅播放" + (left ? "左" : "右")
                + "声道 30 秒，请确认声音仅从对应一侧发出。可点击\"停止\"提前结束。");
        main.removeCallbacks(channelFinishRunnable);
        main.postDelayed(channelFinishRunnable, CHANNEL_TONE_DURATION_MS);
    }

    private void finishChannelTest() {
        main.removeCallbacks(channelFinishRunnable);
        releaseChannelTrack();
        channelText.setText(channelText.getText() + "\nDone. 测试完成。");
        resetChannelButtons();
    }

    private void resetChannelButtons() {
        channelRunning = false;
        channelLeftButton.setText(CHANNEL_LEFT_LABEL);
        channelRightButton.setText(CHANNEL_RIGHT_LABEL);
        channelLeftButton.setEnabled(true);
        channelRightButton.setEnabled(true);
    }

    private void releaseChannelTrack() {
        if (channelTrack != null) {
            try { channelTrack.stop(); } catch (Exception ignored) { }
            channelTrack.release();
            channelTrack = null;
        }
    }

    private void measureAmbientNoise() {
        requirePermission(Manifest.permission.RECORD_AUDIO, this::doMeasureAmbientNoise,
                () -> noiseText.setText("Permission denied. 权限受限：未授予麦克风权限。"));
    }

    @SuppressLint("MissingPermission")
    private void doMeasureAmbientNoise() {
        noiseText.setText("Measuring… 测量中…");
        runAsync(() -> {
            int sampleRate = 44100;
            int minBuf = AudioRecord.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                ui(() -> noiseText.setText("Mic not available for raw capture. 无法获取麦克风原始采样。"));
                return;
            }
            AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4);
            try {
                record.startRecording();
                short[] buffer = new short[minBuf];
                double sumSquares = 0;
                long sampleCount = 0;
                long endTimeMs = System.currentTimeMillis() + 1000;
                while (System.currentTimeMillis() < endTimeMs && !isStopped()) {
                    int read = record.read(buffer, 0, buffer.length);
                    if (read <= 0) continue;
                    for (int i = 0; i < read; i++) {
                        sumSquares += (double) buffer[i] * buffer[i];
                    }
                    sampleCount += read;
                }
                if (sampleCount == 0) {
                    ui(() -> noiseText.setText("No samples captured. 未采集到样本。"));
                    return;
                }
                double rms = Math.sqrt(sumSquares / sampleCount);
                double dbFs = rms > 0 ? 20 * Math.log10(rms / 32768.0) : -96;
                // Uncalibrated mic: shift the dBFS reading into a plausible dB(A) range so it
                // reads like a real sound-level meter. Not a certified SPL measurement.
                double dbA = Math.max(0, dbFs + DBA_CALIBRATION_OFFSET_DB);
                ui(() -> noiseText.setText(String.format(Locale.US,
                        "Ambient level 环境音量：%.1f dB(A) (indicative, uncalibrated mic 估算值，麦克风未经声压级校准)",
                        dbA)));
            } finally {
                record.stop();
                record.release();
            }
        });
    }

    @Override
    protected void onStopTests() {
        stopTone();
        main.removeCallbacksAndMessages(null);
        releaseRecorder();
        releasePlayer();
        resetMicScenarioButtons();
        if (extMicButton != null) extMicButton.setEnabled(true);
        releaseChannelTrack();
        if (channelLeftButton != null && channelRightButton != null) resetChannelButtons();
        releaseToneTrack();
        resetFreqPointButtons();
        if (stressRunning) {
            stressRunning = false;
            if (stressButton != null) stressButton.setText(STRESS_START_LABEL);
        }
        if (sweepRunning) {
            sweepRunning = false;
            if (sweepButton != null) sweepButton.setText(SWEEP_START_LABEL);
        }
        if (audioManager != null) audioManager.setMode(AudioManager.MODE_NORMAL);
        if (recordFile != null && recordFile.exists()) //noinspection ResultOfMethodCallIgnored
            recordFile.delete();
        if (extMicRecordFile != null && extMicRecordFile.exists()) //noinspection ResultOfMethodCallIgnored
            extMicRecordFile.delete();
        if (stressRecordFile != null && stressRecordFile.exists()) //noinspection ResultOfMethodCallIgnored
            stressRecordFile.delete();
    }
}

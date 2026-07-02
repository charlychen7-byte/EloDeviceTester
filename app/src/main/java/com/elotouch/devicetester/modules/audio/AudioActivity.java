package com.elotouch.devicetester.modules.audio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.Build;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Audio module (PRD §3.8): play tones through the earpiece and the speaker via
 * audio-route switching, and record from the mic then play it back.
 */
public class AudioActivity extends BaseTestActivity {

    private AudioManager audioManager;
    private ToneGenerator toneGen;
    private MediaRecorder recorder;
    private MediaPlayer player;
    private File recordFile;
    private TextView micText;
    private TextView latencyText;
    private TextView channelText;

    @Override
    protected String title() {
        return "Audio 音频测试";
    }

    @Override
    protected void buildUi() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        recordFile = new File(getCacheDir(), "elo_mic_test.m4a");

        addSectionTitle("Speaker & Earpiece / 扬声器与听筒");
        addInfo("Plays a tone via the bottom speaker and the top earpiece to verify output "
                + "quality and channels.\n分别通过底部扬声器和顶部听筒播放提示音，验证放音质量与声道。");
        addButton("Speaker / 扬声器播放", this::playSpeaker);
        addButton("Earpiece / 听筒播放", this::playEarpiece);

        addSectionTitle("Microphone / 麦克风测试");
        micText = addInfo("Records 3s then plays back automatically. 点击录音 3 秒，结束后自动回放。");
        addButton("Record 3s & Play / 录音 3 秒并回放", this::recordAndPlayback);

        addSectionTitle("Audio Latency / 音频延迟测试");
        latencyText = addInfo("Tap to measure. 点击开始测量。");
        addButton("Measure Latency / 测量延迟", this::measureLatency);

        addSectionTitle("Sample Rate & Channels / 采样率与声道验证");
        channelText = addInfo("Tap to verify. 点击开始验证。");
        addButton("Verify Left/Right Channel / 验证左右声道", this::verifyChannels);
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

    private void recordAndPlayback() {
        requirePermission(Manifest.permission.RECORD_AUDIO,
                this::startRecording,
                () -> micText.setText("Permission denied. 权限受限：未授予麦克风权限。"));
    }

    private void startRecording() {
        releaseRecorder();
        try {
            recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setOutputFile(recordFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            micText.setText("Recording… (3s) 录音中…（3 秒）");
            main.postDelayed(this::finishRecording, 3000);
        } catch (IOException | IllegalStateException e) {
            micText.setText("Recording failed 录音失败：" + e.getMessage());
        }
    }

    private void finishRecording() {
        releaseRecorder();
        micText.setText("Playing back… 回放中…");
        playback();
    }

    private void playback() {
        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setDataSource(recordFile.getAbsolutePath());
            player.setOnCompletionListener(mp -> ui(() -> micText.setText("Playback done. 回放完成。")));
            player.prepare();
            player.start();
        } catch (IOException e) {
            micText.setText("Playback failed 回放失败：" + e.getMessage());
        }
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

    private void measureLatency() {
        requirePermission(Manifest.permission.RECORD_AUDIO, this::doMeasureLatency,
                () -> latencyText.setText("Permission denied. 权限受限：未授予麦克风权限。"));
    }

    @SuppressLint("MissingPermission")
    private void doMeasureLatency() {
        latencyText.setText("Measuring… 测量中…");
        runAsync(() -> {
            int sampleRate = 44100;
            int minBuf = AudioRecord.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                ui(() -> latencyText.setText("Mic not available for raw capture. 无法获取麦克风原始采样。"));
                return;
            }
            AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4);
            ToneGenerator tone = null;
            try {
                record.startRecording();
                tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
                short[] buffer = new short[minBuf];
                long totalSamples = 0;
                long playSampleMark = -1;
                long thresholdSample = -1;
                boolean tonePlayed = false;
                long endTimeMs = System.currentTimeMillis() + 2500;
                while (System.currentTimeMillis() < endTimeMs && !isStopped()) {
                    int read = record.read(buffer, 0, buffer.length);
                    if (read <= 0) continue;
                    if (!tonePlayed && totalSamples > sampleRate / 5) {
                        tone.startTone(ToneGenerator.TONE_DTMF_0, 300);
                        playSampleMark = totalSamples;
                        tonePlayed = true;
                    }
                    if (tonePlayed && thresholdSample < 0) {
                        for (int i = 0; i < read; i++) {
                            if (Math.abs(buffer[i]) > 8000) {
                                thresholdSample = totalSamples + i;
                                break;
                            }
                        }
                    }
                    totalSamples += read;
                    if (thresholdSample >= 0) break;
                }
                if (playSampleMark >= 0 && thresholdSample >= playSampleMark) {
                    long deltaSamples = thresholdSample - playSampleMark;
                    double ms = deltaSamples * 1000.0 / sampleRate;
                    ui(() -> latencyText.setText(String.format(Locale.US,
                            "Round-trip latency 往返延迟：约 %.0f ms (relative reference 相对参考值)", ms)));
                } else {
                    ui(() -> latencyText.setText("Could not detect the tone in the recording. "
                            + "未能在录音中检测到播放的提示音，请靠近扬声器重试。"));
                }
            } finally {
                if (tone != null) tone.release();
                record.stop();
                record.release();
            }
        });
    }

    private void verifyChannels() {
        String outRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        channelText.setText("Output sample rate 输出采样率：" + outRate + " Hz\n"
                + "Playing left channel… 正在播放左声道…\n"
                + "(THD/distortion metrics need a dedicated audio analyzer and are out of scope; "
                + "phone mics aren't precise enough. THD/失真度需专用音频分析仪，手机麦克风精度不足，不纳入范围)");
        playChannelTone(true);
    }

    private void playChannelTone(boolean left) {
        int sampleRate = 44100;
        int numSamples = sampleRate; // 1 second
        short[] mono = new short[numSamples];
        for (int i = 0; i < numSamples; i++) {
            mono[i] = (short) (Short.MAX_VALUE * 0.5 * Math.sin(2 * Math.PI * 440 * i / sampleRate));
        }
        short[] stereo = new short[numSamples * 2];
        for (int i = 0; i < numSamples; i++) {
            stereo[i * 2] = left ? mono[i] : 0;
            stereo[i * 2 + 1] = left ? 0 : mono[i];
        }
        AudioTrack track = new AudioTrack.Builder()
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
        track.write(stereo, 0, stereo.length);
        track.play();
        String channel = left ? "left 左" : "right 右";
        channelText.setText("Playing " + channel + " channel only — confirm you hear it on that side.\n"
                + "仅播放" + (left ? "左" : "右") + "声道，请确认声音仅从对应一侧发出。");
        main.postDelayed(() -> {
            track.stop();
            track.release();
            if (left) {
                playChannelTone(false);
            } else {
                channelText.setText(channelText.getText() + "\nDone. 测试完成。");
            }
        }, 1200);
    }

    @Override
    protected void onStopTests() {
        stopTone();
        releaseRecorder();
        releasePlayer();
        if (audioManager != null) audioManager.setMode(AudioManager.MODE_NORMAL);
        if (recordFile != null && recordFile.exists()) //noinspection ResultOfMethodCallIgnored
            recordFile.delete();
    }
}

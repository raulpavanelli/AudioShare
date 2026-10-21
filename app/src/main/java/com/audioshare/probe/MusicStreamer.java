package com.audioshare.probe;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.AudioFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decodes the selected track and sends paced 25 ms mono frames to the intercom peers. */
final class MusicStreamer implements Runnable {
    interface Listener {
        int getVolume();
        void onFrame(byte[] pcm, int length);
        void onEnded();
        void onError(String message, Exception error);
    }

    private static final String TAG = "AudioShare";
    private static final int OUTPUT_RATE = 16000;
    private static final int FRAME_SAMPLES = 400;
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;

    private final Context context;
    private final Uri source;
    private final Listener listener;
    private volatile boolean stopped;
    private volatile boolean paused;
    private Thread thread;

    MusicStreamer(Context context, Uri source, Listener listener) {
        this.context = context.getApplicationContext();
        this.source = source;
        this.listener = listener;
    }

    synchronized void start() {
        if (thread != null) return;
        thread = new Thread(this, "AudioShare-MusicStream");
        thread.start();
    }

    void setPaused(boolean value) { paused = value; }

    synchronized void stop() {
        stopped = true;
        Thread active = thread;
        if (active != null) active.interrupt();
    }

    @Override public void run() {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            extractor.setDataSource(context, source, null);
            int audioTrack = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    inputFormat = candidate;
                    break;
                }
            }
            if (audioTrack < 0 || inputFormat == null) throw new IOException("O arquivo não contém uma faixa de áudio compatível.");
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            extractor.selectTrack(audioTrack);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            boolean inputDone = false;
            boolean outputDone = false;
            int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            NetworkFrameEncoder encoder = new NetworkFrameEncoder(listener);

            while (!stopped && !outputDone) {
                if (!paused && !inputDone) {
                    int inputIndex = decoder.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = decoder.getInputBuffer(inputIndex);
                        if (input == null) throw new IOException("Não foi possível acessar o decodificador de áudio.");
                        int sampleSize = extractor.readSampleData(input, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                if (paused) {
                    try { Thread.sleep(8); } catch (InterruptedException ignored) { }
                    continue;
                }

                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int outputIndex = decoder.dequeueOutputBuffer(info, 10000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = decoder.getOutputFormat();
                    if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (android.os.Build.VERSION.SDK_INT >= 24 && outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    Log.i(TAG, "Music decoder output " + sampleRate + " Hz, channels=" + channels + " encoding=" + pcmEncoding);
                } else if (outputIndex >= 0) {
                    ByteBuffer output = decoder.getOutputBuffer(outputIndex);
                    if (output != null && info.size > 0) {
                        byte[] decoded = new byte[info.size];
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        output.get(decoded);
                        encoder.consume(decoded, channels, sampleRate, pcmEncoding);
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(outputIndex, false);
                }
            }
            if (!stopped) listener.onEnded();
        } catch (Exception e) {
            if (!stopped) listener.onError("Não foi possível reproduzir esta faixa", e);
        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) { }
                try { decoder.release(); } catch (Exception ignored) { }
            }
            try { extractor.release(); } catch (Exception ignored) { }
        }
    }

    private final class NetworkFrameEncoder {
        private final Listener listener;
        private final short[] frame = new short[FRAME_SAMPLES];
        private final byte[] packet = new byte[FRAME_BYTES];
        private int framePosition;
        private int inputRate = -1;
        private long resamplePhase;
        private long sampleSum;
        private int sampleCount;
        private long nextFrameAt;

        NetworkFrameEncoder(Listener listener) { this.listener = listener; }

        void consume(byte[] pcm, int channels, int sampleRate, int encoding) {
            if (sampleRate <= 0 || channels <= 0) return;
            if (inputRate != sampleRate) {
                inputRate = sampleRate;
                resamplePhase = 0;
                sampleSum = 0;
                sampleCount = 0;
            }
            boolean floatingPoint = encoding == AudioFormat.ENCODING_PCM_FLOAT;
            int bytesPerSample = floatingPoint ? 4 : 2;
            int bytesPerSourceFrame = bytesPerSample * channels;
            ByteBuffer input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
            for (int offset = 0; offset + bytesPerSourceFrame <= pcm.length; offset += bytesPerSourceFrame) {
                long mono = 0;
                for (int channel = 0; channel < channels; channel++) {
                    int pos = offset + channel * bytesPerSample;
                    int value;
                    if (floatingPoint) {
                        float sample = input.getFloat(pos);
                        value = (int) Math.max(-32768, Math.min(32767, sample * 32767f));
                    } else {
                        value = input.getShort(pos);
                    }
                    mono += value;
                }
                int sample = (int) (mono / channels);
                sampleSum += sample;
                sampleCount++;
                resamplePhase += OUTPUT_RATE;
                while (resamplePhase >= sampleRate) {
                    int volume = listener.getVolume();
                    int scaled = (int) (sampleSum / Math.max(1, sampleCount)) * volume / 100;
                    short output = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
                    append(output);
                    resamplePhase -= sampleRate;
                    sampleSum = 0;
                    sampleCount = 0;
                    while (resamplePhase >= sampleRate) {
                        append(output);
                        resamplePhase -= sampleRate;
                    }
                }
            }
        }

        private void append(short sample) {
            while (paused && !stopped) {
                try { Thread.sleep(8); } catch (InterruptedException ignored) { }
            }
            if (stopped) return;
            frame[framePosition++] = sample;
            if (framePosition < FRAME_SAMPLES) return;
            ByteBuffer output = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
            for (short value : frame) output.putShort(value);
            long now = SystemClock.elapsedRealtime();
            if (nextFrameAt == 0 || now - nextFrameAt > 100) nextFrameAt = now;
            long wait = nextFrameAt - now;
            if (wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException ignored) { }
            }
            listener.onFrame(packet, FRAME_BYTES);
            nextFrameAt += 25;
            framePosition = 0;
        }
    }
}

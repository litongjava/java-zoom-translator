package com.litongjava.zoom.translator;

import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AudioRecorder {

  private TargetDataLine line;
  private AudioFormat format;
  private BlockingQueue<byte[]> audioQueue;
  private volatile boolean running;
  private Thread recordingThread;

  // Audio settings for Google Speech-to-Text
  private static final int SAMPLE_RATE = 16000; // Hz
  private static final int SAMPLE_SIZE_IN_BITS = 16;
  private static final int CHANNELS = 1; // Mono
  private static final boolean SIGNED = true;
  private static final boolean BIG_ENDIAN = false; // Little-endian for Google STT

  // Buffer size (e.g., 100ms of audio at 16kHz, 16-bit mono = 16000  2 bytes/sec  0.1 sec = 3200 bytes)
  private static final int BUFFER_SIZE = SAMPLE_RATE * SAMPLE_SIZE_IN_BITS / 8 * CHANNELS / 10;

  public AudioRecorder(TargetDataLine line, BlockingQueue<byte[]> audioQueue) throws LineUnavailableException {
    this.line = line;
    this.audioQueue = audioQueue;
    this.format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, CHANNELS, SIGNED, BIG_ENDIAN);

    if (!line.isOpen()) {
      line.open(format, BUFFER_SIZE * 2); // Open with a larger internal buffer
      log.info("Audio line opened: " + line.getLineInfo());
    }
  }

  public void startRecording() {
    if (running)
      return;

    running = true;
    line.start(); // Start capturing audio
    log.info("Audio recording started on line: " + line.getLineInfo());

    recordingThread = new Thread(() -> {
      byte[] buffer = new byte[BUFFER_SIZE];
      int bytesRead;

      while (running) {
        bytesRead = line.read(buffer, 0, buffer.length);
        if (bytesRead > 0) {
          try {
            // Make a copy to avoid buffer modification issues
            byte[] audioChunk = new byte[bytesRead];
            System.arraycopy(buffer, 0, audioChunk, 0, bytesRead);
            audioQueue.put(audioChunk);
          } catch (InterruptedException e) {
            log.warn("Audio recording thread interrupted while putting to queue.", e);
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
      log.info("Audio recording thread stopped.");
    }, "AudioRecordingThread");
    recordingThread.start();
  }

  public void stopRecording() {
    running = false;
    if (recordingThread != null) {
      recordingThread.interrupt(); // Interrupt the thread
      try {
        recordingThread.join(1000); // Wait for it to finish
      } catch (InterruptedException e) {
        log.warn("Audio recording thread did not stop gracefully.");
        Thread.currentThread().interrupt();
      }
    }
    if (line != null && line.isRunning()) {
      line.stop();
      line.close();
      log.info("Audio line stopped and closed.");
    }
  }
}
package com.litongjava.zoom.translator;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import com.google.auth.oauth2.GoogleCredentials;

import lombok.extern.slf4j.Slf4j;

@SuppressWarnings("serial")
@Slf4j
public class ZoomRealtimeTranslatorUI extends JFrame {

  private JTextArea originalTextArea;
  private JTextArea translatedTextArea;
  private JButton startButton;
  private JButton stopButton;

  private AudioRecorder audioRecorder;
  private SpeechToTextService speechToTextService;
  private TranslationService translationService;

  // Queue to hold audio chunks for STT processing
  private BlockingQueue<byte[]> audioQueue = new LinkedBlockingQueue<>();
  // Queue to hold transcribed English text for translation
  private BlockingQueue<String> originalTextQueue = new LinkedBlockingQueue<>();

  private Thread audioProcessorThread;
  private Thread translationProcessorThread;

  public ZoomRealtimeTranslatorUI() {
    super("Zoom Realtime Translator");
    initComponents();
    setupServices();
  }

  private void initComponents() {
    setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    setSize(800, 600);
    setLayout(new BorderLayout());

    JPanel mainPanel = new JPanel(new GridLayout(1, 2, 10, 0)); // Two columns for original and translated text

    originalTextArea = new JTextArea();
    originalTextArea.setEditable(false);
    originalTextArea.setLineWrap(true);
    originalTextArea.setWrapStyleWord(true);
    JScrollPane originalScrollPane = new JScrollPane(originalTextArea);
    originalScrollPane.setBorder(BorderFactory.createTitledBorder("Original English Text"));
    mainPanel.add(originalScrollPane);

    translatedTextArea = new JTextArea();
    translatedTextArea.setEditable(false);
    translatedTextArea.setLineWrap(true);
    translatedTextArea.setWrapStyleWord(true);
    JScrollPane translatedScrollPane = new JScrollPane(translatedTextArea);
    translatedScrollPane.setBorder(BorderFactory.createTitledBorder("Translated Chinese Text"));
    mainPanel.add(translatedScrollPane);

    add(mainPanel, BorderLayout.CENTER);

    JPanel controlPanel = new JPanel();
    startButton = new JButton("Start Translation");
    stopButton = new JButton("Stop Translation");
    stopButton.setEnabled(false); // Disable initially

    controlPanel.add(startButton);
    controlPanel.add(stopButton);
    add(controlPanel, BorderLayout.SOUTH);

    startButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        startTranslation();
      }
    });

    stopButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        stopTranslation();
      }
    });
  }

  private void setupServices() {
    try {
      // --- START MODIFICATION ---
      // 从 resources 目录加载 Google Cloud 凭据文件
      InputStream credentialsStream = getClass().getClassLoader().getResourceAsStream("google/gen-lang-client-key.json");
      if (credentialsStream == null) {
        throw new IOException(
            "Google Cloud credentials file 'gen-lang-client-key.json' not found in src/main/resources/google. " + "Please ensure it's named 'key.json' and placed directly under src/main/resources.");
      }
      GoogleCredentials credentials = GoogleCredentials.fromStream(credentialsStream);
      credentialsStream.close(); // 加载后关闭流

      speechToTextService = new SpeechToTextService(credentials); // 传递凭据
      translationService = new TranslationService(credentials); // 传递凭据
      // --- END MODIFICATION ---

      // Important: Select the correct audio input line here.
      TargetDataLine inputLine = getAudioInputLine();
      audioRecorder = new AudioRecorder(inputLine, audioQueue);
    } catch (IOException | LineUnavailableException e) {
      log.error("Failed to initialize services:{}" + e.getMessage(), e);
      JOptionPane.showMessageDialog(this, "Failed to initialize audio or cloud services: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
      startButton.setEnabled(false);
    }
  }

  // Helper to find the correct audio input line (e.g., Stereo Mix)
  private TargetDataLine getAudioInputLine() throws LineUnavailableException {
    // Define desired format
    AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 16000, 16, 1, 2, 16000, false);
    DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

    if (!AudioSystem.isLineSupported(info)) {
      log.error("Line for audio format not supported: " + format);
      throw new LineUnavailableException("Audio format not supported.");
    }

    // Try to find a specific mixer/line for system audio capture
    Mixer.Info[] mixerInfo = AudioSystem.getMixerInfo();
    log.info("Available audio mixers:");
    for (Mixer.Info mInfo : mixerInfo) {
      Mixer mixer = AudioSystem.getMixer(mInfo);
      log.info("  Mixer Name: " + mInfo.getName() + ", Description: " + mInfo.getDescription());
      Line.Info[] targetLineInfos = mixer.getTargetLineInfo(); // TargetDataLine is a TargetLine
      for (Line.Info lineInfo : targetLineInfos) {
        log.info("    Line Type: " + lineInfo.getLineClass().getName() + ", Line Info: " + lineInfo.toString());
        if (lineInfo instanceof DataLine.Info) {
          DataLine.Info dataLineInfo = (DataLine.Info) lineInfo;
          for (AudioFormat af : dataLineInfo.getFormats()) {
            log.info("      Supported Format: " + af);
            if (af.equals(format)) {
              // On Windows, look for "Stereo Mix" or similar
              // On macOS with BlackHole, it might be named "BlackHole 2ch"
              if (mInfo.getName().contains("Stereo Mix") || mInfo.getName().contains("BlackHole")) {
                log.info("Found suitable mixer and line: " + mInfo.getName());
                return (TargetDataLine) mixer.getLine(info);
              }
            }
          }
        }
      }
    }

    // Fallback to default if specific line not found (might capture mic)
    log.warn("Could not find a specific system audio input line (e.g., Stereo Mix/BlackHole). Attempting to use default.");
    return (TargetDataLine) AudioSystem.getLine(info);
  }

  private void startTranslation() {
    startButton.setEnabled(false);
    stopButton.setEnabled(true);
    originalTextArea.setText("");
    translatedTextArea.setText("");
    audioQueue.clear();
    originalTextQueue.clear();

    // Start audio recording in a separate thread
    audioRecorder.startRecording();
    log.info("Audio recording started.");

    // Start thread to send audio to STT service
    audioProcessorThread = new Thread(() -> {
      speechToTextService.startStreamingRecognize(result -> {
        // This callback is invoked when STT returns a result
        if (result != null && !result.isEmpty()) {
          SwingUtilities.invokeLater(() -> {
            originalTextArea.append(result + "\n");
            originalTextQueue.offer(result); // Add to translation queue
          });
        }
      }, audioQueue // Pass the queue to STT service
      );
    });
    audioProcessorThread.start();
    log.info("Speech-to-Text processor thread started.");

    // Start thread to send English text to translation service
    translationProcessorThread = new Thread(() -> {
      try {
        while (!Thread.currentThread().isInterrupted()) {
          String originalText = originalTextQueue.take(); // Blocks until text is available
          if (originalText.equals("STOP_SIGNAL")) { // Sentinel value to stop thread
            break;
          }
          String translatedText = translationService.translate(originalText, "en", "zh-CN"); // English to Simplified Chinese
          SwingUtilities.invokeLater(() -> {
            translatedTextArea.append(translatedText + "\n");
          });
        }
      } catch (InterruptedException e) {
        log.info("Translation processor thread interrupted.");
        Thread.currentThread().interrupt();
      } catch (IOException e) {
        log.error("Translation failed: " + e.getMessage(), e);
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Translation Error: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
      } finally {
        log.info("Translation processor thread stopped.");
      }
    });
    translationProcessorThread.start();
    log.info("Translation processor thread started.");
  }

  private void stopTranslation() {
    startButton.setEnabled(true);
    stopButton.setEnabled(false);

    if (audioRecorder != null) {
      audioRecorder.stopRecording();
      log.info("Audio recording stopped.");
    }
    if (speechToTextService != null) {
      speechToTextService.stopStreamingRecognize();
      log.info("Speech-to-Text service stopped.");
    }

    // Send stop signal to translation thread and interrupt it
    if (translationProcessorThread != null && translationProcessorThread.isAlive()) {
      originalTextQueue.offer("STOP_SIGNAL"); // Sentinel value
      translationProcessorThread.interrupt();
      try {
        translationProcessorThread.join(5000); // Wait for thread to finish gracefully
      } catch (InterruptedException e) {
        log.warn("Translation processor thread did not stop gracefully.");
        Thread.currentThread().interrupt();
      }
    }
    if (audioProcessorThread != null && audioProcessorThread.isAlive()) {
      // The STT thread will stop when audioRecorder stops feeding the queue
      audioProcessorThread.interrupt(); // Interrupt directly if it's blocked on network
      try {
        audioProcessorThread.join(5000); // Wait
      } catch (InterruptedException e) {
        log.warn("STT processor thread did not stop gracefully.");
        Thread.currentThread().interrupt();
      }
    }
    log.info("Translation stopped.");
  }

}
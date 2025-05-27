package com.litongjava.zoom.translator;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.ClientStream;
import com.google.api.gax.rpc.ResponseObserver;
import com.google.api.gax.rpc.StreamController;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.speech.v1p1beta1.RecognitionConfig;
import com.google.cloud.speech.v1p1beta1.SpeechClient;
import com.google.cloud.speech.v1p1beta1.SpeechSettings;
import com.google.cloud.speech.v1p1beta1.StreamingRecognitionConfig;
import com.google.cloud.speech.v1p1beta1.StreamingRecognitionResult;
import com.google.cloud.speech.v1p1beta1.StreamingRecognizeRequest;
import com.google.cloud.speech.v1p1beta1.StreamingRecognizeResponse;
import com.google.protobuf.ByteString;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SpeechToTextService {

  private SpeechClient speechClient;
  private ClientStream<StreamingRecognizeRequest> clientStream;
  private ResponseObserver<StreamingRecognizeResponse> responseObserver;
  private Consumer<String> resultCallback;
  private BlockingQueue<byte[]> audioQueue;
  private volatile boolean streaming = false;
  private Thread audioSenderThread;

  public SpeechToTextService(GoogleCredentials credentials) throws IOException {
    SpeechSettings speechSettings = SpeechSettings.newBuilder()
        //
        .setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build();
    speechClient = SpeechClient.create(speechSettings);
  }

  public void startStreamingRecognize(Consumer<String> callback, BlockingQueue<byte[]> audioQueue) {
    if (streaming) {
      log.warn("STT streaming already in progress.");
      return;
    }
    this.resultCallback = callback;
    this.audioQueue = audioQueue;
    streaming = true;

    responseObserver = new ResponseObserver<StreamingRecognizeResponse>() {
      @Override
      public void onStart(StreamController controller) {
        log.info("STT stream started.");
      }

      @Override
      public void onResponse(StreamingRecognizeResponse response) {
        StreamingRecognitionResult result = response.getResultsList().get(0);
        if (result != null && result.getIsFinal()) { // Only process final results
          String transcript = result.getAlternativesList().get(0).getTranscript();
          log.info("STT Result: " + transcript);
          resultCallback.accept(transcript);
        }
      }

      @Override
      public void onError(Throwable t) {
        log.error("STT stream error: " + t.getMessage(), t);
        streaming = false;
        stopStreamingRecognize();
        // Notify UI about error
        if (resultCallback != null) {
          resultCallback.accept("ERROR: " + t.getMessage());
        }
      }

      @Override
      public void onComplete() {
        log.info("STT stream completed.");
        streaming = false;
      }
    };

    clientStream = speechClient.streamingRecognizeCallable().splitCall(responseObserver);

    // Build the first request for configuration
    RecognitionConfig recognitionConfig = RecognitionConfig.newBuilder().setEncoding(RecognitionConfig.AudioEncoding.LINEAR16).setSampleRateHertz(16000) // Must match AudioRecorder
        .setLanguageCode("en-US") // Source language is English
        .build();

    StreamingRecognitionConfig streamingRecognitionConfig = StreamingRecognitionConfig.newBuilder().setConfig(recognitionConfig).setInterimResults(false) // Only final results
        .setSingleUtterance(false) // Continuous recognition
        .build();

    StreamingRecognizeRequest request = StreamingRecognizeRequest.newBuilder().setStreamingConfig(streamingRecognitionConfig).build();

    clientStream.send(request); // Send configuration request first

    audioSenderThread = new Thread(() -> {
      try {
        while (streaming) {
          byte[] audioChunk = audioQueue.take(); // Blocks until audio is available
          if (!streaming)
            break; // Check flag again after taking from queue
          StreamingRecognizeRequest audioRequest = StreamingRecognizeRequest.newBuilder().setAudioContent(ByteString.copyFrom(audioChunk)).build();
          clientStream.send(audioRequest);
        }
      } catch (InterruptedException e) {
        log.info("STT audio sender thread interrupted.");
        Thread.currentThread().interrupt();
      } finally {
        clientStream.closeSend(); // Close the send stream when done
        log.info("STT audio sender thread stopped. Sending stream closed.");
      }
    }, "STTAudioSenderThread");
    audioSenderThread.start();
    log.info("STT streaming started.");
  }

  public void stopStreamingRecognize() {
    streaming = false;
    if (audioSenderThread != null) {
      audioSenderThread.interrupt();
      try {
        audioSenderThread.join(5000); // Wait for thread to finish
      } catch (InterruptedException e) {
        log.warn("STT audio sender thread did not stop gracefully.");
        Thread.currentThread().interrupt();
      }
    }
    if (responseObserver != null) {
      // responseObserver.onComplete() might not be called if stream closes due to error or interruption
      // but closing the client stream will eventually lead to completion or error.
    }
    if (clientStream != null) {
      // clientStream.closeSend(); // Already called in finally block of sender thread
    }
  }

  public void shutdown() {
    stopStreamingRecognize();
    if (speechClient != null) {
      speechClient.shutdown();
      try {
        speechClient.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      log.info("SpeechClient shut down.");
    }
  }
}
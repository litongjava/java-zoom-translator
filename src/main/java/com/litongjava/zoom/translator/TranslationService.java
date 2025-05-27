package com.litongjava.zoom.translator;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials; // <-- 新增导入
import com.google.cloud.translate.v3.LocationName;
import com.google.cloud.translate.v3.TranslateTextRequest;
import com.google.cloud.translate.v3.TranslateTextResponse;
import com.google.cloud.translate.v3.TranslationServiceClient;
import com.google.cloud.translate.v3.TranslationServiceSettings;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TranslationService {

  private TranslationServiceClient client;
  private String projectId;

  public TranslationService(GoogleCredentials credentials) throws IOException {
    TranslationServiceSettings translationServiceSettings = TranslationServiceSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build();
    client = TranslationServiceClient.create(translationServiceSettings);

    // --- START MODIFICATION ---
    // 1. 尝试从 credentials.getQuotaProjectId() 获取
    this.projectId = credentials.getQuotaProjectId();

    // 2. 如果 quotaProjectId 为 null，尝试从 clientEmail 中解析
    if (this.projectId == null || this.projectId.isEmpty()) {
      String clientEmail = null;
      // 检查 credentials 是否是 ServiceAccountCredentials 的实例，然后进行转型
      if (credentials instanceof ServiceAccountCredentials) {
        ServiceAccountCredentials saCredentials = (ServiceAccountCredentials) credentials;
        clientEmail = saCredentials.getClientEmail(); // 现在可以调用 getClientEmail() 了
        log.info("Attempting to extract Project ID from ServiceAccountCredentials client email.");
      } else {
        log.warn("Credentials are not ServiceAccountCredentials. Cannot extract client email directly.");
      }

      if (clientEmail != null && clientEmail.contains("@") && clientEmail.contains(".iam.gserviceaccount.com")) {
        try {
          // clientEmail 格式通常是: service-account-name@project-id.iam.gserviceaccount.com
          // 我们需要提取 project-id
          int atIndex = clientEmail.indexOf('@');
          int dotIamIndex = clientEmail.indexOf(".iam.gserviceaccount.com");
          if (atIndex != -1 && dotIamIndex != -1 && dotIamIndex > atIndex) {
            this.projectId = clientEmail.substring(atIndex + 1, dotIamIndex);
            log.info("Extracted Project ID from client email: " + this.projectId);
          }
        } catch (Exception e) {
          log.warn("Failed to extract project ID from client email: " + clientEmail, e);
        }
      }
    }

    // 3. 如果仍然为 null，则回退到环境变量或硬编码的默认值
    if (this.projectId == null || this.projectId.isEmpty()) {
      log.warn("Project ID still not determined from credentials. Falling back to GOOGLE_CLOUD_PROJECT env var or hardcoded default.");
      this.projectId = System.getenv("GOOGLE_CLOUD_PROJECT") != null ? System.getenv("GOOGLE_CLOUD_PROJECT") : "your-gcp-project-id-fallback";
    }
    // --- END MODIFICATION ---
    log.info("TranslationService initialized for project: " + projectId);
  }

  public String translate(String text, String sourceLanguage, String targetLanguage) throws IOException {
    if (text == null || text.trim().isEmpty()) {
      return "";
    }

    LocationName parent = LocationName.of(projectId, "global");

    TranslateTextRequest request = TranslateTextRequest.newBuilder().setParent(parent.toString()).setMimeType("text/plain").setSourceLanguageCode(sourceLanguage).setTargetLanguageCode(targetLanguage)
        .addContents(text).build();

    TranslateTextResponse response = client.translateText(request);

    StringBuilder translatedText = new StringBuilder();
    response.getTranslationsList().forEach(translation -> translatedText.append(translation.getTranslatedText()));

    log.info("Translated '" + text + "' to '" + translatedText.toString() + "'");
    return translatedText.toString();
  }

  public void shutdown() {
    if (client != null) {
      client.shutdown();
      try {
        client.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      log.info("TranslationClient shut down.");
    }
  }
}
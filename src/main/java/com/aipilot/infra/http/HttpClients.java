package com.aipilot.infra.http;

import com.aipilot.infra.config.AppConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

public final class HttpClients {
    private HttpClients() {
    }

    public static HttpClient defaultClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public static HttpClient defaultClient(AppConfig config) {
        if (config != null && shouldTrustAll(config)) {
            return trustingBuilder().build();
        }
        return defaultClient();
    }

    private static boolean shouldTrustAll(AppConfig config) {
        if (config.trustAllTls) {
            return true;
        }
        String url = config.apiBaseUrl == null ? "" : config.apiBaseUrl.toLowerCase();
        return url.contains("192.168.") || url.contains("127.0.0.1") || url.contains("localhost");
    }

    public static HttpClient.Builder trustingBuilder() {
        try {
            TrustManager[] trust = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trust, new SecureRandom());
            SSLParameters params = new SSLParameters();
            params.setEndpointIdentificationAlgorithm("");
            return HttpClient.newBuilder()
                    .sslContext(ctx)
                    .sslParameters(params)
                    .connectTimeout(Duration.ofSeconds(15));
        } catch (Exception e) {
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
        }
    }
}

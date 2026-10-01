package com.mirto.launcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

public class SSLBypass {
    private static final Logger LOG = LoggerFactory.getLogger(SSLBypass.class);
    private static SSLContext defaultContext;
    private static HostnameVerifier defaultHostnameVerifier;

    public static void disableSSLVerification() {
        try {
            // Remember the JVM defaults so they can be restored later (the bypass
            // is now applied per connection instead of globally at startup).
            if (defaultContext == null) {
                defaultContext = SSLContext.getDefault();
                defaultHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
            }

            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }

                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }
                    }
            };

            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            // Disable hostname verification
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

            LOG.warn("SSL verification disabled. Trusting all certificates.");
        } catch (Exception e) {
            LOG.error("Failed to disable SSL verification", e);
        }
    }

    /**
     * Restores the JVM default SSL socket factory and hostname verifier captured
     * before {@link #disableSSLVerification()} was first called. Safe to call even
     * if the bypass was never enabled.
     */
    public static void restoreDefaults() {
        if (defaultContext != null) {
            HttpsURLConnection.setDefaultSSLSocketFactory(defaultContext.getSocketFactory());
        }
        if (defaultHostnameVerifier != null) {
            HttpsURLConnection.setDefaultHostnameVerifier(defaultHostnameVerifier);
        }
    }
}
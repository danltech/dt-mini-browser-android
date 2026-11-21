/*
 * Copyright 2026 DanlTech
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.danltech.minibrowser;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Minimaler HTTP(S)-Client. gzip handhabt HttpURLConnection auf Android selbst (transparent). */
public class Net {

    private static final String TAG = "Net";

    /** Server kennt die Sitzung nicht (mehr). */
    public static class NoSession extends IOException {
        public NoSession() {
            super("session");
        }
    }

    /** Auth-Token (X-Mini-Token), optional. */
    private static String authToken = "";
    /** SHA-256 Fingerprint des Server-Zertifikats (Hex klein), optional. */
    private static String certFp = "";

    public static void setAuthToken(String t) {
        authToken = t != null ? t : "";
    }

    public static String getAuthToken() {
        return authToken;
    }

    public static void setCertFingerprint(String fp) {
        certFp = fp != null ? fp.trim().toLowerCase(Locale.US) : "";
    }

    public static String getCertFingerprint() {
        return certFp;
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    public static String getString(String url) throws IOException {
        byte[] b = fetch(url);
        return b == null ? "" : new String(b, "UTF-8");
    }

    /** Liefert null bei HTTP 204 (Bild nicht darstellbar). */
    public static byte[] getBytes(String url) throws IOException {
        return fetch(url);
    }

    private static byte[] fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(70000);
        if (authToken.length() > 0) {
            c.setRequestProperty("X-Mini-Token", authToken);
        }
        if (c instanceof HttpsURLConnection) {
            installPinnedSsl((HttpsURLConnection) c, certFp);
        }
        try {
            int code = c.getResponseCode();
            if (code == 204) return null;
            if (code == 404) throw new NoSession();
            if (code == 401) throw new IOException("HTTP 401: unauthorized (Token pruefen)");
            if (code >= 400) {
                String body = new String(read(c.getErrorStream()), "UTF-8");
                throw new IOException("HTTP " + code + ": " + body);
            }
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * TrustManager: bei gesetztem Fingerprint nur dieses Zertifikat, sonst (Dev)
     * jedes Zertifikat akzeptieren – Hostname-Check aus (selbstsigniert / IP).
     * TLS 1.2 auf Android 4.x aktivieren (sonst "connection closed by peer").
     */
    private static void installPinnedSsl(HttpsURLConnection c, final String expectedFp) {
        try {
            final boolean pin = expectedFp != null && expectedFp.length() > 0;
            TrustManager[] tms = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType)
                                throws java.security.cert.CertificateException {
                            if (chain == null || chain.length == 0) {
                                throw new java.security.cert.CertificateException("empty chain");
                            }
                            if (!pin) {
                                // Kein Fingerprint gesetzt: selbstsigniert trotzdem erlauben
                                return;
                            }
                            String fp = sha256Hex(chain[0].getEncoded());
                            if (!expectedFp.equalsIgnoreCase(fp)) {
                                throw new java.security.cert.CertificateException(
                                        "cert fingerprint mismatch: got " + fp);
                            }
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tms, new java.security.SecureRandom());
            SSLSocketFactory base = ctx.getSocketFactory();
            // Android 4.x: TLS 1.2 standardmaessig aus – ohne das bricht Handshake ab
            c.setSSLSocketFactory(new Tls12SocketFactory(base));
            c.setHostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "SSL pin setup failed: " + e);
        }
    }

    /** SSLSocketFactory, die TLSv1.2 freischaltet (API 16–20). */
    private static final class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        Tls12SocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private Socket patch(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) s;
                try {
                    // Android 4.1: TLS 1.0/1.1 zuerst; 1.2 nur wenn vorhanden
                    String[] supported = ssl.getSupportedProtocols();
                    List<String> enabled = new ArrayList<String>();
                    // Reihenfolge: erst was alte Geraete sicher koennen
                    String[] prefer = new String[] { "TLSv1", "TLSv1.1", "TLSv1.2" };
                    for (String want : prefer) {
                        for (String p : supported) {
                            if (want.equals(p)) {
                                enabled.add(p);
                                break;
                            }
                        }
                    }
                    if (!enabled.isEmpty()) {
                        ssl.setEnabledProtocols(enabled.toArray(new String[0]));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "enable TLS protocols: " + e);
                }
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose)
                throws IOException {
            return patch(delegate.createSocket(s, host, port, autoClose));
        }

        @Override public Socket createSocket(String host, int port) throws IOException {
            return patch(delegate.createSocket(host, port));
        }

        @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException {
            return patch(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return patch(delegate.createSocket(host, port));
        }

        @Override public Socket createSocket(InetAddress address, int port,
                                             InetAddress localAddress, int localPort) throws IOException {
            return patch(delegate.createSocket(address, port, localAddress, localPort));
        }
    }

    private static String sha256Hex(byte[] der) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(der);
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format(Locale.US, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
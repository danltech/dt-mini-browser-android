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
package dt.minibrowser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Minimaler HTTP-Client. gzip handhabt HttpURLConnection auf Android selbst (transparent). */
public class Net {

    /** Server kennt die Sitzung nicht (mehr). */
    public static class NoSession extends IOException {
        public NoSession() {
            super("session");
        }
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
        try {
            int code = c.getResponseCode();
            if (code == 204) return null;
            if (code == 404) throw new NoSession();
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
            ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}

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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicInteger;

/** Ein vom Server berechneter Seitenzustand (Display List). */
public class Page {
    private static final AtomicInteger COUNTER = new AtomicInteger();

    public static class Item {
        public char type;               // r, t, i, a, f
        public float x, y, w, h;        // CSS-px, Seitenkoordinaten
        public String s = "";           // t: Text, a: href, f: kind
        public String text = "";        // f: angezeigter Text
        public int color;               // ARGB
        public int flags;               // t: 1 fett, 2 kursiv, 4 unterstrichen, 8 durchgestrichen, 16 mono
        public int id = -1;             // i: Bild-ID
        public int mode;                // i: 0 strecken, 1 cover
        public float size;              // Schriftgroesse (CSS-px)
        public float radius;            // r: Eckenradius
        public float[] clip;            // i: optionales Clip-Rechteck
        public boolean checked, placeholder;
        public int blk = -1;            // t: Absatz-/Block-ID vom Server (-1 = unbekannt)
        public float textScaleX = -1f;  // Cache fuer die Textbreiten-Anpassung
        // Cache fuer das Text-Layout (wird beim ersten Zeichnen berechnet)
        public float layoutScale = -1f; // Zoomfaktor, fuer den das Layout berechnet wurde
        public boolean perWord;         // true: Woerter einzeln setzen (Abstaende bleiben erhalten)
        public float fontMul = 1f;      // Schriftverkleinerung, falls Zeile sonst zu stark gestaucht wuerde
        public String[] words;          // perWord: die Woerter
        public float[] wx;              // perWord: x-Versatz je Wort (Bildschirm-px)
    }

    public final int gen = COUNTER.incrementAndGet();
    public String sid = "", url = "", title = "", err;
    public float w = 360f, h = 640f;
    public int bg = 0xFFFFFFFF;
    public String focusType, focusValue = "", focusPh = "";
    public Item[] items = new Item[0];
    // Zertifikat (vom Server)
    public boolean https;
    public boolean certOk;
    public String certSubject = "", certIssuer = "", certProtocol = "";
    public long certValidFrom, certValidTo;
    // Download-Hinweis vom Server
    public String downloadId, downloadName;
    public long downloadSize;
    public String external;

    public static Page parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        Page p = new Page();
        p.sid = o.optString("sid", "");
        p.url = o.optString("url", "");
        p.title = o.optString("title", "");
        p.w = (float) o.optDouble("w", 360);
        p.h = (float) o.optDouble("h", 640);
        p.bg = (int) o.optLong("bg", 0xFFFFFFFFL);
        if (o.has("err") && !o.isNull("err")) p.err = o.optString("err");

        JSONObject cert = o.optJSONObject("cert");
        if (cert != null) {
            p.https = cert.optBoolean("https", false);
            p.certOk = cert.optBoolean("ok", p.https);
            p.certSubject = cert.optString("subject", "");
            p.certIssuer = cert.optString("issuer", "");
            p.certProtocol = cert.optString("protocol", "");
            p.certValidFrom = (long) cert.optDouble("validFrom", 0);
            p.certValidTo = (long) cert.optDouble("validTo", 0);
        } else if (p.url.startsWith("https://")) {
            p.https = true;
            p.certOk = true;
        }

        JSONObject dl = o.optJSONObject("download");
        if (dl != null) {
            p.downloadId = dl.optString("id", "");
            p.downloadName = dl.optString("name", "download.bin");
            p.downloadSize = dl.optLong("size", 0);
        }
        if (o.has("external") && !o.isNull("external")) {
            p.external = o.optString("external", "");
        }

        JSONObject f = o.optJSONObject("focus");
        if (f != null) {
            p.focusType = f.optString("type", "text");
            p.focusValue = f.optString("value", "");
            p.focusPh = f.optString("ph", "");
        }

        JSONArray a = o.getJSONArray("items");
        Item[] items = new Item[a.length()];
        for (int i = 0; i < items.length; i++) {
            JSONArray t = a.getJSONArray(i);
            Item it = new Item();
            it.type = t.getString(0).charAt(0);
            it.x = (float) t.getDouble(1);
            it.y = (float) t.getDouble(2);
            it.w = (float) t.getDouble(3);
            it.h = (float) t.getDouble(4);
            switch (it.type) {
                case 'r':
                    it.color = (int) t.getLong(5);
                    it.radius = (float) t.optDouble(6, 0);
                    break;
                case 't':
                    it.s = t.getString(5);
                    it.color = (int) t.getLong(6);
                    it.size = (float) t.getDouble(7);
                    it.flags = t.getInt(8);
                    it.blk = t.optInt(9, -1);
                    break;
                case 'i':
                    it.id = t.getInt(5);
                    it.mode = t.optInt(6, 0);
                    if (t.length() > 7) {
                        JSONArray c = t.getJSONArray(7);
                        it.clip = new float[]{(float) c.getDouble(0), (float) c.getDouble(1),
                                (float) c.getDouble(2), (float) c.getDouble(3)};
                    }
                    break;
                case 'a':
                    it.s = t.getString(5);
                    it.text = t.optString(6, "");   // sichtbarer Linktext (neuere Server)
                    break;
                case 'f':
                    it.s = t.getString(5);
                    it.text = t.getString(6);
                    it.checked = t.getInt(7) == 1;
                    it.placeholder = t.getInt(8) == 1;
                    it.color = (int) t.getLong(9);
                    it.size = (float) t.getDouble(10);
                    break;
                default:
                    break;
            }
            items[i] = it;
        }
        p.items = items;
        return p;
    }
}
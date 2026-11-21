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

import android.annotation.TargetApi;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.util.Log;
import android.util.LruCache;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Zeichnet eine Page (Display List) mit Scrollen, Fling und Pinch-Zoom. */
public class PageView extends View {
    private static final String TAG = "MiniClient";

    public interface Listener {
        /** Tap an Seitenkoordinaten (CSS-px). href gesetzt wenn Link getroffen. */
        void onTap(float pageX, float pageY, String href);

        /** Langer Druck auf einen Link: Adresse und sichtbarer Linktext. */
        void onLinkLongPress(String href, String text);
    }

    private final float density;
    private Listener listener;
    private Page page;
    private String serverBase = "";
    private String hint = "";

    private float scale = 1f, fitScale = 1f, maxScale = 4f, offX, offY;
    private boolean scaleSet, loading, debug;
    private int frames;
    private Page.Item pressed;

    private final OverScroller scroller;
    private final GestureDetector gd;
    private final ScaleGestureDetector sgd;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    private final RectF rf = new RectF();
    private final Rect src = new Rect();
    private final Typeface[] faces = new Typeface[32];

    private final LruCache<String, Bitmap> cache;
    private final Set<String> pending = Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> failed = Collections.synchronizedSet(new HashSet<String>());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    public PageView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        scroller = new OverScroller(context);
        gd = new GestureDetector(context, new GL());
        gd.setIsLongpressEnabled(true);
        sgd = new ScaleGestureDetector(context, new SL());
        stroke.setStyle(Paint.Style.STROKE);
        int max = (int) Math.min(Runtime.getRuntime().maxMemory() / 8, 48L * 1024 * 1024);
        cache = new LruCache<String, Bitmap>(max) {
            @Override
            protected int sizeOf(String key, Bitmap b) {
                return b.getByteCount();
            }
        };
    }

    // ------------------------------------------------------------------ API

    public void setListener(Listener l) { listener = l; }
    public void setServerBase(String s) { serverBase = s; }
    public void setHint(String s) { hint = s; invalidate(); }
    public void setLoading(boolean b) { loading = b; invalidate(); }
    public void setDebug(boolean b) { debug = b; invalidate(); }
    public boolean isDebug() { return debug; }
    public Page getPage() { return page; }

    public void setPage(Page p, boolean keepScroll) {
        boolean keep = keepScroll && page != null && Math.abs(page.w - p.w) < 1f;
        clearSelection();
        page = p;
        pressed = null;
        pending.clear();
        if (!keep) {
            scaleSet = false;
            offX = 0;
            offY = 0;
        }
        updateFit();
        clampOffsets();
        invalidate();
    }

    // ------------------------------------------------------------------ Geometrie

    private void updateFit() {
        int vw = getWidth();
        if (vw <= 0 || page == null) return;
        fitScale = vw / Math.max(1f, page.w);
        maxScale = Math.max(fitScale * 1.01f, density * 4f);
        if (!scaleSet) {
            scale = fitScale;
            scaleSet = true;
        }
        scale = Math.max(fitScale, Math.min(maxScale, scale));
    }

    private float maxOffX() {
        return page == null ? 0 : Math.max(0f, page.w * scale - getWidth());
    }

    private float maxOffY() {
        return page == null ? 0 : Math.max(0f, page.h * scale - getHeight());
    }

    private void clampOffsets() {
        offX = Math.max(0f, Math.min(maxOffX(), offX));
        offY = Math.max(0f, Math.min(maxOffY(), offY));
    }

    private void zoomAt(float newScale, float fx, float fy) {
        if (page == null) return;
        newScale = Math.max(fitScale, Math.min(maxScale, newScale));
        float px = (offX + fx) / scale;
        float py = (offY + fy) / scale;
        scale = newScale;
        offX = px * scale - fx;
        offY = py * scale - fy;
        clampOffsets();
        invalidate();
        onViewMoved();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        updateFit();
        clampOffsets();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        clearSelection();
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ Zeichnen

    @Override
    protected void onDraw(Canvas c) {
        long t0 = System.nanoTime();
        int vw = getWidth(), vh = getHeight();
        if (page == null) {
            c.drawColor(0xFFFFFFFF);
            drawHint(c, vw, vh);
            return;
        }
        c.drawColor(page.bg);
        int drawn = 0;
        for (Page.Item it : page.items) {
            float dx = it.x * scale - offX, dy = it.y * scale - offY;
            float dw = it.w * scale, dh = it.h * scale;
            if (dy > vh || dy + dh < 0 || dx > vw || dx + dw < 0) continue;
            drawn++;
            switch (it.type) {
                case 'r': drawRect(c, it, dx, dy, dw, dh); break;
                case 't': drawText(c, it, dx, dy, dw, dh); break;
                case 'i': drawImage(c, it, dx, dy, dw, dh); break;
                case 'f': drawField(c, it, dx, dy, dw, dh); break;
                default: break;
            }
            if (debug) debugBox(c, it, dx, dy, dw, dh);
        }
        if (selActive) drawSelection(c);
        if (pressed != null) {
            fill.setColor(0x442A7FFF);
            c.drawRect(pressed.x * scale - offX, pressed.y * scale - offY,
                    (pressed.x + pressed.w) * scale - offX, (pressed.y + pressed.h) * scale - offY, fill);
        }
        if (loading) {
            fill.setColor(0xFF2A7FFF);
            c.drawRect(0, 0, vw * 0.6f, 3 * density, fill);
        }
        long ms = (System.nanoTime() - t0) / 1000000L;
        if (debug) {
            String s = "items " + drawn + "/" + page.items.length + "  scale " + String.format("%.2f", scale)
                    + "  off " + (int) offX + "," + (int) offY + "  " + ms + "ms";
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextScaleX(1f);
            paint.setTextSize(11 * density);
            fill.setColor(0xCC000000);
            c.drawRect(0, 0, paint.measureText(s) + 8, 16 * density, fill);
            paint.setColor(0xFFFFFFFF);
            c.drawText(s, 4, 12 * density, paint);
            if (ms > 50) Log.d(TAG, "langsamer Frame: " + ms + "ms, " + drawn + " Items");
        } else if (++frames <= 3) {
            Log.d(TAG, "Frame " + frames + ": " + drawn + " Items, " + ms + "ms");
        }
    }

    private void drawHint(Canvas c, int vw, int vh) {
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextScaleX(1f);
        paint.setTextSize(14 * density);
        paint.setColor(0xFF888888);
        String[] lines = hint.split("\n");
        float y = vh / 2f - lines.length * 10 * density;
        for (String l : lines) {
            c.drawText(l, (vw - paint.measureText(l)) / 2f, y, paint);
            y += 20 * density;
        }
    }

    private void drawRect(Canvas c, Page.Item it, float dx, float dy, float dw, float dh) {
        fill.setColor(it.color);
        if (dw < 1f) dw = 1f;
        if (dh < 1f) dh = 1f;
        if (it.radius > 0.5f && dw > 4f && dh > 4f) {
            rf.set(dx, dy, dx + dw, dy + dh);
            float r = Math.min(it.radius * scale, Math.min(dw, dh) / 2f);
            c.drawRoundRect(rf, r, r, fill);
        } else {
            c.drawRect(dx, dy, dx + dw, dy + dh, fill);
        }
    }

    private Typeface face(int flags) {
        int idx = flags & 0x13; // fett, kursiv, mono
        Typeface t = faces[idx];
        if (t == null) {
            int style = ((flags & 1) != 0 ? Typeface.BOLD : 0) | ((flags & 2) != 0 ? Typeface.ITALIC : 0);
            t = Typeface.create((flags & 16) != 0 ? Typeface.MONOSPACE : Typeface.DEFAULT, style);
            faces[idx] = t;
        }
        return t;
    }

    /**
     * Berechnet einmal pro Textzeile, wie sie auf Androids Schrift passend zur vom Server gemessenen
     * Breite (it.w) gesetzt wird. Wichtig: Die Abstaende zwischen Woertern werden nie zusammengequetscht.
     * Ist die Zeile mit Androids Schrift zu breit, werden die Woerter einzeln gesetzt: Die Abstaende
     * bleiben (mind. 90% eines Leerzeichens), die Woerter werden nur leicht gestaucht (bis 80%) und
     * erst darueber hinaus wird die Schrift insgesamt etwas kleiner. (Ein echter Zeilenumbruch in die
     * naechste Zeile ist nicht moeglich, weil das Layout vom Server fest vorgegeben ist und der Text
     * sonst die darunterliegende Zeile ueberdecken wuerde.)
     */
    private void layoutText(Page.Item it) {
        // Gemessen wird in Bildschirm-Pixeln bei der echten Zeichengroesse: Bei kleinen Groessen
        // rundet Android die Zeichenbreiten (Hinting), die Breite laesst sich daher nicht linear
        // von 16 px auf z.B. 17,8 px umrechnen und fette Woerter wuerden an die Nachbarn stossen.
        if (it.layoutScale == scale) return;
        it.layoutScale = scale;
        it.perWord = false;
        it.fontMul = 1f;
        it.textScaleX = 1f;
        paint.setTypeface(face(it.flags));
        paint.setTextSize(it.size * scale);
        paint.setTextScaleX(1f);
        float total = paint.measureText(it.s);
        float W = it.w * scale;
        if (total <= 0f || W <= 0f) return;
        float r = W / total;
        String[] ws = it.s.split(" ");
        int n = ws.length;
        if (n < 2) {
            // Einzelnes Wort: nur Breite angleichen; bei starker Stauchung Schrift etwas verkleinern
            if (r < 0.8f) {
                it.fontMul = Math.max(0.7f, r / 0.8f);
                it.textScaleX = Math.max(0.6f, r / it.fontMul);
            } else {
                it.textScaleX = Math.min(1.4f, r);
            }
            return;
        }
        if (r >= 0.97f && r <= 1.03f) { // passt praktisch genau: ganze Zeile in einem Rutsch
            it.textScaleX = r;
            return;
        }
        float[] wd = new float[n];
        float wsum = 0f;
        for (int i = 0; i < n; i++) { wd[i] = paint.measureText(ws[i]); wsum += wd[i]; }
        float sp = paint.measureText(" ");
        if (wsum <= 0f || sp <= 0f) { it.textScaleX = Math.max(0.6f, Math.min(1.4f, r)); return; }
        int gaps = n - 1;
        float f = 1f, k, g;
        if (r > 1f) {
            // Androids Schrift ist schmaler: Zusatzplatz auf die Abstaende verteilen (max. 2 Leerzeichen),
            // den Rest ueber leicht breitere Woerter (max. 120%)
            k = 1f;
            g = (W - wsum) / gaps;
            if (g > 2f * sp) {
                k = Math.min(1.2f, (W - gaps * 2f * sp) / wsum);
                g = (W - k * wsum) / gaps;
            }
        } else {
            // Androids Schrift ist breiter: Abstaende bleiben, Woerter werden gestaucht
            float gmin = 0.9f * sp;
            k = (W - gaps * gmin) / wsum;
            if (k < 0.8f) {
                float t = 0.8f * wsum + gaps * gmin;
                f = Math.max(0.7f, W / t);
                k = Math.max(0.6f, Math.min(1f, (W / f - gaps * gmin) / wsum));
            }
            g = (W / f - k * wsum) / gaps;
            if (g < 0f) g = 0f;
        }
        it.words = ws;
        it.wx = new float[n];
        float x = 0f;
        for (int i = 0; i < n; i++) {
            it.wx[i] = x;
            x += (k * wd[i] + g) * f;
        }
        it.fontMul = f;
        it.textScaleX = k;
        it.perWord = true;
    }

    private void drawText(Canvas c, Page.Item it, float dx, float dy, float dw, float dh) {
        layoutText(it);
        paint.setTypeface(face(it.flags));
        paint.setTextSize(it.size * it.fontMul * scale);
        paint.setTextScaleX(it.textScaleX);
        paint.setColor(it.color);
        paint.getFontMetrics(fm);
        float tot = fm.descent - fm.ascent;
        float base = dy + (tot > 0f ? (-fm.ascent) * dh / tot : -fm.ascent);
        if (it.perWord) {
            for (int i = 0; i < it.words.length; i++)
                c.drawText(it.words[i], dx + it.wx[i], base, paint);
        } else {
            c.drawText(it.s, dx, base, paint);
        }
        if ((it.flags & 12) != 0) {
            paint.setStrokeWidth(Math.max(1f, it.size * scale / 14f));
            if ((it.flags & 4) != 0) {
                float y = base + it.size * scale * 0.12f;
                c.drawLine(dx, y, dx + dw, y, paint);
            }
            if ((it.flags & 8) != 0) {
                float y = base - it.size * scale * 0.3f;
                c.drawLine(dx, y, dx + dw, y, paint);
            }
        }
    }

    private void drawImage(Canvas c, Page.Item it, float dx, float dy, float dw, float dh) {
        Bitmap b = image(it);
        if (b == null) {
            fill.setColor(0xFFE8E8E8);
            c.drawRect(dx, dy, dx + dw, dy + dh, fill);
            return;
        }
        boolean clipped = it.clip != null;
        if (clipped) {
            c.save();
            c.clipRect(it.clip[0] * scale - offX, it.clip[1] * scale - offY,
                    (it.clip[0] + it.clip[2]) * scale - offX, (it.clip[1] + it.clip[3]) * scale - offY);
        }
        rf.set(dx, dy, dx + dw, dy + dh);
        float bw = b.getWidth(), bh = b.getHeight();
        if (it.mode == 1 && bw > 0 && bh > 0 && dw > 0 && dh > 0) {
            float ta = dw / dh, ba = bw / bh;
            if (ba > ta) {
                float nw = bh * ta;
                src.set((int) ((bw - nw) / 2f), 0, (int) ((bw + nw) / 2f), (int) bh);
            } else {
                float nh = bw / ta;
                src.set(0, (int) ((bh - nh) / 2f), (int) bw, (int) ((bh + nh) / 2f));
            }
            c.drawBitmap(b, src, rf, bmpPaint);
        } else {
            c.drawBitmap(b, null, rf, bmpPaint);
        }
        if (clipped) c.restore();
    }

    private void drawField(Canvas c, Page.Item it, float dx, float dy, float dw, float dh) {
        String k = it.s;
        if (k.equals("checkbox") || k.equals("radio")) {
            float sz = Math.min(dw, dh);
            float cx = dx + dw / 2f, cy = dy + dh / 2f;
            stroke.setColor(0xFF555555);
            stroke.setStrokeWidth(Math.max(1f, 1.5f * scale));
            if (k.equals("radio")) {
                c.drawCircle(cx, cy, sz / 2f - 1f, stroke);
                if (it.checked) {
                    fill.setColor(0xFF2A7FFF);
                    c.drawCircle(cx, cy, sz / 4f, fill);
                }
            } else {
                rf.set(cx - sz / 2f + 1f, cy - sz / 2f + 1f, cx + sz / 2f - 1f, cy + sz / 2f - 1f);
                c.drawRect(rf, stroke);
                if (it.checked) {
                    stroke.setColor(0xFF2A7FFF);
                    stroke.setStrokeWidth(Math.max(2f, 2f * scale));
                    c.drawLine(cx - sz * 0.25f, cy, cx - sz * 0.05f, cy + sz * 0.2f, stroke);
                    c.drawLine(cx - sz * 0.05f, cy + sz * 0.2f, cx + sz * 0.28f, cy - sz * 0.2f, stroke);
                }
            }
            return;
        }
        if (it.text.length() == 0) return;
        boolean center = k.equals("submit") || k.equals("button") || k.equals("reset") || k.equals("image");
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextScaleX(1f);
        paint.setTextSize(it.size * scale);
        paint.setColor(it.placeholder ? ((it.color & 0x00FFFFFF) | 0x88000000) : it.color);
        paint.getFontMetrics(fm);
        float base = dy + (dh - (fm.descent - fm.ascent)) / 2f - fm.ascent;
        float tw = paint.measureText(it.text);
        float pad = Math.min(dh / 4f, 4f * scale);
        float tx = center ? dx + (dw - tw) / 2f : dx + pad;
        c.save();
        c.clipRect(dx, dy, dx + dw, dy + dh);
        c.drawText(it.text, tx, base, paint);
        c.restore();
    }

    private void debugBox(Canvas c, Page.Item it, float dx, float dy, float dw, float dh) {
        int col;
        switch (it.type) {
            case 't': col = 0xAA0000FF; break;
            case 'i': col = 0xAA00AA00; break;
            case 'a': col = 0xAAFF0000; break;
            case 'f': col = 0xAAFF8800; break;
            default: col = 0x55888888; break;
        }
        stroke.setColor(col);
        stroke.setStrokeWidth(1f);
        c.drawRect(dx, dy, dx + dw, dy + dh, stroke);
    }

    // ------------------------------------------------------------------ Bilder

    private Bitmap image(Page.Item it) {
        int tw = Math.round(Math.max(16f, Math.min(1000f, it.w * density)));
        final String key = page.gen + ":" + it.id + ":" + tw;
        Bitmap b = cache.get(key);
        if (b != null) return b;
        if (!failed.contains(key) && pending.add(key)) {
            final String url = serverBase + "/img?sid=" + Net.enc(page.sid) + "&id=" + it.id
                    + "&tw=" + tw + "&q=40";
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    Bitmap bm = null;
                    try {
                        byte[] d = Net.getBytes(url);
                        if (d != null) {
                            BitmapFactory.Options o = new BitmapFactory.Options();
                            o.inPreferredConfig = Bitmap.Config.RGB_565;
                            bm = BitmapFactory.decodeByteArray(d, 0, d.length, o);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Bild " + key + ": " + t);
                    }
                    if (bm != null) cache.put(key, bm); else failed.add(key);
                    pending.remove(key);
                    postInvalidate();
                }
            });
        }
        return null;
    }

    // ------------------------------------------------------------------ Textauswahl

    private static final int ID_COPY = 1, ID_ALL = 2;
    private boolean selActive, dragging;
    private int aItem, aChar, fItem, fChar;          // Anker und Fokus der Auswahl (Item-Index, Zeichen)
    private ActionMode actionMode;
    private float dragDX, dragDY, lastTX, lastTY;
    private final Path hpath = new Path();
    private final Runnable autoScroll = new Runnable() {
        @Override
        public void run() {
            if (!dragging) return;
            float edge = 64 * density, step = 0f;
            if (lastTY < edge) step = -(edge - lastTY) / edge * 14 * density;
            else if (lastTY > getHeight() - edge) step = (lastTY - (getHeight() - edge)) / edge * 14 * density;
            if (step != 0f) {
                offY += step;
                clampOffsets();
                dragTo(lastTX, lastTY);
            }
            postOnAnimation(this);
        }
    };

    /** Text in die Zwischenablage kopieren. */
    public static void copyToClipboard(Context c, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("text", text));
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(c, R.string.copied, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w(TAG, "Kopieren fehlgeschlagen: " + e);
        }
    }

    public boolean hasSelection() { return selActive; }

    public void clearSelection() {
        boolean had = selActive;
        selActive = false;
        dragging = false;
        ActionMode m = actionMode;
        actionMode = null;
        if (m != null) m.finish();
        if (had) invalidate();
    }

    private int[] selRange() {
        if (aItem < fItem || (aItem == fItem && aChar <= fChar))
            return new int[]{aItem, aChar, fItem, fChar};
        return new int[]{fItem, fChar, aItem, aChar};
    }

    /** x-Versatz (Bildschirm-px) des Zeichens idx innerhalb der Textzeile, passend zum Zeichnen. */
    private float charOffset(Page.Item it, int idx) {
        layoutText(it);
        paint.setTypeface(face(it.flags));
        paint.setTextSize(it.size * it.fontMul * scale);
        paint.setTextScaleX(it.textScaleX);
        int len = it.s.length();
        if (idx <= 0) return 0f;
        if (idx >= len) return it.w * scale;
        if (!it.perWord) return paint.measureText(it.s, 0, idx);
        int pos = 0;
        for (int i = 0; i < it.words.length; i++) {
            int wl = it.words[i].length();
            if (idx <= pos + wl) return it.wx[i] + paint.measureText(it.words[i], 0, idx - pos);
            pos += wl + 1;
        }
        return it.w * scale;
    }

    private int charIndexAt(Page.Item it, float lx) {
        int len = it.s.length();
        if (len == 0 || lx <= 0f) return 0;
        if (lx >= it.w * scale) return len;
        int lo = 0, hi = len;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (charOffset(it, mid) <= lx) lo = mid; else hi = mid;
        }
        float a = charOffset(it, lo), b = charOffset(it, hi);
        return (lx - a < b - lx) ? lo : hi;
    }

    /** Textzeile an Seitenkoordinate. nearest=false: nur Treffer (kleine Toleranz), true: naechste Zeile. */
    private int textItemAt(float px, float py, boolean nearest) {
        int best = -1;
        float bd = Float.MAX_VALUE;
        Page.Item[] its = page.items;
        for (int i = its.length - 1; i >= 0; i--) {
            Page.Item it = its[i];
            if (it.type != 't') continue;
            float dx = px < it.x ? it.x - px : (px > it.x + it.w ? px - (it.x + it.w) : 0f);
            float dy = py < it.y ? it.y - py : (py > it.y + it.h ? py - (it.y + it.h) : 0f);
            if (!nearest && (dx > 6f || dy > 6f)) continue;
            if (dx == 0f && dy == 0f && !nearest) return i;
            float d = dy * 10000f + dx;
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private static boolean isWordChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private boolean startSelectionAt(float px, float py) {
        int i = textItemAt(px, py, false);
        if (i < 0) return false;
        Page.Item it = page.items[i];
        String s = it.s;
        int len = s.length();
        if (len == 0) return false;
        int c = charIndexAt(it, (px - it.x) * scale);
        int pos = c;
        if (pos >= len || !isWordChar(s.charAt(pos))) {
            if (pos > 0 && isWordChar(s.charAt(pos - 1))) pos--; else pos = -1;
        }
        int ws = 0, we = len;
        if (pos >= 0) {
            ws = pos;
            we = pos + 1;
            while (ws > 0 && isWordChar(s.charAt(ws - 1))) ws--;
            while (we < len && isWordChar(s.charAt(we))) we++;
        }
        aItem = i; aChar = ws;
        fItem = i; fChar = we;
        selActive = true;
        return true;
    }

    public void selectAll() {
        if (page == null) return;
        int first = -1, last = -1;
        for (int i = 0; i < page.items.length; i++) {
            if (page.items[i].type == 't') {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) return;
        aItem = first; aChar = 0;
        fItem = last; fChar = page.items[last].s.length();
        selActive = true;
        invalidate();
        onViewMoved();
    }

    private String selectedText() {
        if (!selActive || page == null) return "";
        int[] r = selRange();
        StringBuilder sb = new StringBuilder();
        Page.Item prev = null;
        for (int i = r[0]; i <= r[2] && i < page.items.length; i++) {
            Page.Item it = page.items[i];
            if (it.type != 't') continue;
            int len = it.s.length();
            int a = i == r[0] ? Math.min(r[1], len) : 0;
            int b = i == r[2] ? Math.min(r[3], len) : len;
            if (a >= b) continue;
            if (prev != null) {
                boolean sameLine = Math.abs((it.y + it.h / 2f) - (prev.y + prev.h / 2f)) < Math.min(it.h, prev.h) * 0.5f;
                if (it.blk >= 0 && it.blk == prev.blk) {
                    if (sameLine) {
                        if (it.x - (prev.x + prev.w) > prev.size * 0.15f) sb.append(' ');
                    } else {
                        sb.append(' ');
                    }
                } else if (sameLine && it.x >= prev.x) {
                    sb.append(' ');
                } else {
                    sb.append('\n');
                }
            }
            sb.append(it.s, a, b);
            prev = it;
        }
        return sb.toString();
    }

    private void copySelection() {
        String t = selectedText();
        if (t.length() > 0) copyToClipboard(getContext(), t);
        clearSelection();
    }

    /** Position (Bildschirm) der Unterkante der Zeile am Zeichen: out = {x, y}. */
    private void handlePos(int item, int ch, float[] out) {
        Page.Item it = page.items[item];
        out[0] = it.x * scale - offX + charOffset(it, ch);
        out[1] = (it.y + it.h) * scale - offY;
    }

    private void drawSelection(Canvas c) {
        int vw = getWidth(), vh = getHeight();
        int[] r = selRange();
        fill.setColor(0x552A7FFF);
        for (int i = r[0]; i <= r[2] && i < page.items.length; i++) {
            Page.Item it = page.items[i];
            if (it.type != 't') continue;
            float dx = it.x * scale - offX, dy = it.y * scale - offY;
            float dw = it.w * scale, dh = it.h * scale;
            if (dy > vh || dy + dh < 0 || dx > vw || dx + dw < 0) continue;
            int len = it.s.length();
            int a = i == r[0] ? Math.min(r[1], len) : 0;
            int b = i == r[2] ? Math.min(r[3], len) : len;
            if (a >= b) continue;
            float x1 = a <= 0 ? 0f : charOffset(it, a);
            float x2 = b >= len ? dw : charOffset(it, b);
            c.drawRect(dx + x1, dy, dx + x2, dy + dh, fill);
        }
        float[] p = new float[2];
        handlePos(r[0], r[1], p);
        drawHandle(c, p[0], p[1]);
        handlePos(r[2], r[3], p);
        drawHandle(c, p[0], p[1]);
    }

    private void drawHandle(Canvas c, float x, float y) {
        float r = 9 * density, k = 0.7071f * r;
        fill.setColor(0xFF2A7FFF);
        hpath.reset();
        hpath.moveTo(x, y);
        hpath.lineTo(x + k, y + k);
        hpath.lineTo(x - k, y + k);
        hpath.close();
        c.drawPath(hpath, fill);
        c.drawCircle(x, y + 1.4142f * r, r, fill);
    }

    private boolean grabHandle(MotionEvent e) {
        int[] r = selRange();
        float[] a = new float[2], b = new float[2];
        handlePos(r[0], r[1], a);
        handlePos(r[2], r[3], b);
        float d = 1.4142f * 9 * density;
        float da = (float) Math.hypot(e.getX() - a[0], e.getY() - (a[1] + d));
        float db = (float) Math.hypot(e.getX() - b[0], e.getY() - (b[1] + d));
        if (Math.min(da, db) > 30 * density) return false;
        if (da <= db) {
            aItem = r[2]; aChar = r[3];
            fItem = r[0]; fChar = r[1];
            dragDX = e.getX() - a[0];
            dragDY = e.getY() - (a[1] - page.items[r[0]].h * scale / 2f);
        } else {
            aItem = r[0]; aChar = r[1];
            fItem = r[2]; fChar = r[3];
            dragDX = e.getX() - b[0];
            dragDY = e.getY() - (b[1] - page.items[r[2]].h * scale / 2f);
        }
        dragging = true;
        lastTX = e.getX();
        lastTY = e.getY();
        scroller.forceFinished(true);
        postOnAnimation(autoScroll);
        return true;
    }

    private void dragTo(float sx, float sy) {
        float px = (sx - dragDX + offX) / scale, py = (sy - dragDY + offY) / scale;
        int i = textItemAt(px, py, true);
        if (i < 0) return;
        Page.Item it = page.items[i];
        fItem = i;
        fChar = charIndexAt(it, (px - it.x) * scale);
        invalidate();
        onViewMoved();
    }

    private void onViewMoved() {
        if (selActive && actionMode != null && Build.VERSION.SDK_INT >= 23) invalidateModeRect();
    }

    @TargetApi(23)
    private void invalidateModeRect() {
        if (actionMode != null) actionMode.invalidateContentRect();
    }

    private void selectionRect(Rect out) {
        int[] r = selRange();
        float[] a = new float[2], b = new float[2];
        handlePos(r[0], r[1], a);
        handlePos(r[2], r[3], b);
        float top = a[1] - page.items[r[0]].h * scale, bottom = b[1];
        float left = Math.min(a[0], b[0]), right = Math.max(a[0], b[0]);
        if (r[0] != r[2]) { left = 0f; right = getWidth(); }
        if (right - left < 1f) right = left + 1f;
        top = Math.max(0f, Math.min(top, getHeight() - 2f));
        bottom = Math.max(top + 1f, Math.min(bottom, getHeight()));
        out.set((int) left, (int) top, (int) right, (int) bottom);
    }

    private void showMode() {
        if (actionMode != null) {
            actionMode.invalidate();
            onViewMoved();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23) actionMode = startFloating();
        else actionMode = startActionMode(new SelCallback());
    }

    @TargetApi(23)
    private ActionMode startFloating() {
        return startActionMode(new FloatCallback(), ActionMode.TYPE_FLOATING);
    }

    private class SelCallback implements ActionMode.Callback {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mode.setTitle(android.R.string.selectTextMode);
            menu.add(0, ID_COPY, 0, android.R.string.copy)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
            menu.add(0, ID_ALL, 1, android.R.string.selectAll)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() == ID_COPY) copySelection();
            else if (item.getItemId() == ID_ALL) selectAll();
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            if (actionMode == mode) {
                actionMode = null;
                selActive = false;
                dragging = false;
                invalidate();
            }
        }
    }

    @TargetApi(23)
    private class FloatCallback extends ActionMode.Callback2 {
        private final SelCallback d = new SelCallback();

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) { return d.onCreateActionMode(mode, menu); }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return d.onPrepareActionMode(mode, menu); }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return d.onActionItemClicked(mode, item); }

        @Override
        public void onDestroyActionMode(ActionMode mode) { d.onDestroyActionMode(mode); }

        @Override
        public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
            if (selActive && page != null) selectionRect(outRect);
            else super.onGetContentRect(mode, view, outRect);
        }
    }

    private String textInRect(Page.Item a) {
        StringBuilder sb = new StringBuilder();
        for (Page.Item it : page.items) {
            if (it.type != 't') continue;
            float cx = it.x + it.w / 2f, cy = it.y + it.h / 2f;
            if (cx >= a.x && cx <= a.x + a.w && cy >= a.y && cy <= a.y + a.h) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(it.s);
            }
        }
        return sb.toString();
    }

    private void handleLongPress(float x, float y) {
        if (page == null) return;
        float px = (x + offX) / scale, py = (y + offY) / scale;
        Page.Item link = null;
        float best = Float.MAX_VALUE;
        for (Page.Item it : page.items) {
            if (it.type == 'a' && px >= it.x && px <= it.x + it.w && py >= it.y && py <= it.y + it.h
                    && it.w * it.h < best) {
                best = it.w * it.h;
                link = it;
            }
        }
        if (link != null) {
            clearSelection();
            String txt = link.text != null ? link.text : "";
            if (txt.length() == 0) txt = textInRect(link);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (listener != null) listener.onLinkLongPress(link.s, txt);
            return;
        }
        clearSelection();
        if (startSelectionAt(px, py)) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            invalidate();
            showMode();
        }
    }

    // ------------------------------------------------------------------ Eingabe

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        if (dragging) {
            lastTX = e.getX();
            lastTY = e.getY();
            if (act == MotionEvent.ACTION_MOVE) dragTo(lastTX, lastTY);
            else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                dragging = false;
                onViewMoved();
            }
            return true;
        }
        if (selActive && act == MotionEvent.ACTION_DOWN && grabHandle(e)) return true;
        sgd.onTouchEvent(e);
        if (!sgd.isInProgress()) gd.onTouchEvent(e);
        return true;
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            offX = scroller.getCurrX();
            offY = scroller.getCurrY();
            clampOffsets();
            onViewMoved();
            postInvalidateOnAnimation();
        }
    }

    private void handleTap(float x, float y) {
        if (page == null) return;
        float px = (x + offX) / scale, py = (y + offY) / scale;
        Page.Item hit = null;
        float best = Float.MAX_VALUE;
        for (Page.Item it : page.items) {
            if (px >= it.x && px <= it.x + it.w && py >= it.y && py <= it.y + it.h) {
                if (it.type == 'a' && it.w * it.h < best) {
                    best = it.w * it.h;
                    hit = it;
                }
                if (debug && it.type != 'r') {
                    String s = it.s.length() > 40 ? it.s.substring(0, 40) : it.s;
                    Log.d(TAG, "Tap trifft " + it.type + " [" + (int) it.x + "," + (int) it.y + " "
                            + (int) it.w + "x" + (int) it.h + "] " + s);
                }
            }
        }
        pressed = hit;
        invalidate();
        String href = (hit != null && hit.type == 'a') ? hit.s : null;
        Log.d(TAG, "Tap page=" + (int) px + "," + (int) py + (href != null ? " link=" + href : ""));
        if (listener != null) listener.onTap(px, py, href);
    }

    private class GL extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            scroller.forceFinished(true);
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
            offX += dx;
            offY += dy;
            clampOffsets();
            invalidate();
            onViewMoved();
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
            if (page == null) return false;
            scroller.fling((int) offX, (int) offY, (int) -vx, (int) -vy,
                    0, (int) maxOffX(), 0, (int) maxOffY());
            postInvalidateOnAnimation();
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            handleLongPress(e.getX(), e.getY());
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (selActive) {
                clearSelection();
                return true;
            }
            handleTap(e.getX(), e.getY());
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            float target = scale < fitScale * 1.5f ? Math.min(maxScale, fitScale * 2.2f) : fitScale;
            zoomAt(target, e.getX(), e.getY());
            return true;
        }
    }

    private class SL extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector d) {
            zoomAt(scale * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
            return true;
        }
    }
}
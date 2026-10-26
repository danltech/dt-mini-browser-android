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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Log;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

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

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
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
        gd.setIsLongpressEnabled(false);
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

    /** Streckfaktor, damit Androids Text genauso breit wird wie von Chromium gemessen. */
    private float textScaleX(Page.Item it) {
        if (it.textScaleX >= 0f) return it.textScaleX;
        paint.setTypeface(face(it.flags));
        paint.setTextSize(it.size);
        paint.setTextScaleX(1f);
        float mw = paint.measureText(it.s);
        float sx = (mw > 0f && it.w > 0f) ? it.w / mw : 1f;
        it.textScaleX = Math.max(0.6f, Math.min(1.4f, sx));
        return it.textScaleX;
    }

    private void drawText(Canvas c, Page.Item it, float dx, float dy, float dw, float dh) {
        float sx = textScaleX(it);
        paint.setTypeface(face(it.flags));
        paint.setTextSize(it.size * scale);
        paint.setTextScaleX(sx);
        paint.setColor(it.color);
        paint.getFontMetrics(fm);
        float tot = fm.descent - fm.ascent;
        float base = dy + (tot > 0f ? (-fm.ascent) * dh / tot : -fm.ascent);
        c.drawText(it.s, dx, base, paint);
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

    // ------------------------------------------------------------------ Eingabe

    @Override
    public boolean onTouchEvent(MotionEvent e) {
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
        public boolean onSingleTapConfirmed(MotionEvent e) {
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

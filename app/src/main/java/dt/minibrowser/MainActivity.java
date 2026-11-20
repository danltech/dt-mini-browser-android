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

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.app.DownloadManager;
import android.net.Uri;
import android.os.Environment;
import android.webkit.MimeTypeMap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import java.util.UUID;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Date;
import java.text.SimpleDateFormat;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.view.View;
import android.view.Gravity;
import android.graphics.Typeface;
import android.graphics.Color;

public class MainActivity extends Activity implements PageView.Listener {
    private static final String TAG = "MiniClient";
    private static final int LOAD = 0, TAP = 1, TYPE = 2, BACK = 3;
    private static final int M_RELOAD = 1, M_BACK = 2, M_DEBUG = 3, M_SERVER = 4;
    private static final int M_BOOKMARK = 5, M_HISTORY = 6, M_BOOKMARKS = 7;
    private static final int M_TABS = 8, M_NEW_TAB = 9, M_SETTINGS = 10, M_SHARE = 11;

    private static class Tab {
        String sid;
        String url = "";
        String title = "";
        Page page;
        int depth;
    }

    private PageView pv;
    private EditText urlField;
    private TextView lockView;
    private SharedPreferences prefs;
    private Store store;
    private String server;

    private static final int REQ_QR_SETUP = 9421;
    /** Edit-Felder des Server-Dialogs, solange QR-Scan laeuft. */
    private EditText pendingEtUrl, pendingEtTok, pendingEtFp;
    private String clientId;
    private String deviceModel;
    private String androidVer;
    private String sid;
    private int depth;
    private int reqCounter;
    private boolean busy;
    private float density;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<Tab> tabs = new ArrayList<Tab>();
    private int tabIndex = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        density = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("mini", MODE_PRIVATE);
        server = prefs.getString("server", "http://10.0.2.2:8080");
        clientId = prefs.getString("clientId", null);
        Net.setAuthToken(prefs.getString("token", ""));
        Net.setCertFingerprint(prefs.getString("certFp", ""));
        if (clientId == null) {
            clientId = UUID.randomUUID().toString();
            prefs.edit().putString("clientId", clientId).apply();
            Log.d(TAG, "Neue Client-ID erzeugt: " + clientId);
        } else {
            Log.d(TAG, "Bestehende Client-ID: " + clientId);
        }
        deviceModel = Build.MODEL != null ? Build.MODEL : "Android";
        androidVer = Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "10";
        Log.d(TAG, "Gerät: " + deviceModel + ", Android " + androidVer);

        pv = new PageView(this);
        pv.setListener(this);
        applyServer();
        setContentView(pv);

        urlField = new EditText(this);
        urlField.setSingleLine(true);
        urlField.setHint(getString(R.string.hint_url));
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent ev) {
                go();
                return true;
            }
        });

        lockView = new TextView(this);
        lockView.setText("  ");
        lockView.setTextSize(18);
        lockView.setGravity(Gravity.CENTER);
        lockView.setPadding(12, 0, 12, 0);
        lockView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCertDialog();
            }
        });

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(lockView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bar.addView(urlField, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ActionBar ab = getActionBar();
        if (ab != null) {
            ab.setDisplayShowTitleEnabled(false);
            ab.setDisplayShowHomeEnabled(false);
            ab.setDisplayShowCustomEnabled(true);
            ab.setCustomView(bar, new ActionBar.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        Log.d(TAG, "Start, Server=" + server + ", density=" + density);
        store = new Store(this);
        tabs.add(new Tab());
        tabIndex = 0;
        handleViewIntent(getIntent());
        ui.post(new Runnable() {
            @Override public void run() { maybePromptDefaultBrowser(); }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleViewIntent(intent);
    }

    private void handleViewIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            String u = intent.getData().toString();
            if (u.length() > 0) {
                urlField.setText(u);
                load(u);
            }
        }
    }

    private boolean isDefaultBrowser() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://example.com"));
            ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) return false;
            return getPackageName().equals(ri.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    private void maybePromptDefaultBrowser() {
        if (isDefaultBrowser()) return;
        if (prefs.getBoolean("skip_default_prompt", false)) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.default_browser_title)
                .setMessage(R.string.default_browser_message)
                .setPositiveButton(R.string.settings, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
                        } catch (Exception e) {
                            try {
                                startActivity(new Intent(Settings.ACTION_SETTINGS));
                            } catch (Exception e2) {
                                Toast.makeText(MainActivity.this, R.string.toast_choose_default, Toast.LENGTH_LONG).show();
                            }
                        }
                    }
                })
                .setNeutralButton(R.string.dont_ask_again, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        prefs.edit().putBoolean("skip_default_prompt", true).apply();
                    }
                })
                .setNegativeButton(R.string.later, null)
                .show();
    }

    /** BCP-47 / ISO language code for the server (en fallback, de when German). */
    private String clientLang() {
        Locale loc = Locale.getDefault();
        String lang = loc != null ? loc.getLanguage() : "en";
        if (lang == null || lang.length() == 0) return "en";
        return lang.toLowerCase(Locale.US);
    }

    private void applyServer() {
        if (!server.startsWith("http://") && !server.startsWith("https://")) {
            // Bei gesetztem Zertifikat-Fingerprint HTTPS bevorzugen
            if (Net.getCertFingerprint().length() > 0) server = "https://" + server;
            else server = "http://" + server;
        }
        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        pv.setServerBase(server);
        pv.setHint(getString(R.string.hint_empty, server));
    }

    // ------------------------------------------------------------------ Aktionen

    private void go() {
        String in = urlField.getText().toString().trim();
        if (in.length() == 0) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlField.getWindowToken(), 0);
        pv.requestFocus();
        load(in);
    }

    private void load(String input) {
        int w = Math.round(pv.getWidth() / density);
        int h = Math.round(pv.getHeight() / density);
        String path = "/load?url=" + Net.enc(input) + "&w=" + w + "&h=" + h + "&dpr=" + density
                + "&cid=" + Net.enc(clientId)
                + "&av=" + Net.enc(androidVer)
                + "&model=" + Net.enc(deviceModel)
                + "&lang=" + Net.enc(clientLang());
        if (sid != null) path += "&sid=" + Net.enc(sid);
        request(path, LOAD);
    }

    @Override
    public void onTap(float x, float y, String href) {
        if (busy || sid == null) return;
        // Externe Links direkt an Apps (Play Store, tel, mailto, …)
        if (href != null && isExternalUrl(href)) {
            openExternal(href);
            return;
        }
        String path = "/tap?sid=" + Net.enc(sid) + "&cid=" + Net.enc(clientId)
                + "&x=" + Math.round(x) + "&y=" + Math.round(y)
                + "&lang=" + Net.enc(clientLang());
        if (href != null && href.length() > 0) {
            path += "&href=" + Net.enc(href);
        }
        request(path, TAP);
    }

    @Override
    public void onLinkLongPress(final String href, final String text) {
        if (href == null || href.length() == 0) return;
        final boolean web = href.startsWith("http://") || href.startsWith("https://");
        final ArrayList<Integer> acts = new ArrayList<Integer>();
        ArrayList<String> labels = new ArrayList<String>();
        if (web) {
            acts.add(0);
            labels.add(getString(R.string.open_new_tab));
        }
        acts.add(1);
        labels.add(getString(R.string.copy_link_address));
        if (text != null && text.length() > 0) {
            acts.add(2);
            labels.add(getString(R.string.copy_link_text));
        }
        String title = href.length() > 80 ? href.substring(0, 77) + "..." : href;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        int a = acts.get(which);
                        if (a == 0) openInNewTab(href);
                        else if (a == 1) PageView.copyToClipboard(MainActivity.this, href);
                        else PageView.copyToClipboard(MainActivity.this, text);
                    }
                })
                .show();
    }

    private void openInNewTab(String href) {
        saveCurrentTab();
        tabs.add(new Tab());
        tabIndex = tabs.size() - 1;
        sid = null;
        depth = 0;
        pv.setPage(null, false);
        urlField.setText(href);
        updateLock(null);
        Toast.makeText(this, getString(R.string.tab_n, tabs.size()), Toast.LENGTH_SHORT).show();
        invalidateOptionsMenu();
        load(href);
    }

    private boolean isExternalUrl(String u) {
        if (u == null) return false;
        String x = u.toLowerCase();
        return x.startsWith("market:") || x.startsWith("intent:") || x.startsWith("tel:")
                || x.startsWith("mailto:") || x.startsWith("sms:") || x.startsWith("geo:")
                || x.startsWith("whatsapp:") || x.contains("play.google.com/store");
    }

    private void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.no_app_for_link, url), Toast.LENGTH_LONG).show();
        }
    }

    private void sendText(String text, boolean enter) {
        if (sid == null) return;
        request("/type?sid=" + Net.enc(sid) + "&cid=" + Net.enc(clientId)
                + "&t=" + Net.enc(text) + (enter ? "&enter=1" : "")
                + "&lang=" + Net.enc(clientLang()), TYPE);
    }

    @Override
    public void onBackPressed() {
        if (pv.hasSelection()) {
            pv.clearSelection();
            return;
        }
        if (depth > 0 && sid != null) {
            request("/back?sid=" + Net.enc(sid) + "&cid=" + Net.enc(clientId) + "&lang=" + Net.enc(clientLang()), BACK);
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ Netzwerk

    private void request(final String path, final int kind) {
        final int my = ++reqCounter;
        busy = true;
        pv.setLoading(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Page p = null;
                String error = null;
                boolean noSession = false;
                long t0 = System.currentTimeMillis();
                long netMs = 0, parseMs = 0;
                int bytes = 0;
                try {
                    String body = Net.getString(server + path);
                    netMs = System.currentTimeMillis() - t0;
                    bytes = body.length();
                    long t1 = System.currentTimeMillis();
                    p = Page.parse(body);
                    parseMs = System.currentTimeMillis() - t1;
                } catch (Net.NoSession e) {
                    noSession = true;
                } catch (Throwable e) {
                    error = e.toString();
                    Log.e(TAG, "Anfrage fehlgeschlagen: " + path, e);
                }
                final Page fp = p;
                final String ferr = error;
                final boolean fns = noSession;
                Log.d(TAG, path.substring(0, Math.min(60, path.length())) + " -> "
                        + (p != null ? p.items.length + " Items" : "kein Ergebnis")
                        + ", " + bytes + " Zeichen, Netz " + netMs + "ms, Parse " + parseMs + "ms");
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (my != reqCounter) return; // veraltete Antwort
                        busy = false;
                        pv.setLoading(false);
                        if (fns && kind != LOAD) {
                            Toast.makeText(MainActivity.this, R.string.session_expired, Toast.LENGTH_SHORT).show();
                            sid = null;
                            Page cur = pv.getPage();
                            if (cur != null && cur.url.length() > 0) load(cur.url);
                        } else if (fp != null) {
                            apply(fp, kind);
                        } else {
                            Toast.makeText(MainActivity.this,
                                    fns ? getString(R.string.server_unknown_request)
                                            : getString(R.string.error_prefix, ferr), Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "net").start();
    }

    private void apply(Page p, int kind) {
        Page prev = pv.getPage();
        sid = p.sid;
        boolean newUrl = prev == null || !p.url.equals(prev.url);
        if (p.err == null) {
            if (kind == BACK) depth = Math.max(0, depth - 1);
            else if (prev != null && newUrl) depth++;
        }
        boolean keep = kind != LOAD && kind != BACK && !newUrl;
        pv.setPage(p, keep);
        if (p.err != null) {
            Toast.makeText(this, p.err, Toast.LENGTH_LONG).show();
        } else if (p.url.length() > 0) {
            urlField.setText(p.url);
        }
        updateLock(p);
        // Tab-Status
        if (tabIndex >= 0 && tabIndex < tabs.size()) {
            Tab tb = tabs.get(tabIndex);
            tb.sid = p.sid;
            tb.url = p.url;
            tb.title = p.title != null ? p.title : p.url;
            tb.page = p;
            tb.depth = depth;
        }
        if (p.err == null && p.url != null && p.url.startsWith("http")) {
            store.addHistory(p.url, p.title);
        }
        if (p.external != null && p.external.length() > 0) {
            openExternal(p.external);
        }
        if (p.downloadId != null && p.downloadId.length() > 0) {
            startDownload(p);
        }
        if (kind == TAP && p.focusType != null) showTextDialog(p);
        invalidateOptionsMenu();
    }

    private void startDownload(Page p) {
        final String id = p.downloadId;
        final String name = (p.downloadName != null && p.downloadName.length() > 0)
                ? p.downloadName : "download.bin";
        final long size = p.downloadSize;
        final String url = server + "/download?sid=" + Net.enc(sid) + "&id=" + Net.enc(id);

        // Kurz nachfragen
        String msg = name;
        if (size > 0) {
            if (size < 1024) msg += " (" + size + " B)";
            else if (size < 1024 * 1024) msg += " (" + (size / 1024) + " KB)";
            else msg += " (" + (size / (1024 * 1024)) + " MB)";
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.download)
                .setMessage(msg)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        enqueueDownload(url, name);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void enqueueDownload(String url, String filename) {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm == null) {
                Toast.makeText(this, R.string.download_manager_unavailable, Toast.LENGTH_SHORT).show();
                return;
            }
            // Dateiname bereinigen
            String safe = filename.replaceAll("[\\/:*?\"<>|]", "_");
            if (safe.length() == 0) safe = "download.bin";
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(safe);
            req.setDescription("DTMiniBrowserAE");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setAllowedOverRoaming(true);
            try {
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safe);
            } catch (Exception e) {
                req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, safe);
            }
            String ext = "";
            int dot = safe.lastIndexOf('.');
            if (dot >= 0) ext = safe.substring(dot + 1).toLowerCase();
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) req.setMimeType(mime);
            long id = dm.enqueue(req);
            Toast.makeText(this, getString(R.string.download_started, safe), Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Download enqueued id=" + id + " url=" + url);
        } catch (Exception e) {
            Log.e(TAG, "Download fehlgeschlagen", e);
            Toast.makeText(this, getString(R.string.download_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void updateLock(Page p) {
        if (lockView == null) return;
        if (p == null || p.url.length() == 0) {
            lockView.setText("  ");
            lockView.setTextColor(Color.GRAY);
            return;
        }
        if (p.https && p.certOk) {
            lockView.setText("[S]");
            lockView.setTextColor(0xFF2E7D32); // grün = sicher
            lockView.setTypeface(Typeface.DEFAULT_BOLD);
        } else if (p.https) {
            lockView.setText("[!]");
            lockView.setTextColor(0xFFF9A825); // gelb
            lockView.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            lockView.setText("[X]");
            lockView.setTextColor(0xFFC62828); // rot = unsicher
            lockView.setTypeface(Typeface.DEFAULT_BOLD);
        }
    }

    private void showCertDialog() {
        Page p = pv.getPage();
        if (p == null) {
            Toast.makeText(this, R.string.no_page_loaded, Toast.LENGTH_SHORT).show();
            return;
        }

        String title = getString(R.string.certificate);
        if (p.certSubject.length() > 0) title = getString(R.string.certificate_for, p.certSubject);
        else if (p.url.length() > 0) {
            try {
                java.net.URI u = java.net.URI.create(p.url);
                title = getString(R.string.certificate_for, u.getHost() != null ? u.getHost() : p.url);
            } catch (Exception e) {
                title = getString(R.string.certificate);
            }
        }

        String dash = getString(R.string.em_dash);
        StringBuilder sb = new StringBuilder();
        if (!p.https) {
            sb.append(getString(R.string.conn_insecure));
        } else {
            sb.append(getString(R.string.conn_secure));

            sb.append(getString(R.string.issued_to));
            sb.append(getString(R.string.common_name));
            sb.append("    ").append(p.certSubject.length() > 0 ? p.certSubject : dash).append("\n\n");

            sb.append(getString(R.string.issued_by));
            sb.append(getString(R.string.common_name));
            sb.append("    ").append(p.certIssuer.length() > 0 ? p.certIssuer : dash).append("\n\n");

            sb.append(getString(R.string.validity));
            Locale loc = Locale.getDefault();
            SimpleDateFormat df = new SimpleDateFormat("EEEE, d. MMMM yyyy HH:mm:ss", loc);
            if (p.certValidFrom > 0) {
                sb.append(getString(R.string.issued_on)).append(df.format(new Date(p.certValidFrom * 1000L))).append("\n");
            } else {
                sb.append(getString(R.string.issued_on)).append(dash).append("\n");
            }
            if (p.certValidTo > 0) {
                sb.append(getString(R.string.valid_until)).append(df.format(new Date(p.certValidTo * 1000L))).append("\n");
            } else {
                sb.append(getString(R.string.valid_until)).append(dash).append("\n");
            }
            sb.append("\n");

            if (p.certProtocol.length() > 0) {
                sb.append(getString(R.string.protocol_label)).append(p.certProtocol).append("\n\n");
            }

            sb.append(getString(R.string.cert_note));
        }

        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(13);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(24, 16, 24, 16);
        tv.setTextIsSelectable(true);

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    // ------------------------------------------------------------------ Texteingabe

    private void showTextDialog(Page p) {
        final EditText et = new EditText(this);
        et.setText(p.focusValue);
        et.setHint(p.focusPh);
        String t = p.focusType;
        int type = InputType.TYPE_CLASS_TEXT;
        if ("password".equals(t)) type |= InputType.TYPE_TEXT_VARIATION_PASSWORD;
        else if ("email".equals(t)) type |= InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
        else if ("url".equals(t)) type |= InputType.TYPE_TEXT_VARIATION_URI;
        else if ("number".equals(t)) type = InputType.TYPE_CLASS_NUMBER;
        else if ("tel".equals(t)) type = InputType.TYPE_CLASS_PHONE;
        else if ("textarea".equals(t)) type |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        et.setInputType(type);
        et.selectAll();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button paste = new Button(this, null, android.R.attr.borderlessButtonStyle);
        paste.setText(android.R.string.paste);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pasteInto(et); }
        });
        Button copy = new Button(this, null, android.R.attr.borderlessButtonStyle);
        copy.setText(android.R.string.copy);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyFrom(et); }
        });
        row.addView(paste, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog d = new AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        sendText(et.getText().toString(), false);
                    }
                })
                .setNeutralButton(R.string.send, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int w) {
                        sendText(et.getText().toString(), true);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    /** Zwischenablage an der Cursorposition (bzw. statt der Auswahl) einfuegen. */
    private void pasteInto(EditText et) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return;
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return;
            CharSequence t = cd.getItemAt(0).coerceToText(this);
            if (t == null) return;
            int a = Math.max(0, Math.min(et.getSelectionStart(), et.getSelectionEnd()));
            int b = Math.max(0, Math.max(et.getSelectionStart(), et.getSelectionEnd()));
            et.getText().replace(a, b, t);
        } catch (Exception e) {
            Log.w(TAG, "Einfuegen fehlgeschlagen: " + e);
        }
    }

    /** Ausgewaehlten Text (oder ohne Auswahl alles) aus dem Feld kopieren. */
    private void copyFrom(EditText et) {
        int a = Math.max(0, Math.min(et.getSelectionStart(), et.getSelectionEnd()));
        int b = Math.max(0, Math.max(et.getSelectionStart(), et.getSelectionEnd()));
        String all = et.getText().toString();
        String t = a < b ? all.substring(a, b) : all;
        if (t.length() > 0) PageView.copyToClipboard(this, t);
    }

    /** Server-Adresse, Token und Zertifikat-Fingerprint (Setup per QR/Text). */
    private void showServerDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);

        final EditText etUrl = new EditText(this);
        etUrl.setHint(R.string.server_address);
        etUrl.setText(server);
        etUrl.setSingleLine(true);
        box.addView(etUrl);

        final EditText etTok = new EditText(this);
        etTok.setHint(R.string.auth_token);
        etTok.setText(Net.getAuthToken());
        etTok.setSingleLine(true);
        box.addView(etTok);

        final EditText etFp = new EditText(this);
        etFp.setHint(R.string.cert_fingerprint);
        etFp.setText(Net.getCertFingerprint());
        etFp.setSingleLine(true);
        box.addView(etFp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button scanSetup = new Button(this, null, android.R.attr.borderlessButtonStyle);
        scanSetup.setText(R.string.scan_qr);
        scanSetup.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pendingEtUrl = etUrl;
                pendingEtTok = etTok;
                pendingEtFp = etFp;
                startQrScan();
            }
        });
        row.addView(scanSetup, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button pasteSetup = new Button(this, null, android.R.attr.borderlessButtonStyle);
        pasteSetup.setText(R.string.paste_setup);
        pasteSetup.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String clip = readClipboardText();
                if (clip != null && applySetupString(clip, etUrl, etTok, etFp)) {
                    Toast.makeText(MainActivity.this, R.string.setup_applied, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, R.string.setup_invalid, Toast.LENGTH_LONG).show();
                }
            }
        });
        row.addView(pasteSetup, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);

        new AlertDialog.Builder(this)
                .setTitle(R.string.server_address)
                .setView(box)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        commitServerFields(etUrl, etTok, etFp);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void commitServerFields(EditText etUrl, EditText etTok, EditText etFp) {
        if (etUrl != null) server = etUrl.getText().toString().trim();
        String tok = etTok != null ? etTok.getText().toString().trim() : Net.getAuthToken();
        String fp = etFp != null ? etFp.getText().toString().trim().toLowerCase() : Net.getCertFingerprint();
        Net.setAuthToken(tok);
        Net.setCertFingerprint(fp);
        prefs.edit()
                .putString("server", server)
                .putString("token", tok)
                .putString("certFp", fp)
                .apply();
        applyServer();
    }

    /** QR scannen via ZXing Barcode Scanner Intent (Android 4 / API 16 kompatibel). */
    private void startQrScan() {
        Intent intent = new Intent("com.google.zxing.client.android.SCAN");
        intent.putExtra("SCAN_MODE", "QR_CODE_MODE");
        intent.putExtra("RESULT_DISPLAY_DURATION_MS", 0L);
        intent.putExtra("PROMPT_MESSAGE", getString(R.string.scan_qr_prompt));
        try {
            startActivityForResult(intent, REQ_QR_SETUP);
        } catch (ActivityNotFoundException e) {
            promptInstallScanner();
        }
    }

    private void promptInstallScanner() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.scan_qr)
                .setMessage(R.string.scanner_missing)
                .setPositiveButton(R.string.install_scanner, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        // Offizielle ZXing Barcode Scanner App
                        Uri market = Uri.parse("market://details?id=com.google.zxing.client.android");
                        Uri web = Uri.parse(
                                "https://play.google.com/store/apps/details?id=com.google.zxing.client.android");
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, market));
                        } catch (ActivityNotFoundException ex) {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, web));
                            } catch (Exception ignored) {
                                Toast.makeText(MainActivity.this, R.string.scanner_missing, Toast.LENGTH_LONG).show();
                            }
                        }
                    }
                })
                .setNegativeButton(R.string.paste_setup, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String clip = readClipboardText();
                        if (clip != null && applySetupString(clip, pendingEtUrl, pendingEtTok, pendingEtFp)) {
                            commitServerFields(pendingEtUrl, pendingEtTok, pendingEtFp);
                            Toast.makeText(MainActivity.this, R.string.setup_applied, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, R.string.setup_invalid, Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNeutralButton(R.string.cancel, null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_QR_SETUP) return;
        if (resultCode != RESULT_OK || data == null) return;
        String contents = data.getStringExtra("SCAN_RESULT");
        if (contents == null) contents = "";
        contents = contents.trim();
        if (applySetupString(contents, pendingEtUrl, pendingEtTok, pendingEtFp)) {
            // Felder sind u.U. schon weg (Dialog zu) – trotzdem speichern
            if (pendingEtUrl != null) {
                commitServerFields(pendingEtUrl, pendingEtTok, pendingEtFp);
            } else {
                // Werte stehen schon in Net / server durch applySetupString ohne EditTexts
                // applySetupString mit null-EditTexts: braucht Expand - siehe unten
                prefs.edit()
                        .putString("server", server)
                        .putString("token", Net.getAuthToken())
                        .putString("certFp", Net.getCertFingerprint())
                        .apply();
                applyServer();
            }
            Toast.makeText(this, R.string.setup_applied, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, getString(R.string.setup_invalid) + "\n" + contents, Toast.LENGTH_LONG).show();
        }
    }

    private String readClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return null;
            CharSequence t = cd.getItemAt(0).coerceToText(this);
            return t != null ? t.toString().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Setup-String vom Server (QR / setup.txt):
     *   dtmini:1|https://host:port|TOKEN|FINGERPRINT
     *   dtmini:token|TOKEN
     *   dtmini:cert|FINGERPRINT|https://host:port
     * Schreibt bei Erfolg auch in server / Net, auch wenn EditTexts null sind.
     */
    private boolean applySetupString(String s, EditText etUrl, EditText etTok, EditText etFp) {
        if (s == null) return false;
        s = s.trim();
        // Scanner / Messenger haengen oft Newlines, Spaces oder URL-Encoding an
        s = s.replace("\r", "").replace("\n", "").trim();
        if (s.contains("%7C") || s.contains("%3A") || s.contains("%7c")) {
            try { s = java.net.URLDecoder.decode(s, "UTF-8"); } catch (Exception ignored) {}
        }
        // Manchmal kommt der Text in Anfuehrungszeichen
        if (s.length() >= 2 && ((s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
                || (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\''))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (!s.startsWith("dtmini:")) return false;
        String[] p = s.split("\\|", -1);
        if (p.length < 2) return false;
        if (p[0].equals("dtmini:1") && p.length >= 2) {
            if (p[1].length() > 0) {
                if (etUrl != null) etUrl.setText(p[1]);
                server = p[1];
            }
            if (p.length >= 3) {
                if (etTok != null) etTok.setText(p[2]);
                Net.setAuthToken(p[2]);
            }
            if (p.length >= 4) {
                String fp = p[3].trim().toLowerCase();
                if (etFp != null) etFp.setText(fp);
                Net.setCertFingerprint(fp);
            }
            return true;
        }
        if (p[0].equals("dtmini:token") && p.length >= 2) {
            if (etTok != null) etTok.setText(p[1]);
            Net.setAuthToken(p[1]);
            return true;
        }
        if (p[0].equals("dtmini:cert") && p.length >= 2) {
            String fp = p[1].trim().toLowerCase();
            if (etFp != null) etFp.setText(fp);
            Net.setCertFingerprint(fp);
            if (p.length >= 3 && p[2].length() > 0) {
                if (etUrl != null) etUrl.setText(p[2]);
                server = p[2];
            }
            return true;
        }
        return false;
    }


    // ------------------------------------------------------------------ Menue


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, M_NEW_TAB, 0, R.string.new_tab);
        menu.add(0, M_TABS, 1, getString(R.string.tabs_count, tabs.size()));
        menu.add(0, M_RELOAD, 2, R.string.reload);
        menu.add(0, M_BACK, 3, R.string.back);
        menu.add(0, M_BOOKMARK, 4, R.string.add_bookmark);
        menu.add(0, M_BOOKMARKS, 5, R.string.bookmarks);
        menu.add(0, M_HISTORY, 6, R.string.history);
        menu.add(0, M_SETTINGS, 7, R.string.settings);
        menu.add(0, M_DEBUG, 8, R.string.debug_overlay).setCheckable(true).setChecked(pv.isDebug());
        menu.add(0, M_SERVER, 9, R.string.server_address);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case M_RELOAD: {
                Page cur = pv.getPage();
                if (cur != null && cur.url.length() > 0) load(cur.url);
                else if (urlField.getText().length() > 0) go();
                return true;
            }
            case M_BACK:
                onBackPressed();
                return true;
            case M_DEBUG:
                pv.setDebug(!pv.isDebug());
                item.setChecked(pv.isDebug());
                return true;
            case M_SERVER: {
                showServerDialog();
                return true;
            }
            case M_BOOKMARK: {
                Page cur = pv.getPage();
                if (cur != null && cur.url.length() > 0) {
                    store.addBookmark(cur.url, cur.title);
                    Toast.makeText(this, R.string.bookmark_saved, Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            case M_BOOKMARKS:
                showEntryList(getString(R.string.bookmarks), store.getBookmarks(), true);
                return true;
            case M_HISTORY:
                showEntryList(getString(R.string.history), store.getHistory(), false);
                return true;
            case M_NEW_TAB:
                newTab();
                return true;
            case M_TABS:
                showTabs();
                return true;
            case M_SETTINGS:
                showSettings();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void newTab() {
        saveCurrentTab();
        Tab tb = new Tab();
        tabs.add(tb);
        tabIndex = tabs.size() - 1;
        sid = null;
        depth = 0;
        pv.setPage(null, false);
        urlField.setText("");
        updateLock(null);
        Toast.makeText(this, getString(R.string.tab_n, tabs.size()), Toast.LENGTH_SHORT).show();
        invalidateOptionsMenu();
    }

    private void saveCurrentTab() {
        if (tabIndex < 0 || tabIndex >= tabs.size()) return;
        Tab tb = tabs.get(tabIndex);
        tb.sid = sid;
        tb.depth = depth;
        Page cur = pv.getPage();
        if (cur != null) {
            tb.page = cur;
            tb.url = cur.url;
            tb.title = cur.title != null ? cur.title : cur.url;
        }
    }

    private void switchTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        saveCurrentTab();
        tabIndex = index;
        Tab tb = tabs.get(tabIndex);
        sid = tb.sid;
        depth = tb.depth;
        if (tb.page != null) {
            pv.setPage(tb.page, false);
            urlField.setText(tb.url);
            updateLock(tb.page);
        } else if (tb.url != null && tb.url.length() > 0) {
            load(tb.url);
        } else {
            pv.setPage(null, false);
            urlField.setText("");
            updateLock(null);
        }
        invalidateOptionsMenu();
    }

    private void showTabs() {
        final int n = tabs.size();
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) {
            Tab tb = tabs.get(i);
            String t = (tb.title != null && tb.title.length() > 0) ? tb.title : (tb.url.length() > 0 ? tb.url : getString(R.string.empty_tab));
            if (t.length() > 40) t = t.substring(0, 37) + "...";
            labels[i] = (i == tabIndex ? "• " : "  ") + t;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.tabs)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switchTab(which);
                    }
                })
                .setNeutralButton(R.string.close_current, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        closeCurrentTab();
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void closeCurrentTab() {
        if (tabs.size() <= 1) {
            sid = null;
            depth = 0;
            tabs.get(0).page = null;
            tabs.get(0).url = "";
            tabs.get(0).title = "";
            tabs.get(0).sid = null;
            pv.setPage(null, false);
            urlField.setText("");
            return;
        }
        tabs.remove(tabIndex);
        if (tabIndex >= tabs.size()) tabIndex = tabs.size() - 1;
        Tab tb = tabs.get(tabIndex);
        sid = tb.sid;
        depth = tb.depth;
        if (tb.page != null) {
            pv.setPage(tb.page, false);
            urlField.setText(tb.url);
            updateLock(tb.page);
        } else {
            pv.setPage(null, false);
            urlField.setText(tb.url != null ? tb.url : "");
        }
        invalidateOptionsMenu();
    }

    private void showEntryList(String title, final java.util.List<Store.Entry> entries, final boolean bookmarks) {
        if (entries.isEmpty()) {
            Toast.makeText(this, getString(R.string.is_empty, title), Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            Store.Entry e = entries.get(i);
            String t = (e.title != null && e.title.length() > 0) ? e.title : e.url;
            if (t.length() > 48) t = t.substring(0, 45) + "...";
            labels[i] = t;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        load(entries.get(which).url);
                    }
                })
                .setNegativeButton(R.string.close, null);
        if (!bookmarks) {
            b.setNeutralButton(R.string.clear_history, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    store.clearHistory();
                    Toast.makeText(MainActivity.this, R.string.history_cleared, Toast.LENGTH_SHORT).show();
                }
            });
        }
        b.show();
    }

    private void showSettings() {
        boolean skipHint = prefs.getBoolean("skip_default_prompt", false);
        String[] items = new String[] {
                isDefaultBrowser() ? getString(R.string.default_browser_yes) : getString(R.string.set_as_default),
                getString(R.string.clear_history),
                skipHint ? getString(R.string.default_hint_off) : getString(R.string.default_hint_on)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            maybePromptDefaultBrowser();
                            try {
                                startActivity(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
                            } catch (Exception e) {}
                        } else if (which == 1) {
                            store.clearHistory();
                            Toast.makeText(MainActivity.this, R.string.history_cleared, Toast.LENGTH_SHORT).show();
                        } else if (which == 2) {
                            boolean skip = prefs.getBoolean("skip_default_prompt", false);
                            prefs.edit().putBoolean("skip_default_prompt", !skip).apply();
                            Toast.makeText(MainActivity.this, !skip ? R.string.hint_off : R.string.hint_on, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

}
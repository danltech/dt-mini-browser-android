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
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import java.util.UUID;
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
        server = prefs.getString("server", "http://192.168.0.52:8080");
        clientId = prefs.getString("clientId", null);
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
        if (!server.startsWith("http")) server = "http://" + server;
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
        AlertDialog d = new AlertDialog.Builder(this)
                .setView(et)
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
                final EditText et = new EditText(this);
                et.setText(server);
                et.setSingleLine(true);
                new AlertDialog.Builder(this)
                        .setTitle(R.string.server_address)
                        .setView(et)
                        .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                server = et.getText().toString().trim();
                                prefs.edit().putString("server", server).apply();
                                applyServer();
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
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

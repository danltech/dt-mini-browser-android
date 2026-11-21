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

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Verlauf und Lesezeichen in SharedPreferences (JSON). */
public class Store {
    private final SharedPreferences prefs;

    public static class Entry {
        public String url;
        public String title;
        public long time;

        public Entry(String url, String title, long time) {
            this.url = url != null ? url : "";
            this.title = title != null ? title : "";
            this.time = time;
        }
    }

    public Store(Context ctx) {
        prefs = ctx.getSharedPreferences("mini_store", Context.MODE_PRIVATE);
    }

    public void addHistory(String url, String title) {
        if (url == null || url.length() == 0 || url.startsWith("about:")) return;
        List<Entry> list = getHistory();
        // Duplikat vorne entfernen
        for (int i = list.size() - 1; i >= 0; i--) {
            if (url.equals(list.get(i).url)) list.remove(i);
        }
        list.add(0, new Entry(url, title, System.currentTimeMillis()));
        while (list.size() > 100) list.remove(list.size() - 1);
        save("history", list);
    }

    public List<Entry> getHistory() {
        return load("history");
    }

    public void clearHistory() {
        prefs.edit().remove("history").apply();
    }

    public void addBookmark(String url, String title) {
        if (url == null || url.length() == 0) return;
        List<Entry> list = getBookmarks();
        for (Entry e : list) {
            if (url.equals(e.url)) return;
        }
        list.add(0, new Entry(url, title, System.currentTimeMillis()));
        save("bookmarks", list);
    }

    public void removeBookmark(String url) {
        List<Entry> list = getBookmarks();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (url.equals(list.get(i).url)) list.remove(i);
        }
        save("bookmarks", list);
    }

    public boolean isBookmarked(String url) {
        for (Entry e : getBookmarks()) {
            if (url.equals(e.url)) return true;
        }
        return false;
    }

    public List<Entry> getBookmarks() {
        return load("bookmarks");
    }

    private void save(String key, List<Entry> list) {
        try {
            JSONArray a = new JSONArray();
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("url", e.url);
                o.put("title", e.title);
                o.put("time", e.time);
                a.put(o);
            }
            prefs.edit().putString(key, a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private List<Entry> load(String key) {
        List<Entry> list = new ArrayList<Entry>();
        try {
            String raw = prefs.getString(key, "[]");
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                list.add(new Entry(o.optString("url"), o.optString("title"), o.optLong("time")));
            }
        } catch (Exception ignored) {
        }
        return list;
    }
}

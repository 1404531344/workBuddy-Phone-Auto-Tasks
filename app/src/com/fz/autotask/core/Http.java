package com.fz.autotask.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/** 极简 HTTP POST（无第三方库）。 */
public final class Http {

    public static final class Resp {
        public int status = -1;
        public String body = "";
        public String error = "";
        public java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        @Override
        public String toString() {
            return "http " + status + (error.isEmpty() ? "" : " (" + error + ")");
        }
    }

    private Http() {
    }

    public static Resp postJson(String url, Map<String, String> headers, String body, int timeoutMs) {
        Resp r = new Resp();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Content-Type", "application/json;charset=utf-8");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        c.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            byte[] out = (body == null || body.isEmpty() ? "{}" : body).getBytes("UTF-8");
            c.setFixedLengthStreamingMode(out.length);
            OutputStream os = c.getOutputStream();
            os.write(out);
            os.flush();
            os.close();

            r.status = c.getResponseCode();
            InputStream is = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = readAll(is);
            try {
                for (java.util.Map.Entry<String, java.util.List<String>> he : c.getHeaderFields().entrySet()) {
                    if (he.getKey() != null && he.getValue() != null && !he.getValue().isEmpty()) {
                        r.headers.put(he.getKey(), he.getValue().get(0));
                    }
                }
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            r.status = -1;
            r.error = e.toString();
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        return r;
    }

    /** GET（用于 OAuth 轮询这类只读接口）。 */
    public static Resp get(String url, Map<String, String> headers, int timeoutMs) {
        Resp r = new Resp();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        c.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            r.status = c.getResponseCode();
            InputStream is = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = readAll(is);
            try {
                for (java.util.Map.Entry<String, java.util.List<String>> he : c.getHeaderFields().entrySet()) {
                    if (he.getKey() != null && he.getValue() != null && !he.getValue().isEmpty()) {
                        r.headers.put(he.getKey(), he.getValue().get(0));
                    }
                }
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            r.status = -1;
            r.error = e.toString();
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        return r;
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }
}

package com.fz.autotask.modules.workbuddy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 HTTPS/1.1 POST（自己实现 TLS + 报文），用来绕开 OkHttp/HttpURLConnection 的自动行为。
 * 只支持 https、单一请求、Connection: close。
 */
public final class RawHttps {

    public static final class Resp {
        public int status = -1;
        public String body = "";
        public String error = "";
        public Map<String, String> headers = new LinkedHashMap<String, String>();
    }

    private RawHttps() {
    }

    public static Resp post(String url, Map<String, String> headers, String body, int timeoutMs) {
        Resp r = new Resp();
        SSLSocket sock = null;
        try {
            URL u = new URL(url);
            URI uri = u.toURI();
            String host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : 443;
            String path = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, null, null);
            SSLSocketFactory f = ctx.getSocketFactory();
            sock = (SSLSocket) f.createSocket();
            sock.connect(new InetSocketAddress(host, port), timeoutMs);
            sock.setSoTimeout(timeoutMs);
            SSLParameters p = sock.getSSLParameters();
            try {
                p.setApplicationProtocols(new String[]{"http/1.1"});
            } catch (Exception ignored) {
            }
            p.setEndpointIdentificationAlgorithm("HTTPS");
            sock.setSSLParameters(p);
            sock.startHandshake();

            byte[] data = (body == null || body.isEmpty() ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            sb.append("POST ").append(path).append(" HTTP/1.1\r\n");
            sb.append("Host: ").append(host).append("\r\n");
            sb.append("Connection: close\r\n");
            sb.append("Content-Length: ").append(data.length).append("\r\n");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                    }
                }
            }
            sb.append("\r\n");

            OutputStream os = sock.getOutputStream();
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            os.write(data);
            os.flush();

            InputStream is = sock.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int prev3 = -1, prev2 = -1, prev1 = -1, cur;
            while ((cur = is.read()) >= 0) {
                head.write(cur);
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && cur == '\n') {
                    break;
                }
                prev3 = prev2;
                prev2 = prev1;
                prev1 = cur;
            }
            String headStr = new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
            String[] lines = headStr.split("\r\n");
            if (lines.length > 0) {
                String[] parts = lines[0].split(" ");
                if (parts.length >= 2) {
                    try {
                        r.status = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            int contentLength = -1;
            boolean chunked = false;
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c <= 0) {
                    continue;
                }
                String k = lines[i].substring(0, c).trim();
                String v = lines[i].substring(c + 1).trim();
                r.headers.put(k, v);
                String lk = k.toLowerCase();
                if (lk.equals("content-length")) {
                    try {
                        contentLength = Integer.parseInt(v);
                    } catch (NumberFormatException ignored) {
                    }
                } else if (lk.equals("transfer-encoding") && v.toLowerCase().contains("chunked")) {
                    chunked = true;
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (chunked) {
                readChunked(is, out);
            } else if (contentLength >= 0) {
                readFixed(is, out, contentLength);
            } else {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            r.body = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            r.status = -1;
            r.error = e.toString();
        } finally {
            if (sock != null) {
                try {
                    sock.close();
                } catch (Exception ignored) {
                }
            }
        }
        return r;
    }

    private static void readFixed(InputStream is, ByteArrayOutputStream out, int len) throws Exception {
        byte[] buf = new byte[8192];
        int left = len;
        while (left > 0) {
            int n = is.read(buf, 0, Math.min(buf.length, left));
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            left -= n;
        }
    }

    private static void readChunked(InputStream is, ByteArrayOutputStream out) throws Exception {
        while (true) {
            StringBuilder line = new StringBuilder();
            int ch;
            while ((ch = is.read()) >= 0) {
                if (ch == '\n') {
                    break;
                }
                if (ch != '\r') {
                    line.append((char) ch);
                }
            }
            if (ch < 0) {
                break;
            }
            String s = line.toString().trim();
            int semi = s.indexOf(';');
            if (semi >= 0) {
                s = s.substring(0, semi);
            }
            if (s.isEmpty()) {
                continue;
            }
            int size;
            try {
                size = Integer.parseInt(s, 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (size == 0) {
                break;
            }
            readFixed(is, out, size);
            is.read();
            is.read();
        }
    }
}

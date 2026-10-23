package com.riverguard.v2;

import java.net.URI;

/** Only explicit private-Wi-Fi IPv4 destinations can be opened inside the app. */
public final class LocalAddress {
    private LocalAddress() {}

    public static boolean isPrivateUrl(String value) {
        try {
            URI uri = new URI(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getUserInfo() != null) return false;
            int port = uri.getPort();
            if (port == 0 || port > 65535 || port < -1) return false;
            String host = uri.getHost();
            if (host == null) return false;
            String[] pieces = host.split("\\.", -1);
            if (pieces.length != 4) return false;
            int[] ip = new int[4];
            for (int i = 0; i < 4; i++) {
                if (!pieces[i].matches("0|[1-9][0-9]{0,2}")) return false;
                ip[i] = Integer.parseInt(pieces[i]);
                if (ip[i] > 255) return false;
            }
            return ip[0] == 10 || (ip[0] == 192 && ip[1] == 168) || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31);
        } catch (Exception ignored) {
            return false;
        }
    }
}

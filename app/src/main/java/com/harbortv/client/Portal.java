package com.harbortv.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Portal {
    static final String USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) MAG254";
    private static final String MAG424_USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) "
            + "AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3";
    private static final Pattern STREAM = Pattern.compile("https?://[^\\s\"']+", Pattern.CASE_INSENSITIVE);
    private final URI address;
    private final String mac;
    private final String serial;
    private final String model;
    private final String approvedRedirect;
    private final CookieManager cookies = new CookieManager(null, (uri, cookie) ->
            !"mac".equalsIgnoreCase(cookie.getName()) && !"stb_lang".equalsIgnoreCase(cookie.getName())
                    && CookiePolicy.ACCEPT_ORIGINAL_SERVER.shouldAccept(uri, cookie));
    private String endpoint;
    private String token;
    private final Map<String, String> seriesModules = new HashMap<>();

    Portal(String address, String mac, String serial) {
        this(address, mac, serial, "MAG254");
    }

    Portal(String address, String mac, String serial, String model) {
        this(address, mac, serial, model, "");
    }

    Portal(String address, String mac, String serial, String model, String approvedRedirect) {
        if (!"MAG254".equals(model) && !"MAG424".equals(model)) {
            throw new IllegalArgumentException("Choose a supported STB model.");
        }
        this.address = httpUri(address.trim());
        this.mac = mac.trim().toUpperCase(Locale.ROOT);
        this.serial = serial.trim();
        this.model = model;
        this.approvedRedirect = approvedRedirect;
        if (!this.mac.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) {
            throw new IllegalArgumentException("Enter the MAC registered in your panel (six pairs separated by colons).");
        }
    }

    static URI httpUri(String value) {
        try {
            URI uri = URI.create(value);
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
        }
        throw new IllegalArgumentException("Enter a valid URL starting with http:// or https://.");
    }

    interface ConnectionProgress {
        void update(int percent);
    }

    List<Channel> connect(ConnectionProgress progress) throws IOException {
        token = null;
        seriesModules.clear();
        cookies.getCookieStore().removeAll();
        PortalHttpException endpointError = null;
        IOException replyError = null;
        String challenge = "";
        String invalidToken = "0";
        boolean ministra = "MAG424".equals(model);
        Set<String> candidates = new LinkedHashSet<>();
        URI portalPage = discoverPortalPage();
        progress.update(20);
        if (!portalPage.equals(address)) candidates.addAll(endpoints(portalPage));
        candidates.addAll(endpoints(address));
        for (String candidate : candidates) {
            endpoint = candidate;
            try {
                Object reply = request("stb", "handshake", "token", "", "prehash", ministra ? "0" : "false");
                if (reply instanceof JSONObject) {
                    String issuedToken = ((JSONObject) reply).optString("token", "");
                    if (!issuedToken.isEmpty() && !"null".equals(issuedToken)) {
                        token = issuedToken;
                        challenge = field((JSONObject) reply, "random", "");
                        invalidToken = flag((JSONObject) reply, "not_valid") ? "1" : "0";
                        break;
                    }
                }
                replyError = new IOException("The portal did not issue a session. Check MAC registration and access.");
            } catch (PortalResponseException error) {
                if (replyError == null) replyError = error;
            } catch (PortalHttpException error) {
                if (error.status == 401 || error.status == 403) throw error;
                if (endpointError == null || endpointError.status == 404) endpointError = error;
            }
        }
        if (token == null) {
            if (replyError != null) throw replyError;
            if (endpointError != null) throw endpointError;
            throw new IOException("Could not find a Stalker portal endpoint at this URL.");
        }

        progress.update(45);
        Object profile = ministra ? ministraProfile(challenge, invalidToken)
                : request("stb", "get_profile", "hd", "1", "stb_type", model,
                        "client_type", "STB", "video_out", "hdmi", "sn", serial);
        if (!(profile instanceof JSONObject)) {
            throw new IOException("The portal did not return a device profile. Check the MAC and serial in the panel.");
        }
        String profileToken = field((JSONObject) profile, "token", "");
        if (!profileToken.isEmpty()) token = profileToken;
        if (ministra) {
            initializePortal("get_localization");
            initializePortal("get_modules");
        }
        progress.update(70);
        try {
            JSONArray rows = channelRows(ministra
                    ? request("itv", "get_all_channels", "force_ch_link_check",
                            flag((JSONObject) profile, "force_ch_link_check") ? "1" : "0")
                    : request("itv", "get_all_channels"));
            if (rows != null) {
                List<Channel> channels = new ArrayList<>();
                addChannels(channels, rows);
                return channels;
            }
        } catch (PortalResponseException error) {
            if (error.sessionRejected) throw error;
            // Some panels only implement the paged channel list.
        } catch (PortalHttpException error) {
            if (error.status != 400 && error.status != 404 && error.status != 405
                    && error.status != 501) throw error;
        }
        return channelsInCategory("*");
    }

    private Object ministraProfile(String challenge, String invalidToken) throws IOException {
        JSONObject metrics = new JSONObject();
        try {
            metrics.put("mac", mac);
            metrics.put("sn", serial);
            metrics.put("model", model);
            metrics.put("type", "STB");
            metrics.put("uid", "");
            metrics.put("random", challenge);
        } catch (JSONException error) {
            throw new IOException("Could not prepare the device profile.", error);
        }
        return request("stb", "get_profile", "hd", "1", "stb_type", model, "client_type", "STB",
                "video_out", "hdmi", "sn", serial, "device_id", "", "device_id2", "",
                "num_banks", "2", "image_version", "220",
                "auth_second_step", "1", "not_valid_token", invalidToken, "metrics", metrics.toString(),
                "timestamp", Long.toString(System.currentTimeMillis() / 1000), "prehash", "0");
    }

    private void initializePortal(String action) throws IOException {
        try {
            request("stb", action);
        } catch (PortalResponseException error) {
            if (error.sessionRejected) throw error;
        } catch (PortalHttpException error) {
            if (error.status != 400 && error.status != 404 && error.status != 405
                    && error.status != 501) throw error;
        }
    }

    List<JSONObject> channelCategories() throws IOException {
        return objects(request("itv", "get_genres"), "channel categories");
    }

    List<Channel> channelsInCategory(String category) throws IOException {
        List<Channel> channels = new ArrayList<>();
        int total = -1;
        int received = 0;
        String previousPage = null;
        for (int page = 1; page <= 500; page++) {
            Object reply = request("itv", "get_ordered_list", "genre", category,
                    "fav", "0", "sortby", "number", "p", Integer.toString(page));
            JSONArray rows = channelRows(reply);
            if (rows == null) {
                throw new IOException("The portal did not provide a channel list. Check the MAC's live TV package in the panel.");
            }
            if (reply instanceof JSONObject) {
                total = ((JSONObject) reply).optInt("total_items", total);
            }
            if (rows.length() == 0) {
                if (received < total) throw new IOException("The portal returned an incomplete channel list.");
                return channels;
            }
            String currentPage = rows.toString();
            if (currentPage.equals(previousPage)) {
                throw new IOException("The portal repeated a page while loading channels.");
            }
            addChannels(channels, rows);
            received += rows.length();
            if (total < 0 || received >= total) return channels;
            previousPage = currentPage;
        }
        throw new IOException("The portal channel list exceeded 500 pages.");
    }

    private static JSONArray channelRows(Object reply) {
        return reply instanceof JSONObject ? ((JSONObject) reply).optJSONArray("data")
                : reply instanceof JSONArray ? (JSONArray) reply : null;
    }

    private static void addChannels(List<Channel> channels, JSONArray rows) {
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row == null || row.isNull("cmd") || row.optString("cmd").trim().isEmpty()) continue;
            channels.add(new Channel(field(row, "id", field(row, "ch_id", "")),
                    row.optString("number"), row.optString("name", "Channel"),
                    row.optString("cmd"), row.optInt("use_http_tmp_link") == 1,
                    flag(row, "tv_archive") || flag(row, "enable_tv_archive")
                            || flag(row, "has_archive") || row.optInt("tv_archive_duration") > 0));
        }
    }

    String resolve(Channel channel) throws IOException {
        String direct = streamUrl(channel.command, false);
        if (!channel.temporaryLink && direct != null) return direct;
        return createLink("itv", channel.command, "");
    }

    List<JSONObject> categories(String type) throws IOException {
        return objects(request(type, "get_categories"), "categories");
    }

    CatalogPage catalog(String type, int page, String... filters) throws IOException {
        String[] parameters = new String[filters.length + 2];
        System.arraycopy(filters, 0, parameters, 0, filters.length);
        parameters[filters.length] = "p";
        parameters[filters.length + 1] = Integer.toString(page);
        Object reply = request(type, "get_ordered_list", parameters);
        List<JSONObject> items = objects(reply, "titles");
        int total = reply instanceof JSONObject ? ((JSONObject) reply).optInt("total_items", -1) : -1;
        int size = reply instanceof JSONObject
                ? ((JSONObject) reply).optInt("max_page_items", items.size()) : items.size();
        return new CatalogPage(items, !items.isEmpty() && (total < 0 || size > 0 && (long) page * size < total));
    }

    CatalogPage seriesCatalog(int page, String movieId, String seasonId) throws IOException {
        String[] filters = {"movie_id", movieId, "season_id", seasonId, "episode_id", "0"};
        String key = movieId + "/" + seasonId;
        String module = seriesModules.get(key);
        if (module != null) return catalog(module, page, filters);
        try {
            CatalogPage result = catalog("series", page, filters);
            if (!result.items.isEmpty() && isSeriesDetail(result.items, seasonId)) {
                seriesModules.put(key, "series");
                return result;
            }
        } catch (PortalResponseException error) {
            if (error.sessionRejected) throw error;
        } catch (PortalHttpException error) {
            if (error.status != 400 && error.status != 404 && error.status != 405
                    && error.status != 501) throw error;
        }
        CatalogPage legacy = catalog("vod", page, filters);
        if (!isSeriesDetail(legacy.items, seasonId)) {
            throw new IOException("The portal returned a catalogue instead of seasons or episodes.");
        }
        seriesModules.put(key, "vod");
        return legacy;
    }

    private static boolean isSeriesDetail(List<JSONObject> items, String seasonId) {
        for (JSONObject item : items) {
            if (flag(item, "is_episode") || !field(item, "series_number", "").isEmpty()
                    || (!field(item, "season_id", "").isEmpty() && !"0".equals(field(item, "season_id", "")))) {
                return true;
            }
            if ("0".equals(seasonId)) {
                JSONArray episodes = item.optJSONArray("series");
                if (flag(item, "is_season") || (episodes != null && episodes.length() > 0)
                        || field(item, "id", "").matches("[0-9]+:[0-9]+")) return true;
            }
        }
        return items.isEmpty();
    }

    List<JSONObject> programmes(Channel channel, String date, boolean today) throws IOException {
        List<JSONObject> programmes = new ArrayList<>();
        String previous = null;
        try {
            int total = -1;
            // On some panels page zero jumps to the current programme in the middle of today.
            for (int page = 1; page <= 100; page++) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Guide request interrupted.");
                Object reply = request("epg", "get_simple_data_table", "ch_id", channel.id,
                        "date", date, "p", Integer.toString(page));
                List<JSONObject> rows = programmeObjects(reply, channel.id);
                if (rows.isEmpty()) break;
                String key = rows.toString();
                if (key.equals(previous)) {
                    throw new IOException("The portal repeated a page of programme listings.");
                }
                programmes.addAll(rows);
                previous = key;
                if (reply instanceof JSONObject) total = ((JSONObject) reply).optInt("total_items", total);
                if (total < 0 || programmes.size() >= total) return programmes;
            }
            if (!programmes.isEmpty() && programmes.size() < total) {
                throw new IOException("The portal returned an incomplete programme guide.");
            }
        } catch (PortalResponseException error) {
            if (!today || !programmes.isEmpty()) throw error;
        } catch (PortalHttpException error) {
            if (!today || !programmes.isEmpty() || (error.status != 404 && error.status != 501)) throw error;
        }
        if (programmes.isEmpty() && today) {
            return programmeObjects(request("itv", "get_short_epg", "ch_id", channel.id, "size", "12"), channel.id);
        }
        return programmes;
    }

    List<JSONObject> guidePreview(Channel channel, String date) throws IOException {
        try {
            List<JSONObject> preview = programmeObjects(
                    request("itv", "get_short_epg", "ch_id", channel.id, "size", "8"), channel.id);
            if (!preview.isEmpty()) return preview;
        } catch (PortalResponseException ignored) {
            // Some portals expose programme data through the dated guide only.
        } catch (PortalHttpException error) {
            if (error.status != 404 && error.status != 405 && error.status != 501) throw error;
        }
        return programmeObjects(request("epg", "get_simple_data_table", "ch_id", channel.id,
                "date", date, "p", "0"), channel.id);
    }

    String posterUrl(JSONObject item) {
        URI portal = URI.create(endpoint);
        String base = portal.getRawPath().replaceFirst("/(?:c/)?server/load\\.php$|/portal\\.php$", "");
        URI root = URI.create(portal.getScheme() + "://" + portal.getRawAuthority() + base + "/");
        for (String key : new String[] {"screenshot_uri", "poster", "cover", "cover_big", "pic", "image", "logo"}) {
            String value = field(item, key, "").trim();
            if (value.isEmpty()) continue;
            try {
                return httpUri(root.resolve(value.replace(" ", "%20")).toASCIIString()).toASCIIString();
            } catch (IllegalArgumentException ignored) {
                // Try the next artwork field.
            }
        }
        return "";
    }

    String resolveVideo(String command, String episode) throws IOException {
        String direct = streamUrl(command, false);
        if (episode.isEmpty() && direct != null) return direct;
        if (command.isEmpty()) throw new IOException("The portal did not provide a playback command for this title.");
        return createLink("vod", command, episode);
    }

    String resolveMovie(JSONObject item) throws IOException {
        String command = field(item, "cmd", field(item, "play_url", field(item, "url", "")));
        if (command.trim().isEmpty()) {
            String id = field(item, "id", "");
            if (id.matches("[0-9]+")) command = "/media/" + id + ".mpg";
        }
        return resolveVideo(command, "");
    }

    ReplayLinks resolveArchive(JSONObject programme) throws IOException {
        String command = archiveCommand(programme);
        if (!archived(programme) || command.isEmpty()) {
            throw new IOException("The guide entry did not contain a replay command or programme ID.");
        }
        Object reply = linkReply("tv_archive", command, "");
        String primary = playableLink(reply);
        String alternate = null;
        if (reply instanceof JSONObject) {
            JSONObject result = (JSONObject) reply;
            if (result.optInt("error") == 0) alternate = streamUrl(field(result, "download_cmd", ""), true);
        }
        if (primary == null) {
            if (alternate == null) throw new IOException("The portal did not provide a playable replay stream.");
            return new ReplayLinks(alternate, null);
        }
        return new ReplayLinks(primary, primary.equals(alternate) ? null : alternate);
    }

    private String createLink(String type, String command, String episode) throws IOException {
        String resolved = playableLink(linkReply(type, command, episode));
        if (resolved != null) return resolved;
        throw new IOException("The portal did not provide a playable HTTP stream.");
    }

    private Object linkReply(String type, String command, String episode) throws IOException {
        return "itv".equals(type)
                ? request(type, "create_link", "cmd", command, "forced_storage", "0",
                        "disable_ad", "0", "download", "0")
                : request(type, "create_link", "cmd", command, "series", episode,
                        "forced_storage", "0", "disable_ad", "0", "download", "0");
    }

    private String playableLink(Object reply) {
        if (reply instanceof JSONObject) {
            JSONObject result = (JSONObject) reply;
            if (result.optInt("error") == 0) {
                return streamUrl(field(result, "cmd", ""), true);
            }
        } else if (reply instanceof String) {
            return streamUrl((String) reply, true);
        }
        return null;
    }

    static final class ReplayLinks {
        final String primary;
        final String alternate;

        ReplayLinks(String primary, String alternate) {
            this.primary = primary;
            this.alternate = alternate;
        }
    }

    static String field(JSONObject row, String key, String fallback) {
        return row.isNull(key) ? fallback : row.optString(key, fallback);
    }

    static boolean flag(JSONObject row, String key) {
        String value = field(row, key, "");
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    static boolean archived(JSONObject programme) {
        return flag(programme, "has_archive") || flag(programme, "mark_archive")
                || flag(programme, "archive") || flag(programme, "is_archive")
                || !field(programme, "archive_cmd", "").isEmpty();
    }

    private static String archiveCommand(JSONObject programme) {
        String command = field(programme, "archive_cmd", field(programme, "cmd", "")).trim();
        if (!command.isEmpty()) return command;
        String id = field(programme, "id", "");
        return id.matches("[0-9]+(?:_[0-9]+)?") ? "auto /media/" + id + ".mpg" : "";
    }

    private static List<JSONObject> programmeObjects(Object reply, String channelId) throws IOException {
        if (reply instanceof JSONObject) {
            JSONObject listing = (JSONObject) reply;
            JSONObject channelMap = listing.optJSONObject("data");
            JSONArray rows = channelMap == null ? listing.optJSONArray(channelId) : channelMap.optJSONArray(channelId);
            if (rows == null) rows = listing.optJSONArray("epg_listings");
            if (rows != null) return objects(rows, "programme listings");
        }
        return objects(reply, "programme listings");
    }

    private static List<JSONObject> objects(Object reply, String label) throws IOException {
        JSONArray rows = channelRows(reply);
        if (rows == null) throw new IOException("The portal did not provide " + label + ".");
        List<JSONObject> items = new ArrayList<>();
        for (int index = 0; index < rows.length(); index++) {
            JSONObject item = rows.optJSONObject(index);
            if (item != null) items.add(item);
        }
        return items;
    }

    static final class CatalogPage {
        final List<JSONObject> items;
        final boolean next;

        CatalogPage(List<JSONObject> items, boolean next) {
            this.items = items;
            this.next = next;
        }
    }

    private String streamUrl(String command, boolean resolved) {
        Matcher matcher = STREAM.matcher(command);
        if (!matcher.find()) return null;
        try {
            URI uri = httpUri(matcher.group());
            String host = uri.getHost();
            if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                    || "[::1]".equals(host) || (!resolved && uri.getPath().startsWith("/ch/"))) return null;
            return uri.toASCIIString();
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private URI discoverPortalPage() throws PortalRedirect {
        String path = address.getRawPath().replaceAll("/+$", "");
        if (!path.isEmpty() && !path.endsWith("/c")) return address;
        URI page = URI.create(address.getScheme() + "://" + address.getRawAuthority()
                + (path.isEmpty() ? "/" : path));
        for (int hop = 0; hop < 4; hop++) {
            try {
                HttpURLConnection connection = (HttpURLConnection) page.toURL().openConnection();
                try {
                    connection.setConnectTimeout(3000);
                    connection.setReadTimeout(4000);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("User-Agent", portalUserAgent());
                    int status = connection.getResponseCode();
                    cookies.put(page, connection.getHeaderFields());
                    if (status == 200) {
                        return page.getRawPath().matches(".*/c(?:/|/index\\.html)?") ? page : address;
                    }
                    if (status < 300 || status >= 400) return address;
                    String location = connection.getHeaderField("Location");
                    if (location == null) return address;
                    URI next = httpUri(page.resolve(location).toASCIIString());
                    if ("https".equalsIgnoreCase(page.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())) {
                        return address;
                    }
                    if (!page.getHost().equalsIgnoreCase(next.getHost()) && !approvedRedirect.equals(next.toASCIIString())) {
                        throw new PortalRedirect(next.toASCIIString(), next.getRawAuthority());
                    }
                    page = next;
                } finally {
                    connection.disconnect();
                }
            } catch (PortalRedirect redirect) {
                throw redirect;
            } catch (IOException | IllegalArgumentException error) {
                return address;
            }
        }
        return address;
    }

    static final class PortalRedirect extends IOException {
        final String destination;
        final String authority;

        PortalRedirect(String destination, String authority) {
            this.destination = destination;
            this.authority = authority;
        }
    }

    private Set<String> endpoints(URI portalAddress) {
        Set<String> result = new LinkedHashSet<>();
        String path = portalAddress.getRawPath().replaceAll("/+$", "");
        String origin = portalAddress.getScheme() + "://" + portalAddress.getRawAuthority();
        if (path.endsWith(".php")) {
            result.add(portalAddress.toASCIIString());
        } else {
            String base = path.replaceFirst("/c(?:/index\\.html)?$", "");
            result.add(origin + base + "/server/load.php");
            result.add(origin + base + "/portal.php");
            result.add(origin + base + "/c/server/load.php");
            if (base.isEmpty()) result.add(origin + "/stalker_portal/server/load.php");
        }
        return result;
    }

    private Object request(String type, String action, String... parameters) throws IOException {
        try {
            return requestOnce(type, action, parameters);
        } catch (EmptyPortalResponse error) {
            if (!"get_ordered_list".equals(action)) throw error;
            return requestOnce(type, action, parameters);
        }
    }

    private Object requestOnce(String type, String action, String... parameters) throws IOException {
        StringBuilder target = new StringBuilder(endpoint)
                .append(endpoint.contains("?") ? '&' : '?')
                .append("type=").append(type).append("&action=").append(action)
                .append("&JsHttpRequest=1-xml");
        for (int i = 0; i < parameters.length; i += 2) {
            target.append('&').append(parameters[i]).append('=')
                    .append(URLEncoder.encode(parameters[i + 1], "UTF-8"));
        }
        URI requestUri = URI.create(target.toString());
        HttpURLConnection connection = (HttpURLConnection) requestUri.toURL().openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", portalUserAgent());
            connection.setRequestProperty("X-User-Agent", "Model: " + model + "; Link: Ethernet");
            String path = requestUri.getRawPath().replaceFirst("/(?:c/)?server/load\\.php$|/portal\\.php$", "");
            connection.setRequestProperty("Referer", requestUri.getScheme() + "://"
                    + requestUri.getRawAuthority() + path + "/c/");
            String cookie = "mac=" + URLEncoder.encode(mac, "UTF-8") + "; stb_lang=en";
            List<String> sessionCookies = cookies.get(requestUri, Collections.emptyMap()).get("Cookie");
            if (sessionCookies != null) {
                for (String sessionCookie : sessionCookies) {
                    if (!sessionCookie.isEmpty()) cookie += "; " + sessionCookie;
                }
            }
            if ("MAG424".equals(model) && !cookie.matches("(?s).*(?:^|;\\s*)timezone=.*")) {
                cookie += "; timezone=" + URLEncoder.encode(TimeZone.getDefault().getID(), "UTF-8");
            }
            connection.setRequestProperty("Cookie", cookie);
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            int status = connection.getResponseCode();
            cookies.put(requestUri, connection.getHeaderFields());
            if (status != 200) throw new PortalHttpException(status, action);
            try (InputStream body = connection.getInputStream();
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = body.read(buffer)) != -1) {
                    if (bytes.size() + count > 16 * 1024 * 1024) {
                        throw new IOException("The portal response is too large for this client.");
                    }
                    bytes.write(buffer, 0, count);
                }
                return decodeResponse(bytes.toString("UTF-8"), action);
            }
        } finally {
            connection.disconnect();
        }
    }

    private String portalUserAgent() {
        return "MAG424".equals(model) ? MAG424_USER_AGENT : USER_AGENT;
    }

    private static Object decodeResponse(String body, String action) throws PortalResponseException {
        boolean listing = "get_all_channels".equals(action) || "get_ordered_list".equals(action)
                || "get_genres".equals(action)
                || "get_categories".equals(action) || "get_simple_data_table".equals(action)
                || "get_short_epg".equals(action);
        String text = body.trim();
        if (text.startsWith("\uFEFF")) text = text.substring(1).trim();
        if (text.isEmpty()) throw new EmptyPortalResponse(action);
        if (text.startsWith("<")) {
            throw new PortalResponseException(action,
                    "the server sent a web page. Check the portal URL or enter its exact API endpoint.");
        }
        if (denied(text)) {
            throw new PortalResponseException(action, "the panel rejected the session.", true);
        }
        String shape = "a value without portal data";
        try {
            Object value = new JSONTokener(text).nextValue();
            if (value instanceof JSONObject) {
                JSONObject envelope = (JSONObject) value;
                if (denied(envelope.optString("error")) || denied(envelope.optString("message"))) {
                    throw new PortalResponseException(action, "the panel rejected the session.", true);
                }
                if (envelope.has("js")) {
                    Object payload = envelope.get("js");
                    if (payload instanceof String) {
                        String nested = ((String) payload).trim();
                        if (nested.startsWith("{") || nested.startsWith("[")) {
                            return new JSONTokener(nested).nextValue();
                        }
                    }
                    return payload;
                }
                if (("handshake".equals(action) && envelope.has("token"))
                        || ("get_profile".equals(action) && envelope.has("id"))
                        || (listing && (envelope.has("data") || envelope.has("epg_listings")))
                        || ("create_link".equals(action) && envelope.has("cmd"))) return envelope;
                shape = envelope.has("error") || envelope.has("message")
                        ? "a JSON error or status message" : "a JSON object without channel data";
            }
            if (listing && value instanceof JSONArray) return value;
        } catch (JSONException error) {
            throw new PortalResponseException(action, "the server sent invalid JSON.");
        }
        throw new PortalResponseException(action, "the server returned " + shape + ".");
    }

    private static boolean denied(String text) {
        String normalized = text.trim().toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        return normalized.equals("authorization failed") || normalized.equals("authentication failed")
                || normalized.equals("unauthorized") || normalized.equals("access denied")
                || normalized.equals("invalid token") || normalized.equals("token expired");
    }

    private static String stage(String action) {
        switch (action) {
            case "handshake": return "Connecting to the portal";
            case "get_profile": return "Loading the device profile";
            case "get_localization": return "Loading portal localization";
            case "get_modules": return "Loading portal modules";
            case "get_all_channels": return "Loading the full channel list";
            case "get_ordered_list": return "Loading the list";
            case "get_categories": return "Loading categories";
            case "get_genres": return "Loading channel categories";
            case "get_short_epg":
            case "get_simple_data_table": return "Loading the TV guide";
            default: return "Opening the channel";
        }
    }

    static final class Channel {
        final String id;
        final String number;
        final String name;
        final String command;
        final boolean temporaryLink;
        final boolean archive;

        Channel(String id, String number, String name, String command, boolean temporaryLink, boolean archive) {
            this.id = id;
            this.number = number;
            this.name = name;
            this.command = command;
            this.temporaryLink = temporaryLink;
            this.archive = archive;
        }
    }

    private static final class PortalHttpException extends IOException {
        final int status;

        PortalHttpException(int status, String action) {
            super(stage(action) + " failed. " + (status == 401 || status == 403
                    ? "The panel denied access. Check MAC registration and access (HTTP " + status + ")."
                    : status >= 300 && status < 400
                    ? "The portal redirected the request. Enter its final URL directly."
                    : "The portal returned HTTP " + status + "."));
            this.status = status;
        }
    }

    private static class PortalResponseException extends IOException {
        final boolean sessionRejected;

        PortalResponseException(String action, String reason) {
            this(action, reason, false);
        }

        PortalResponseException(String action, String reason, boolean sessionRejected) {
            super(stage(action) + " failed because " + reason);
            this.sessionRejected = sessionRejected;
        }
    }

    private static final class EmptyPortalResponse extends PortalResponseException {
        EmptyPortalResponse(String action) {
            super(action, "the server sent an empty response.");
        }
    }
}

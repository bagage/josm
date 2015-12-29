// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.tools;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpRetryException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.data.Version;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.io.Compression;
import org.openstreetmap.josm.io.ProgressInputStream;
import org.openstreetmap.josm.io.ProgressOutputStream;
import org.openstreetmap.josm.io.UTFInputStreamReader;

/**
 * Provides a uniform access for a HTTP/HTTPS server. This class should be used in favour of {@link HttpURLConnection}.
 * @since 9168
 */
public final class HttpClient {

    private URL url;
    private final String requestMethod;
    private int connectTimeout = Main.pref.getInteger("socket.timeout.connect", 15) * 1000;
    private int readTimeout = Main.pref.getInteger("socket.timeout.read", 30) * 1000;
    private byte[] requestBody;
    private long ifModifiedSince;
    private long contentLength;
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private int maxRedirects = Main.pref.getInteger("socket.maxredirects", 5);
    private boolean useCache;
    private String reasonForRequest;

    private HttpClient(URL url, String requestMethod) {
        this.url = url;
        this.requestMethod = requestMethod;
        this.headers.put("Accept-Encoding", "gzip");
    }

    /**
     * Opens the HTTP connection.
     * @return HTTP response
     * @throws IOException if any I/O error occurs
     */
    public Response connect() throws IOException {
        return connect(null);
    }

    /**
     * Opens the HTTP connection.
     * @param progressMonitor progress monitor
     * @return HTTP response
     * @throws IOException if any I/O error occurs
     * @since 9179
     */
    public Response connect(ProgressMonitor progressMonitor) throws IOException {
        if (progressMonitor == null) {
            progressMonitor = NullProgressMonitor.INSTANCE;
        }
        final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(requestMethod);
        connection.setRequestProperty("User-Agent", Version.getInstance().getFullAgentString());
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setInstanceFollowRedirects(maxRedirects > 0);
        if (ifModifiedSince > 0) {
            connection.setIfModifiedSince(ifModifiedSince);
        }
        if (contentLength > 0) {
            connection.setFixedLengthStreamingMode(contentLength);
        }
        connection.setUseCaches(useCache);
        if (!useCache) {
            connection.setRequestProperty("Cache-Control", "no-cache");
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (header.getValue() != null) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
        }

        progressMonitor.beginTask(tr("Contacting Server..."), 1);
        progressMonitor.indeterminateSubTask(null);

        if ("PUT".equals(requestMethod) || "POST".equals(requestMethod) || "DELETE".equals(requestMethod)) {
            Main.info("{0} {1} ({2} kB) ...", requestMethod, url, requestBody.length / 1024);
            headers.put("Content-Length", String.valueOf(requestBody.length));
            connection.setDoOutput(true);
            try (OutputStream out = new BufferedOutputStream(
                    new ProgressOutputStream(connection.getOutputStream(), requestBody.length, progressMonitor))) {
                out.write(requestBody);
            }
        }

        boolean successfulConnection = false;
        try {
            try {
                connection.connect();
                final boolean hasReason = reasonForRequest != null && !reasonForRequest.isEmpty();
                Main.info("{0} {1}{2} -> {3}{4}",
                        requestMethod, url, hasReason ? " (" + reasonForRequest + ")" : "",
                        connection.getResponseCode(),
                        connection.getContentLengthLong() > 0 ? " (" + connection.getContentLengthLong() / 1024 + "KB)" : ""
                );
                if (Main.isDebugEnabled()) {
                    Main.debug("RESPONSE: " + connection.getHeaderFields());
                }
            } catch (IOException e) {
                Main.info("{0} {1} -> !!!", requestMethod, url);
                Main.warn(e);
                //noinspection ThrowableResultOfMethodCallIgnored
                Main.addNetworkError(url, Utils.getRootCause(e));
                throw e;
            }
            if (isRedirect(connection.getResponseCode())) {
                final String redirectLocation = connection.getHeaderField("Location");
                if (redirectLocation == null) {
                    /* I18n: argument is HTTP response code */
                    String msg = tr("Unexpected response from HTTP server. Got {0} response without ''Location'' header." +
                            " Can''t redirect. Aborting.", connection.getResponseCode());
                    throw new IOException(msg);
                } else if (maxRedirects > 0) {
                    url = new URL(redirectLocation);
                    maxRedirects--;
                    Main.info(tr("Download redirected to ''{0}''", redirectLocation));
                    return connect();
                } else if (maxRedirects == 0) {
                    String msg = tr("Too many redirects to the download URL detected. Aborting.");
                    throw new IOException(msg);
                }
            }
            Response response = new Response(connection, progressMonitor);
            successfulConnection = true;
            return response;
        } finally {
            if (!successfulConnection) {
                connection.disconnect();
            }
        }
    }

    /**
     * A wrapper for the HTTP response.
     */
    public static final class Response {
        private final HttpURLConnection connection;
        private final ProgressMonitor monitor;
        private final int responseCode;
        private final String responseMessage;
        private boolean uncompress;
        private boolean uncompressAccordingToContentDisposition;

        private Response(HttpURLConnection connection, ProgressMonitor monitor) throws IOException {
            CheckParameterUtil.ensureParameterNotNull(connection, "connection");
            CheckParameterUtil.ensureParameterNotNull(monitor, "monitor");
            this.connection = connection;
            this.monitor = monitor;
            this.responseCode = connection.getResponseCode();
            this.responseMessage = connection.getResponseMessage();
        }

        /**
         * Sets whether {@link #getContent()} should uncompress the input stream if necessary.
         *
         * @param uncompress whether the input stream should be uncompressed if necessary
         * @return {@code this}
         */
        public Response uncompress(boolean uncompress) {
            this.uncompress = uncompress;
            return this;
        }

        /**
         * Sets whether {@link #getContent()} should uncompress the input stream according to {@code Content-Disposition}
         * HTTP header.
         * @param uncompressAccordingToContentDisposition whether the input stream should be uncompressed according to
         * {@code Content-Disposition}
         * @return {@code this}
         * @since 9172
         */
        public Response uncompressAccordingToContentDisposition(boolean uncompressAccordingToContentDisposition) {
            this.uncompressAccordingToContentDisposition = uncompressAccordingToContentDisposition;
            return this;
        }

        /**
         * Returns the URL.
         * @return the URL
         * @see HttpURLConnection#getURL()
         * @since 9172
         */
        public URL getURL() {
            return connection.getURL();
        }

        /**
         * Returns the request method.
         * @return the HTTP request method
         * @see HttpURLConnection#getRequestMethod()
         * @since 9172
         */
        public String getRequestMethod() {
            return connection.getRequestMethod();
        }

        /**
         * Returns an input stream that reads from this HTTP connection, or,
         * error stream if the connection failed but the server sent useful data.
         * <p>
         * Note: the return value can be null, if both the input and the error stream are null.
         * Seems to be the case if the OSM server replies a 401 Unauthorized, see #3887
         * @return input or error stream
         * @throws IOException if any I/O error occurs
         *
         * @see HttpURLConnection#getInputStream()
         * @see HttpURLConnection#getErrorStream()
         */
        public InputStream getContent() throws IOException {
            InputStream in;
            try {
                in = connection.getInputStream();
            } catch (IOException ioe) {
                in = connection.getErrorStream();
            }
            in = new ProgressInputStream(in, getContentLength(), monitor);
            in = "gzip".equalsIgnoreCase(getContentEncoding()) ? new GZIPInputStream(in) : in;
            Compression compression = Compression.NONE;
            if (uncompress) {
                final String contentType = getContentType();
                Main.debug("Uncompressing input stream according to Content-Type header: {0}", contentType);
                compression = Compression.forContentType(contentType);
            }
            if (uncompressAccordingToContentDisposition && Compression.NONE.equals(compression)) {
                final String contentDisposition = getHeaderField("Content-Disposition");
                final Matcher matcher = Pattern.compile("filename=\"([^\"]+)\"").matcher(contentDisposition);
                if (matcher.find()) {
                    Main.debug("Uncompressing input stream according to Content-Disposition header: {0}", contentDisposition);
                    compression = Compression.byExtension(matcher.group(1));
                }
            }
            in = compression.getUncompressedInputStream(in);
            return in;
        }

        /**
         * Returns {@link #getContent()} wrapped in a buffered reader.
         *
         * Detects Unicode charset in use utilizing {@link UTFInputStreamReader}.
         * @return buffered reader
         * @throws IOException if any I/O error occurs
         */
        public BufferedReader getContentReader() throws IOException {
            return new BufferedReader(
                    UTFInputStreamReader.create(getContent())
            );
        }

        /**
         * Fetches the HTTP response as String.
         * @return the response
         * @throws IOException if any I/O error occurs
         */
        @SuppressWarnings("resource")
        public String fetchContent() throws IOException {
            try (Scanner scanner = new Scanner(getContentReader()).useDelimiter("\\A")) {
                return scanner.hasNext() ? scanner.next() : "";
            }
        }

        /**
         * Gets the response code from this HTTP connection.
         * @return HTTP response code
         *
         * @see HttpURLConnection#getResponseCode()
         */
        public int getResponseCode() {
            return responseCode;
        }

        /**
         * Gets the response message from this HTTP connection.
         * @return HTTP response message
         *
         * @see HttpURLConnection#getResponseMessage()
         * @since 9172
         */
        public String getResponseMessage() {
            return responseMessage;
        }

        /**
         * Returns the {@code Content-Encoding} header.
         * @return {@code Content-Encoding} HTTP header
         */
        public String getContentEncoding() {
            return connection.getContentEncoding();
        }

        /**
         * Returns the {@code Content-Type} header.
         * @return {@code Content-Type} HTTP header
         */
        public String getContentType() {
            return connection.getHeaderField("Content-Type");
        }

        /**
         * Returns the {@code Content-Length} header.
         * @return {@code Content-Length} HTTP header
         */
        public long getContentLength() {
            return connection.getContentLengthLong();
        }

        /**
         * Returns the value of the named header field.
         * @param name the name of a header field
         * @return the value of the named header field, or {@code null} if there is no such field in the header
         * @see HttpURLConnection#getHeaderField(String)
         * @since 9172
         */
        public String getHeaderField(String name) {
            return connection.getHeaderField(name);
        }

        /**
         * Returns the list of Strings that represents the named header field values.
         * @param name the name of a header field
         * @return unmodifiable List of Strings that represents the corresponding field values
         * @see HttpURLConnection#getHeaderFields()
         * @since 9172
         */
        public List<String> getHeaderFields(String name) {
            return connection.getHeaderFields().get(name);
        }

        /**
         * @see HttpURLConnection#disconnect()
         */
        public void disconnect() {
            // TODO is this block necessary for disconnecting?
            // Fix upload aborts - see #263
            connection.setConnectTimeout(100);
            connection.setReadTimeout(100);
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Main.warn("InterruptedException in " + getClass().getSimpleName() + " during cancel");
            }

            connection.disconnect();
        }
    }

    /**
     * Creates a new instance for the given URL and a {@code GET} request
     *
     * @param url the URL
     * @return a new instance
     */
    public static HttpClient create(URL url) {
        return create(url, "GET");
    }

    /**
     * Creates a new instance for the given URL and a {@code GET} request
     *
     * @param url the URL
     * @param requestMethod the HTTP request method to perform when calling
     * @return a new instance
     */
    public static HttpClient create(URL url, String requestMethod) {
        return new HttpClient(url, requestMethod);
    }

    /**
     * Returns the URL set for this connection.
     * @return the URL
     * @see #create(URL)
     * @see #create(URL, String)
     * @since 9172
     */
    public URL getURL() {
        return url;
    }

    /**
     * Returns the request method set for this connection.
     * @return the HTTP request method
     * @see #create(URL, String)
     * @since 9172
     */
    public String getRequestMethod() {
        return requestMethod;
    }

    /**
     * Returns the set value for the given {@code header}.
     * @param header HTTP header name
     * @return HTTP header value
     * @since 9172
     */
    public String getRequestHeader(String header) {
        return headers.get(header);
    }

    /**
     * Sets whether not to set header {@code Cache-Control=no-cache}
     *
     * @param useCache whether not to set header {@code Cache-Control=no-cache}
     * @return {@code this}
     * @see HttpURLConnection#setUseCaches(boolean)
     */
    public HttpClient useCache(boolean useCache) {
        this.useCache = useCache;
        return this;
    }

    /**
     * Sets whether not to set header {@code Connection=close}
     * <p/>
     * This might fix #7640, see
     * <a href='https://web.archive.org/web/20140118201501/http://www.tikalk.com/java/forums/httpurlconnection-disable-keep-alive'>here</a>.
     *
     * @param keepAlive whether not to set header {@code Connection=close}
     * @return {@code this}
     */
    public HttpClient keepAlive(boolean keepAlive) {
        return setHeader("Connection", keepAlive ? null : "close");
    }

    /**
     * Sets a specified timeout value, in milliseconds, to be used when opening a communications link to the resource referenced
     * by this URLConnection. If the timeout expires before the connection can be established, a
     * {@link java.net.SocketTimeoutException} is raised. A timeout of zero is interpreted as an infinite timeout.
     * @param connectTimeout an {@code int} that specifies the connect timeout value in milliseconds
     * @return {@code this}
     * @see HttpURLConnection#setConnectTimeout(int)
     */
    public HttpClient setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    /**
     * Sets the read timeout to a specified timeout, in milliseconds. A non-zero value specifies the timeout when reading from
     * input stream when a connection is established to a resource. If the timeout expires before there is data available for
     * read, a {@link java.net.SocketTimeoutException} is raised. A timeout of zero is interpreted as an infinite timeout.
     * @param readTimeout an {@code int} that specifies the read timeout value in milliseconds
     * @return {@code this}
     * @see HttpURLConnection#setReadTimeout(int)
     */
    public HttpClient setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
        return this;
    }

    /**
     * This method is used to enable streaming of a HTTP request body without internal buffering,
     * when the content length is known in advance.
     * <p>
     * An exception will be thrown if the application attempts to write more data than the indicated content-length,
     * or if the application closes the OutputStream before writing the indicated amount.
     * <p>
     * When output streaming is enabled, authentication and redirection cannot be handled automatically.
     * A {@linkplain HttpRetryException} will be thrown when reading the response if authentication or redirection
     * are required. This exception can be queried for the details of the error.
     *
     * @param contentLength The number of bytes which will be written to the OutputStream
     * @return {@code this}
     * @see HttpURLConnection#setFixedLengthStreamingMode(long)
     * @since 9178
     */
    public HttpClient setFixedLengthStreamingMode(long contentLength) {
        this.contentLength = contentLength;
        return this;
    }

    /**
     * Sets the {@code Accept} header.
     * @param accept header value
     *
     * @return {@code this}
     */
    public HttpClient setAccept(String accept) {
        return setHeader("Accept", accept);
    }

    /**
     * Sets the request body for {@code PUT}/{@code POST} requests.
     * @param requestBody request body
     *
     * @return {@code this}
     */
    public HttpClient setRequestBody(byte[] requestBody) {
        this.requestBody = requestBody;
        return this;
    }

    /**
     * Sets the {@code If-Modified-Since} header.
     * @param ifModifiedSince header value
     *
     * @return {@code this}
     */
    public HttpClient setIfModifiedSince(long ifModifiedSince) {
        this.ifModifiedSince = ifModifiedSince;
        return this;
    }

    /**
     * Sets the maximum number of redirections to follow.
     *
     * Set {@code maxRedirects} to {@code -1} in order to ignore redirects, i.e.,
     * to not throw an {@link IOException} in {@link #connect()}.
     * @param maxRedirects header value
     *
     * @return {@code this}
     */
    public HttpClient setMaxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
        return this;
    }

    /**
     * Sets an arbitrary HTTP header.
     * @param key header name
     * @param value header value
     *
     * @return {@code this}
     */
    public HttpClient setHeader(String key, String value) {
        this.headers.put(key, value);
        return this;
    }

    /**
     * Sets arbitrary HTTP headers.
     * @param headers HTTP headers
     *
     * @return {@code this}
     */
    public HttpClient setHeaders(Map<String, String> headers) {
        this.headers.putAll(headers);
        return this;
    }

    /**
     * Sets a reason to show on console. Can be {@code null} if no reason is given.
     * @param reasonForRequest Reason to show
     * @return {@code this}
     * @since 9172
     */
    public HttpClient setReasonForRequest(String reasonForRequest) {
        this.reasonForRequest = reasonForRequest;
        return this;
    }

    private static boolean isRedirect(final int statusCode) {
        switch (statusCode) {
            case HttpURLConnection.HTTP_MOVED_PERM: // 301
            case HttpURLConnection.HTTP_MOVED_TEMP: // 302
            case HttpURLConnection.HTTP_SEE_OTHER: // 303
            case 307: // TEMPORARY_REDIRECT:
            case 308: // PERMANENT_REDIRECT:
                return true;
            default:
                return false;
        }
    }
}
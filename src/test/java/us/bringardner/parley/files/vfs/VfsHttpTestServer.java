/*
 * <PRE>
 *
 * Copyright Tony Bringarder 1998, 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 */
package us.bringardner.parley.files.vfs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import us.bringardner.parley.core.LogLevel;
import us.bringardner.parley.files.test.FileSourceTestSupport;

/**
 * A small web server for the vfshttp tests (the JDK's own), serving a local folder: files with
 * their length and time, ranges ("bytes=N-"), a folder without its "/" redirected to it, a folder
 * with its "/" as a listing page (with a parent link, sort links, an absolute link and a link to
 * another site, as real servers' pages have), a folder named "nolist" refused (403); Basic
 * login when a user is set; HTTPS with an SSLContext. Its threads stop with it.
 */
final class VfsHttpTestServer implements VfsTestServer {

	static final String HOST = "127.0.0.1";
	private static final AtomicInteger COUNT = new AtomicInteger();
	private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

	private final Path home;
	private final SSLContext tls;
	private volatile String user;
	private volatile String password;
	private volatile boolean ranges = true;
	private final java.util.Map<String, String> redirects = new java.util.concurrent.ConcurrentHashMap<>();
	private HttpServer svr;
	private ExecutorService pool;

	/** A plain server (TLS null) or an HTTPS one. */
	VfsHttpTestServer(SSLContext tls) throws IOException {
		this.tls = tls;
		home = Paths.get(FileSourceTestSupport.scratchDir("vfs-http-" + COUNT.incrementAndGet()));
		Files.createDirectories(home);
	}

	static VfsHttpTestServer started(SSLContext tls) throws IOException {
		VfsHttpTestServer s = new VfsHttpTestServer(tls);
		s.start();
		return s;
	}

	/** Asks for Basic login as USER (null: no login). */
	void login(String user, String password) {
		this.user = user;
		this.password = password;
	}

	/** PATH answers 302 to LOCATION (null: no longer). */
	void redirect(String path, String location) {
		if( location == null ) {
			redirects.remove(path);
		} else {
			redirects.put(path, location);
		}
	}

	/** Whether "Range" is answered (206); if not, the whole file (200), as some servers do. */
	void ranges(boolean on) {
		ranges = on;
	}

	@Override
	public synchronized void start() throws IOException {
		HttpServer s;
		if( tls == null ) {
			s = HttpServer.create(new InetSocketAddress(HOST, 0), 0);
		} else {
			HttpsServer h = HttpsServer.create(new InetSocketAddress(HOST, 0), 0);
			h.setHttpsConfigurator(new HttpsConfigurator(tls));
			s = h;
		}
		pool = Executors.newFixedThreadPool(4);
		s.setExecutor(pool);
		s.createContext("/", this::handle);
		s.start();
		svr = s;
	}

	@Override
	public synchronized void stop() {
		HttpServer s = svr;
		svr = null;
		if( s != null ) {
			s.stop(0);
		}
		if( pool != null ) {
			pool.shutdownNow();
			try {
				pool.awaitTermination(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			pool = null;
		}
	}

	@Override
	public synchronized boolean isRunning() {
		return svr != null;
	}

	@Override
	public String getName() {
		return tls == null ? "HTTP test server" : "HTTPS test server";
	}

	synchronized int port() {
		return svr.getAddress().getPort();
	}

	@Override
	public Path home() {
		return home;
	}

	Properties properties() {
		Properties p = new Properties();
		p.setProperty(VfsHttpFileSourceFactory.PROP_HOST, HOST);
		p.setProperty(VfsHttpFileSourceFactory.PROP_PORT, "" + port());
		p.setProperty(VfsHttpFileSourceFactory.PROP_TIMEOUT, "10s");
		if( user != null ) {
			p.setProperty(VfsHttpFileSourceFactory.PROP_USER, user);
			p.setProperty(VfsHttpFileSourceFactory.PROP_PASSWORD, password);
		}
		return p;
	}

	@Override
	public VfsHttpFileSourceFactory connect() throws IOException {
		VfsHttpFileSourceFactory f = tls == null ? new VfsHttpFileSourceFactory() : new VfsHttpsFileSourceFactory();
		f.setLogLevel(LogLevel.ERROR);
		f.setConnectionProperties(properties());
		if( !f.connect() ) {
			throw new IOException("can't connect to " + getName());
		}
		return f;
	}

	// ------------------------------------------------------------------ serving

	private void handle(HttpExchange x) throws IOException {
		try {
			serve(x);
		} finally {
			x.close();
		}
	}

	private void serve(HttpExchange x) throws IOException {
		{
			if( user != null && !authorized(x) ) {
				x.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"test\"");
				x.sendResponseHeaders(401, -1);
				return;
			}
			String to = redirects.get(x.getRequestURI().getPath());
			if( to != null ) {
				x.getResponseHeaders().add("Location", to);
				x.sendResponseHeaders(302, -1);
				return;
			}
			boolean head = "HEAD".equals(x.getRequestMethod());
			String raw = x.getRequestURI().getRawPath();
			String path = x.getRequestURI().getPath();
			Path p = home.resolve(path.substring(1)).normalize();
			if( !p.startsWith(home) || !Files.exists(p) ) {
				x.sendResponseHeaders(404, -1);
				return;
			}
			if( Files.isDirectory(p) ) {
				if( !raw.endsWith("/") ) {
					x.getResponseHeaders().add("Location", raw + "/");
					x.sendResponseHeaders(301, -1);
					return;
				}
				if( p.getFileName() != null && p.getFileName().toString().equals("nolist") ) {
					x.sendResponseHeaders(403, -1);
					return;
				}
				byte[] page = listing(p, raw).getBytes(StandardCharsets.UTF_8);
				x.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
				send(x, head, 200, page, 0);
				return;
			}
			if( raw.endsWith("/") ) {
				// a file asked for as a folder: 404, as Apache, nginx and Python's server answer
				x.sendResponseHeaders(404, -1);
				return;
			}
			byte[] data = Files.readAllBytes(p);
			x.getResponseHeaders().add("Last-Modified", HTTP_DATE.format(Files.getLastModifiedTime(p).toInstant()));
			x.getResponseHeaders().add("Content-Type", path.endsWith(".txt") ? "text/plain" : "application/octet-stream");
			String range = x.getRequestHeaders().getFirst("Range");
			if( ranges && range != null && range.startsWith("bytes=") && range.endsWith("-") ) {
				int from = Integer.parseInt(range.substring(6, range.length() - 1));
				x.getResponseHeaders().add("Content-Range", "bytes " + from + "-" + (data.length - 1) + "/" + data.length);
				send(x, head, 206, data, from);
				return;
			}
			send(x, head, 200, data, 0);
		}
	}

	private static void send(HttpExchange x, boolean head, int status, byte[] data, int from) throws IOException {
		int len = data.length - from;
		if( head ) {
			x.getResponseHeaders().add("Content-Length", "" + len);
			x.sendResponseHeaders(status, -1);
			return;
		}
		x.sendResponseHeaders(status, len == 0 ? -1 : len);
		try (OutputStream out = x.getResponseBody()) {
			out.write(data, from, len);
		}
	}

	private boolean authorized(HttpExchange x) {
		String auth = x.getRequestHeaders().getFirst("Authorization");
		String want = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
		return want.equals(auth);
	}

	/** A listing page as the common servers write one: links in several forms, and links that aren't children. */
	private static String listing(Path dir, String rawPath) throws IOException {
		StringBuilder b = new StringBuilder("<html><head><title>Index of ").append(rawPath).append("</title></head><body>\n");
		b.append("<a href=\"?C=N;O=D\">Name</a> <a href=\"?C=M;O=A\">Last modified</a>\n");
		b.append("<a href=\"../\">Parent Directory</a>\n");
		b.append("<a href='http://example.com/elsewhere.txt'>elsewhere</a>\n");
		List<Path> kids = new ArrayList<>();
		try (Stream<Path> s = Files.list(dir)) {
			s.sorted().forEach(kids::add);
		}
		boolean absolute = false;
		for (Path k : kids) {
			String name = URLEncoder.encode(k.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20");
			String href = Files.isDirectory(k) ? name + "/" : name;
			if( !absolute ) {
				// one link as an absolute path, as some servers write them
				href = rawPath + href;
				absolute = true;
			}
			b.append("<a href=\"").append(href).append("\">").append(k.getFileName()).append("</a> ")
					.append(Instant.ofEpochMilli(Files.getLastModifiedTime(k).toMillis())).append('\n');
		}
		return b.append("</body></html>\n").toString();
	}

	/** Reads all of IN (for tests). */
	static String text(InputStream in) throws IOException {
		return new String(in.readAllBytes(), StandardCharsets.UTF_8);
	}
}

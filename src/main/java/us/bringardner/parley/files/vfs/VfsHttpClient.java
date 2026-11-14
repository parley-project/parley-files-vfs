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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import org.apache.commons.vfs2.FileName;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystem;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.UserAuthenticationData;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.apache.commons.vfs2.provider.GenericFileName;
import org.apache.commons.vfs2.provider.http5.Http5FileNameParser;
import org.apache.commons.vfs2.provider.http5.Http5FileProvider;
import org.apache.commons.vfs2.provider.http5.Http5FileSystem;
import org.apache.commons.vfs2.provider.http5.Http5FileSystemConfigBuilder;
import org.apache.commons.vfs2.provider.http5s.Http5sFileNameParser;
import org.apache.commons.vfs2.util.UserAuthenticatorUtils;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;

/**
 * vfshttp's and vfshttps's use of httpclient5, in a class of its own: loaded only once connect has
 * checked that httpclient5 is there, never when the registry loads the factories without it.
 * <p>
 * Each connection's manager gets providers of its own for {@code http} and {@code https} in place of
 * VFS's, which differ in two things (docs/DESIGN.md, 16.1 and 16.2):
 * <ol>
 * <li>The server's certificate chain is checked as Parley checks it ({@link VfsTrustManager}), and
 * its host by httpclient5's hostname verifier: VFS's provider trusts every certificate (httpclient5's
 * TrustAllStrategy), whatever its options.</li>
 * <li>The file system can ask the server whether a path is a folder and read a folder's listing
 * page, with VFS's own client (its login, timeouts, proxy and redirects).</li>
 * </ol>
 */
final class VfsHttpClient {

	/** The most of a listing page read. */
	static final int MAX_LISTING = 16 * 1024 * 1024;

	private static final Pattern HREF = Pattern.compile("<a\\s[^>]*?href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");

	private VfsHttpClient() {
	}

	/** Puts this module's http and https providers in the manager, in place of VFS's. HOST names them in errors. */
	static void install(StandardFileSystemManager m, String host) throws FileSystemException {
		for (String scheme : new String[] { "http", "https" }) {
			m.removeProvider(scheme);
			m.addProvider(scheme, new Provider("https".equals(scheme), host));
		}
	}

	/** VFS's http options, as the factory's settings have them. */
	static void configure(FileSystemOptions options, int timeoutMs) {
		Http5FileSystemConfigBuilder b = Http5FileSystemConfigBuilder.getInstance();
		Duration t = Duration.ofMillis(timeoutMs);
		b.setConnectionTimeout(options, t);
		b.setSoTimeout(options, t);
		b.setFollowRedirect(options, true);
		b.setHostnameVerificationEnabled(options, true);
	}

	/** The provider: VFS's, with Parley's certificate check and a file system that can be asked more. */
	static final class Provider extends Http5FileProvider {

		private static final UserAuthenticationData.Type[] AUTHENTICATOR_TYPES = { UserAuthenticationData.USERNAME,
				UserAuthenticationData.PASSWORD };

		private final String host;

		Provider(boolean https, String host) {
			this.host = host;
			setFileNameParser(https ? Http5sFileNameParser.getInstance() : Http5FileNameParser.getInstance());
		}

		/**
		 * Parley's check of the chain, never VFS's trust-all; the host of each connection (a
		 * redirect's too) is checked by httpclient5's hostname verifier, which {@link #configure}
		 * keeps on.
		 */
		@Override
		protected SSLContext createSSLContext(Http5FileSystemConfigBuilder builder, FileSystemOptions options)
				throws FileSystemException {
			try {
				SSLContext ret = SSLContext.getInstance("TLS");
				ret.init(null, new TrustManager[] { new VfsTrustManager(null) }, null);
				return ret;
			} catch (GeneralSecurityException e) {
				throw new FileSystemException("Can't check " + host + "'s certificate: " + e, e);
			}
		}

		/**
		 * As VFS's, with {@link Site} as the file system, and the client given a copy of the user and
		 * password: VFS gives httpclient5 the authenticator's own password array, which httpclient5
		 * keeps, and then clears it, so every login it then makes sends an empty password
		 * (docs/DESIGN.md, 16.3).
		 */
		@Override
		protected FileSystem doCreateFileSystem(FileName name, FileSystemOptions options) throws FileSystemException {
			GenericFileName rootName = (GenericFileName) name;
			UserAuthenticationData authData = null;
			HttpClient client;
			HttpClientContext context;
			try {
				Http5FileSystemConfigBuilder builder = Http5FileSystemConfigBuilder.getInstance();
				authData = UserAuthenticatorUtils.authenticate(options, AUTHENTICATOR_TYPES);
				context = createHttpClientContext(builder, rootName, options, copy(authData));
				client = createHttpClient(builder, rootName, options);
			} finally {
				UserAuthenticatorUtils.cleanup(authData);
			}
			return new Site(rootName, options, client, context);
		}

		/** A copy of DATA's user and password, which the client keeps for the connection's life. */
		static UserAuthenticationData copy(UserAuthenticationData data) {
			if( data == null ) {
				return null;
			}
			UserAuthenticationData ret = new UserAuthenticationData();
			for (UserAuthenticationData.Type t : AUTHENTICATOR_TYPES) {
				char[] v = data.getData(t);
				if( v != null ) {
					ret.setData(t, v.clone());
				}
			}
			return ret;
		}
	}

	/** VFS's http file system, which can also be asked about a folder. */
	static final class Site extends Http5FileSystem {

		Site(FileName rootName, FileSystemOptions options, HttpClient client, HttpClientContext context) {
			super(rootName, options, client, context);
		}

		/** @return the site's own URI ({@code http://host:80/}) */
		URI base() {
			return getInternalBaseURI();
		}

		/** The URI of PATH ("/a b/c"), each segment percent-encoded, on this site. */
		URI uri(String path) {
			StringBuilder b = new StringBuilder();
			for (String seg : path.split("/", -1)) {
				if( b.length() > 0 || !seg.isEmpty() ) {
					b.append('/');
				}
				b.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
			}
			String rel = b.toString();
			while( rel.startsWith("/") ) {
				rel = rel.substring(1);
			}
			return getInternalBaseURI().resolve(rel);
		}

		/** HEAD of PATH: the status, after redirects. */
		int status(String path) throws IOException {
			CloseableHttpClient c = (CloseableHttpClient) getHttpClient();
			return c.execute(new HttpHead(uri(path)), getHttpClientContext(), r -> r.getCode());
		}

		/**
		 * HEAD of PATH (a folder's path, with its "/"): whether it is a folder's page, after
		 * redirects: an HTML page (200), or a listing the server refuses to show (403).
		 */
		boolean folderPage(String path) throws IOException {
			CloseableHttpClient c = (CloseableHttpClient) getHttpClient();
			return c.execute(new HttpHead(uri(path)), getHttpClientContext(), r -> {
				if( r.getCode() == 403 ) {
					return true;
				}
				if( r.getCode() != 200 ) {
					return false;
				}
				org.apache.hc.core5.http.Header type = r.getFirstHeader("Content-Type");
				return type != null && type.getValue().toLowerCase(Locale.ROOT).contains("html");
			});
		}

		/** A page: its status, type and text (at most {@link #MAX_LISTING} bytes). */
		static final class Page {
			final int status;
			final String type;
			final String text;

			Page(int status, String type, String text) {
				this.status = status;
				this.type = type;
				this.text = text;
			}
		}

		Page get(String path) throws IOException {
			CloseableHttpClient c = (CloseableHttpClient) getHttpClient();
			return c.execute(new HttpGet(uri(path)), getHttpClientContext(), r -> {
				HttpEntity e = r.getEntity();
				if( e == null || r.getCode() != 200 ) {
					return new Page(r.getCode(), null, "");
				}
				ContentType ct = ContentType.parseLenient(e.getContentType());
				Charset cs = ct == null || ct.getCharset() == null ? StandardCharsets.UTF_8 : ct.getCharset();
				try (InputStream in = e.getContent()) {
					byte[] data = in.readNBytes(MAX_LISTING);
					return new Page(r.getCode(), ct == null ? null : ct.getMimeType(), new String(data, cs));
				}
			});
		}
	}

	/**
	 * Asks the site for its root, so a refused login or an error fails the connect with a message
	 * that says so (VFS's says only that the type can't be determined).
	 */
	static void checkRoot(FileObject root, String url) throws IOException {
		int code = site(root).status("/");
		if( code == 401 ) {
			throw new IOException(url + " refused the login (401 Unauthorized)");
		}
		if( code >= 500 ) {
			throw new IOException(url + " answered " + code);
		}
	}

	/** The path of FO on its site ("/" or "/a/b"). */
	static String pathOf(FileObject fo) throws FileSystemException {
		return fo.getName().getPathDecoded();
	}

	private static Site site(FileObject fo) throws IOException {
		FileSystem fs = fo.getFileSystem();
		if( !(fs instanceof Site) ) {
			throw new IOException("Not a vfshttp file system: " + fs.getClass().getName());
		}
		return (Site) fs;
	}

	/**
	 * Whether FO is a folder: "/" is; any other path is when the server answers it with a "/" after
	 * it (as a folder's listing is served) with an HTML page, or refuses to show that listing (403).
	 * Servers answer a file's path with a "/" after it with 404; one that serves the file instead
	 * isn't taken for a folder unless the file is HTML.
	 */
	static boolean isFolder(FileObject fo) throws IOException {
		String p = pathOf(fo);
		if( "/".equals(p) ) {
			return true;
		}
		return site(fo).folderPage(p + "/");
	}

	/**
	 * A folder's children from its listing page: each link on it to a file or folder directly in the
	 * folder (a name, "name/", or the same as an absolute path or URL on this site), in order, once
	 * each; parent and sort links and other sites are left out. Null for a page that isn't HTML or a
	 * folder whose page can't be read (none: the caller lists nothing). The value says whether the
	 * link named a folder (it ends in "/").
	 */
	static Map<String, Boolean> list(FileObject fo) throws IOException {
		Site s = site(fo);
		String p = pathOf(fo);
		String folder = p.endsWith("/") ? p : p + "/";
		Site.Page page = s.get(folder);
		if( page.status != 200 || page.type == null || !page.type.toLowerCase(Locale.ROOT).contains("html") ) {
			return null;
		}
		return names(page.text, folder, s.base());
	}

	/** The names PAGE links to in FOLDER ("/a/"), on the site BASE ({@code http://host:80/}). */
	static Map<String, Boolean> names(String page, String folder, URI base) {
		Map<String, Boolean> ret = new LinkedHashMap<>();
		Matcher m = HREF.matcher(page);
		while( m.find() ) {
			String href = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
			String rel = relative(unescape(href.trim()), folder, base);
			if( rel == null ) {
				continue;
			}
			boolean isFolder = rel.endsWith("/");
			String name = isFolder ? rel.substring(0, rel.length() - 1) : rel;
			if( name.isEmpty() || name.indexOf('/') >= 0 || name.equals(".") || name.equals("..") ) {
				continue;
			}
			name = VfsFileSourceFactory.decode(name);
			if( !ret.containsKey(name) ) {
				ret.put(name, isFolder);
			}
		}
		return ret;
	}

	/** HREF relative to FOLDER, or null if it points elsewhere (a query, another site, above or beside the folder). */
	private static String relative(String href, String folder, URI base) {
		int hash = href.indexOf('#');
		if( hash >= 0 ) {
			href = href.substring(0, hash);
		}
		if( href.isEmpty() || href.indexOf('?') >= 0 ) {
			return null;
		}
		if( SCHEME.matcher(href).matches() || href.startsWith("//") ) {
			try {
				URI u = href.startsWith("//") ? new URI(base.getScheme() + ":" + href) : new URI(href);
				if( u.getHost() == null || !u.getHost().equalsIgnoreCase(base.getHost()) || port(u) != port(base)
						|| !u.getScheme().equalsIgnoreCase(base.getScheme()) ) {
					return null;
				}
				href = u.getRawPath() == null ? "" : u.getRawPath();
			} catch (URISyntaxException e) {
				return null;
			}
		}
		if( href.startsWith("/") ) {
			String encodedFolder = encodedPath(folder);
			if( href.startsWith(encodedFolder) ) {
				return href.substring(encodedFolder.length());
			}
			if( href.startsWith(folder) ) {
				return href.substring(folder.length());
			}
			return null;
		}
		while( href.startsWith("./") ) {
			href = href.substring(2);
		}
		return href;
	}

	private static int port(URI u) {
		if( u.getPort() > 0 ) {
			return u.getPort();
		}
		return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
	}

	private static String encodedPath(String path) {
		StringBuilder b = new StringBuilder();
		for (String seg : path.split("/", -1)) {
			b.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20")).append('/');
		}
		return b.substring(0, b.length() - 1);
	}

	/** The few HTML entities an href holds. */
	private static String unescape(String s) {
		return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
	}

	/** The children of FO as files: the names on its listing page. */
	static List<FileObject> children(FileObject fo, Map<String, Boolean> names) throws IOException {
		List<FileObject> ret = new ArrayList<>(names.size());
		for (String name : names.keySet()) {
			ret.add(fo.resolveFile(org.apache.commons.vfs2.provider.UriParser.encode(name)));
		}
		return ret;
	}
}

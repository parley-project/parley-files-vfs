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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.vfs2.Capability;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;

import us.bringardner.parley.core.util.Durations;
import us.bringardner.parley.files.ConnectionSetting;

/**
 * A web site through VFS's http provider (type id {@value #FACTORY_ID}), read only, which runs on
 * Apache HttpClient 5: add {@code org.apache.httpcomponents.client5:httpclient5} to use it. For
 * HTTPS use {@code vfshttps}.
 * <p>
 * Settings: host, port (80; 443 for vfshttps), user, password (sent when the server asks) and
 * timeout (30s). Paths are the site's URL paths.
 * <p>
 * Where VFS needs help (docs/DESIGN.md, 16): VFS's http says every URL is a file and can't list, so
 * a path is a folder when the server serves it with a "/" after it, and a folder's files are the
 * links on its listing page (Apache's, nginx's and the like); a folder whose page can't be read or
 * isn't HTML lists nothing. VFS trusts every HTTPS certificate; here the certificate is checked as
 * Parley checks it ({@link VfsTrustManager}), also when a redirect leads to HTTPS.
 */
public class VfsHttpFileSourceFactory extends VfsFileSourceFactory {

	private static final long serialVersionUID = 1L;

	public static final String FACTORY_ID = TYPE_ID_PREFIX + "http";

	public static final String PROP_HOST = "host";
	public static final String PROP_PORT = "port";
	public static final String PROP_USER = "user";
	public static final String PROP_PASSWORD = "password";
	/** How long to wait to connect and for data. */
	public static final String PROP_TIMEOUT = "timeout";
	public static final int DEFAULT_PORT = 80;
	public static final int DEFAULT_TLS_PORT = 443;
	public static final int DEFAULT_TIMEOUT = 30_000;

	private volatile String host;
	private volatile int port = -1;
	private volatile String user;
	private volatile String password;
	private volatile int timeout = DEFAULT_TIMEOUT;

	/** Whether each path is a folder, as found (by a listing, or by asking): forgotten on refresh. */
	private final transient Map<String, Boolean> folders = new ConcurrentHashMap<>();

	@Override
	public String getTypeId() {
		return FACTORY_ID;
	}

	/** @return true for HTTPS (vfshttps) */
	protected boolean tls() {
		return false;
	}

	@Override
	public String vfsScheme() {
		return tls() ? "https" : "http";
	}

	/** @return 80, or 443 for vfshttps */
	public int defaultPort() {
		return tls() ? DEFAULT_TLS_PORT : DEFAULT_PORT;
	}

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host == null || host.trim().isEmpty() ? null : host.trim();
	}

	public int getPort() {
		return port <= 0 ? defaultPort() : port;
	}

	/** @param port the port; 0 or less means the type's default */
	public void setPort(int port) {
		this.port = port <= 0 ? -1 : port;
	}

	public String getUser() {
		return user;
	}

	public void setUser(String user) {
		this.user = user == null || user.isEmpty() ? null : user;
	}

	/** The password as given: a secret or a reference to one. */
	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password == null || password.isEmpty() ? null : password;
	}

	/** @return the timeout in ms */
	public int getTimeout() {
		return timeout;
	}

	public void setTimeout(int timeout) {
		this.timeout = Math.max(0, timeout);
	}

	@Override
	protected String userName() {
		return user;
	}

	@Override
	protected String passwordValue() {
		return password;
	}

	@Override
	protected String libraryClass() {
		return "org.apache.hc.client5.http.classic.HttpClient";
	}

	@Override
	protected String libraryName() {
		return "httpclient5 (org.apache.httpcomponents.client5:httpclient5)";
	}

	/**
	 * {@code http://host:80/}, without the user: httpclient5 refuses a request URI with one
	 * ("deprecated userinfo component"), and VFS's requests are made from the root URI, so the user
	 * and password go only through the authenticator (docs/DESIGN.md, 16.3).
	 */
	@Override
	protected String rootUri() throws IOException {
		if( host == null ) {
			throw new IOException("No host for " + getTypeId());
		}
		return vfsScheme() + "://" + hostPort() + "/";
	}

	private String hostPort() {
		String h = host == null ? "" : host;
		h = h.indexOf(':') >= 0 && !h.startsWith("[") ? "[" + h + "]" : h;
		return h.toLowerCase(Locale.ROOT) + ":" + getPort();
	}

	/** The site and the user: another user is another file system. */
	@Override
	protected String fileSystemKey() throws IOException {
		return rootUri() + "#" + (user == null ? "" : user);
	}

	/** {@code vfshttp://tony@host:80/}: with the user, so it opens the same file system again. */
	@Override
	public String getURL() {
		return getTypeId() + "://" + (user == null ? "" : encode(user) + "@") + hostPort() + "/";
	}

	/** VFS's manager, with this module's http and https providers in place of VFS's. */
	@Override
	protected StandardFileSystemManager openManager() throws IOException {
		StandardFileSystemManager m = super.openManager();
		try {
			VfsHttpClient.install(m, host);
		} catch (IOException | RuntimeException | Error e) {
			m.close();
			throw e;
		}
		return m;
	}

	/** The root, after asking the server for it: a refused login says so. */
	@Override
	protected FileObject resolveRoot(StandardFileSystemManager m, FileSystemOptions options) throws IOException {
		FileObject ret = super.resolveRoot(m, options);
		VfsHttpClient.checkRoot(ret, getURL());
		return ret;
	}

	@Override
	protected void configure(FileSystemOptions options) {
		VfsHttpClient.configure(options, timeout);
	}

	@Override
	protected synchronized void disConnectImpl() throws IOException {
		folders.clear();
		super.disConnectImpl();
	}

	/** Folders are listed from their pages. */
	@Override
	protected boolean hasCapability(Capability c) throws IOException {
		return c == Capability.LIST_CHILDREN || super.hasCapability(c);
	}

	@Override
	protected boolean isFolder(FileObject fo) throws IOException {
		String p = VfsHttpClient.pathOf(fo);
		Boolean ret = folders.get(p);
		if( ret == null ) {
			ret = VfsHttpClient.isFolder(fo);
			folders.put(p, ret);
		}
		return ret;
	}

	@Override
	protected boolean isFile(FileObject fo) throws IOException {
		return !isFolder(fo) && fo.isFile();
	}

	/** A folder exists whether or not the server answers its path without the "/". */
	@Override
	protected boolean exists(FileObject fo) throws IOException {
		return isFolder(fo) || fo.exists();
	}

	/** The links on the folder's listing page; none if it has no page or the page isn't HTML. */
	@Override
	protected FileObject[] children(FileObject fo) throws IOException {
		Map<String, Boolean> names = VfsHttpClient.list(fo);
		if( names == null ) {
			return new FileObject[0];
		}
		String p = VfsHttpClient.pathOf(fo);
		String prefix = p.endsWith("/") ? p : p + "/";
		for (Map.Entry<String, Boolean> e : names.entrySet()) {
			if( e.getValue() ) {
				// a link ending in "/" is a folder; one without may still be (asked when wanted)
				folders.put(prefix + e.getKey(), true);
			}
		}
		return VfsHttpClient.children(fo, names).toArray(new FileObject[0]);
	}

	@Override
	protected void refreshed(FileObject fo) {
		folders.remove(fo.getName().getPath());
		try {
			folders.remove(VfsHttpClient.pathOf(fo));
		} catch (IOException e) {
			// a name that can't be decoded was never kept under its decoded path
		}
	}

	// ------------------------------------------------------------------ settings

	@Override
	public List<ConnectionSetting> getConnectionSettings() {
		return List.of(ConnectionSetting.text(PROP_HOST, "Host").asRequired(),
				ConnectionSetting.integer(PROP_PORT, "Port", 1L, 65535L).withDefault("" + defaultPort()).asRequired(),
				ConnectionSetting.text(PROP_USER, "User"),
				ConnectionSetting.secret(PROP_PASSWORD, "Password"),
				ConnectionSetting.duration(PROP_TIMEOUT, "Timeout").asAdvanced().withDefault(Durations.format(DEFAULT_TIMEOUT)));
	}

	@Override
	public Properties getConnectProperties() {
		Properties ret = new Properties();
		ret.setProperty(PROP_HOST, host == null ? "" : host);
		ret.setProperty(PROP_PORT, "" + getPort());
		ret.setProperty(PROP_USER, user == null ? "" : user);
		ret.setProperty(PROP_PASSWORD, password == null ? "" : password);
		ret.setProperty(PROP_TIMEOUT, Durations.format(timeout));
		return ret;
	}

	/** Only the settings present in P change; an empty value clears one. */
	@Override
	public void setConnectionProperties(Properties p) {
		if( p.containsKey(PROP_HOST) ) {
			setHost(p.getProperty(PROP_HOST));
		}
		String portValue = p.getProperty(PROP_PORT);
		if( portValue != null ) {
			setPort(portValue.trim().isEmpty() ? -1 : Integer.parseInt(portValue.trim()));
		}
		if( p.containsKey(PROP_USER) ) {
			setUser(p.getProperty(PROP_USER));
		}
		if( p.containsKey(PROP_PASSWORD) ) {
			setPassword(p.getProperty(PROP_PASSWORD));
		}
		String t = p.getProperty(PROP_TIMEOUT);
		if( t != null && !t.trim().isEmpty() ) {
			setTimeout(Durations.toIntMillis(Durations.parse(t)));
		}
	}
}

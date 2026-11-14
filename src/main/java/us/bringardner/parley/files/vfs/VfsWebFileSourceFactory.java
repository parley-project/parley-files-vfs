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
import java.util.Properties;

import us.bringardner.parley.core.util.Durations;
import us.bringardner.parley.files.ConnectionSetting;

/**
 * What vfshttp and vfswebdav share: a web server, by host and port, with a user and password sent
 * when the server asks for a login, and a timeout (30s). Paths are the server's URL paths. The VFS
 * root URI has no user (httpclient5 refuses a request URI with one, and a user in it adds nothing
 * for httpclient 4): the user and password go only through the authenticator, and the factory's
 * own URL and {@link #isSameFileSystem} keep the user (docs/DESIGN.md, 16.3).
 */
public abstract class VfsWebFileSourceFactory extends VfsFileSourceFactory {

	private static final long serialVersionUID = 1L;

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

	/** @return true for the TLS type (vfshttps, vfswebdavs) */
	protected abstract boolean tls();

	/** @return 80, or 443 for TLS */
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

	/** {@code http://host:80/} (the VFS scheme's), without the user. */
	@Override
	protected String rootUri() throws IOException {
		if( host == null ) {
			throw new IOException("No host for " + getTypeId());
		}
		return vfsScheme() + "://" + hostPort() + "/";
	}

	/** host:port, the host in brackets if it is an IPv6 address. */
	String hostPort() {
		String h = host == null ? "" : host;
		h = h.indexOf(':') >= 0 && !h.startsWith("[") ? "[" + h + "]" : h;
		return h.toLowerCase(Locale.ROOT) + ":" + getPort();
	}

	/** The server and the user: another user is another file system. */
	@Override
	protected String fileSystemKey() throws IOException {
		return rootUri() + "#" + (user == null ? "" : user);
	}

	/** {@code vfshttp://tony@host:80/}: the type's own URL, with the user, so it opens the same file system again. */
	@Override
	public String getURL() {
		return getTypeId() + "://" + (user == null ? "" : encode(user) + "@") + hostPort() + "/";
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

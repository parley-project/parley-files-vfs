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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.provider.sftp.BytesIdentityInfo;
import org.apache.commons.vfs2.provider.sftp.IdentityProvider;
import org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;

import us.bringardner.parley.core.util.Durations;
import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.credentials.CredentialRef;

/**
 * SFTP through VFS's sftp provider (type id {@value #FACTORY_ID}), which runs on JSch: add
 * {@code com.github.mwiede:jsch}, the maintained fork, to use it.
 * <p>
 * The settings are parley-files-sftp's, with the same names and defaults, so a saved sftp
 * connection works as a vfssftp one: host, port (22), user, auth, password, identityFile,
 * privateKey, knownHosts, strictHostKeyChecking (yes) and connectTimeout. Paths are the server's
 * ({@code userDirIsRoot} is set false: VFS's default takes "/" to be the user's home directory).
 * <p>
 * Host keys are checked as parley-files-sftp checks them: against knownHosts (a local file,
 * ~/.ssh/known_hosts when it isn't set) unless strictHostKeyChecking is "no". A key (pasted, or
 * read from identityFile) is given to VFS as bytes. Logging in through an ssh-agent isn't
 * supported yet.
 */
public class VfsSftpFileSourceFactory extends VfsFileSourceFactory {

	private static final long serialVersionUID = 1L;

	public static final String FACTORY_ID = TYPE_ID_PREFIX + "sftp";

	public static final String PROP_HOST = "host";
	public static final String PROP_PORT = "port";
	public static final String PROP_USER = "user";
	public static final String PROP_PASSWORD = "password";
	public static final String PROP_PRIVATE_KEY_FILE_NAME = "identityFile";
	public static final String PROP_PRIVATE_KEY = "privateKey";
	/** How to log in: {@link #AUTH_PASSWORD}, {@link #AUTH_KEY_FILE} or {@link #AUTH_KEY}. */
	public static final String PROP_AUTH = "auth";
	public static final String AUTH_PASSWORD = "Password";
	public static final String AUTH_KEY_FILE = "Private Key File";
	public static final String AUTH_KEY = "Private Key";
	/** A local known_hosts file; empty means ~/.ssh/known_hosts. */
	public static final String PROP_KNOWN_HOSTS = "knownHosts";
	/** "yes" (the default) connects only to servers whose key is known; "no" accepts any. */
	public static final String PROP_STRICT_HOST_KEY_CHECKING = "strictHostKeyChecking";
	public static final String DEFAULT_STRICT_HOST_KEY_CHECKING = "yes";
	public static final String PROP_CONNECT_TIMEOUT = "connectTimeout";
	public static final int DEFAULT_PORT = 22;
	public static final int DEFAULT_CONNECT_TIMEOUT = 30_000;

	private volatile String host;
	private volatile int port = DEFAULT_PORT;
	private volatile String user;
	private volatile String password;
	private volatile String privateKeyFileName;
	private volatile byte[] privateKey;
	private volatile String auth;
	private volatile String knownHosts;
	private volatile String strictHostKeyChecking = DEFAULT_STRICT_HOST_KEY_CHECKING;
	private volatile int connectTimeout = DEFAULT_CONNECT_TIMEOUT;

	@Override
	public String getTypeId() {
		return FACTORY_ID;
	}

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host == null || host.trim().isEmpty() ? null : host.trim();
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port <= 0 ? DEFAULT_PORT : port;
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

	public String getStrictHostKeyChecking() {
		return strictHostKeyChecking;
	}

	/** "yes" or "no"; null or empty means the default ("yes"). */
	public void setStrictHostKeyChecking(String value) {
		if( value == null || value.trim().isEmpty() ) {
			value = DEFAULT_STRICT_HOST_KEY_CHECKING;
		}
		value = value.trim().toLowerCase();
		if( !value.equals("yes") && !value.equals("no") ) {
			throw new IllegalArgumentException("strictHostKeyChecking must be yes or no, not " + value);
		}
		this.strictHostKeyChecking = value;
	}

	/** How this factory logs in: as set, else a pasted key, else a key file, else a password. */
	public String getAuth() {
		if( auth != null ) {
			return auth;
		}
		return privateKey != null ? AUTH_KEY : privateKeyFileName != null ? AUTH_KEY_FILE : AUTH_PASSWORD;
	}

	@Override
	protected String userName() {
		return user;
	}

	/** The password only when logging in with one. */
	@Override
	protected String passwordValue() {
		return AUTH_PASSWORD.equals(getAuth()) ? password : null;
	}

	@Override
	protected String rootUri() throws IOException {
		if( host == null ) {
			throw new IOException("No host for " + FACTORY_ID);
		}
		String h = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
		return "sftp://" + (user == null ? "" : encode(user) + "@") + h.toLowerCase(java.util.Locale.ROOT) + ":" + port + "/";
	}

	@Override
	protected void configure(FileSystemOptions options) throws IOException {
		SftpFileSystemConfigBuilder b = SftpFileSystemConfigBuilder.getInstance();
		// paths are the server's, as in parley-files-sftp (VFS's default: "/" is the home directory)
		b.setUserDirIsRoot(options, false);
		b.setConnectTimeout(options, Duration.ofMillis(Math.max(0, connectTimeout)));
		b.setStrictHostKeyChecking(options, strictHostKeyChecking);
		if( knownHosts != null ) {
			// VFS takes a java.io.File; without one it reads ~/.ssh/known_hosts, as parley-files-sftp does
			b.setKnownHosts(options, new File(knownHosts));
		}
		String a = getAuth();
		if( AUTH_KEY.equals(a) || AUTH_KEY_FILE.equals(a) ) {
			b.setIdentityProvider(options, new BytesIdentityInfo(resolvePrivateKey(), null));
			b.setPreferredAuthentications(options, "publickey");
		} else {
			// no identities: VFS would otherwise try ~/.ssh/id_rsa
			b.setIdentityProvider(options, new IdentityProvider[0]);
			b.setPreferredAuthentications(options, "password,keyboard-interactive");
		}
	}

	/** The private key's bytes: the pasted key (a reference resolved), else identityFile's. */
	private byte[] resolvePrivateKey() throws IOException {
		if( privateKey != null ) {
			String text = new String(privateKey, StandardCharsets.UTF_8);
			if( !CredentialRef.isReference(text) ) {
				return privateKey.clone();
			}
			char[] key = resolveSecret(PROP_PRIVATE_KEY, text);
			try {
				java.nio.ByteBuffer bb = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(key));
				byte[] ret = Arrays.copyOfRange(bb.array(), bb.position(), bb.limit());
				Arrays.fill(bb.array(), (byte) 0);
				return ret;
			} finally {
				Arrays.fill(key, '\0');
			}
		}
		if( privateKeyFileName == null ) {
			throw new IOException("No private key for " + getURL() + ": set " + PROP_PRIVATE_KEY + " or " + PROP_PRIVATE_KEY_FILE_NAME);
		}
		// a local file by its nature (the setting is a local file), read as a FileProxy
		try (InputStream in = FileSource.of(new File(privateKeyFileName)).getInputStream()) {
			return in.readAllBytes();
		}
	}

	// ------------------------------------------------------------------ permission bits

	/** SftpFileObject.getPermissions(boolean), protected: found once, null if it isn't there. */
	private static volatile Method getPermissions;
	private static volatile boolean hookFailed;

	/**
	 * The file's mode from VFS's sftp provider: its protected {@code getPermissions(false)} (a
	 * fresh stat; false: without working out the user's ids), read by reflection. Null, logged once,
	 * if this VFS hasn't got it.
	 */
	@Override
	protected Integer posixMode(FileObject fo) {
		if( hookFailed ) {
			return null;
		}
		try {
			Method m = getPermissions;
			if( m == null ) {
				m = find(fo.getClass());
				m.setAccessible(true);
				getPermissions = m;
			}
			Object perms = m.invoke(fo, Boolean.FALSE);
			Object mode = perms.getClass().getMethod("getPermissions").invoke(perms);
			return ((Integer) mode) & 0777;
		} catch (ReflectiveOperationException | RuntimeException e) {
			hookFailed = true;
			logError("Can't read permission bits from VFS's sftp provider (" + fo.getClass().getName()
					+ "); owner, group and other permissions read as false: " + e);
			return null;
		}
	}

	private static Method find(Class<?> cls) throws NoSuchMethodException {
		for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredMethod("getPermissions", boolean.class);
			} catch (NoSuchMethodException e) {
				// try the superclass
			}
		}
		throw new NoSuchMethodException(cls.getName() + ".getPermissions(boolean)");
	}

	// ------------------------------------------------------------------ settings

	@Override
	public List<ConnectionSetting> getConnectionSettings() {
		return List.of(
				ConnectionSetting.text(PROP_HOST, "Host").asRequired(),
				ConnectionSetting.integer(PROP_PORT, "Port", 1L, 65535L).withDefault("" + DEFAULT_PORT).asRequired(),
				ConnectionSetting.text(PROP_USER, "User").asRequired(),
				ConnectionSetting.choice(PROP_AUTH, "Log in with", AUTH_PASSWORD, AUTH_KEY_FILE, AUTH_KEY),
				ConnectionSetting.secret(PROP_PASSWORD, "Password").visibleWhen(PROP_AUTH, AUTH_PASSWORD),
				ConnectionSetting.localFile(PROP_PRIVATE_KEY_FILE_NAME, "Private key file").asRequired()
					.withDescription("An OpenSSH or PEM private key file").visibleWhen(PROP_AUTH, AUTH_KEY_FILE),
				ConnectionSetting.multilineSecret(PROP_PRIVATE_KEY, "Private key").asRequired()
					.withDescription("Paste a private key here, in PEM or OpenSSH format").visibleWhen(PROP_AUTH, AUTH_KEY),
				ConnectionSetting.localFile(PROP_KNOWN_HOSTS, "Known hosts file").asAdvanced()
					.withDescription("Server keys to trust; empty for ~/.ssh/known_hosts"),
				ConnectionSetting.choice(PROP_STRICT_HOST_KEY_CHECKING, "Check the server's key", "yes", "no").asAdvanced()
					.withDefault(DEFAULT_STRICT_HOST_KEY_CHECKING)
					.withDescription("no accepts any server, which lets one be impersonated"),
				ConnectionSetting.duration(PROP_CONNECT_TIMEOUT, "Connect timeout").asAdvanced()
					.withDefault(Durations.format(DEFAULT_CONNECT_TIMEOUT)));
	}

	@Override
	public Properties getConnectProperties() {
		Properties ret = new Properties();
		ret.setProperty(PROP_HOST, host == null ? "" : host);
		ret.setProperty(PROP_PORT, "" + port);
		ret.setProperty(PROP_USER, user == null ? "" : user);
		ret.setProperty(PROP_AUTH, getAuth());
		ret.setProperty(PROP_PASSWORD, password == null ? "" : password);
		ret.setProperty(PROP_PRIVATE_KEY_FILE_NAME, privateKeyFileName == null ? "" : privateKeyFileName);
		ret.setProperty(PROP_PRIVATE_KEY, privateKey == null ? "" : new String(privateKey, StandardCharsets.UTF_8));
		ret.setProperty(PROP_KNOWN_HOSTS, knownHosts == null ? "" : knownHosts);
		ret.setProperty(PROP_STRICT_HOST_KEY_CHECKING, strictHostKeyChecking);
		ret.setProperty(PROP_CONNECT_TIMEOUT, Durations.format(Math.max(0, connectTimeout)));
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
			setPort(portValue.trim().isEmpty() ? DEFAULT_PORT : Integer.parseInt(portValue.trim()));
		}
		if( p.containsKey(PROP_USER) ) {
			setUser(p.getProperty(PROP_USER));
		}
		if( p.containsKey(PROP_PASSWORD) ) {
			setPassword(p.getProperty(PROP_PASSWORD));
		}
		if( p.containsKey(PROP_PRIVATE_KEY_FILE_NAME) ) {
			String file = p.getProperty(PROP_PRIVATE_KEY_FILE_NAME);
			privateKeyFileName = file == null || file.isEmpty() ? null : file;
		}
		if( p.containsKey(PROP_PRIVATE_KEY) ) {
			String key = p.getProperty(PROP_PRIVATE_KEY);
			privateKey = key == null || key.isEmpty() ? null : key.getBytes(StandardCharsets.UTF_8);
		}
		if( p.containsKey(PROP_AUTH) ) {
			String a = p.getProperty(PROP_AUTH, "").trim();
			auth = null;
			for (String known : new String[] { AUTH_PASSWORD, AUTH_KEY_FILE, AUTH_KEY }) {
				if( known.equalsIgnoreCase(a) ) {
					auth = known;
				}
			}
		}
		if( p.containsKey(PROP_KNOWN_HOSTS) ) {
			String kh = p.getProperty(PROP_KNOWN_HOSTS);
			knownHosts = kh == null || kh.isEmpty() ? null : kh;
		}
		if( p.containsKey(PROP_STRICT_HOST_KEY_CHECKING) ) {
			setStrictHostKeyChecking(p.getProperty(PROP_STRICT_HOST_KEY_CHECKING));
		}
		String timeout = p.getProperty(PROP_CONNECT_TIMEOUT);
		if( timeout != null && !timeout.trim().isEmpty() ) {
			connectTimeout = Durations.toIntMillis(Durations.parse(timeout));
		}
	}
}

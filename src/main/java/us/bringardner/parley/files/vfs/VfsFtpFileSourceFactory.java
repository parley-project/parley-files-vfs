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
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.provider.ftp.FtpFileSystemConfigBuilder;
import org.apache.commons.vfs2.provider.ftps.FtpsDataChannelProtectionLevel;
import org.apache.commons.vfs2.provider.ftps.FtpsFileSystemConfigBuilder;
import org.apache.commons.vfs2.provider.ftps.FtpsMode;

import us.bringardner.parley.core.util.Durations;
import us.bringardner.parley.files.ConnectionSetting;

/**
 * FTP through VFS's ftp provider (type id {@value #FACTORY_ID}), which runs on Apache Commons Net:
 * add {@code commons-net:commons-net} to use it.
 * <p>
 * The settings are parley-files-ftp's, with the same names and defaults, so a saved ftp connection
 * works as a vfsftp one: host, port (21), user, password, secure (false) and timeout (4s).
 * {@code secure} true is explicit TLS (AUTH TLS before logging in, through VFS's ftps provider in
 * its explicit mode), as in parley-files-ftp; for implicit TLS use {@code vfsftps}. No user logs in
 * as anonymous, as VFS does.
 * <p>
 * VFS's defaults that would make it behave unlike parley-files-ftp are changed (docs/DESIGN.md,
 * section 10): passive mode (VFS uses active), paths from the server's root (VFS's "/" is the login
 * directory), modification times from MDTM where the server has it (to the second, not the
 * listing's minute), UTF-8 file names, and with TLS the data connections protected too (PROT P;
 * VFS sends no PROT, so they were in the clear) and the server's certificate checked as Parley's
 * client checks it ({@link VfsTrustManager}).
 * <p>
 * Not offered from parley-files-ftp's settings: account (VFS can't send ACCT) and storeAtOffset
 * (VFS's ftp can't write at a position).
 */
public class VfsFtpFileSourceFactory extends VfsFileSourceFactory {

	private static final long serialVersionUID = 1L;

	public static final String FACTORY_ID = TYPE_ID_PREFIX + "ftp";

	public static final String PROP_HOST = "host";
	public static final String PROP_PORT = "port";
	public static final String PROP_USER = "user";
	public static final String PROP_PASSWORD = "password";
	/** vfsftp only: "true" for explicit TLS (AUTH TLS), as parley-files-ftp's secure. */
	public static final String PROP_SECURE = "secure";
	/** How long to wait to connect, for a reply and for data (a duration, as parley-files-ftp's). */
	public static final String PROP_TIMEOUT = "timeout";
	/** The ftp port (RFC 959), plain or with explicit TLS. */
	public static final int DEFAULT_PORT = 21;
	/** The ftps port: implicit TLS. */
	public static final int DEFAULT_IMPLICIT_TLS_PORT = 990;
	public static final int DEFAULT_TIMEOUT = 4000;

	private volatile String host;
	private volatile int port = -1;
	private volatile String user;
	private volatile String password;
	private volatile boolean secure;
	private volatile int timeout = DEFAULT_TIMEOUT;

	@Override
	public String getTypeId() {
		return FACTORY_ID;
	}

	/**
	 * Whether this type is TLS from the first byte (vfsftps). False here: vfsftp is plain FTP, or
	 * explicit TLS when {@value #PROP_SECURE} is true.
	 * @return true for implicit TLS
	 */
	protected boolean implicitTls() {
		return false;
	}

	/** @return the port when none is given: 21, or 990 for vfsftps */
	public int defaultPort() {
		return implicitTls() ? DEFAULT_IMPLICIT_TLS_PORT : DEFAULT_PORT;
	}

	/** @return true if the connection uses TLS (always for vfsftps) */
	public boolean isSecure() {
		return implicitTls() || secure;
	}

	/** @param secure true for explicit TLS (vfsftp; vfsftps is always TLS) */
	public void setSecure(boolean secure) {
		this.secure = secure;
	}

	/** The VFS scheme: ftps whenever TLS is used (VFS's ftps provider does both modes). */
	@Override
	public String vfsScheme() {
		return isSecure() ? "ftps" : "ftp";
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

	/** vfsftps too: VFS's ftp and ftps run on commons-net. */
	@Override
	protected String libraryClass() {
		return "org.apache.commons.net.ftp.FTPClient";
	}

	@Override
	protected String libraryName() {
		return "commons-net (commons-net:commons-net)";
	}

	@Override
	protected String rootUri() throws IOException {
		if( host == null ) {
			throw new IOException("No host for " + getTypeId());
		}
		return vfsScheme() + "://" + authority() + "/";
	}

	/** user@host:port, the host in brackets if it is an IPv6 address. */
	private String authority() {
		String h = host == null ? "" : host;
		h = h.indexOf(':') >= 0 && !h.startsWith("[") ? "[" + h + "]" : h;
		return (user == null ? "" : encode(user) + "@") + h.toLowerCase(Locale.ROOT) + ":" + getPort();
	}

	/**
	 * This type's own URL, not VFS's: {@code vfsftp://tony@host:21/}, with {@code ?secure=true} for
	 * explicit TLS, so it opens a vfsftp factory again ({@code vfs} + VFS's root URI would be
	 * {@code vfsftps:}, which is implicit TLS).
	 */
	@Override
	public String getURL() {
		return getTypeId() + "://" + authority() + "/" + (secure && !implicitTls() ? "?" + PROP_SECURE + "=true" : "");
	}

	@Override
	protected void configure(FileSystemOptions options) throws IOException {
		FtpFileSystemConfigBuilder b = isSecure() ? FtpsFileSystemConfigBuilder.getInstance() : FtpFileSystemConfigBuilder.getInstance();
		// VFS's ftp is in active mode unless told; parley-files-ftp and most networks use passive
		b.setPassiveMode(options, true);
		// paths are the server's, as in parley-files-ftp (VFS's default: "/" is the login directory)
		b.setUserDirIsRoot(options, false);
		// the time to the second where the server has MDTM, not the listing's minute
		b.setMdtmLastModifiedTime(options, true);
		b.setControlEncoding(options, StandardCharsets.UTF_8.name());
		b.setAutodetectUtf8(options, Boolean.TRUE);
		Duration t = Duration.ofMillis(timeout);
		b.setConnectTimeout(options, t);
		b.setSoTimeout(options, t);
		b.setDataTimeout(options, t);
		// a stream closed before the end makes the server answer 426 (or 550): without these VFS
		// takes that as a failed close, so head(), or reading only part of a file, throws
		b.setTransferAbortedOkReplyCodes(options, FtpFileSystemConfigBuilder.getSaneTransferAbortedOkReplyCodes());
		if( isSecure() ) {
			FtpsFileSystemConfigBuilder s = FtpsFileSystemConfigBuilder.getInstance();
			s.setFtpsMode(options, implicitTls() ? FtpsMode.IMPLICIT : FtpsMode.EXPLICIT);
			// VFS sends no PROT unless told, so the files and listings would go in the clear
			s.setDataChannelProtectionLevel(options, FtpsDataChannelProtectionLevel.P);
			try {
				s.setTrustManager(options, new VfsTrustManager(host));
			} catch (GeneralSecurityException e) {
				throw new IOException("Can't check " + host + "'s certificate: " + e, e);
			}
		}
	}

	// ------------------------------------------------------------------ permission bits

	/** FtpFileObject's private ftpFile (commons-net's FTPFile): found once. */
	private static volatile Field ftpFileField;
	private static volatile boolean hookFailed;

	/**
	 * The file's mode from the server's listing, which VFS's ftp provider keeps in its private
	 * {@code ftpFile} (commons-net's {@code FTPFile}), read by reflection. Null when the listing
	 * gave none, and (logged once) if this VFS hasn't got the field.
	 */
	@Override
	protected Integer posixMode(FileObject fo) {
		Object entry = listingEntry(fo);
		return entry == null ? null : FtpBits.mode(entry);
	}

	/**
	 * A directory's time from the server's listing; a file's from VFS (MDTM, to the second). VFS
	 * asks MDTM for a directory too, which servers answer only for files (RFC 3659), and then fails
	 * (commons-net gives it no time, and VFS doesn't check).
	 */
	@Override
	protected long lastModified(FileObject fo) throws IOException {
		if( !fo.isFolder() ) {
			return super.lastModified(fo);
		}
		Object entry = listingEntry(fo);
		long ret = entry == null ? -1 : FtpBits.time(entry);
		return ret < 0 ? 0L : ret;
	}

	/** VFS's FTPFile for FO (its entry in its directory's listing), or null. */
	private Object listingEntry(FileObject fo) {
		if( hookFailed ) {
			return null;
		}
		try {
			// the type makes VFS read the file's entry from its directory's listing
			fo.getType();
		} catch (IOException e) {
			return null;
		}
		try {
			Field f = ftpFileField;
			if( f == null ) {
				f = find(fo.getClass());
				f.setAccessible(true);
				ftpFileField = f;
			}
			return f.get(fo);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			hookFailed = true;
			logError("Can't read the listing VFS's ftp provider keeps (" + fo.getClass().getName()
					+ "); owner, group and other permissions read as false, a directory's time as 0: " + e);
			return null;
		}
	}

	private static Field find(Class<?> cls) throws NoSuchFieldException {
		for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField("ftpFile");
			} catch (NoSuchFieldException e) {
				// try the superclass
			}
		}
		throw new NoSuchFieldException(cls.getName() + ".ftpFile");
	}

	/**
	 * commons-net's FTPFile, in a class of its own: it is loaded only once a file's bits are asked
	 * for (so after a connection, which needs commons-net), never when the registry loads this
	 * factory on a class path without commons-net.
	 */
	private static final class FtpBits {

		static Integer mode(Object v) {
			if( !(v instanceof FTPFile) || !((FTPFile) v).isValid() ) {
				return null;
			}
			FTPFile file = (FTPFile) v;
			int mode = 0;
			for (int who : new int[] { FTPFile.USER_ACCESS, FTPFile.GROUP_ACCESS, FTPFile.WORLD_ACCESS }) {
				for (int what : new int[] { FTPFile.READ_PERMISSION, FTPFile.WRITE_PERMISSION, FTPFile.EXECUTE_PERMISSION }) {
					mode = (mode << 1) | (file.hasPermission(who, what) ? 1 : 0);
				}
			}
			return mode;
		}

		/** The listing's time (ms), or -1 if it gave none. */
		static long time(Object v) {
			if( !(v instanceof FTPFile) || ((FTPFile) v).getTimestamp() == null ) {
				return -1;
			}
			return ((FTPFile) v).getTimestamp().getTimeInMillis();
		}
	}

	// ------------------------------------------------------------------ settings

	@Override
	public List<ConnectionSetting> getConnectionSettings() {
		List<ConnectionSetting> ret = new ArrayList<>(List.of(
				ConnectionSetting.text(PROP_HOST, "Host").asRequired(),
				ConnectionSetting.integer(PROP_PORT, "Port", 1L, 65535L).withDefault("" + defaultPort()).asRequired(),
				ConnectionSetting.text(PROP_USER, "User"),
				ConnectionSetting.secret(PROP_PASSWORD, "Password")));
		if( !implicitTls() ) {
			// vfsftps is always TLS: nothing to choose
			ret.add(ConnectionSetting.bool(PROP_SECURE, "Use TLS (FTPS)")
					.withDescription("Explicit TLS: AUTH TLS on this port before logging in. For implicit TLS (port 990) use the vfsftps type."));
		}
		ret.add(ConnectionSetting.duration(PROP_TIMEOUT, "Timeout").asAdvanced()
				.withDefault(Durations.format(DEFAULT_TIMEOUT)));
		return ret;
	}

	@Override
	public Properties getConnectProperties() {
		Properties ret = new Properties();
		ret.setProperty(PROP_HOST, host == null ? "" : host);
		ret.setProperty(PROP_PORT, "" + getPort());
		ret.setProperty(PROP_USER, user == null ? "" : user);
		ret.setProperty(PROP_PASSWORD, password == null ? "" : password);
		ret.setProperty(PROP_SECURE, "" + isSecure());
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
		String s = p.getProperty(PROP_SECURE);
		if( s != null ) {
			setSecure("true".equalsIgnoreCase(s.trim()));
		}
		String t = p.getProperty(PROP_TIMEOUT);
		if( t != null && !t.trim().isEmpty() ) {
			setTimeout(Durations.toIntMillis(Durations.parse(t)));
		}
	}
}

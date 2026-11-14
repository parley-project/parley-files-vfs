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
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.apache.commons.vfs2.Capability;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.impl.DefaultFileSystemConfigBuilder;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceUser;

/**
 * The code every VFS scheme shares: a factory is one VFS file system (one server account, one
 * archive, one web site), opened through a VFS manager of its own. Each scheme is a small subclass
 * that names its type id and settings, builds the file system's root URI and sets its scheme's
 * options (see docs/DESIGN.md, section 3).
 * <p>
 * The manager is never {@code VFS.getManager()}: that one is shared by the whole JVM, and closing
 * it would close every other user's file systems. {@link #disConnect()} closes this factory's
 * manager, and with it every file system and stream it opened.
 * <p>
 * What a file system can do (write, append, random access, set times) is read from VFS's
 * capabilities when it is used, not coded per scheme.
 */
public abstract class VfsFileSourceFactory extends FileSourceFactory {

	private static final long serialVersionUID = 1L;

	/** The VFS scheme's prefix in every type id: vfs + the VFS scheme (vfssftp, vfszip ...). */
	public static final String TYPE_ID_PREFIX = "vfs";

	/** This factory's own manager; null when disconnected. */
	private transient volatile StandardFileSystemManager manager;
	/** The file system's root; null when disconnected. */
	private transient volatile FileObject root;
	/** The current directory's path on this file system. Never changes anything shared. */
	private volatile String currentDirectory = "/";

	// ------------------------------------------------------------------ what a scheme supplies

	/**
	 * The VFS scheme this factory opens (sftp, zip ...): by default the type id without its
	 * {@value #TYPE_ID_PREFIX} prefix.
	 * @return the scheme
	 */
	public String vfsScheme() {
		String id = getTypeId();
		return id.startsWith(TYPE_ID_PREFIX) ? id.substring(TYPE_ID_PREFIX.length()) : id;
	}

	/**
	 * The VFS URI of the file system's root, built from the settings, without a password
	 * ({@code sftp://tony@host:22/}, {@code zip:file:///tmp/a.zip!/}). It names the file system, so
	 * two factories with the same root URI are the same file system.
	 * @return the root URI
	 * @throws IOException if the settings don't make one (no host, say)
	 */
	protected abstract String rootUri() throws IOException;

	/**
	 * Sets the scheme's own options before the root is resolved (sftp: paths from the server's
	 * root, timeouts, host keys, keys).
	 * @param options the file system's options
	 * @throws IOException if a setting can't be used (a key file that can't be read, say)
	 */
	protected abstract void configure(FileSystemOptions options) throws IOException;

	/** @return the user to log in as, or null if the scheme has none (an archive) */
	protected String userName() {
		return null;
	}

	/** @return the password setting as given (a secret or a reference to one), or null */
	protected String passwordValue() {
		return null;
	}

	/**
	 * The file's nine permission bits (owner, group, other: 0777) where the scheme's provider
	 * keeps them, else null: VFS's API gives only the connected user's access (docs/DESIGN.md,
	 * section 5.3). A hook that reaches VFS internals returns null when it can't, never throws.
	 * @param fo the file
	 * @return the bits, or null if they aren't known
	 */
	protected Integer posixMode(FileObject fo) {
		return null;
	}

	/**
	 * Sets and clears permission bits, for a scheme that can do it better than VFS: null means use
	 * VFS's own setters. vfssftp sends only the mode, as parley-files-sftp does; VFS's sftp sends
	 * the file's whole stat back (docs/DESIGN.md, 10.5).
	 * @param fo the file, which exists
	 * @param set the bits to set (0400, 0444 ...)
	 * @param clear the bits to clear
	 * @return true if the bits are as asked, false if the server refused, null to use VFS's setters
	 * @throws IOException if the server can't be asked
	 */
	protected Boolean changeMode(FileObject fo, int set, int clear) throws IOException {
		return null;
	}

	/**
	 * The file's modification time (ms), for a scheme whose provider needs help with it (VFS's ftp
	 * can't give a directory's when it uses MDTM). The default is VFS's own.
	 * @param fo the file, which exists
	 * @return the time
	 * @throws IOException if it can't be read
	 */
	protected long lastModified(FileObject fo) throws IOException {
		return fo.getContent().getLastModifiedTime();
	}

	/**
	 * Whether the file system applies operations in one order (one server, one archive), for
	 * {@link us.bringardner.parley.files.FileSourceCapabilities#orderedOperations()}. The default is
	 * true.
	 * @return true if operations are applied in one order
	 */
	protected boolean orderedOperations() {
		return true;
	}

	// ------------------------------------------------------------------ connection

	@Override
	protected synchronized boolean connectImpl() throws IOException {
		if( isConnected() ) {
			return true;
		}
		StandardFileSystemManager m = new StandardFileSystemManager();
		m.setLogger(new VfsLog(this));
		try {
			m.init();
			FileSystemOptions options = new FileSystemOptions();
			if( userName() != null ) {
				DefaultFileSystemConfigBuilder.getInstance().setUserAuthenticator(options, new VfsAuthenticator(this));
			}
			configure(options);
			FileObject r = m.resolveFile(rootUri(), options);
			// a wrong host, password or archive fails the connect, not the first file call
			r.exists();
			manager = m;
			root = r;
			return true;
		} catch (IOException | RuntimeException e) {
			m.close();
			throw e;
		}
	}

	@Override
	protected synchronized void disConnectImpl() throws IOException {
		StandardFileSystemManager m = manager;
		manager = null;
		root = null;
		if( m != null ) {
			// closes every file system and stream this manager opened
			m.close();
		}
	}

	@Override
	public boolean isConnected() {
		return manager != null && root != null;
	}

	/**
	 * The secret a setting stands for, resolved now (a keychain reference, say). For
	 * {@link VfsAuthenticator}, which VFS asks when it opens a connection.
	 */
	char[] resolveSecret(String property, String value) throws IOException {
		return secret(property, value);
	}

	/** The root, connecting first if needed. */
	FileObject root() throws IOException {
		FileObject r = root;
		if( r == null ) {
			if( !connect() ) {
				throw new IOException("Can't connect to " + getURL());
			}
			r = root;
			if( r == null ) {
				throw new IOException("Not connected to " + getURL());
			}
		}
		return r;
	}

	/** @return true if the file system has the capability (connecting first if needed) */
	boolean hasCapability(Capability c) throws IOException {
		return root().getFileSystem().hasCapability(c);
	}

	/** {@link #posixMode(FileObject)} for VfsFileSource, which is in this package */
	Integer modeOf(FileObject fo) {
		return posixMode(fo);
	}

	/** {@link #changeMode(FileObject, int, int)} for VfsFileSource */
	Boolean changeModeOf(FileObject fo, int set, int clear) throws IOException {
		return changeMode(fo, set, clear);
	}

	/** {@link #lastModified(FileObject)} for VfsFileSource */
	long lastModifiedOf(FileObject fo) throws IOException {
		return lastModified(fo);
	}

	/** {@link #orderedOperations()} for VfsFileSource */
	boolean isOrdered() {
		return orderedOperations();
	}

	// ------------------------------------------------------------------ files

	/**
	 * The file at PATH: an absolute path on this file system, a path relative to the current
	 * directory, or (for a nested VFS URL, which {@code FileSourceUri} gives whole) a value that
	 * starts with the root URI, whose rest is the path.
	 */
	@Override
	public FileSource createFileSource(String path) throws IOException {
		String p = stripRoot(path == null ? "" : path.trim());
		if( p.isEmpty() ) {
			p = "/";
		}
		if( !p.startsWith("/") ) {
			p = currentDirectory + (currentDirectory.endsWith("/") ? "" : "/") + p;
		}
		return new VfsFileSource(this, VfsFileSource.normalize(p));
	}

	/** PATH with the root URI (with or without its VFS scheme) taken off the front. */
	private String stripRoot(String path) {
		String r;
		try {
			r = rootUri();
		} catch (IOException e) {
			return path;
		}
		String scheme = vfsScheme() + ":";
		for (String prefix : new String[] { r, r.startsWith(scheme) ? r.substring(scheme.length()) : null }) {
			if( prefix == null ) {
				continue;
			}
			String bare = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
			if( path.equals(bare) ) {
				return "/";
			}
			if( path.startsWith(bare + "/") ) {
				return path.substring(bare.length());
			}
		}
		return path;
	}

	@Override
	public FileSource[] listRoots() throws IOException {
		return new FileSource[] { createFileSource("/") };
	}

	@Override
	public FileSource getCurrentDirectory() throws IOException {
		return createFileSource(currentDirectory);
	}

	/** Sets where relative paths start, for this factory only. */
	@Override
	public void setCurrentDirectory(FileSource dir) throws IOException {
		if( dir == null ) {
			currentDirectory = "/";
			return;
		}
		if( !isSameFileSystem(dir.getFileSourceFactory()) ) {
			throw new IOException(dir.getAbsolutePath() + " is on another file system");
		}
		currentDirectory = dir.getAbsolutePath();
	}

	@Override
	public boolean isVersionSupported() {
		return false;
	}

	@Override
	public char getPathSeparatorChar() {
		return ':';
	}

	@Override
	public char getSeparatorChar() {
		return '/';
	}

	/** VFS can't make links. */
	@Override
	public FileSource createSymbolicLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		throw new UnsupportedOperationException(getTypeId() + " can't make symbolic links");
	}

	/** VFS can't make links. */
	@Override
	public FileSource createLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		throw new UnsupportedOperationException(getTypeId() + " can't make links");
	}

	/** The user from the settings (the connected user), else the local user. */
	@Override
	public FileSourceUser whoAmI() {
		String user = userName();
		if( user == null || user.isEmpty() ) {
			return super.whoAmI();
		}
		FileSourceUser ret = new FileSourceUser();
		ret.setName(user);
		return ret;
	}

	/**
	 * A new factory of the same class with the same settings and its own manager, so its own
	 * connection.
	 */
	@Override
	public FileSourceFactory createThreadSafeCopy() {
		try {
			VfsFileSourceFactory ret = getClass().getDeclaredConstructor().newInstance();
			ret.setConnectionProperties(getConnectProperties());
			ret.currentDirectory = currentDirectory;
			return ret;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Can't copy " + getClass().getName() + ": " + e, e);
		}
	}

	/** The same class and the same root URI (scheme, user, host and port; or the same archive). */
	@Override
	public boolean isSameFileSystem(FileSourceFactory other) {
		if( other == this ) {
			return true;
		}
		if( other == null || other.getClass() != getClass() ) {
			return false;
		}
		try {
			return rootUri().equals(((VfsFileSourceFactory) other).rootUri());
		} catch (IOException e) {
			return false;
		}
	}

	@Override
	public boolean isSecretProperty(String name) {
		return "password".equals(name) || "privateKey".equals(name);
	}

	/** The file system's URL, without a password: {@code vfs} + the root URI. Works while disconnected. */
	@Override
	public String getURL() {
		try {
			return TYPE_ID_PREFIX + rootUri();
		} catch (IOException e) {
			return getTypeId() + ":";
		}
	}

	@Override
	public String getTitle() {
		return getURL();
	}

	/**
	 * A {@code filesource:} URL for a file on this file system, as the other FileSources make, without
	 * the password: {@code filesource://tony@host:2222/dir?sourcetype=vfssftp}. A scheme with no
	 * server (an archive) overrides this.
	 * @param path the file's absolute path
	 * @return the URL
	 */
	protected String fileSourceUrl(String path) {
		Properties p = getConnectProperties();
		String host = p.getProperty("host", "");
		String user = userName();
		String port = p.getProperty("port", "");
		StringBuilder b = new StringBuilder(FILE_SOURCE_PROTOCOL).append("://");
		if( user != null && !user.isEmpty() ) {
			b.append(encode(user)).append('@');
		}
		b.append(host);
		if( !port.isEmpty() ) {
			b.append(':').append(port);
		}
		return b.append(path).append('?').append(QUERY_STRING_SOURCE_TYPE).append('=').append(getTypeId()).toString();
	}

	/** Percent-encodes a user name for a URL's authority. */
	static String encode(String s) {
		try {
			return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20");
		} catch (UnsupportedEncodingException e) {
			return s;
		}
	}

	// ------------------------------------------------------------------ settings from a URL

	/**
	 * Takes the settings a URL carries: user, password, host and port from its authority (a
	 * scheme with no server has none), and any of {@link #getConnectProperties()} named in its
	 * query ({@code ?strictHostKeyChecking=no}).
	 */
	@Override
	public void setConnectionProperties(URL url) {
		Properties p = new Properties();
		String auth = url.getAuthority();
		if( auth != null && !auth.isEmpty() ) {
			// user[:password]@host[:port]: the host starts after the LAST '@', the password after the
			// FIRST ':' of the user part, so a raw '@' or ':' in a password still works
			int at = auth.lastIndexOf('@');
			if( at >= 0 ) {
				String userInfo = auth.substring(0, at);
				auth = auth.substring(at + 1);
				int colon = userInfo.indexOf(':');
				if( colon >= 0 ) {
					p.setProperty("user", decode(userInfo.substring(0, colon)));
					p.setProperty("password", decode(userInfo.substring(colon + 1)));
				} else {
					p.setProperty("user", decode(userInfo));
				}
			}
			int colon = auth.lastIndexOf(':');
			if( colon > 0 && auth.indexOf(']') < colon ) {
				p.setProperty("host", auth.substring(0, colon));
				p.setProperty("port", auth.substring(colon + 1));
			} else if( !auth.isEmpty() ) {
				p.setProperty("host", auth);
			}
		}
		String query = url.getQuery();
		if( query != null ) {
			Properties known = getConnectProperties();
			for (String part : query.split("&")) {
				String[] kv = part.split("=", 2);
				if( kv.length == 2 && known.containsKey(kv[0]) ) {
					p.setProperty(kv[0], decode(kv[1]));
				}
			}
		}
		if( !p.isEmpty() ) {
			setConnectionProperties(p);
		}
	}

	/** Percent-decodes one part of a URL; a '+' stays a '+' (it means a space only in form data). */
	static String decode(String s) {
		try {
			return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8.name());
		} catch (UnsupportedEncodingException | IllegalArgumentException e) {
			return s;
		}
	}
}

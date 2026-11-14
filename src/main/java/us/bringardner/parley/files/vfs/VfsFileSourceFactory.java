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

	/**
	 * The class this scheme's own library must provide, for a scheme whose library is an optional
	 * dependency (sftp: JSch; ftp, ftps: commons-net); null when VFS needs nothing more. Without it,
	 * VFS either drops the scheme (sftp: an unknown scheme, reported as a "relative path") or fails
	 * with a NoClassDefFoundError (ftp), so connect checks for it first.
	 * @return a class name, or null
	 */
	protected String libraryClass() {
		return null;
	}

	/** @return the library {@link #libraryClass()} comes from, as an error names it: its name and Maven coordinates */
	protected String libraryName() {
		return null;
	}

	/**
	 * Throws an IOException naming the library to add when the scheme's isn't on the class path.
	 * {@link #libraryClass()} may name several classes, separated by commas (vfshdfs: VFS's HDFS
	 * provider and Hadoop's), each of which must be there.
	 */
	private void checkLibrary() throws IOException {
		String classes = libraryClass();
		if( classes == null ) {
			return;
		}
		for (String each : classes.split(",")) {
			String cls = each.trim();
			try {
				Class.forName(cls, false, getClass().getClassLoader());
			} catch (ClassNotFoundException | LinkageError e) {
				IOException ex = new IOException(getTypeId() + " needs " + libraryName() + " on the class path (" + cls + " is missing)");
				ex.initCause(e);
				throw ex;
			}
		}
	}

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
	 * Whether FO exists: by default VFS's answer. vfsgz and vfsbz2 answer for their one file, which
	 * VFS says exists before the compressed file does (docs/DESIGN.md, 15.3).
	 * @param fo the file
	 * @return true if it exists
	 * @throws IOException if it can't be told
	 */
	protected boolean exists(FileObject fo) throws IOException {
		return fo.exists();
	}

	/**
	 * Whether FO is a folder: by default VFS's answer. vfshttp asks the server (VFS's http says
	 * every URL is a file: docs/DESIGN.md, 16.2).
	 * @param fo the file
	 * @return true for a folder
	 * @throws IOException if it can't be told
	 */
	protected boolean isFolder(FileObject fo) throws IOException {
		return fo.isFolder();
	}

	/**
	 * Whether FO is a file: by default VFS's answer.
	 * @param fo the file
	 * @return true for a file
	 * @throws IOException if it can't be told
	 */
	protected boolean isFile(FileObject fo) throws IOException {
		return fo.isFile();
	}

	/**
	 * A folder's children: by default VFS's. vfshttp reads them from the server's listing page.
	 * @param fo a folder
	 * @return its children
	 * @throws IOException if they can't be listed
	 */
	protected FileObject[] children(FileObject fo) throws IOException {
		return fo.getChildren();
	}

	/**
	 * The stream a file is written through: by default VFS's. vfswebdav checks, once it is closed,
	 * that the server has stored what was written (docs/DESIGN.md, 17.3).
	 * @param fo the file
	 * @param out VFS's stream
	 * @return the stream to write to
	 * @throws IOException if it can't be made
	 */
	protected java.io.OutputStream output(FileObject fo, java.io.OutputStream out) throws IOException {
		return out;
	}

	/**
	 * Told when a file is refreshed, so a scheme can forget what it kept about it (vfshttp: whether
	 * it is a folder). Nothing by default.
	 * @param fo the file
	 */
	protected void refreshed(FileObject fo) {
		// nothing kept
	}

	/**
	 * A file's length: by default VFS's. vfsgz and vfsbz2 count it, as VFS doesn't know it (15.3).
	 * @param fo a file that exists
	 * @return the length in bytes
	 * @throws IOException if it can't be read
	 */
	protected long length(FileObject fo) throws IOException {
		return fo.getContent().getSize();
	}

	/**
	 * Refuses a write the scheme can't make although VFS would (vfsgz and vfsbz2: any name but their
	 * one file, which VFS would write over the whole compressed file). Nothing by default.
	 * @param fo the file to be written
	 * @param path its path
	 * @throws IOException if it can't be written
	 */
	protected void checkWrite(FileObject fo, String path) throws IOException {
		// any file VFS can write
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
		checkLibrary();
		StandardFileSystemManager m = openManager();
		try {
			FileSystemOptions options = new FileSystemOptions();
			if( userName() != null ) {
				DefaultFileSystemConfigBuilder.getInstance().setUserAuthenticator(options, new VfsAuthenticator(this));
			}
			configure(options);
			FileObject r = resolveRoot(m, options);
			// a wrong host, password or archive fails the connect, not the first file call
			r.exists();
			prepareRoot(r);
			manager = m;
			root = r;
			return true;
		} catch (IOException | RuntimeException | Error e) {
			// whatever failed, Errors too, the manager made for this connect is closed
			closeManager(m);
			throw e;
		}
	}

	/**
	 * A started VFS manager for this connection: by default a new one of this factory's own, never
	 * VFS's one for the whole JVM. vfsram and vfstmp share one per name (docs/DESIGN.md, 14).
	 * @return the manager
	 * @throws IOException if VFS can't start one
	 */
	protected StandardFileSystemManager openManager() throws IOException {
		StandardFileSystemManager m = new StandardFileSystemManager();
		m.setLogger(new VfsLog(this));
		try {
			m.init();
		} catch (IOException | RuntimeException | Error e) {
			m.close();
			throw e;
		}
		return m;
	}

	/**
	 * Gives back a manager {@link #openManager()} gave: by default closes it, and with it every file
	 * system and stream it opened.
	 * @param m the manager
	 */
	protected void closeManager(StandardFileSystemManager m) {
		m.close();
	}

	/**
	 * The file system's root, resolved on the connection's manager: by default {@link #rootUri()}.
	 * @param m the manager
	 * @param options the options {@link #configure(FileSystemOptions)} set
	 * @return the root
	 * @throws IOException if it can't be resolved
	 */
	protected FileObject resolveRoot(StandardFileSystemManager m, FileSystemOptions options) throws IOException {
		return m.resolveFile(rootUri(), options);
	}

	/**
	 * Checks or makes the root once it is resolved, before the connection is used (vfsfile: it must
	 * be a folder; vfstmp: it is made).
	 * @param root the root
	 * @throws IOException if the root can't be used
	 */
	protected void prepareRoot(FileObject root) throws IOException {
		// nothing by default
	}

	/**
	 * The VFS file at PATH (absolute and normalized: "/" or "/a/b") on this connection: by default
	 * PATH under the root, so a factory whose root is a folder (vfsfile) reaches nothing above it.
	 * @param root the root
	 * @param path the path
	 * @return the file, which may not exist
	 * @throws IOException if VFS can't resolve it
	 */
	protected FileObject resolve(FileObject root, String path) throws IOException {
		return "/".equals(path) ? root : root.resolveFile(path.substring(1));
	}

	/**
	 * Whether F was resolved on this factory's current connection (its manager), so a file kept from
	 * before a reconnect is resolved again.
	 */
	boolean isCurrent(FileObject f) throws IOException {
		StandardFileSystemManager m = manager;
		return m != null && f.getFileSystem().getFileSystemManager() == m;
	}

	/** {@link #resolve(FileObject, String)} on the current connection, for VfsFileSource */
	FileObject fileObject(String path) throws IOException {
		return resolve(root(), path);
	}

	@Override
	protected synchronized void disConnectImpl() throws IOException {
		StandardFileSystemManager m = manager;
		manager = null;
		root = null;
		if( m != null ) {
			// closes every file system and stream this manager opened (a shared one: when its last
			// factory gives it back)
			closeManager(m);
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

	/**
	 * @param c a capability
	 * @return true if the file system has it (connecting first if needed): by default the root's
	 *         file system's answer
	 * @throws IOException if it can't connect
	 */
	protected boolean hasCapability(Capability c) throws IOException {
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

	/** {@link #exists(FileObject)} for VfsFileSource */
	boolean existsOf(FileObject fo) throws IOException {
		return exists(fo);
	}

	/** {@link #isFolder(FileObject)} for VfsFileSource */
	boolean isFolderOf(FileObject fo) throws IOException {
		return isFolder(fo);
	}

	/** {@link #isFile(FileObject)} for VfsFileSource */
	boolean isFileOf(FileObject fo) throws IOException {
		return isFile(fo);
	}

	/** {@link #children(FileObject)} for VfsFileSource */
	FileObject[] childrenOf(FileObject fo) throws IOException {
		return children(fo);
	}

	/** {@link #output(FileObject, java.io.OutputStream)} for VfsFileSource */
	java.io.OutputStream outputOf(FileObject fo, java.io.OutputStream out) throws IOException {
		return output(fo, out);
	}

	/** {@link #refreshed(FileObject)} for VfsFileSource */
	void refreshedOf(FileObject fo) {
		refreshed(fo);
	}

	/** {@link #length(FileObject)} for VfsFileSource */
	long lengthOf(FileObject fo) throws IOException {
		return length(fo);
	}

	/** {@link #checkWrite(FileObject, String)} for VfsFileSource */
	void checkWriteOf(FileObject fo, String path) throws IOException {
		checkWrite(fo, path);
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
			return fileSystemKey().equals(((VfsFileSourceFactory) other).fileSystemKey());
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * What names the file system, for {@link #isSameFileSystem(FileSourceFactory)}: by default the
	 * root URI; vfsram and vfstmp add their name.
	 * @return the key
	 * @throws IOException if the settings don't make one
	 */
	protected String fileSystemKey() throws IOException {
		return rootUri();
	}

	/**
	 * The settings, besides the server's address and user, that say which file system a
	 * {@code filesource:} URL means (vfsfile's root, vfsram's name), added to its query; none by
	 * default. Only those that aren't their default.
	 * @return the settings, in order
	 */
	protected java.util.Map<String, String> urlSettings() {
		return java.util.Collections.emptyMap();
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
		b.append(path).append('?').append(QUERY_STRING_SOURCE_TYPE).append('=').append(getTypeId());
		for (java.util.Map.Entry<String, String> e : urlSettings().entrySet()) {
			b.append('&').append(e.getKey()).append('=').append(encode(e.getValue()));
		}
		return b.toString();
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

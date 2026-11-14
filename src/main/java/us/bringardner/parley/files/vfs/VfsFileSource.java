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

import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.apache.commons.vfs2.Capability;
import org.apache.commons.vfs2.FileContent;
import org.apache.commons.vfs2.FileContentInfo;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.RandomAccessContent;
import org.apache.commons.vfs2.util.RandomAccessMode;

import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceCapabilities;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceFilter;
import us.bringardner.parley.files.FileSourceProgress;
import us.bringardner.parley.files.FileSourceRandomAccessStream;
import us.bringardner.parley.files.IRandomAccessStream;
import us.bringardner.parley.files.ISeekableInputStream;
import us.bringardner.parley.files.fileproxy.FileProxy;
import us.bringardner.parley.files.lock.LockScope;

/**
 * One path on a VFS file system: a {@link FileSource} over a VFS {@code FileObject}.
 * <p>
 * VFS does some things the FileSource contract doesn't allow, so they are checked here first
 * (docs/DESIGN.md, section 2.2): moving onto a file that exists deletes it in VFS, so
 * {@link #renameTo(FileSource)} returns false instead; VFS's create-folder and create-file make
 * every missing parent and quietly do nothing if the file is there, so {@link #mkdir()} and
 * {@link #createNewFile()} check for themselves. <b>Neither is atomic</b>, against the FileSource
 * contract: VFS has no create-if-missing, so two clients racing for one path can both be told
 * true ({@link #getCapabilities()} says so).
 * <p>
 * A write on a file system VFS can't write (an archive) throws an IOException saying it is read
 * only.
 */
public class VfsFileSource extends BaseObject implements FileSource {

	private static final long serialVersionUID = 1L;

	private final VfsFileSourceFactory factory;
	/** Absolute and normalized: "/" or "/a/b". */
	private final String path;
	/** Resolved when first needed, and again after the factory reconnects. */
	private transient volatile FileObject fo;

	VfsFileSource(VfsFileSourceFactory factory, String path) {
		this.factory = factory;
		this.path = path;
	}

	private VfsFileSource(VfsFileSourceFactory factory, String path, FileObject fo) {
		this(factory, path);
		this.fo = fo;
	}

	// ------------------------------------------------------------------ logging goes to the factory

	@Override
	public void logDebug(String msg) {
		factory.logDebug(msg);
	}

	@Override
	public void logDebug(String msg, Throwable error) {
		factory.logDebug(msg, error);
	}

	@Override
	public void logError(String msg) {
		factory.logError(msg);
	}

	@Override
	public void logError(String msg, Throwable error) {
		factory.logError(msg, error);
	}

	@Override
	public void logInfo(String msg) {
		factory.logInfo(msg);
	}

	@Override
	public void logInfo(String msg, Throwable error) {
		factory.logInfo(msg, error);
	}

	@Override
	public boolean isDebugEnabled() {
		return factory.isDebugEnabled();
	}

	@Override
	public boolean isErrorEnabled() {
		return factory.isErrorEnabled();
	}

	@Override
	public boolean isInfoEnabled() {
		return factory.isInfoEnabled();
	}

	// ------------------------------------------------------------------ the FileObject

	/**
	 * The VFS file, resolved on the factory's current connection: a FileObject from an earlier
	 * one (whose manager is closed) is resolved again.
	 */
	FileObject fo() throws IOException {
		factory.root();
		FileObject f = fo;
		if( f == null || !factory.isCurrent(f) ) {
			f = factory.fileObject(path);
			fo = f;
		}
		return f;
	}

	/** Throws the read-only IOException if the file system can't do C. */
	private void require(Capability c, String what) throws IOException {
		if( !factory.hasCapability(c) ) {
			throw new IOException(factory.getTypeId() + " is read only here: it can't " + what + " (" + path + ")");
		}
	}

	// ------------------------------------------------------------------ names and paths

	/**
	 * PATH made absolute and without "." and "..", with no I/O: ".." at the root stays at the root,
	 * as java.io.File's canonical path does.
	 * @param p a path starting with "/"
	 * @return the normalized path ("/" or "/a/b")
	 */
	static String normalize(String p) {
		Deque<String> parts = new ArrayDeque<>();
		for (String s : p.split("/")) {
			if( s.isEmpty() || s.equals(".") ) {
				continue;
			}
			if( s.equals("..") ) {
				parts.pollLast();
			} else {
				parts.addLast(s);
			}
		}
		if( parts.isEmpty() ) {
			return "/";
		}
		StringBuilder b = new StringBuilder();
		for (String s : parts) {
			b.append('/').append(s);
		}
		return b.toString();
	}

	@Override
	public String getName() {
		return path.equals("/") ? "" : path.substring(path.lastIndexOf('/') + 1);
	}

	@Override
	public String getAbsolutePath() {
		return path;
	}

	/**
	 * The same as the absolute path: VFS can't tell where a symbolic link leads, so the canonical
	 * path is lexical ("." and ".." resolved), as in parley-files-ftp.
	 */
	@Override
	public String getCanonicalPath() throws IOException {
		return path;
	}

	@Override
	public String getParent() {
		if( path.equals("/") ) {
			return null;
		}
		int idx = path.lastIndexOf('/');
		return idx == 0 ? "/" : path.substring(0, idx);
	}

	@Override
	public FileSource getParentFile() throws IOException {
		String p = getParent();
		return p == null ? null : new VfsFileSource(factory, p);
	}

	/** The file at PATH under this one: relative to this even if it starts with "/". */
	@Override
	public FileSource getChild(String child) throws IOException {
		return new VfsFileSource(factory, normalize(path + "/" + child));
	}

	@Override
	public FileSourceFactory getFileSourceFactory() {
		return factory;
	}

	/** The plain VFS URI of this file (with no password): {@code sftp://tony@host:22/home/tony}. */
	public String getVfsUri() throws IOException {
		return fo().getName().getFriendlyURI();
	}

	/** {@code vfs} + the VFS URI, the URL that opens this file again (without a password). */
	@Override
	public String getTitle() throws IOException {
		return VfsFileSourceFactory.TYPE_ID_PREFIX + getVfsUri();
	}

	/**
	 * A {@code filesource:} URL with this file's settings, as the other FileSources make, without
	 * the password (as parley-files-sftp's): {@code filesource://tony@host:2222/dir?sourcetype=vfssftp}.
	 */
	@Override
	public URL toURL() throws MalformedURLException {
		return new URL(factory.fileSourceUrl(path));
	}

	@Override
	public String toString() {
		return path;
	}

	/** Same class, same file system, same path, as every FileSource. */
	@Override
	public boolean equals(Object obj) {
		if( this == obj ) {
			return true;
		}
		if( obj == null || obj.getClass() != getClass() ) {
			return false;
		}
		VfsFileSource o = (VfsFileSource) obj;
		return path.equals(o.path) && factory.isSameFileSystem(o.factory);
	}

	@Override
	public int hashCode() {
		return path.hashCode();
	}

	/** By path, then by kind, as the other FileSources. */
	@Override
	public int compareTo(Object o) {
		if( o instanceof FileSource ) {
			int ret = path.compareTo(((FileSource) o).getAbsolutePath());
			return ret != 0 ? ret : getClass().getName().compareTo(o.getClass().getName());
		}
		return path.compareTo(String.valueOf(o));
	}

	// ------------------------------------------------------------------ type and attributes

	@Override
	public boolean exists() throws IOException {
		return factory.existsOf(fo());
	}

	@Override
	public boolean isFile() throws IOException {
		FileObject f = fo();
		return factory.existsOf(f) && f.isFile();
	}

	@Override
	public boolean isDirectory() throws IOException {
		return fo().isFolder();
	}

	/** VFS's answer, or a name starting with "." (VFS's sftp provider doesn't say). */
	@Override
	public boolean isHidden() throws IOException {
		return getName().startsWith(".") || fo().isHidden();
	}

	@Override
	public long length() throws IOException {
		FileObject f = fo();
		return f.isFile() && factory.existsOf(f) ? factory.lengthOf(f) : 0L;
	}

	@Override
	public long lastModified() throws IOException {
		FileObject f = fo();
		if( !factory.existsOf(f) || !factory.hasCapability(Capability.GET_LAST_MODIFIED) ) {
			return 0L;
		}
		return factory.lastModifiedOf(f);
	}

	/** Not known through VFS: 0. */
	@Override
	public long lastAccessTime() throws IOException {
		return 0L;
	}

	/** Not known through VFS: 0. */
	@Override
	public long creationTime() throws IOException {
		return 0L;
	}

	/** Not known through VFS: 0. */
	@Override
	public long getCreateDate() throws IOException {
		return 0L;
	}

	@Override
	public boolean setLastModifiedTime(long time) throws IOException {
		FileObject f = fo();
		if( !factory.existsOf(f) ) {
			return false;
		}
		Capability c = f.isFolder() ? Capability.SET_LAST_MODIFIED_FOLDER : Capability.SET_LAST_MODIFIED_FILE;
		if( !factory.hasCapability(c) ) {
			return false;
		}
		f.getContent().setLastModifiedTime(time);
		return true;
	}

	@Override
	public boolean setLastAccessTime(long time) throws IOException {
		return false;
	}

	@Override
	public boolean setCreateTime(long time) throws IOException {
		return false;
	}

	/** VFS's guess from the file, else parley-files' table by extension. */
	@Override
	public String getContentType() {
		try {
			FileObject f = fo();
			if( f.isFile() ) {
				FileContentInfo info = f.getContent().getContentInfo();
				if( info != null && info.getContentType() != null ) {
					return info.getContentType();
				}
			}
		} catch (IOException | RuntimeException e) {
			logDebug("No content type from VFS for " + path + ": " + e);
		}
		return FileProxy.getContentType(getName());
	}

	/** VFS doesn't say who owns a file: a principal with no name (unknown). */
	@Override
	public UserPrincipal getOwner() throws IOException {
		return () -> null;
	}

	/** VFS doesn't say a file's group: a principal with no name (unknown). */
	@Override
	public GroupPrincipal getGroup() throws IOException {
		return () -> null;
	}

	@Override
	public boolean setOwner(UserPrincipal owner) throws IOException {
		return false;
	}

	@Override
	public boolean setGroup(GroupPrincipal group) throws IOException {
		return false;
	}

	/** VFS can tell a link is one, but not where it leads: null. */
	@Override
	public FileSource getLinkedTo() throws IOException {
		return null;
	}

	@Override
	public void refresh() throws IOException {
		FileObject f = fo;
		if( f != null ) {
			f.refresh();
		}
	}

	// ------------------------------------------------------------------ permissions

	/** The connected user's access, as VFS works it out. */
	@Override
	public boolean canRead() throws IOException {
		FileObject f = fo();
		return factory.existsOf(f) && f.isReadable();
	}

	@Override
	public boolean canWrite() throws IOException {
		FileObject f = fo();
		return factory.existsOf(f) && f.isWriteable();
	}

	@Override
	public boolean canExecute() throws IOException {
		FileObject f = fo();
		return factory.existsOf(f) && f.isExecutable();
	}

	/** One of the nine bits from the scheme's hook; false if it isn't known. */
	private boolean bit(int mask) throws IOException {
		FileObject f = fo();
		if( !factory.existsOf(f) ) {
			return false;
		}
		Integer mode = factory.modeOf(f);
		return mode != null && (mode & mask) != 0;
	}

	@Override
	public boolean canOwnerRead() throws IOException {
		return bit(0400);
	}

	@Override
	public boolean canOwnerWrite() throws IOException {
		return bit(0200);
	}

	@Override
	public boolean canOwnerExecute() throws IOException {
		return bit(0100);
	}

	@Override
	public boolean canGroupRead() throws IOException {
		return bit(040);
	}

	@Override
	public boolean canGroupWrite() throws IOException {
		return bit(020);
	}

	@Override
	public boolean canGroupExecute() throws IOException {
		return bit(010);
	}

	@Override
	public boolean canOtherRead() throws IOException {
		return bit(04);
	}

	@Override
	public boolean canOtherWrite() throws IOException {
		return bit(02);
	}

	@Override
	public boolean canOtherExecute() throws IOException {
		return bit(01);
	}

	@Override
	public boolean setReadable(boolean b, boolean ownerOnly) throws IOException {
		FileObject f = fo();
		if( !factory.existsOf(f) ) {
			return false;
		}
		Boolean ret = changeMode(f, b, ownerOnly ? 0400 : 0444);
		return ret != null ? ret : f.setReadable(b, ownerOnly);
	}

	@Override
	public boolean setWritable(boolean b, boolean ownerOnly) throws IOException {
		FileObject f = fo();
		if( !factory.existsOf(f) ) {
			return false;
		}
		Boolean ret = changeMode(f, b, ownerOnly ? 0200 : 0222);
		return ret != null ? ret : f.setWritable(b, ownerOnly);
	}

	@Override
	public boolean setExecutable(boolean b, boolean ownerOnly) throws IOException {
		FileObject f = fo();
		if( !factory.existsOf(f) ) {
			return false;
		}
		Boolean ret = changeMode(f, b, ownerOnly ? 0100 : 0111);
		return ret != null ? ret : f.setExecutable(b, ownerOnly);
	}

	/** BITS on or off through the scheme's own way; false if it has none (VFS can't set them alone). */
	private boolean changeModeAlone(boolean on, int bits) throws IOException {
		FileObject f = fo();
		if( !factory.existsOf(f) ) {
			return false;
		}
		Boolean ret = changeMode(f, on, bits);
		return ret != null && ret;
	}

	/** The scheme's own way of turning BITS on or off, or null for VFS's setters. */
	private Boolean changeMode(FileObject f, boolean on, int bits) throws IOException {
		return factory.changeModeOf(f, on ? bits : 0, on ? 0 : bits);
	}

	/** The owner's permission, as java.io.File.setReadable(b). */
	@Override
	public boolean setReadable(boolean b) throws IOException {
		return setReadable(b, true);
	}

	@Override
	public boolean setWritable(boolean b) throws IOException {
		return setWritable(b, true);
	}

	@Override
	public boolean setExecutable(boolean b) throws IOException {
		return setExecutable(b, true);
	}

	@Override
	public boolean setOwnerReadable(boolean b) throws IOException {
		return setReadable(b, true);
	}

	@Override
	public boolean setOwnerWritable(boolean b) throws IOException {
		return setWritable(b, true);
	}

	@Override
	public boolean setOwnerExecutable(boolean b) throws IOException {
		return setExecutable(b, true);
	}

	/** The group's permission alone, where the scheme can (vfssftp); false where VFS can't set it alone. */
	@Override
	public boolean setGroupReadable(boolean b) throws IOException {
		return changeModeAlone(b, 040);
	}

	/** The group's permission alone, where the scheme can (vfssftp); false where VFS can't set it alone. */
	@Override
	public boolean setGroupWritable(boolean b) throws IOException {
		return changeModeAlone(b, 020);
	}

	/** The group's permission alone, where the scheme can (vfssftp); false where VFS can't set it alone. */
	@Override
	public boolean setGroupExecutable(boolean b) throws IOException {
		return changeModeAlone(b, 010);
	}

	/** Other users' permission alone, where the scheme can (vfssftp); false where VFS can't set it alone. */
	@Override
	public boolean setOtherReadable(boolean b) throws IOException {
		return changeModeAlone(b, 04);
	}

	/** Other users' permission alone, where the scheme can (vfssftp); false where VFS can't set it alone. */
	@Override
	public boolean setOtherWritable(boolean b) throws IOException {
		return changeModeAlone(b, 02);
	}

	/** Other users' permission alone, where the scheme can (vfssftp); false where VFS can't set it alone. */
	@Override
	public boolean setOtherExecutable(boolean b) throws IOException {
		return changeModeAlone(b, 01);
	}

	/** Takes write permission from everyone. */
	@Override
	public boolean setReadOnly() throws IOException {
		return setWritable(false, false);
	}

	// ------------------------------------------------------------------ create, delete, rename

	/**
	 * As java.io.File: true if this call created the file, false if something is there, and an
	 * IOException if the directory it goes in isn't there.
	 * <p>
	 * <b>Not atomic, against FileSource's contract</b>: VFS has no create-if-missing, so this checks
	 * and then creates, and two clients racing for one path can both be told true.
	 */
	@Override
	public boolean createNewFile() throws IOException {
		FileObject f = fo();
		if( factory.existsOf(f) ) {
			return false;
		}
		FileSource parent = getParentFile();
		if( parent == null || !parent.isDirectory() ) {
			throw new IOException("No such directory: " + getParent());
		}
		require(Capability.CREATE, "create files");
		f.createFile();
		return true;
	}

	/**
	 * As java.io.File: one level, false if something is there or the parent isn't. Not atomic
	 * (VFS's create-folder does nothing if the folder is there).
	 */
	@Override
	public boolean mkdir() throws IOException {
		FileObject f = fo();
		if( factory.existsOf(f) ) {
			return false;
		}
		FileSource parent = getParentFile();
		if( parent == null || !parent.isDirectory() ) {
			return false;
		}
		require(Capability.CREATE, "create directories");
		try {
			f.createFolder();
		} catch (FileSystemException e) {
			// someone else may have made it meanwhile
			f.refresh();
			if( factory.existsOf(f) ) {
				return false;
			}
			throw e;
		}
		return true;
	}

	/** As java.io.File: false if something is there, else creates it and every missing parent. */
	@Override
	public boolean mkdirs() throws IOException {
		FileObject f = fo();
		if( factory.existsOf(f) ) {
			return false;
		}
		require(Capability.CREATE, "create directories");
		f.createFolder();
		return true;
	}

	/** As java.io.File: false if it isn't there or is a directory with something in it. */
	@Override
	public boolean delete() throws IOException {
		FileObject f = fo();
		if( !factory.existsOf(f) ) {
			return false;
		}
		require(Capability.DELETE, "delete");
		return f.delete();
	}

	/**
	 * Renames on the same file system only, and never onto something that exists: VFS would
	 * delete it first, a directory with everything in it.
	 */
	@Override
	public boolean renameTo(FileSource dest) throws IOException {
		if( !(dest instanceof VfsFileSource) || !factory.isSameFileSystem(dest.getFileSourceFactory()) ) {
			return false;
		}
		FileObject from = fo();
		FileObject to = ((VfsFileSource) dest).fo();
		if( !factory.existsOf(from) || factory.existsOf(to) || !from.canRenameTo(to) ) {
			return false;
		}
		FileSource destParent = dest.getParentFile();
		if( destParent == null || !destParent.isDirectory() ) {
			return false;
		}
		require(Capability.RENAME, "rename");
		from.moveTo(to);
		return true;
	}

	// ------------------------------------------------------------------ listing

	@Override
	public String[] list() throws IOException {
		return list(null);
	}

	@Override
	public String[] list(FileSourceFilter filter) throws IOException {
		FileSource[] kids = listFiles(filter);
		if( kids == null ) {
			return null;
		}
		String[] ret = new String[kids.length];
		for (int i = 0; i < kids.length; i++) {
			ret[i] = kids[i].getName();
		}
		return ret;
	}

	@Override
	public FileSource[] listFiles() throws IOException {
		return listFiles((FileSourceProgress) null);
	}

	@Override
	public FileSource[] listFiles(FileSourceFilter filter) throws IOException {
		FileSource[] kids = listFiles();
		if( kids == null || filter == null ) {
			return kids;
		}
		List<FileSource> ret = new ArrayList<>();
		for (FileSource f : kids) {
			if( filter.accept(f) ) {
				ret.add(f);
			}
		}
		return ret.toArray(new FileSource[0]);
	}

	/**
	 * The directory's files, in one call (for sftp, ftp and WebDAV the children come with their
	 * attributes); null if this isn't a directory, as java.io.File. A file system that can't list
	 * (http) gives an empty list.
	 */
	@Override
	public FileSource[] listFiles(FileSourceProgress progress) throws IOException {
		FileObject f = fo();
		if( !f.isFolder() ) {
			return null;
		}
		if( !factory.hasCapability(Capability.LIST_CHILDREN) ) {
			return new FileSource[0];
		}
		FileObject[] kids = f.getChildren();
		if( progress != null ) {
			progress.setMaximum(kids.length);
		}
		List<FileSource> ret = new ArrayList<>(kids.length);
		for (FileObject kid : kids) {
			if( progress != null ) {
				if( progress.isCanceled() ) {
					break;
				}
				progress.setProgress(ret.size());
			}
			if( !factory.existsOf(kid) ) {
				// vfsgz and vfsbz2 list their one file even when the compressed file isn't there
				continue;
			}
			String name = kid.getName().getBaseName();
			ret.add(new VfsFileSource(factory, normalize(path + "/" + name), kid));
		}
		return ret.toArray(new FileSource[0]);
	}

	/** Nothing is kept: VFS keeps its own cache. */
	@Override
	public void dereferenceChilderen() {
		// nothing to drop
	}

	// ------------------------------------------------------------------ streams

	/** FileNotFoundException, as java.io.FileInputStream, if it isn't a file. */
	private FileObject readable() throws IOException {
		FileObject f = fo();
		if( f.isFolder() ) {
			throw new FileNotFoundException(path + " (Is a directory)");
		}
		if( !factory.existsOf(f) ) {
			throw new FileNotFoundException(path + " (No such file or directory)");
		}
		return f;
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return readable().getContent().getInputStream();
	}

	/**
	 * From a position: VFS's random access (a seek, then a stream) where the file system has it,
	 * else a stream that skips to it.
	 */
	@Override
	public InputStream getInputStream(long startingPosition) throws IOException {
		if( startingPosition < 0 ) {
			throw new IllegalArgumentException("Negative position " + startingPosition);
		}
		FileObject f = readable();
		if( startingPosition == 0 ) {
			return f.getContent().getInputStream();
		}
		if( factory.hasCapability(Capability.RANDOM_ACCESS_READ) ) {
			RandomAccessContent rac = f.getContent().getRandomAccessContent(RandomAccessMode.READ);
			try {
				rac.seek(Math.min(startingPosition, rac.length()));
				return new ClosingInputStream(rac.getInputStream(), rac);
			} catch (IOException | RuntimeException e) {
				rac.close();
				throw e;
			}
		}
		InputStream in = f.getContent().getInputStream();
		try {
			long left = startingPosition;
			byte[] discard = null;
			while( left > 0 ) {
				long n = in.skip(left);
				if( n <= 0 ) {
					if( discard == null ) {
						discard = new byte[8192];
					}
					int got = in.read(discard, 0, (int) Math.min(discard.length, left));
					if( got < 0 ) {
						break;
					}
					n = got;
				}
				left -= n;
			}
			return in;
		} catch (IOException | RuntimeException e) {
			in.close();
			throw e;
		}
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		return getOutputStream(false);
	}

	/**
	 * As java.io.FileOutputStream: FileNotFoundException for a directory or a missing parent (VFS
	 * would make the parents).
	 */
	@Override
	public OutputStream getOutputStream(boolean append) throws IOException {
		FileObject f = fo();
		if( f.isFolder() ) {
			throw new FileNotFoundException(path + " (Is a directory)");
		}
		FileSource parent = getParentFile();
		if( parent == null || !parent.isDirectory() ) {
			throw new FileNotFoundException(path + " (No such file or directory)");
		}
		require(Capability.WRITE_CONTENT, "write");
		factory.checkWriteOf(f, path);
		if( append && !factory.hasCapability(Capability.APPEND_CONTENT) ) {
			throw new IOException(factory.getTypeId() + " can't append to a file (" + path + ")");
		}
		return f.getContent().getOutputStream(append);
	}

	/** Over VFS's random access, where the file system has it. */
	@Override
	public ISeekableInputStream getSeekableInputStream() throws IOException {
		FileObject f = readable();
		if( !factory.hasCapability(Capability.RANDOM_ACCESS_READ) ) {
			throw new IOException(factory.getTypeId() + " has no random access (" + path + ")");
		}
		return new VfsSeekableInputStream(this, f.getContent().getRandomAccessContent(RandomAccessMode.READ));
	}

	/**
	 * "r" needs VFS's random access reading; "rw" needs random access writing, which no remote
	 * VFS scheme has (docs/DESIGN.md, section 2.1).
	 */
	@Override
	public IRandomAccessStream getRandomAccessStream(String mode) throws IOException {
		boolean write = !"r".equals(mode);
		if( !factory.hasCapability(write ? Capability.RANDOM_ACCESS_WRITE : Capability.RANDOM_ACCESS_READ) ) {
			throw new IOException(factory.getTypeId() + " can't " + (write ? "write" : "read") + " at a position in a file (" + path + ")");
		}
		FileObject f = fo();
		if( f.isFolder() || (!write && !factory.existsOf(f)) ) {
			throw new FileNotFoundException(path + (f.isFolder() ? " (Is a directory)" : " (No such file or directory)"));
		}
		if( write && !factory.existsOf(f) ) {
			if( !createNewFile() && !factory.existsOf(f) ) {
				throw new FileNotFoundException(path + " (can't be created)");
			}
		}
		VfsRandomAccessIoController io = new VfsRandomAccessIoController(this,
				f.getContent().getRandomAccessContent(write ? RandomAccessMode.READWRITE : RandomAccessMode.READ));
		try {
			return new FileSourceRandomAccessStream(io, mode);
		} catch (IOException | RuntimeException e) {
			try {
				io.close();
			} catch (Exception e2) {
				e.addSuppressed(e2);
			}
			throw e;
		}
	}

	/** Closes a RandomAccessContent along with the stream read from it. */
	private static final class ClosingInputStream extends FilterInputStream {
		private final RandomAccessContent rac;

		ClosingInputStream(InputStream in, RandomAccessContent rac) {
			super(in);
			this.rac = rac;
		}

		@Override
		public void close() throws IOException {
			try {
				super.close();
			} finally {
				rac.close();
			}
		}
	}

	// ------------------------------------------------------------------ locks and capabilities

	/**
	 * Locks reach this JVM only; createNewFile and mkdir are <b>not</b> atomic (see
	 * {@link #createNewFile()}); replace isn't; sync only sends the data; one server or archive,
	 * so operations are applied in one order.
	 */
	@Override
	public FileSourceCapabilities getCapabilities() {
		return FileSourceCapabilities.defaults(LockScope.PROCESS).withAtomicCreateNewFile(false).withAtomicMkdir(false)
				.withAtomicReplace(false).withDurableSync(false).withOrderedOperations(factory.isOrdered());
	}

	// ------------------------------------------------------------------ versions: none

	@Override
	public boolean isVersionSupported() {
		return false;
	}

	@Override
	public long getVersion() {
		return 0;
	}

	@Override
	public long getVersionDate() {
		return 0;
	}

	@Override
	public boolean setVersionDate(long time) {
		return false;
	}

	@Override
	public boolean setVersion(long version, boolean saveChange) {
		return false;
	}

	@Override
	public long getMaxVersion() {
		return 0;
	}

	/** The FileContent, for the random access classes. */
	FileContent content() throws IOException {
		return fo().getContent();
	}
}

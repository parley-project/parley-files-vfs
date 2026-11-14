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

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;

/**
 * A WebDAV server through VFS's WebDAV provider (type id {@value #FACTORY_ID}, VFS's webdav4 scheme),
 * read and write. Add {@code org.apache.commons:commons-vfs2-jackrabbit2}, which brings httpclient 4
 * and jackrabbit-webdav. For HTTPS use {@code vfswebdavs}.
 * <p>
 * Settings: host, port (80; 443 for vfswebdavs), user, password and timeout (30s)
 * ({@link VfsWebFileSourceFactory}). Paths are the server's URL paths.
 * <p>
 * Where VFS needs help (docs/DESIGN.md, 17): its own providers check certificates as Parley does
 * (VFS's trust every one); a file's type and a folder's listing come from a PROPFIND of its own
 * (VFS's can't read a server's indented answer, and lists members the server doesn't have); and
 * every upload is checked, once its stream is closed, to be on the server at the size written.
 * VFS keeps an upload in memory until its stream is closed. Parley's own WebDAV FileSource is
 * To-Do 17.57.
 */
public class VfsWebdavFileSourceFactory extends VfsWebFileSourceFactory {

	private static final long serialVersionUID = 1L;

	public static final String FACTORY_ID = TYPE_ID_PREFIX + "webdav";

	@Override
	public String getTypeId() {
		return FACTORY_ID;
	}

	@Override
	protected boolean tls() {
		return false;
	}

	/** webdav4 (webdav4s for TLS): VFS's current WebDAV client; its webdav and webdav3 are Jackrabbit 1's. */
	@Override
	public String vfsScheme() {
		return tls() ? "webdav4s" : "webdav4";
	}

	@Override
	protected String libraryClass() {
		return "org.apache.commons.vfs2.provider.webdav4.Webdav4FileProvider";
	}

	@Override
	protected String libraryName() {
		return "commons-vfs2-jackrabbit2 (org.apache.commons:commons-vfs2-jackrabbit2)";
	}

	/** VFS's manager, with this module's webdav4 and webdav4s providers in place of VFS's. */
	@Override
	protected StandardFileSystemManager openManager() throws IOException {
		StandardFileSystemManager m = super.openManager();
		try {
			VfsWebdavClient.install(m);
		} catch (IOException | RuntimeException | Error e) {
			m.close();
			throw e;
		}
		return m;
	}

	@Override
	protected void configure(FileSystemOptions options) {
		VfsWebdavClient.configure(options, getTimeout());
	}

	/**
	 * Checks, once the stream is closed (when VFS sends the file), that the server has the file at
	 * the size written: an upload a server cuts short or drops fails here, not later.
	 */
	@Override
	protected OutputStream output(FileObject fo, OutputStream out) {
		return new FilterOutputStream(out) {
			private long written;
			private boolean closed;

			@Override
			public void write(int b) throws IOException {
				out.write(b);
				written++;
			}

			@Override
			public void write(byte[] b, int off, int len) throws IOException {
				out.write(b, off, len);
				written += len;
			}

			@Override
			public void close() throws IOException {
				if( closed ) {
					return;
				}
				closed = true;
				out.close();
				fo.refresh();
				long stored = fo.exists() ? fo.getContent().getSize() : -1;
				if( stored != written ) {
					throw new IOException(getTypeId() + ": the server has " + (stored < 0 ? "no " + fo.getName().getPath()
							: stored + " bytes of " + fo.getName().getPath()) + " after " + written + " were written");
				}
			}
		};
	}
}

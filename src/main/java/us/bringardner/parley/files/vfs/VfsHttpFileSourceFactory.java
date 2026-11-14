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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.vfs2.Capability;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;

/**
 * A web site through VFS's http provider (type id {@value #FACTORY_ID}), read only, which runs on
 * Apache HttpClient 5: add {@code org.apache.httpcomponents.client5:httpclient5} to use it. For
 * HTTPS use {@code vfshttps}.
 * <p>
 * Settings: host, port (80; 443 for vfshttps), user, password (sent when the server asks) and
 * timeout (30s) ({@link VfsWebFileSourceFactory}). Paths are the site's URL paths.
 * <p>
 * Where VFS needs help (docs/DESIGN.md, 16): VFS's http says every URL is a file and can't list, so
 * a path is a folder when the server serves it with a "/" after it, and a folder's files are the
 * links on its listing page (Apache's, nginx's and the like); a folder whose page can't be read or
 * isn't HTML lists nothing. VFS trusts every HTTPS certificate; here the certificate is checked as
 * Parley checks it, also when a redirect leads to HTTPS. VFS's login through an authenticator
 * sends an empty password; here it works.
 */
public class VfsHttpFileSourceFactory extends VfsWebFileSourceFactory {

	private static final long serialVersionUID = 1L;

	public static final String FACTORY_ID = TYPE_ID_PREFIX + "http";

	/** Whether each path is a folder, as found (by a listing, or by asking): forgotten on refresh. */
	private final transient Map<String, Boolean> folders = new ConcurrentHashMap<>();

	@Override
	public String getTypeId() {
		return FACTORY_ID;
	}

	@Override
	protected boolean tls() {
		return false;
	}

	@Override
	public String vfsScheme() {
		return tls() ? "https" : "http";
	}

	@Override
	protected String libraryClass() {
		return "org.apache.hc.client5.http.classic.HttpClient";
	}

	@Override
	protected String libraryName() {
		return "httpclient5 (org.apache.httpcomponents.client5:httpclient5)";
	}

	/** VFS's manager, with this module's http and https providers in place of VFS's. */
	@Override
	protected StandardFileSystemManager openManager() throws IOException {
		StandardFileSystemManager m = super.openManager();
		try {
			VfsHttpClient.install(m, getHost());
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
		VfsHttpClient.configure(options, getTimeout());
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
}

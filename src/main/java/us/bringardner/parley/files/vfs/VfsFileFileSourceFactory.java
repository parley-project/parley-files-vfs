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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemOptions;

import us.bringardner.parley.files.ConnectionSetting;

/**
 * The local disk through VFS's file scheme (type id {@value #FACTORY_ID}); no library needed.
 * <p>
 * One factory serves one local folder, its {@value #PROP_ROOT} (by default the whole disk:
 * {@code /}, or on Windows the drive of the working directory), and its paths are from there, so
 * nothing above the root can be reached (docs/DESIGN.md, 14.1). Its URL is VFS's with {@code vfs}
 * in front: {@code vfsfile:///tmp/a.txt} is the disk's {@code /tmp/a.txt}.
 * <p>
 * Permission bits are read and set with java.nio's POSIX attributes where the disk has them.
 */
public class VfsFileFileSourceFactory extends VfsFileSourceFactory {

	private static final long serialVersionUID = 1L;

	public static final String FACTORY_ID = TYPE_ID_PREFIX + "file";

	/** The local folder the factory serves as "/". */
	public static final String PROP_ROOT = "root";

	private volatile String root;

	@Override
	public String getTypeId() {
		return FACTORY_ID;
	}

	/** @return the disk's root: "/", or on Windows the drive of the working directory */
	public static String defaultRoot() {
		Path cwd = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
		Path r = cwd.getRoot();
		return r == null ? "/" : r.toString();
	}

	/** @return the folder served as "/" */
	public String getRoot() {
		return root == null ? defaultRoot() : root;
	}

	/** @param root a local folder; empty or null means the whole disk */
	public void setRoot(String root) {
		this.root = root == null || root.trim().isEmpty() ? null : root.trim();
	}

	private Path rootPath() {
		return Paths.get(getRoot()).toAbsolutePath().normalize();
	}

	/** {@code file:///tmp/x/}: the root folder's URI. */
	@Override
	protected String rootUri() {
		String ret = rootPath().toUri().toString();
		return ret.endsWith("/") ? ret : ret + "/";
	}

	@Override
	protected void configure(FileSystemOptions options) {
		// nothing to set
	}

	/** The root must be a folder that exists. */
	@Override
	protected void prepareRoot(FileObject r) throws IOException {
		if( !r.isFolder() ) {
			throw new IOException(getRoot() + (r.exists() ? " isn't a folder" : " doesn't exist"));
		}
	}

	@Override
	protected Integer posixMode(FileObject fo) {
		return LocalBits.mode(LocalBits.path(fo, this));
	}

	@Override
	protected Boolean changeMode(FileObject fo, int set, int clear) throws IOException {
		Boolean ret = LocalBits.change(LocalBits.path(fo, this), set, clear);
		if( ret != null ) {
			fo.refresh();
		}
		return ret;
	}

	@Override
	protected Map<String, String> urlSettings() {
		return root == null ? Collections.emptyMap() : Collections.singletonMap(PROP_ROOT, rootPath().toString());
	}

	@Override
	public List<ConnectionSetting> getConnectionSettings() {
		return List.of(ConnectionSetting.localFile(PROP_ROOT, "Root folder").withDefault(defaultRoot())
				.withDescription("The local folder served as \"/\"; nothing above it can be reached."));
	}

	@Override
	public Properties getConnectProperties() {
		Properties ret = new Properties();
		ret.setProperty(PROP_ROOT, root == null ? "" : root);
		return ret;
	}

	@Override
	public void setConnectionProperties(Properties p) {
		if( p.containsKey(PROP_ROOT) ) {
			setRoot(p.getProperty(PROP_ROOT));
		}
	}
}

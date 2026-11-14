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
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.vfs2.Capability;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;

import us.bringardner.parley.files.ConnectionSetting;

/**
 * The class path's resources through VFS's res scheme (type id {@value #FACTORY_ID}), read only;
 * no library needed, no settings.
 * <p>
 * VFS's res isn't a file system: {@code res:a/b.txt} becomes the jar entry or file that holds the
 * resource ({@code jar:file:///lib/x.jar!/a/b.txt}). So here {@code /a/b.txt} is the resource
 * {@code a/b.txt}, found by the class loader that loaded VFS; "/" is an empty folder (the class
 * path can't be listed, as an http site can't), and a folder lists the first class path entry
 * that has it (docs/DESIGN.md, 14.4).
 */
public class VfsResFileSourceFactory extends VfsFileSourceFactory {

	private static final long serialVersionUID = 1L;

	public static final String FACTORY_ID = TYPE_ID_PREFIX + "res";

	/** What a resource can do: read, list, the type and the time. Never write. */
	private static final Set<Capability> CAPABILITIES = EnumSet.of(Capability.READ_CONTENT, Capability.GET_TYPE,
			Capability.LIST_CHILDREN, Capability.GET_LAST_MODIFIED, Capability.URI);

	@Override
	public String getTypeId() {
		return FACTORY_ID;
	}

	/** {@code res:/}: the class path, for the URL and to tell file systems apart (there is one). */
	@Override
	protected String rootUri() {
		return "res:/";
	}

	@Override
	protected void configure(FileSystemOptions options) {
		// nothing to set
	}

	/** "/": an empty folder of this factory's own (its manager's memory, never written). */
	@Override
	protected FileObject resolveRoot(StandardFileSystemManager m, FileSystemOptions options) throws IOException {
		return m.resolveFile("ram:///");
	}

	/**
	 * The resource PATH names (without its leading "/"), where the class path has it; a missing one
	 * is a file under the empty root, which doesn't exist.
	 */
	@Override
	protected FileObject resolve(FileObject root, String path) throws IOException {
		if( "/".equals(path) ) {
			return root;
		}
		try {
			return root.getFileSystem().getFileSystemManager().resolveFile("res:" + path.substring(1));
		} catch (FileSystemException e) {
			// VFS's res throws for a resource the class path hasn't got
			return root.resolveFile(path.substring(1));
		}
	}

	/** A resource can be read and listed, never written: VFS's answer would be its jar's or folder's. */
	@Override
	protected boolean hasCapability(Capability c) throws IOException {
		root();
		return CAPABILITIES.contains(c);
	}

	/** {@code vfsres:/}. */
	@Override
	public String getURL() {
		return getTypeId() + ":/";
	}

	@Override
	public List<ConnectionSetting> getConnectionSettings() {
		return List.of();
	}

	@Override
	public Properties getConnectProperties() {
		return new Properties();
	}

	@Override
	public void setConnectionProperties(Properties p) {
		// no settings
	}
}

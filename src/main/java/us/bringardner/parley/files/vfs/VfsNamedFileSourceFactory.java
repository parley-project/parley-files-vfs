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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;

import us.bringardner.parley.files.ConnectionSetting;

/**
 * The base of the schemes whose file system lives inside the VFS manager (vfsram: memory; vfstmp:
 * a temporary folder VFS makes). Factories of the same type with the same {@value #PROP_NAME}, and
 * their thread-safe copies, share one manager and so one file system, which lasts until the last
 * of them disconnects (docs/DESIGN.md, 14.2). Nothing to log in to.
 */
public abstract class VfsNamedFileSourceFactory extends VfsFileSourceFactory {

	private static final long serialVersionUID = 1L;

	/** Which file system: factories with the same name share it. */
	public static final String PROP_NAME = "name";
	public static final String DEFAULT_NAME = "default";

	private volatile String name = DEFAULT_NAME;

	public String getName() {
		return name;
	}

	/** @param name the file system's name; empty or null means {@value #DEFAULT_NAME} */
	public void setName(String name) {
		this.name = name == null || name.trim().isEmpty() ? DEFAULT_NAME : name.trim();
	}

	/** The key the manager is shared under: the type and the name. */
	String sharedKey() {
		return getTypeId() + ":" + name;
	}

	@Override
	protected StandardFileSystemManager openManager() throws IOException {
		return SharedManagers.acquire(sharedKey(), super::openManager);
	}

	@Override
	protected void closeManager(StandardFileSystemManager m) {
		SharedManagers.release(sharedKey(), m);
	}

	/** {@code ram:///}, {@code tmp:///}: the name isn't in VFS's URI (each name has its own manager). */
	@Override
	protected String rootUri() {
		return vfsScheme() + ":///";
	}

	@Override
	protected void configure(FileSystemOptions options) throws IOException {
		// nothing to set
	}

	/** The root as the scheme gives it, made if it isn't there yet (VFS's tmp makes it on first use). */
	@Override
	protected void prepareRoot(FileObject root) throws IOException {
		if( !root.exists() ) {
			root.createFolder();
		}
	}

	@Override
	protected String fileSystemKey() {
		return rootUri() + "#" + name;
	}

	@Override
	protected Map<String, String> urlSettings() {
		return DEFAULT_NAME.equals(name) ? Collections.emptyMap() : Collections.singletonMap(PROP_NAME, name);
	}

	/** {@code vfsram:///}, with {@code ?name=...} for a name other than the default. */
	@Override
	public String getURL() {
		return getTypeId() + ":///" + (DEFAULT_NAME.equals(name) ? "" : "?" + PROP_NAME + "=" + encode(name));
	}

	/** A memory or temporary file system: the files are this JVM's own, nothing else orders them. */
	@Override
	public List<ConnectionSetting> getConnectionSettings() {
		return List.of(ConnectionSetting.text(PROP_NAME, "Name").withDefault(DEFAULT_NAME)
				.withDescription("Factories with the same name share the file system; it lasts until the last of them disconnects."));
	}

	@Override
	public Properties getConnectProperties() {
		Properties ret = new Properties();
		ret.setProperty(PROP_NAME, name);
		return ret;
	}

	@Override
	public void setConnectionProperties(Properties p) {
		if( p.containsKey(PROP_NAME) ) {
			setName(p.getProperty(PROP_NAME));
		}
	}
}

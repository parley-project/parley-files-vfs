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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import us.bringardner.parley.core.LogLevel;
import us.bringardner.parley.files.test.FileSourceTestSupport;

/**
 * The "server" for a scheme with no server (vfsfile, vfstmp, vfsram), so the tests every scheme
 * shares run against them: vfsfile gets a new local folder of its own as its root, vfstmp and
 * vfsram a name of their own, so no two test classes share a file system.
 */
final class VfsLocalTestServer implements VfsTestServer {

	enum Kind {
		FILE, TMP, RAM
	}

	private static final AtomicInteger COUNT = new AtomicInteger();

	private final Kind kind;
	private final Path home;
	private final String name;
	private volatile boolean running;

	private VfsLocalTestServer(Kind kind) throws IOException {
		this.kind = kind;
		int n = COUNT.incrementAndGet();
		name = "test-" + n;
		if( kind == Kind.FILE ) {
			home = Paths.get(FileSourceTestSupport.scratchDir("vfs-file-" + n));
			Files.createDirectories(home);
		} else {
			home = null;
		}
	}

	static VfsLocalTestServer started(Kind kind) throws IOException {
		VfsLocalTestServer s = new VfsLocalTestServer(kind);
		s.start();
		return s;
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public void start() {
		running = true;
	}

	@Override
	public void stop() {
		running = false;
	}

	@Override
	public String getName() {
		return "vfs" + kind.name().toLowerCase() + " (no server)";
	}

	/** vfsfile's root; null for vfstmp and vfsram, whose files are VFS's own. */
	@Override
	public Path home() {
		return home;
	}

	/** The settings that make a factory of this kind use this "server"'s file system. */
	Properties properties() {
		Properties p = new Properties();
		if( kind == Kind.FILE ) {
			p.setProperty(VfsFileFileSourceFactory.PROP_ROOT, home.toString());
		} else {
			p.setProperty(VfsNamedFileSourceFactory.PROP_NAME, name);
		}
		return p;
	}

	VfsFileSourceFactory newFactory() {
		switch (kind) {
		case FILE:
			return new VfsFileFileSourceFactory();
		case TMP:
			return new VfsTmpFileSourceFactory();
		default:
			return new VfsRamFileSourceFactory();
		}
	}

	@Override
	public VfsFileSourceFactory connect() throws IOException {
		VfsFileSourceFactory f = newFactory();
		f.setLogLevel(LogLevel.ERROR);
		f.setConnectionProperties(properties());
		assertTrue(f.connect(), "can't connect " + getName());
		return f;
	}
}

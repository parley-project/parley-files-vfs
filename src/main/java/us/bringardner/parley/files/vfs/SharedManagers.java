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
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.vfs2.impl.StandardFileSystemManager;

/**
 * VFS managers shared by name, for the schemes whose file system lives in the manager itself
 * (vfsram's memory, vfstmp's temporary folder): factories with the same type and name, and their
 * thread-safe copies, use one manager, so they see the same files; it is closed, and its files
 * with it, when the last of them disconnects (docs/DESIGN.md, 14.2).
 */
final class SharedManagers {

	private static final class Entry {
		final StandardFileSystemManager manager;
		int users;

		Entry(StandardFileSystemManager manager) {
			this.manager = manager;
		}
	}

	/** Opens a manager when none is shared under a key yet. */
	interface Opener {
		StandardFileSystemManager open() throws IOException;
	}

	private static final Map<String, Entry> BY_KEY = new HashMap<>();

	private SharedManagers() {
	}

	/**
	 * The manager shared under KEY, opened with OPENER if there is none; each call must be matched by
	 * one {@link #release(String, StandardFileSystemManager)}.
	 */
	static synchronized StandardFileSystemManager acquire(String key, Opener opener) throws IOException {
		Entry e = BY_KEY.get(key);
		if( e == null ) {
			e = new Entry(opener.open());
			BY_KEY.put(key, e);
		}
		e.users++;
		return e.manager;
	}

	/** Gives back a manager {@link #acquire(String, Opener)} gave; the last user's release closes it. */
	static void release(String key, StandardFileSystemManager m) {
		StandardFileSystemManager toClose = null;
		synchronized (SharedManagers.class) {
			Entry e = BY_KEY.get(key);
			if( e == null || e.manager != m ) {
				// not shared (or already closed): close it as an unshared one would be
				toClose = m;
			} else if( --e.users <= 0 ) {
				BY_KEY.remove(key);
				toClose = m;
			}
		}
		if( toClose != null ) {
			toClose.close();
		}
	}

	/** @return how many factories use the manager shared under KEY (0 if none), for tests */
	static synchronized int users(String key) {
		Entry e = BY_KEY.get(key);
		return e == null ? 0 : e.users;
	}
}

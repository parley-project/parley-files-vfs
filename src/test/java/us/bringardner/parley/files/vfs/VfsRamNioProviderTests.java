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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.test.AbstractNioProviderTests;

/** java.nio.file.Files over vfsram (the shared NIO suite). */
public class VfsRamNioProviderTests extends AbstractNioProviderTests {

	@BeforeAll
	public static void setUpBeforeAll() throws IOException {
		localTestFileDirPath = testFilesDir(VfsRamNioProviderTests.class);
		localCacheDirPath = scratchDir("VfsRamNioProviderTests");
		remoteTestFileDirPath = "/TestFiles";
		VfsLocalTestServer s = VfsLocalTestServer.started(VfsLocalTestServer.Kind.RAM);
		server = s;
		factory = s.connect();
	}

	/** VFS's ram stores what is written when the stream closes. */
	@Override
	protected boolean forcedWritesAreVisibleBeforeClose() {
		return false;
	}

	/** Sets permission bits, which VFS's ram hasn't got (docs/DESIGN.md 14.3). */
	@Override
	@Test
	public void testFileAttributes() throws Exception {
		Assumptions.abort("VFS's ram has no permission bits (DESIGN 14.3)");
	}

	/** Creates a directory with rwxr-x---, which VFS's ram can't set. */
	@Override
	@Test
	public void testCreateDir() throws Exception {
		Assumptions.abort("VFS's ram has no permission bits (DESIGN 14.3)");
	}
}

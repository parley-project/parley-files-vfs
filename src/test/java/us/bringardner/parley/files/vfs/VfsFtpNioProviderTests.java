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

/** java.nio.file.Files over vfsftp (the shared NIO suite), on a Parley FTP server of its own. */
public class VfsFtpNioProviderTests extends AbstractNioProviderTests {

	@BeforeAll
	public static void setUpBeforeAll() throws IOException {
		localTestFileDirPath = testFilesDir(VfsFtpNioProviderTests.class);
		localCacheDirPath = scratchDir("VfsFtpNioCache");
		remoteTestFileDirPath = "/TestFiles";
		VfsFtpTestServer s = VfsFtpTestServer.started();
		server = s;
		factory = s.connect();
	}

	/** An FTP upload is stored when it ends (STOR), as parley-files-ftp's NIO tests say too. */
	@Override
	protected boolean forcedWritesAreVisibleBeforeClose() {
		return false;
	}

	/** Sets permission bits, which VFS's ftp can't (docs/DESIGN.md 5.3 and 10). */
	@Override
	@Test
	public void testFileAttributes() throws Exception {
		Assumptions.abort("VFS's ftp can't set permission bits (DESIGN 5.3, 10)");
	}

	/** Creates a directory with rwxr-x---, which VFS's ftp can't set. */
	@Override
	@Test
	public void testCreateDir() throws Exception {
		Assumptions.abort("VFS's ftp can't set permission bits (DESIGN 5.3, 10)");
	}
}

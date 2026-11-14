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

/** java.nio.file.Files over vfssftp (the shared NIO suite), on a MINA SFTP server of its own. */
public class VfsSftpNioProviderTests extends AbstractNioProviderTests {

	@BeforeAll
	public static void setUpBeforeAll() throws IOException {
		localTestFileDirPath = testFilesDir(VfsSftpNioProviderTests.class);
		localCacheDirPath = scratchDir("VfsSftpNioCache");
		remoteTestFileDirPath = "/TestFiles";
		VfsSftpTestServer s = VfsSftpTestServer.started();
		server = s;
		factory = s.connect();
	}

	/**
	 * Reads the file in another thread (assertTimeoutPreemptively's) and closes it in this one. A
	 * JSch stream read by a thread that has since ended is closed under its reader: JSch's pipe sees
	 * the reading thread is dead ("Read end dead") when the next data arrives, and drops the channel,
	 * so the close here fails with "Pipe closed" (README, "What VFS does", item 10). The transfer
	 * itself works.
	 */
	@Override
	@Test
	public void transferToStopsWhenTheTargetTakesNothing() throws Exception {
		Assumptions.abort("JSch drops a stream whose reading thread has ended (README, What VFS does, item 10)");
	}

	/**
	 * Sets group and other permission bits alone (rwxr-x---), which VFS can't (docs/DESIGN.md 5.3,
	 * Q2: false for anything VFS doesn't give). VfsSftpPermissionBitsTest checks what VFS can do.
	 */
	@Override
	@Test
	public void testFileAttributes() throws Exception {
		Assumptions.abort("VFS can't set group or other permission bits alone (DESIGN 5.3)");
	}

	/** Creates a directory with rwxr-x--- (group and other bits alone), which VFS can't set. */
	@Override
	@Test
	public void testCreateDir() throws Exception {
		Assumptions.abort("VFS can't set group or other permission bits alone (DESIGN 5.3)");
	}
}

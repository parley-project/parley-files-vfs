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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.test.AbstractTestClass;

/**
 * The shared FileSource suite against vfssftp, on a MINA SFTP server of its own (also 17.2's
 * correctness run for VFS's sftp).
 */
public class TestVfsSftp extends AbstractTestClass {

	@BeforeAll
	public static void setUpBeforeAll() throws IOException {
		localTestFileDirPath = testFilesDir(TestVfsSftp.class);
		localCacheDirPath = scratchDir("VfsSftpCache");
		remoteTestFileDirPath = "/TestFiles";
		VfsSftpTestServer s = VfsSftpTestServer.started();
		server = s;
		factory = s.connect();
	}

	/**
	 * VFS can set the owner's permissions, or everyone's at once, but not the group's or other
	 * users' alone (docs/DESIGN.md, 5.3): the owner's bits must toggle, and the group's and
	 * others' setters must say false and change nothing.
	 */
	@Override
	@Test
	@Order(3)
	@DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX permission bits: the test server serves a Windows directory")
	public void testPermissions() throws IOException {
		FileSource dir = factory.createFileSource(remoteTestFileDirPath);
		if( !dir.exists() ) {
			assertTrue(dir.mkdirs(), "can't create " + dir);
		}
		FileSource file = dir.getChild("TestPermissions.txt");
		try (OutputStream out = file.getOutputStream()) {
			out.write("Put some data in the file".getBytes());
		}
		try {
			for (Permissions p : Permissions.values()) {
				switch (p) {
				case OwnerRead:
				case OwnerExecute:
					changeAndValidatePermission(p, file);
					break;
				case OwnerWrite:
					// turned off, it couldn't be turned back on
					break;
				default:
					boolean before = getPermission(p, file);
					assertFalse(setPermission(p, file, !before), p + ": VFS can't set it alone");
					assertEquals(before, getPermission(p, file), p + " must not change");
				}
			}
		} finally {
			assertTrue(file.delete(), "can't delete " + file);
		}
	}
}

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

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.test.AbstractTestClass;

/** The shared FileSource suite against vfsram. */
public class TestVfsRam extends AbstractTestClass {

	@BeforeAll
	public static void setUpBeforeAll() throws IOException {
		localTestFileDirPath = testFilesDir(TestVfsRam.class);
		localCacheDirPath = scratchDir("TestVfsRam");
		remoteTestFileDirPath = "/TestFiles";
		VfsLocalTestServer s = VfsLocalTestServer.started(VfsLocalTestServer.Kind.RAM);
		server = s;
		factory = s.connect();
	}

	/**
	 * VFS's ram keeps no permission bits: every setter must say false and change nothing
	 * (docs/DESIGN.md, 14.3).
	 */
	@Override
	@Test
	@Order(3)
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
				boolean before = getPermission(p, file);
				assertFalse(setPermission(p, file, !before), p + ": VFS's ram can't set it");
				assertEquals(before, getPermission(p, file), p + " must not change");
			}
		} finally {
			assertTrue(file.delete(), "can't delete " + file);
		}
	}
}

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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import us.bringardner.parley.files.FileSource;

/** The sftp permission hook (AbstractVfsPermissionBitsTest), and setting bits, which sftp can. */
public class VfsSftpPermissionBitsTest extends AbstractVfsPermissionBitsTest {

	@BeforeAll
	static void start() throws IOException {
		startOn(VfsSftpTestServer.started());
	}

	/** setReadable(b, false) is everyone's; setReadable(b) and setOwnerReadable(b) the owner's. */
	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void ownerOnlyAndEveryone(boolean ownerOnly) throws IOException {
		FileSource f = factory.createFileSource("/everyone-" + ownerOnly + ".txt");
		try (OutputStream out = f.getOutputStream()) {
			out.write('x');
		}
		Path local = server.home().resolve(f.getName());
		Files.setPosixFilePermissions(local, PosixFilePermissions.fromString("rw-------"));
		try {
			assertTrue(f.setReadable(true, ownerOnly));
			assertTrue(f.setExecutable(true, ownerOnly));
			assertEquals(ownerOnly ? "rwx------" : "rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(local)));
			assertTrue(f.setOwnerExecutable(false));
			assertEquals(ownerOnly ? "rw-------" : "rw-r-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(local)));
		} finally {
			Files.setPosixFilePermissions(local, PosixFilePermissions.fromString("rw-------"));
			assertTrue(f.delete());
		}
	}

	/** A file with MODE, its local path; the caller deletes it. */
	private static FileSource file(String name, String mode) throws IOException {
		FileSource f = factory.createFileSource("/" + name);
		try (OutputStream out = f.getOutputStream()) {
			out.write("some data".getBytes());
		}
		Files.setPosixFilePermissions(server.home().resolve(name), PosixFilePermissions.fromString(mode));
		f.refresh();
		return f;
	}

	private static String mode(FileSource f) throws IOException {
		return PosixFilePermissions.toString(Files.getPosixFilePermissions(server.home().resolve(f.getName())));
	}

	/**
	 * Only the mode is sent (DESIGN 10.5): VFS's own setter sent the whole stat back, and the MINA
	 * server refuses its times on a file the owner can't read, as an ordinary user.
	 */
	@Test
	void aFileItsOwnerCantReadCanBeChanged() throws IOException {
		FileSource f = file("unreadable.txt", "-w-------");
		try {
			assertTrue(f.setReadable(true));
			assertEquals("rw-------", mode(f));
			assertTrue(f.canOwnerRead(), "VFS's cached stat is refreshed");
		} finally {
			Files.setPosixFilePermissions(server.home().resolve(f.getName()), PosixFilePermissions.fromString("rw-------"));
			assertTrue(f.delete());
		}
	}

	/** The change starts from the server's mode, not a cached one, so a change made elsewhere stays. */
	@Test
	void aChangeMadeElsewhereIsKept() throws IOException {
		FileSource f = file("elsewhere.txt", "rw-------");
		try {
			assertEquals("rw-------", "" + (f.canOwnerRead() ? 'r' : '-') + (f.canOwnerWrite() ? 'w' : '-') + "-------");
			// changed on the server behind this FileSource's back
			Files.setPosixFilePermissions(server.home().resolve(f.getName()), PosixFilePermissions.fromString("rw-r--r--"));
			assertTrue(f.setExecutable(true));
			assertEquals("rwxr--r--", mode(f));
		} finally {
			assertTrue(f.delete());
		}
	}
}

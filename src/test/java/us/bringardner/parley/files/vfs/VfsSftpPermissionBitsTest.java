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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import us.bringardner.parley.files.FileSource;

/**
 * The sftp permission hook (it reads VFS internals by reflection): the nine bits must be the ones
 * the server's file has, set here with chmod on the directory the test server serves. A VFS
 * upgrade that moves what the hook reads fails this.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX permission bits")
public class VfsSftpPermissionBitsTest {

	private static VfsSftpTestServer server;
	private static VfsSftpFileSourceFactory factory;

	@BeforeAll
	static void start() throws IOException {
		server = VfsSftpTestServer.started();
		factory = server.connect();
	}

	@AfterAll
	static void stop() throws IOException {
		try {
			if( factory != null ) {
				factory.disConnect();
			}
		} finally {
			if( server != null ) {
				server.stop();
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "rwxrwxrwx", "r--------", "rw-r-----", "rwx---r-x", "---rw--wx", "r-xr-xr--" })
	void bitsAreTheServers(String mode) throws IOException {
		FileSource f = factory.createFileSource("/bits-" + mode + ".txt");
		try (OutputStream out = f.getOutputStream()) {
			out.write('x');
		}
		Path local = server.home().resolve(f.getName());
		Files.setPosixFilePermissions(local, PosixFilePermissions.fromString(mode));
		try {
			f.refresh();
			String seen = "" + c(f.canOwnerRead(), 'r') + c(f.canOwnerWrite(), 'w') + c(f.canOwnerExecute(), 'x')
					+ c(f.canGroupRead(), 'r') + c(f.canGroupWrite(), 'w') + c(f.canGroupExecute(), 'x')
					+ c(f.canOtherRead(), 'r') + c(f.canOtherWrite(), 'w') + c(f.canOtherExecute(), 'x');
			assertEquals(mode, seen);
		} finally {
			Files.setPosixFilePermissions(local, PosixFilePermissions.fromString("rw-------"));
			assertTrue(f.delete());
		}
	}

	private static char c(boolean b, char ch) {
		return b ? ch : '-';
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
}

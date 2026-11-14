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

/**
 * The ftp permission hook (AbstractVfsPermissionBitsTest): the bits from the server's listing.
 * Parley's FtpServer lists a file's bits as its FileProxy reports them, and for root the OS says
 * every file is writable, so as root the owner's write bit is always on: run as an ordinary user
 * (parley-files-ftp's tests need the same).
 */
public class VfsFtpPermissionBitsTest extends AbstractVfsPermissionBitsTest {

	@BeforeAll
	static void start() throws IOException {
		Assumptions.assumeFalse("root".equals(System.getProperty("user.name")),
				"as root, Parley's FtpServer lists every file as writable by its owner");
		startOn(VfsFtpTestServer.started());
	}
}

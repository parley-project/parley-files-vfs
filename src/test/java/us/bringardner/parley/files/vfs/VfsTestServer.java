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
import java.nio.file.Path;

import us.bringardner.parley.files.test.TestServerController;

/**
 * A test server for one VFS scheme (an SFTP or FTP server started in the test), so the tests every
 * scheme shares (AbstractVfsTrapsTest and the like) run against each.
 */
interface VfsTestServer extends TestServerController {

	/**
	 * @return a factory connected to this server; the caller disconnects it
	 * @throws IOException if it can't connect
	 */
	VfsFileSourceFactory connect() throws IOException;

	/** @return the local directory the server serves as "/" */
	Path home();
}

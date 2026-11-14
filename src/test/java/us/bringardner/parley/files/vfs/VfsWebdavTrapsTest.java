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

/** {@link AbstractVfsTrapsTest} for vfswebdav, on Tomcat's WebdavServlet. */
public class VfsWebdavTrapsTest extends AbstractVfsTrapsTest {

	@BeforeAll
	static void start() throws IOException {
		startOn(VfsWebdavTestServer.started());
	}

	/** Appends, which vfswebdav refuses. */
	@Override
	@Test
	void appendAddsToTheEnd() {
		Assumptions.abort("VFS's WebDAV can't append, and vfswebdav refuses it (DESIGN 17.4)");
	}

	/** Append is refused, saying so. */
	@Test
	void appendIsRefused() throws IOException {
		us.bringardner.parley.files.FileSource f = dir.getChild("append.txt");
		write(f, "x");
		IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> f.getOutputStream(true).close());
		org.junit.jupiter.api.Assertions.assertEquals("vfswebdav can't append to a file (/traps/append.txt)", e.getMessage());
		org.junit.jupiter.api.Assertions.assertTrue(f.delete());
	}
}

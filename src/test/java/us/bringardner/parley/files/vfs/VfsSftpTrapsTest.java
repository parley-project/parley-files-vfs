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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;

/** The VFS traps (AbstractVfsTrapsTest) over vfssftp, and what sftp adds: times can be set. */
public class VfsSftpTrapsTest extends AbstractVfsTrapsTest {

	@BeforeAll
	static void start() throws IOException {
		startOn(VfsSftpTestServer.started());
	}

	@Test
	void timesCanBeSet() throws IOException {
		FileSource f = dir.getChild("times.txt");
		write(f, "t");
		long when = 1_600_000_000_000L;
		assertTrue(f.setLastModifiedTime(when));
		f.refresh();
		assertEquals(when, f.lastModified(), "SFTP keeps whole seconds");
		assertFalse(dir.getChild("no-such-file").setLastModifiedTime(when));
		assertTrue(f.delete());
	}
}

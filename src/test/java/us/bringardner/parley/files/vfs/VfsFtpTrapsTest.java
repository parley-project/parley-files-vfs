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
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;

/**
 * The VFS traps (AbstractVfsTrapsTest) over vfsftp, and what ftp adds: times can't be set (VFS's
 * ftp has no way to), but are read to the second (MDTM, which VFS uses only when told).
 */
public class VfsFtpTrapsTest extends AbstractVfsTrapsTest {

	@BeforeAll
	static void start() throws IOException {
		startOn(VfsFtpTestServer.started());
	}

	@Test
	void timesCantBeSetButAreReadToTheSecond() throws IOException {
		FileSource f = dir.getChild("times.txt");
		write(f, "t");
		long when = 1_600_000_123_000L;
		assertFalse(f.setLastModifiedTime(when), "VFS's ftp can't set a time");
		Files.setLastModifiedTime(server.home().resolve("traps").resolve("times.txt"), FileTime.fromMillis(when));
		f.refresh();
		assertEquals(when, f.lastModified(), "MDTM gives the seconds the listing leaves out");
		assertTrue(f.delete());
	}
}

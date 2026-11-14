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

import org.junit.jupiter.api.BeforeAll;

import us.bringardner.parley.files.test.AbstractRandomAccessStreamTests;

/** The shared random-access suite, which writes at positions (vfstmp can) against vfstmp. */
public class VfsTmpRandomAccessStreamTests extends AbstractRandomAccessStreamTests {

	@BeforeAll
	public static void setUpBeforeAll() throws IOException {
		localTestFileDirPath = testFilesDir(VfsTmpRandomAccessStreamTests.class);
		localCacheDirPath = scratchDir("VfsTmpRandomAccessStreamTests");
		remoteTestFileDirPath = "/TestFiles";
		VfsLocalTestServer s = VfsLocalTestServer.started(VfsLocalTestServer.Kind.TMP);
		server = s;
		factory = s.connect();
	}
}

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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.lock.LockScope;
import us.bringardner.parley.files.test.AbstractLockTests;

/** The shared lock suite over vfssftp: FileSource's in-process locks (scope PROCESS). */
public class VfsSftpLockTest extends AbstractLockTests {

	private static final AtomicInteger DIRS = new AtomicInteger();
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

	@Override
	protected FileSource newLockDirectory() throws Exception {
		FileSource d = factory.createFileSource("/locks" + System.nanoTime() + "-" + DIRS.incrementAndGet());
		assertTrue(d.mkdir(), "can't create " + d);
		return d;
	}

	@Test
	public void vfsLocksStayInThisJvm() {
		assertEquals(LockScope.PROCESS, dir.getLockScope());
	}
}

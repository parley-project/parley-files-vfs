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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.IRandomAccessStream;
import us.bringardner.parley.files.ISeekableInputStream;

/**
 * Random access over vfssftp: reading at a position works ("r", seekable streams, a stream from a
 * position); writing at a position doesn't (VFS's sftp has no random access writing), so "rw" is
 * refused. The shared AbstractRandomAccessStreamTests writes, so this stands in for it.
 */
public class VfsSftpRandomAccessTest {

	/** More than two chunks of VfsRandomAccessIoController, not a whole number of them. */
	private static final byte[] DATA = new byte[VfsRandomAccessIoController.CHUNK_SIZE * 2 + 12_345];

	private static VfsSftpTestServer server;
	private static VfsSftpFileSourceFactory factory;
	private static FileSource file;

	@BeforeAll
	static void start() throws IOException {
		new Random(42).nextBytes(DATA);
		server = VfsSftpTestServer.started();
		factory = server.connect();
		file = factory.createFileSource("/random.bin");
		try (OutputStream out = file.getOutputStream()) {
			out.write(DATA);
		}
	}

	@AfterAll
	static void stop() throws IOException {
		try {
			if( factory != null ) {
				if( file != null ) {
					file.delete();
				}
				factory.disConnect();
			}
		} finally {
			if( server != null ) {
				server.stop();
			}
		}
	}

	private static byte[] slice(long from, int len) {
		return Arrays.copyOfRange(DATA, (int) from, (int) Math.min(DATA.length, from + len));
	}

	@Test
	void readOnlyRandomAccessReadsAnywhere() throws Exception {
		try (IRandomAccessStream ram = file.getRandomAccessStream("r")) {
			assertEquals(DATA.length, ram.length());
			Random r = new Random(7);
			for (int i = 0; i < 50; i++) {
				long pos = r.nextInt(DATA.length);
				int len = 1 + r.nextInt(3000);
				ram.seek(pos);
				byte[] buf = new byte[len];
				int got = 0;
				while( got < len ) {
					int n = ram.read(buf, got, len - got);
					if( n < 0 ) {
						break;
					}
					got += n;
				}
				assertArrayEquals(slice(pos, len), Arrays.copyOf(buf, got), "at " + pos);
			}
			ram.seek(DATA.length);
			assertEquals(-1, ram.read(), "at the end");
		}
	}

	@Test
	void writingAtAPositionIsRefused() {
		IOException e = assertThrows(IOException.class, () -> file.getRandomAccessStream("rw").close());
		assertTrue(e.getMessage().contains("write at a position"), e.getMessage());
	}

	@Test
	void seekableStreamReadsAnywhere() throws IOException {
		ISeekableInputStream in = file.getSeekableInputStream();
		try {
			assertEquals(DATA.length, in.length());
			for (long pos : new long[] { 0, 1, 65_535, 65_536, 100_000, DATA.length - 10 }) {
				in.seek(pos);
				byte[] buf = new byte[20];
				int n = in.read(buf, 0, buf.length);
				assertArrayEquals(slice(pos, 20), Arrays.copyOf(buf, n), "at " + pos);
				assertEquals(pos + n, in.getFilePointer());
			}
			in.seek(DATA.length + 5);
			assertEquals(-1, in.read());
			assertThrows(IOException.class, () -> in.seek(-1));
		} finally {
			in.close();
		}
		assertThrows(IOException.class, in::read, "closed");
	}

	@Test
	void streamFromAPosition() throws IOException {
		for (long pos : new long[] { 0, 10, 70_000, DATA.length - 1, DATA.length, DATA.length + 100 }) {
			try (InputStream in = file.getInputStream(pos)) {
				assertArrayEquals(slice(Math.min(pos, DATA.length), DATA.length), in.readAllBytes(), "from " + pos);
			}
		}
	}

	@Test
	void headAndTail() throws IOException {
		assertArrayEquals(slice(0, 100), file.head(100));
		assertArrayEquals(slice(DATA.length - 100, 100), file.tail(100));
	}
}

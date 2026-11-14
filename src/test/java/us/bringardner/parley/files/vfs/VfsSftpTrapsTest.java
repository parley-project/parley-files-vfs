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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceCapabilities;
import us.bringardner.parley.files.lock.LockScope;

/**
 * The places where VFS, passed straight through, would break the FileSource contract
 * (docs/DESIGN.md, 2.2 and 5.4): each must behave as java.io.File does.
 */
public class VfsSftpTrapsTest {

	private static VfsSftpTestServer server;
	private static VfsSftpFileSourceFactory factory;
	private static FileSource dir;

	@BeforeAll
	static void start() throws IOException {
		server = VfsSftpTestServer.started();
		factory = server.connect();
		dir = factory.createFileSource("/traps");
		assertTrue(dir.mkdirs(), "can't create " + dir);
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

	private static void write(FileSource f, String text) throws IOException {
		try (OutputStream out = f.getOutputStream()) {
			out.write(text.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static String read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** VFS's moveTo deletes what is at the destination first; renameTo must refuse instead. */
	@Test
	void renameOntoAnExistingFileReturnsFalseAndKeepsBoth() throws IOException {
		FileSource a = dir.getChild("rename-a.txt");
		FileSource b = dir.getChild("rename-b.txt");
		write(a, "a");
		write(b, "b");
		assertFalse(a.renameTo(b));
		assertEquals("a", read(a));
		assertEquals("b", read(b));
		assertTrue(a.delete());
		assertTrue(b.delete());
	}

	/** Above all, a directory at the destination must not be deleted with everything in it. */
	@Test
	void renameOntoAnExistingDirectoryLeavesItAlone() throws IOException {
		FileSource a = dir.getChild("rename-file.txt");
		FileSource d = dir.getChild("rename-dir");
		write(a, "a");
		assertTrue(d.mkdir());
		write(d.getChild("inside.txt"), "inside");
		assertFalse(a.renameTo(d));
		assertEquals("inside", read(d.getChild("inside.txt")));
		assertTrue(d.getChild("inside.txt").delete());
		assertTrue(d.delete());
		assertTrue(a.delete());
	}

	@Test
	void renameReplacingAFileReplacesIt() throws IOException {
		FileSource a = dir.getChild("replace-a.txt");
		FileSource b = dir.getChild("replace-b.txt");
		write(a, "new");
		write(b, "old");
		assertTrue(a.renameTo(b, true));
		assertFalse(a.exists());
		assertEquals("new", read(b));
		assertTrue(b.delete());
	}

	@Test
	void renameIntoAMissingDirectoryReturnsFalse() throws IOException {
		FileSource a = dir.getChild("rename-nowhere.txt");
		write(a, "a");
		assertFalse(a.renameTo(dir.getChild("missing/rename-nowhere.txt")));
		assertFalse(dir.getChild("missing").exists(), "no parent made");
		assertTrue(a.delete());
	}

	/** VFS's create-folder is mkdirs and quietly returns if the folder is there. */
	@Test
	void mkdirIsOneLevelAndFalseIfThere() throws IOException {
		FileSource deep = dir.getChild("mk/one/two");
		assertFalse(deep.mkdir(), "mkdir without its parent");
		assertFalse(dir.getChild("mk").exists(), "no parent made");
		FileSource one = dir.getChild("mk1");
		assertTrue(one.mkdir());
		assertFalse(one.mkdir(), "mkdir of an existing directory");
		assertFalse(one.mkdirs(), "mkdirs of an existing directory");
		assertTrue(one.delete());
	}

	/** VFS's create-file does nothing if the file is there, and makes missing parents. */
	@Test
	void createNewFileOnAnExistingFileReturnsFalseAndKeepsIt() throws IOException {
		FileSource f = dir.getChild("create.txt");
		assertTrue(f.createNewFile());
		assertFalse(f.createNewFile(), "created twice");
		write(f, "content");
		assertFalse(f.createNewFile());
		assertEquals("content", read(f), "not truncated");
		assertThrows(IOException.class, () -> dir.getChild("nowhere/create.txt").createNewFile(),
				"no parent: an IOException, as java.io.File");
		assertFalse(dir.getChild("nowhere").exists(), "no parent made");
		assertTrue(f.delete());
	}

	/** VFS's output stream makes missing parents; FileOutputStream doesn't. */
	@Test
	void writingIntoAMissingDirectoryFails() throws IOException {
		assertThrows(FileNotFoundException.class, () -> dir.getChild("absent/out.txt").getOutputStream());
		assertFalse(dir.getChild("absent").exists());
		assertThrows(FileNotFoundException.class, () -> dir.getOutputStream(), "a directory");
		assertThrows(FileNotFoundException.class, () -> dir.getChild("absent.txt").getInputStream());
		assertThrows(FileNotFoundException.class, () -> dir.getInputStream(), "a directory");
	}

	@Test
	void deleteIsLikeJavaIoFile() throws IOException {
		FileSource d = dir.getChild("del");
		assertTrue(d.mkdir());
		write(d.getChild("x.txt"), "x");
		assertFalse(d.delete(), "a directory with something in it");
		assertTrue(d.exists());
		assertTrue(d.getChild("x.txt").delete());
		assertTrue(d.delete());
		assertFalse(d.delete(), "already gone");
	}

	@Test
	void appendAddsToTheEnd() throws IOException {
		FileSource f = dir.getChild("append.txt");
		write(f, "one");
		try (OutputStream out = f.getOutputStream(true)) {
			out.write("two".getBytes(StandardCharsets.UTF_8));
		}
		assertEquals("onetwo", read(f));
		assertArrayEquals("one".getBytes(StandardCharsets.UTF_8), f.head(3));
		assertTrue(f.delete());
	}

	@Test
	void listingIsNullForAFileAndNamesTheChildren() throws IOException {
		FileSource d = dir.getChild("list");
		assertTrue(d.mkdir());
		write(d.getChild("a.txt"), "a");
		assertTrue(d.getChild("b").mkdir());
		assertNull(d.getChild("a.txt").listFiles(), "a file has no children");
		String[] names = d.list();
		java.util.Arrays.sort(names);
		assertArrayEquals(new String[] { "a.txt", "b" }, names);
		assertTrue(d.getChild("a.txt").delete());
		assertTrue(d.getChild("b").delete());
		assertTrue(d.delete());
	}

	@Test
	void pathsAreLexical() throws IOException {
		FileSource f = dir.getChild("x/../y/./z.txt");
		assertEquals("/traps/y/z.txt", f.getAbsolutePath());
		assertEquals("z.txt", f.getName());
		assertEquals("/traps/y", f.getParent());
		assertEquals("/", factory.createFileSource("/..").getAbsolutePath());
		assertNull(factory.createFileSource("/").getParent());
		assertEquals(dir.getChild("a"), dir.getChild("/a"), "getChild is relative even with a leading /");
	}

	@Test
	void capabilitiesClaimNothingAtomic() {
		FileSourceCapabilities c = dir.getCapabilities();
		assertEquals(LockScope.PROCESS, c.lockScope());
		assertFalse(c.atomicCreateNewFile());
		assertFalse(c.atomicMkdir());
		assertFalse(c.atomicReplace());
		assertFalse(c.durableSync());
		assertTrue(c.orderedOperations());
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

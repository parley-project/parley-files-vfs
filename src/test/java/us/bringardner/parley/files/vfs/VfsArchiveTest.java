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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import us.bringardner.parley.core.LogLevel;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.test.FileSourceTestSupport;

/**
 * vfszip, vfsjar, vfstar, vfstgz and vfstbz2 (docs/DESIGN.md, 15): each reads the same tree from
 * an archive written in its format, and refuses every write; the archive setting (a local path or a
 * VFS URI), the URLs (README A16) and an archive inside an archive. Every factory is disconnected
 * after its test.
 */
public class VfsArchiveTest {

	private static Path dir;

	private final List<FileSourceFactory> made = new ArrayList<>();

	@BeforeAll
	static void archives() throws IOException {
		dir = Paths.get(FileSourceTestSupport.scratchDir("vfs-archives"));
		Files.createDirectories(dir);
		for (String type : new String[] { "zip", "jar", "tar", "tgz", "tbz2" }) {
			TestArchives.write(type, dir.resolve("t." + type));
		}
		// t.zip inside outer.zip
		try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(dir.resolve("outer.zip")))) {
			z.putNextEntry(new ZipEntry("t.zip"));
			z.write(Files.readAllBytes(dir.resolve("t.zip")));
			z.closeEntry();
		}
	}

	@AfterEach
	void disconnectAll() throws IOException {
		for (FileSourceFactory f : made) {
			f.disConnect();
		}
		made.clear();
	}

	static VfsArchiveFileSourceFactory newFactory(String type) {
		VfsArchiveFileSourceFactory f = (VfsArchiveFileSourceFactory) FileSourceFactory.getFileSourceFactory("vfs" + type);
		f.setLogLevel(LogLevel.ERROR);
		return f;
	}

	private VfsArchiveFileSourceFactory connected(String type, String archive) throws IOException {
		VfsArchiveFileSourceFactory f = newFactory(type);
		f.setArchive(archive);
		made.add(f);
		assertTrue(f.connect(), "can't open " + archive);
		return f;
	}

	private static String read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String[] sorted(String[] names) {
		Arrays.sort(names);
		return names;
	}

	@ParameterizedTest
	@ValueSource(strings = { "zip", "jar", "tar", "tgz", "tbz2" })
	void readsTheTree(String type) throws IOException {
		VfsArchiveFileSourceFactory f = connected(type, dir.resolve("t." + type).toString());
		FileSource root = f.createFileSource("/");
		assertTrue(root.isDirectory());
		List<String> names = new ArrayList<>(Arrays.asList(sorted(root.list())));
		// a jar written by JarOutputStream may add META-INF; it isn't part of the tree
		names.remove("META-INF");
		assertEquals(Arrays.asList("a.txt", "dir", "empty"), names, type);
		FileSource a = f.createFileSource("/a.txt");
		assertTrue(a.isFile());
		assertEquals("hello", read(a));
		assertEquals(5L, a.length());
		assertEquals(TestArchives.TIME, a.lastModified(), type + ": the entry's time");
		FileSource b = f.createFileSource("/dir/b.txt");
		assertEquals(TestArchives.TREE.get("dir/b.txt"), read(b));
		assertArrayEquals(new String[] { "b.txt" }, f.createFileSource("/dir").list());
		assertEquals(0, f.createFileSource("/empty").list().length);
		assertFalse(f.createFileSource("/missing.txt").exists());
		assertNull(a.list(), "a file has no listing");
		try (InputStream in = b.getInputStream(6)) {
			assertEquals("file, a little longer", new String(in.readAllBytes(), StandardCharsets.UTF_8), "from a position");
		}
	}

	/** README A14: every write says the file system is read only, and nothing changes. */
	@ParameterizedTest
	@ValueSource(strings = { "zip", "jar", "tar", "tgz", "tbz2" })
	void readOnly(String type) throws IOException {
		Path archive = dir.resolve("t." + type);
		byte[] before = Files.readAllBytes(archive);
		VfsArchiveFileSourceFactory f = connected(type, archive.toString());
		FileSource a = f.createFileSource("/a.txt");
		List<IOException> errors = new ArrayList<>();
		errors.add(assertThrows(IOException.class, () -> a.getOutputStream().close()));
		errors.add(assertThrows(IOException.class, () -> f.createFileSource("/new.txt").createNewFile()));
		errors.add(assertThrows(IOException.class, () -> f.createFileSource("/newdir").mkdir()));
		errors.add(assertThrows(IOException.class, a::delete));
		errors.add(assertThrows(IOException.class, () -> a.renameTo(f.createFileSource("/c.txt"))));
		for (IOException e : errors) {
			assertTrue(e.getMessage().contains("read only"), e.getMessage());
		}
		assertFalse(a.setLastModifiedTime(1000L));
		assertFalse(a.canWrite());
		assertArrayEquals(before, Files.readAllBytes(archive), "the archive is unchanged");
	}

	/** The archive as a local path or as its file: URI is the same file system. */
	@Test
	void archiveIsALocalPathOrAVfsUri() throws IOException {
		Path zip = dir.resolve("t.zip");
		VfsArchiveFileSourceFactory byPath = connected("zip", zip.toString());
		VfsArchiveFileSourceFactory byUri = connected("zip", zip.toUri().toString());
		assertEquals(byPath.archiveUri(), byUri.archiveUri());
		assertTrue(byPath.isSameFileSystem(byUri));
		assertEquals("hello", read(byUri.createFileSource("/a.txt")));
		assertFalse(byPath.isSameFileSystem(connected("zip", dir.resolve("outer.zip").toString())), "another archive");
		VfsArchiveFileSourceFactory tar = newFactory("tar");
		tar.setArchive(zip.toString());
		assertFalse(byPath.isSameFileSystem(tar), "another type");
	}

	@Test
	void anArchiveInsideAnArchive() throws IOException {
		String inner = "zip:" + dir.resolve("outer.zip").toUri() + "!/t.zip";
		VfsArchiveFileSourceFactory f = connected("zip", inner);
		assertEquals("hello", read(f.createFileSource("/a.txt")));
		assertEquals("vfszip:" + inner + "!/", f.getURL());
	}

	@Test
	void aMissingArchiveFailsTheConnect() {
		VfsArchiveFileSourceFactory f = newFactory("zip");
		Path missing = dir.resolve("none.zip");
		f.setArchive(missing.toString());
		made.add(f);
		IOException e = assertThrows(IOException.class, f::connect);
		assertTrue(e.getMessage().contains("none.zip"), e.getMessage());
		assertFalse(f.isConnected());
		VfsArchiveFileSourceFactory none = newFactory("zip");
		made.add(none);
		assertEquals("No archive for vfszip", assertThrows(IOException.class, none::connect).getMessage());
	}

	/** README A16: a nested VFS URL reaches the factory whole, and opens. */
	@ParameterizedTest
	@ValueSource(strings = { "zip", "tgz" })
	void aVfsUrlOpens(String type) throws IOException {
		String archive = dir.resolve("t." + type).toUri().toString();
		FileSource f = FileSourceFactory.getFileSource("vfs" + type + ":" + archive + "!/dir/b.txt");
		made.add(f.getFileSourceFactory());
		assertEquals("/dir/b.txt", f.getAbsolutePath());
		assertEquals(TestArchives.TREE.get("dir/b.txt"), read(f));
		assertEquals(archive, ((VfsArchiveFileSourceFactory) f.getFileSourceFactory()).getArchive());
		FileSource root = FileSourceFactory.getFileSource("vfs" + type + ":" + archive + "!/");
		made.add(root.getFileSourceFactory());
		assertEquals("/", root.getAbsolutePath());
		java.net.URLConnection c = new URL("vfs" + type + ":" + archive + "!/a.txt").openConnection();
		try (InputStream in = c.getInputStream()) {
			assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	@Test
	void urlsAndSettings() throws IOException {
		Path zip = dir.resolve("t.zip");
		VfsArchiveFileSourceFactory f = connected("zip", zip.toString());
		String uri = zip.toAbsolutePath().normalize().toUri().toString();
		assertEquals("vfszip:" + uri + "!/", f.getURL());
		FileSource b = f.createFileSource("/dir/b.txt");
		assertEquals("zip:" + uri + "!/dir/b.txt", ((VfsFileSource) b).getVfsUri());
		String fsUrl = b.toURL().toString();
		assertTrue(fsUrl.startsWith("filesource:/dir/b.txt?sourcetype=vfszip&archive="), fsUrl);
		FileSource again = FileSourceFactory.getFileSource(fsUrl);
		made.add(again.getFileSourceFactory());
		assertEquals(TestArchives.TREE.get("dir/b.txt"), read(again));
		VfsArchiveFileSourceFactory back = newFactory("zip");
		back.setConnectionProperties(f.getConnectProperties());
		assertTrue(f.isSameFileSystem(back));
		assertEquals(1, f.getConnectionSettings().size());
		FileSourceFactory copy = f.createThreadSafeCopy();
		made.add(copy);
		assertTrue(copy.connect());
		assertEquals("hello", read(copy.createFileSource("/a.txt")));
	}

	/** A nested server's password isn't put in URLs. */
	@Test
	void aPasswordInTheArchiveUriStaysOutOfUrls() throws IOException {
		VfsArchiveFileSourceFactory f = newFactory("zip");
		f.setArchive("sftp://tony:secret@host/a.zip");
		assertEquals("vfszip:sftp://tony@host/a.zip!/", f.getURL());
		assertFalse(f.createFileSource("/x").toURL().toString().contains("secret"));
		assertEquals("sftp://tony:secret@host/a.zip", f.archiveUri(), "VFS is given it");
	}

	@Test
	void writingSaysWhatToUseInstead() throws IOException {
		VfsArchiveFileSourceFactory f = connected("zip", dir.resolve("t.zip").toString());
		IOException e = assertThrows(IOException.class, () -> {
			try (OutputStream out = f.createFileSource("/a.txt").getOutputStream()) {
				out.write(1);
			}
		});
		assertTrue(e.getMessage().startsWith("vfszip is read only here"), e.getMessage());
	}
}

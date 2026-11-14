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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.test.FileSourceTestSupport;

/**
 * vfsgz and vfsbz2 (docs/DESIGN.md, 15.3): the one file is read and written, its length counted,
 * other names refused, and a compressed file that isn't there yet is made by writing its file.
 */
public class VfsCompressedTest {

	private static Path dir;

	private final List<FileSourceFactory> made = new ArrayList<>();

	@BeforeAll
	static void scratch() throws IOException {
		dir = Paths.get(FileSourceTestSupport.scratchDir("vfs-compressed"));
		Files.createDirectories(dir);
	}

	@AfterEach
	void disconnectAll() throws IOException {
		for (FileSourceFactory f : made) {
			f.disConnect();
		}
		made.clear();
	}

	private VfsCompressedFileSourceFactory connected(String type, Path archive) throws IOException {
		VfsCompressedFileSourceFactory f = (VfsCompressedFileSourceFactory) VfsArchiveTest.newFactory(type);
		f.setArchive(archive.toString());
		made.add(f);
		assertTrue(f.connect());
		return f;
	}

	private static String read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void write(FileSource f, String text) throws IOException {
		try (OutputStream out = f.getOutputStream()) {
			out.write(text.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static String decompressed(String type, Path p) throws IOException {
		try (InputStream raw = Files.newInputStream(p);
				InputStream in = "gz".equals(type) ? new GZIPInputStream(raw) : new BZip2CompressorInputStream(raw)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "gz", "bz2" })
	void readsAndCountsItsOneFile(String type) throws IOException {
		Path p = TestArchives.compressed(type, dir.resolve("read.txt." + type), "one file inside");
		VfsCompressedFileSourceFactory f = connected(type, p);
		assertEquals("read.txt", f.fileName());
		assertArrayEquals(new String[] { "read.txt" }, f.createFileSource("/").list());
		FileSource one = f.createFileSource("/read.txt");
		assertTrue(one.exists() && one.isFile());
		assertEquals("one file inside", read(one));
		assertEquals(15L, one.length(), "counted: VFS doesn't know it");
		assertFalse(f.createFileSource("/other.txt").exists(), "nothing else is there");
	}

	@ParameterizedTest
	@ValueSource(strings = { "gz", "bz2" })
	void writingReplacesTheCompressedFile(String type) throws IOException {
		Path p = TestArchives.compressed(type, dir.resolve("write.txt." + type), "before");
		VfsCompressedFileSourceFactory f = connected(type, p);
		FileSource one = f.createFileSource("/write.txt");
		assertEquals(6L, one.length());
		write(one, "after, and longer");
		assertEquals("after, and longer", decompressed(type, p));
		assertEquals("after, and longer", read(one));
		assertEquals(17L, one.length(), "counted again after the change");
	}

	/** VFS would write any name over the compressed file. */
	@ParameterizedTest
	@ValueSource(strings = { "gz", "bz2" })
	void onlyItsOneFileCanBeWritten(String type) throws IOException {
		Path p = TestArchives.compressed(type, dir.resolve("only.txt." + type), "keep me");
		VfsCompressedFileSourceFactory f = connected(type, p);
		IOException e = assertThrows(IOException.class, () -> write(f.createFileSource("/other.txt"), "x"));
		assertEquals("vfs" + type + " holds one file, /only.txt: /other.txt can't be written", e.getMessage());
		assertEquals("keep me", decompressed(type, p));
		assertTrue(assertThrows(IOException.class, () -> f.createFileSource("/only.txt").delete()).getMessage().contains("read only"));
	}

	/** Decided 2026-11-14: a compressed file that isn't there yet is made by writing its file. */
	@ParameterizedTest
	@ValueSource(strings = { "gz", "bz2" })
	void writingMakesAMissingCompressedFile(String type) throws IOException {
		Path p = dir.resolve("new.txt." + type);
		Files.deleteIfExists(p);
		VfsCompressedFileSourceFactory f = connected(type, p);
		FileSource one = f.createFileSource("/new.txt");
		assertFalse(one.exists(), "VFS says it does; it isn't there");
		assertEquals(0, f.createFileSource("/").list().length);
		assertEquals(0L, one.length());
		write(one, "made");
		assertTrue(Files.exists(p));
		assertEquals("made", decompressed(type, p));
		assertTrue(one.exists());
		assertEquals(4L, one.length());
		assertArrayEquals(new String[] { "new.txt" }, f.createFileSource("/").list());
	}
}

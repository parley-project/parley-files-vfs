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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;

/**
 * Writes the test archives: the same tree in each format (a.txt, dir/, dir/b.txt, and empty/), with
 * known times, and single compressed files.
 */
final class TestArchives {

	/** Every entry's time (to the second, which every format keeps). */
	static final long TIME = 1_600_000_000_000L;

	/** The files and their contents; folders end in "/". */
	static final Map<String, String> TREE = new LinkedHashMap<>();
	static {
		TREE.put("a.txt", "hello");
		TREE.put("dir/", null);
		TREE.put("dir/b.txt", "inner file, a little longer");
		TREE.put("empty/", null);
	}

	private TestArchives() {
	}

	/** An archive of TYPE (zip, jar, tar, tgz, tbz2) with {@link #TREE}, written to FILE. */
	static Path write(String type, Path file) throws IOException {
		try (OutputStream raw = Files.newOutputStream(file)) {
			switch (type) {
			case "zip":
				try (ZipOutputStream z = new ZipOutputStream(raw)) {
					zip(z);
				}
				break;
			case "jar":
				try (JarOutputStream z = new JarOutputStream(raw)) {
					zip(z);
				}
				break;
			case "tar":
				tar(raw);
				break;
			case "tgz":
				try (GZIPOutputStream g = new GZIPOutputStream(raw)) {
					tar(g);
				}
				break;
			case "tbz2":
				try (BZip2CompressorOutputStream b = new BZip2CompressorOutputStream(raw)) {
					tar(b);
				}
				break;
			default:
				throw new IllegalArgumentException(type);
			}
		}
		return file;
	}

	private static void zip(ZipOutputStream z) throws IOException {
		for (Map.Entry<String, String> e : TREE.entrySet()) {
			ZipEntry ze = new ZipEntry(e.getKey());
			ze.setLastModifiedTime(FileTime.fromMillis(TIME));
			z.putNextEntry(ze);
			if( e.getValue() != null ) {
				z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
			}
			z.closeEntry();
		}
	}

	private static void tar(OutputStream out) throws IOException {
		TarArchiveOutputStream t = new TarArchiveOutputStream(out);
		for (Map.Entry<String, String> e : TREE.entrySet()) {
			TarArchiveEntry te = new TarArchiveEntry(e.getKey());
			te.setModTime(TIME);
			byte[] data = e.getValue() == null ? new byte[0] : e.getValue().getBytes(StandardCharsets.UTF_8);
			te.setSize(data.length);
			t.putArchiveEntry(te);
			t.write(data);
			t.closeArchiveEntry();
		}
		// finish, not close: the caller closes the stream under it
		t.finish();
	}

	/** TEXT compressed with TYPE (gz or bz2) into FILE. */
	static Path compressed(String type, Path file, String text) throws IOException {
		try (OutputStream raw = Files.newOutputStream(file);
				OutputStream c = "gz".equals(type) ? new GZIPOutputStream(raw) : new BZip2CompressorOutputStream(raw)) {
			c.write(text.getBytes(StandardCharsets.UTF_8));
		}
		return file;
	}
}

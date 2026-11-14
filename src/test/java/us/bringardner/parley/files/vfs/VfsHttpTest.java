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
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.LogLevel;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.ISeekableInputStream;

/**
 * vfshttp (docs/DESIGN.md, 16) against a web server of the test's own: reading, folders from
 * listing pages, read only, reading at a position (with and without the server's ranges), a
 * login, URLs and settings. Every factory is disconnected after its test, the server after all.
 */
public class VfsHttpTest {

	private static final long TIME = 1_600_000_123_000L;
	private static final byte[] BIG = new byte[200_000];

	private static VfsHttpTestServer server;
	private final List<FileSourceFactory> made = new ArrayList<>();

	@BeforeAll
	static void start() throws IOException {
		server = VfsHttpTestServer.started(null);
		Path h = server.home();
		Files.write(h.resolve("a.txt"), "hello".getBytes(StandardCharsets.UTF_8));
		Files.setLastModifiedTime(h.resolve("a.txt"), FileTime.fromMillis(TIME));
		Files.createDirectories(h.resolve("dir").resolve("sub"));
		Files.write(h.resolve("dir").resolve("b.txt"), "inner".getBytes(StandardCharsets.UTF_8));
		Files.write(h.resolve("dir").resolve("a b.txt"), "spaced".getBytes(StandardCharsets.UTF_8));
		Files.createDirectories(h.resolve("nolist"));
		Files.write(h.resolve("nolist").resolve("hidden.txt"), "x".getBytes(StandardCharsets.UTF_8));
		new Random(7).nextBytes(BIG);
		Files.write(h.resolve("big.bin"), BIG);
	}

	@AfterAll
	static void stop() {
		if( server != null ) {
			server.stop();
		}
	}

	@AfterEach
	void disconnectAll() throws IOException {
		for (FileSourceFactory f : made) {
			f.disConnect();
		}
		made.clear();
		server.login(null, null);
		server.ranges(true);
	}

	private VfsHttpFileSourceFactory connected() throws IOException {
		VfsHttpFileSourceFactory f = server.connect();
		made.add(f);
		return f;
	}

	private static String read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return VfsHttpTestServer.text(in);
		}
	}

	private static String[] sorted(String[] names) {
		Arrays.sort(names);
		return names;
	}

	@Test
	void registeredUnderTheirTypeIds() {
		assertTrue(FileSourceFactory.getFileSourceFactory("vfshttp") instanceof VfsHttpFileSourceFactory);
		assertTrue(FileSourceFactory.getFileSourceFactory("vfshttps") instanceof VfsHttpsFileSourceFactory);
	}

	@Test
	void readsAFile() throws IOException {
		VfsHttpFileSourceFactory f = connected();
		FileSource a = f.createFileSource("/a.txt");
		assertTrue(a.exists());
		assertTrue(a.isFile());
		assertFalse(a.isDirectory());
		assertEquals("hello", read(a));
		assertEquals(5L, a.length());
		assertEquals(TIME, a.lastModified(), "Last-Modified, to the second");
		assertNull(a.list(), "a file has no listing");
		FileSource missing = f.createFileSource("/missing.txt");
		assertFalse(missing.exists());
		assertFalse(missing.isFile());
		assertFalse(missing.isDirectory());
	}

	/** DESIGN 16.2: a folder is what the server serves with a "/" after it; its files are its page's links. */
	@Test
	void foldersFromListingPages() throws IOException {
		VfsHttpFileSourceFactory f = connected();
		FileSource root = f.createFileSource("/");
		assertTrue(root.exists() && root.isDirectory());
		assertArrayEquals(new String[] { "a.txt", "big.bin", "dir", "nolist" }, sorted(root.list()),
				"no parent, sort or other-site links");
		FileSource dir = f.createFileSource("/dir");
		assertTrue(dir.exists());
		assertTrue(dir.isDirectory());
		assertFalse(dir.isFile());
		assertEquals(0L, dir.length());
		assertArrayEquals(new String[] { "a b.txt", "b.txt", "sub" }, sorted(dir.list()), "names decoded");
		for (FileSource k : dir.listFiles()) {
			assertEquals(k.getName().equals("sub"), k.isDirectory(), k.getName());
			assertEquals(!k.getName().equals("sub"), k.isFile(), k.getName());
		}
		assertEquals("spaced", read(f.createFileSource("/dir/a b.txt")));
		assertEquals(0, f.createFileSource("/dir/sub").list().length);
		assertThrows(IOException.class, () -> dir.getInputStream(), "a folder can't be read as a file");
	}

	/** README A15: a folder whose page can't be read lists nothing. */
	@Test
	void aFolderWithoutAListingListsNothing() throws IOException {
		VfsHttpFileSourceFactory f = connected();
		FileSource nolist = f.createFileSource("/nolist");
		assertEquals(0, nolist.list().length);
		assertEquals("x", read(f.createFileSource("/nolist/hidden.txt")), "its files can still be read by name");
	}

	/** README A14. */
	@Test
	void readOnly() throws IOException {
		VfsHttpFileSourceFactory f = connected();
		FileSource a = f.createFileSource("/a.txt");
		List<IOException> errors = new ArrayList<>();
		errors.add(assertThrows(IOException.class, () -> a.getOutputStream().close()));
		errors.add(assertThrows(IOException.class, () -> f.createFileSource("/new.txt").createNewFile()));
		errors.add(assertThrows(IOException.class, () -> f.createFileSource("/newdir").mkdir()));
		errors.add(assertThrows(IOException.class, a::delete));
		for (IOException e : errors) {
			assertTrue(e.getMessage().contains("read only"), e.getMessage());
		}
		assertFalse(a.canWrite());
		assertEquals("hello", read(a));
	}

	@Test
	void readsAtAPosition() throws Exception {
		for (boolean ranges : new boolean[] { true, false }) {
			server.ranges(ranges);
			VfsHttpFileSourceFactory f = connected();
			FileSource big = f.createFileSource("/big.bin");
			assertEquals(BIG.length, big.length());
			try (InputStream in = big.getInputStream(150_000)) {
				assertArrayEquals(Arrays.copyOfRange(BIG, 150_000, BIG.length), in.readAllBytes(), "ranges " + ranges);
			}
			ISeekableInputStream in = big.getSeekableInputStream();
			try {
				in.seek(123_456);
				byte[] got = new byte[100];
				int n = in.read(got, 0, got.length);
				assertTrue(n > 0);
				assertArrayEquals(Arrays.copyOfRange(BIG, 123_456, 123_456 + n), Arrays.copyOf(got, n), "ranges " + ranges);
			} finally {
				in.close();
			}
			f.disConnect();
		}
	}

	@Test
	void aLogin() throws IOException {
		server.login("tony", "secret");
		VfsHttpFileSourceFactory f = connected();
		assertEquals("hello", read(f.createFileSource("/a.txt")));
		assertArrayEquals(new String[] { "a b.txt", "b.txt", "sub" }, sorted(f.createFileSource("/dir").list()),
				"the listing's requests log in too");
		for (String password : new String[] { "wrong", null }) {
			VfsHttpFileSourceFactory bad = new VfsHttpFileSourceFactory();
			bad.setLogLevel(LogLevel.ERROR);
			Properties p = server.properties();
			if( password == null ) {
				p.remove(VfsHttpFileSourceFactory.PROP_PASSWORD);
				p.remove(VfsHttpFileSourceFactory.PROP_USER);
			} else {
				p.setProperty(VfsHttpFileSourceFactory.PROP_PASSWORD, password);
			}
			bad.setConnectionProperties(p);
			made.add(bad);
			IOException e = assertThrows(IOException.class, bad::connect, "password " + password);
			assertTrue(e.getMessage().endsWith("refused the login (401 Unauthorized)"), e.getMessage());
			assertFalse(bad.isConnected());
		}
	}

	@Test
	void urls() throws IOException {
		VfsHttpFileSourceFactory f = connected();
		String base = "//" + VfsHttpTestServer.HOST + ":" + server.port();
		assertEquals("vfshttp:" + base + "/", f.getURL());
		FileSource b = f.createFileSource("/dir/b.txt");
		assertEquals("http:" + base + "/dir/b.txt", ((VfsFileSource) b).getVfsUri());
		assertEquals("filesource:" + base + "/dir/b.txt?sourcetype=vfshttp", b.toURL().toString());
		FileSource byUrl = FileSourceFactory.getFileSource("vfshttp:" + base + "/dir/b.txt");
		made.add(byUrl.getFileSourceFactory());
		assertEquals("inner", read(byUrl));
		FileSource again = FileSourceFactory.getFileSource(b.toURL().toString());
		made.add(again.getFileSourceFactory());
		assertEquals("inner", read(again));
		java.net.URLConnection c = new URL("vfshttp:" + base + "/a.txt").openConnection();
		FileSource opened;
		try (InputStream in = c.getInputStream()) {
			opened = ((VfsUrlConnection) c).getFileSource();
			assertEquals("hello", VfsHttpTestServer.text(in));
		}
		assertFalse(opened.getFileSourceFactory().isConnected(), "closing the stream disconnects the URL's factory");
	}

	@Test
	void settingsRoundTripAndCopies() throws IOException {
		VfsHttpFileSourceFactory f = connected();
		VfsHttpFileSourceFactory back = new VfsHttpFileSourceFactory();
		back.setConnectionProperties(f.getConnectProperties());
		assertTrue(f.isSameFileSystem(back));
		assertEquals(5, f.getConnectionSettings().size());
		assertEquals(80, new VfsHttpFileSourceFactory().getPort());
		assertEquals(443, new VfsHttpsFileSourceFactory().getPort());
		assertEquals(10_000, f.getTimeout());
		assertEquals(30_000, new VfsHttpFileSourceFactory().getTimeout());
		FileSourceFactory copy = f.createThreadSafeCopy();
		made.add(copy);
		assertTrue(copy.connect());
		assertEquals("hello", read(copy.createFileSource("/a.txt")));
		VfsHttpsFileSourceFactory tls = new VfsHttpsFileSourceFactory();
		tls.setConnectionProperties(f.getConnectProperties());
		assertFalse(f.isSameFileSystem(tls), "another type");
	}

	@Test
	void nobodyListeningFailsTheConnect() throws IOException {
		VfsHttpFileSourceFactory f = new VfsHttpFileSourceFactory();
		f.setLogLevel(LogLevel.ERROR);
		Properties p = server.properties();
		try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
			p.setProperty(VfsHttpFileSourceFactory.PROP_PORT, "" + s.getLocalPort());
		}
		f.setConnectionProperties(p);
		made.add(f);
		assertThrows(IOException.class, f::connect);
		assertFalse(f.isConnected());
	}

	/** The listing page's links, as Apache, nginx and Python's http.server write them. */
	@Test
	void listingLinks() {
		URI base = URI.create("http://host:8080/");
		String apache = "<a href=\"?C=N;O=D\">Name</a><a href=\"/pub/\">Parent Directory</a>"
				+ "<a href=\"a.txt\">a.txt</a><a href=\"sub%20dir/\">sub dir/</a><a href=\"a.txt\">again</a>";
		Map<String, Boolean> got = VfsHttpClient.names(apache, "/pub/files/", base);
		assertEquals(List.of("a.txt", "sub dir"), new ArrayList<>(got.keySet()));
		assertEquals(Boolean.TRUE, got.get("sub dir"));
		assertEquals(Boolean.FALSE, got.get("a.txt"));
		String nginx = "<a href=\"../\">../</a>\n<a href=\"b.tar.gz\">b.tar.gz</a>\n<A HREF='c/'>c/</A>";
		assertEquals(List.of("b.tar.gz", "c"), new ArrayList<>(VfsHttpClient.names(nginx, "/x/", base).keySet()));
		String absolute = "<a href=\"/x/d.txt\">d</a><a href=\"http://host:8080/x/e.txt\">e</a>"
				+ "<a href=\"http://other/x/f.txt\">f</a><a href=\"https://host:8080/x/g.txt\">g</a>"
				+ "<a href=\"/y/h.txt\">h</a><a href=\"./i.txt\">i</a><a href=\"j.txt#top\">j</a><a href=\"k/l.txt\">deeper</a>"
				+ "<a href=\"m&amp;n.txt\">m</a>";
		assertEquals(List.of("d.txt", "e.txt", "i.txt", "j.txt", "m&n.txt"),
				new ArrayList<>(VfsHttpClient.names(absolute, "/x/", base).keySet()));
	}

	@Test
	void writingSaysReadOnly() throws IOException {
		VfsHttpFileSourceFactory f = connected();
		IOException e = assertThrows(IOException.class, () -> {
			try (OutputStream out = f.createFileSource("/a.txt").getOutputStream()) {
				out.write(1);
			}
		});
		assertTrue(e.getMessage().startsWith("vfshttp is read only here"), e.getMessage());
	}
}

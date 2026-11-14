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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.LogLevel;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * vfswebdav itself (docs/DESIGN.md, 17), on Tomcat's WebdavServlet: folders and files told apart and
 * listed although Tomcat indents its answers (VFS alone can't), members the server doesn't have
 * left out, an upload the server cuts short failing its close, a login, URLs and settings. The
 * shared suites run in TestVfsWebdav and the like. Every factory is disconnected after its test.
 */
public class VfsWebdavTest {

	private static VfsWebdavTestServer server;
	private final List<FileSourceFactory> made = new ArrayList<>();

	@BeforeAll
	static void start() throws IOException {
		server = VfsWebdavTestServer.started();
		Files.createDirectories(server.home().resolve("dir").resolve("sub"));
		Files.write(server.home().resolve("dir").resolve("b.txt"), "inner".getBytes(StandardCharsets.UTF_8));
		Files.createDirectories(server.home().resolve("with space"));
		Files.write(server.home().resolve("with space").resolve("c d.txt"), "spaced".getBytes(StandardCharsets.UTF_8));
		Files.createDirectories(server.home().resolve("phantom"));
		Files.write(server.home().resolve("phantom").resolve("real.txt"), "real".getBytes(StandardCharsets.UTF_8));
	}

	@AfterAll
	static void stop() throws IOException {
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
	}

	private VfsWebdavFileSourceFactory connected() throws IOException {
		VfsWebdavFileSourceFactory f = server.connect();
		made.add(f);
		return f;
	}

	private static String read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void write(FileSource f, byte[] data) throws IOException {
		try (OutputStream out = f.getOutputStream()) {
			out.write(data);
		}
	}

	private static String[] sorted(String[] names) {
		Arrays.sort(names);
		return names;
	}

	@Test
	void registeredUnderTheirTypeIds() {
		assertTrue(FileSourceFactory.getFileSourceFactory("vfswebdav") instanceof VfsWebdavFileSourceFactory);
		assertTrue(FileSourceFactory.getFileSourceFactory("vfswebdavs") instanceof VfsWebdavsFileSourceFactory);
	}

	/** DESIGN 17.2: Tomcat indents its answers; folders and files are still told apart and listed. */
	@Test
	void foldersAndFilesOnAServerThatIndents() throws IOException {
		VfsWebdavFileSourceFactory f = connected();
		FileSource root = f.createFileSource("/");
		assertTrue(root.isDirectory());
		FileSource dir = f.createFileSource("/dir");
		assertTrue(dir.isDirectory());
		assertFalse(dir.isFile());
		assertArrayEquals(new String[] { "b.txt", "sub" }, sorted(dir.list()));
		for (FileSource k : dir.listFiles()) {
			assertEquals(k.getName().equals("sub"), k.isDirectory(), k.getName());
		}
		FileSource b = f.createFileSource("/dir/b.txt");
		assertTrue(b.isFile());
		assertEquals("inner", read(b));
		assertEquals(5L, b.length());
		assertArrayEquals(new String[] { "c d.txt" }, f.createFileSource("/with space").list(), "names decoded");
		assertEquals("spaced", read(f.createFileSource("/with space/c d.txt")));
		assertFalse(f.createFileSource("/missing").exists());
	}

	/** What the third mitigation fixes: VFS alone can't tell what "/" is on Tomcat. */
	@Test
	void vfsAloneCantReadAServerThatIndents() throws IOException {
		StandardFileSystemManager m = new StandardFileSystemManager();
		try {
			m.init();
			FileObject root = m.resolveFile("webdav4://" + VfsWebdavTestServer.HOST + ":" + server.port() + "/");
			FileSystemException e = assertThrows(FileSystemException.class, root::getType);
			assertTrue(String.valueOf(e.getCause()).contains("ClassCastException"), String.valueOf(e.getCause()));
		} finally {
			m.close();
		}
	}

	/** DESIGN 17.2: only the folder's own members that the server says are there. */
	@Test
	void membersTheServerHasntGotAreLeftOut() throws IOException {
		VfsWebdavFileSourceFactory f = connected();
		FileSource phantom = f.createFileSource("/phantom");
		assertTrue(phantom.isDirectory());
		assertArrayEquals(new String[] { "real.txt" }, phantom.list(), "not ghost.txt (404), sub/deep.txt or /elsewhere/x.txt");
	}

	/** DESIGN 17.3: an upload the server cuts short fails at close, saying so. */
	@Test
	void anUploadTheServerCutsShortFails() throws IOException {
		VfsWebdavFileSourceFactory f = connected();
		FileSource dir = f.createFileSource("/short");
		assertTrue(dir.mkdir());
		IOException e = assertThrows(IOException.class, () -> write(dir.getChild("half.txt"), new byte[1000]));
		assertEquals("vfswebdav: the server has 500 bytes of /short/half.txt after 1000 were written", e.getMessage());
		FileSource ok = f.createFileSource("/whole.txt");
		write(ok, new byte[1000]);
		assertEquals(1000L, ok.length());
		assertTrue(ok.delete());
	}

	@Test
	void writesCreatesRenamesDeletes() throws IOException {
		VfsWebdavFileSourceFactory f = connected();
		FileSource d = f.createFileSource("/work");
		assertTrue(d.mkdir());
		FileSource a = d.getChild("a.txt");
		write(a, "written".getBytes(StandardCharsets.UTF_8));
		assertEquals("written", new String(Files.readAllBytes(server.home().resolve("work").resolve("a.txt")), StandardCharsets.UTF_8));
		FileSource moved = d.getChild("moved.txt");
		assertTrue(a.renameTo(moved));
		assertFalse(a.exists());
		assertEquals("written", read(moved));
		assertArrayEquals(new String[] { "moved.txt" }, d.list());
		assertTrue(moved.delete());
		assertEquals(0, d.list().length);
		assertTrue(d.delete());
		assertFalse(d.exists());
	}

	@Test
	void aLogin() throws IOException {
		server.login("tony", "secret");
		VfsWebdavFileSourceFactory f = connected();
		assertEquals("inner", read(f.createFileSource("/dir/b.txt")));
		assertArrayEquals(new String[] { "b.txt", "sub" }, sorted(f.createFileSource("/dir").list()));
		VfsWebdavFileSourceFactory bad = new VfsWebdavFileSourceFactory();
		bad.setLogLevel(LogLevel.ERROR);
		Properties p = server.properties();
		p.setProperty(VfsWebFileSourceFactory.PROP_PASSWORD, "wrong");
		bad.setConnectionProperties(p);
		made.add(bad);
		assertThrows(IOException.class, bad::connect);
		assertFalse(bad.isConnected());
	}

	@Test
	void urlsAndSettings() throws IOException {
		VfsWebdavFileSourceFactory f = connected();
		String base = "//" + VfsWebdavTestServer.HOST + ":" + server.port();
		assertEquals("vfswebdav:" + base + "/", f.getURL());
		FileSource b = f.createFileSource("/dir/b.txt");
		assertEquals("filesource:" + base + "/dir/b.txt?sourcetype=vfswebdav", b.toURL().toString());
		FileSource byUrl = FileSourceFactory.getFileSource("vfswebdav:" + base + "/dir/b.txt");
		made.add(byUrl.getFileSourceFactory());
		assertEquals("inner", read(byUrl));
		java.net.URLConnection c = new URL("vfswebdav:" + base + "/dir/b.txt").openConnection();
		try (InputStream in = c.getInputStream()) {
			assertEquals("inner", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		VfsWebdavFileSourceFactory back = new VfsWebdavFileSourceFactory();
		back.setConnectionProperties(f.getConnectProperties());
		assertTrue(f.isSameFileSystem(back));
		assertEquals(5, f.getConnectionSettings().size());
		assertEquals("webdav4", f.vfsScheme());
		assertEquals("webdav4s", new VfsWebdavsFileSourceFactory().vfsScheme());
		assertEquals(443, new VfsWebdavsFileSourceFactory().getPort());
		FileSourceFactory copy = f.createThreadSafeCopy();
		made.add(copy);
		assertTrue(copy.connect());
		assertEquals("inner", read(copy.createFileSource("/dir/b.txt")));
	}

	/** The resourcetype parser, on the layouts servers send. */
	@Test
	void resourceTypes() throws Exception {
		javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(true);
		org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
				"<D:r xmlns:D='DAV:'><D:collection/><D:other/></D:r>".getBytes(StandardCharsets.UTF_8)));
		org.w3c.dom.Node collection = doc.getDocumentElement().getFirstChild();
		org.w3c.dom.Node other = collection.getNextSibling();
		org.w3c.dom.Node text = doc.createTextNode("\n  ");
		assertTrue(VfsWebdavClient.isCollection(collection));
		assertTrue(VfsWebdavClient.isCollection(List.of(text, collection, text)), "indented");
		assertFalse(VfsWebdavClient.isCollection(other));
		assertFalse(VfsWebdavClient.isCollection(List.of(text)));
		assertFalse(VfsWebdavClient.isCollection((Object) null));
	}
}

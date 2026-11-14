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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.test.FileSourceTestSupport;

/**
 * The vfssftp factory: its registration and URLs (Q8, Q9), settings, copies, file systems and
 * host keys (Q5).
 */
public class VfsSftpFactoryTest {

	private static VfsSftpTestServer server;
	private static VfsSftpFileSourceFactory factory;

	@BeforeAll
	static void start() throws IOException {
		server = VfsSftpTestServer.started();
		factory = server.connect();
		try (OutputStream out = factory.createFileSource("/hello.txt").getOutputStream()) {
			out.write("hello".getBytes(StandardCharsets.UTF_8));
		}
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

	private static String read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void registeredUnderItsTypeId() {
		assertTrue(FileSourceFactory.isRegisteredFactory("vfssftp"));
		assertTrue(FileSourceFactory.getFileSourceFactory("vfssftp") instanceof VfsSftpFileSourceFactory);
		assertTrue(Arrays.asList(FileSourceFactory.getRegisterdFactories()).contains("vfssftp"));
	}

	/** Q8: a vfssftp URL opens through parley-files' getFileSource(URL), with no change to it. */
	@Test
	void aVfsUrlOpensThroughGetFileSource() throws IOException {
		String url = "vfssftp://" + VfsSftpTestServer.USER + ":" + VfsSftpTestServer.PASSWORD + "@" + VfsSftpTestServer.HOST
				+ ":" + server.port() + "/hello.txt?strictHostKeyChecking=no";
		FileSource f = FileSourceFactory.getFileSource(url);
		try {
			assertTrue(f instanceof VfsFileSource);
			assertEquals("/hello.txt", f.getAbsolutePath());
			assertEquals("hello", read(f));
		} finally {
			f.getFileSourceFactory().disConnect();
		}
	}

	@Test
	void aVfsUrlOpensAsAJavaNetUrl() throws IOException {
		URL url = new URL("vfssftp://" + VfsSftpTestServer.USER + ":" + VfsSftpTestServer.PASSWORD + "@" + VfsSftpTestServer.HOST
				+ ":" + server.port() + "/hello.txt?strictHostKeyChecking=no");
		java.net.URLConnection c = url.openConnection();
		assertTrue(c instanceof VfsUrlConnection);
		FileSource f;
		try (InputStream in = c.getInputStream()) {
			f = ((VfsUrlConnection) c).getFileSource();
			assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		assertFalse(f.getFileSourceFactory().isConnected(), "closing the stream disconnects the URL's factory");
	}

	@Test
	void filesKnowTheirUrls() throws IOException {
		FileSource f = factory.createFileSource("/hello.txt");
		String base = "://" + VfsSftpTestServer.USER + "@" + VfsSftpTestServer.HOST + ":" + server.port();
		assertEquals("filesource" + base + "/hello.txt?sourcetype=vfssftp", f.toURL().toString());
		assertEquals("vfssftp" + base + "/", factory.getURL());
		assertEquals("vfssftp" + base + "/hello.txt", f.getTitle());
		assertEquals("sftp" + base + "/hello.txt", ((VfsFileSource) f).getVfsUri());
		assertFalse(f.toURL().toString().contains(VfsSftpTestServer.PASSWORD), "no password");
	}

	/** A file's filesource: URL opens it again, given the password in the query (no password in the URL). */
	@Test
	void aFileSourceUrlOpensItAgain() throws IOException {
		String url = factory.createFileSource("/hello.txt").toURL().toString()
				+ "&password=" + VfsSftpTestServer.PASSWORD + "&strictHostKeyChecking=no";
		FileSource f = FileSourceFactory.getFileSource(url);
		try {
			assertEquals("hello", read(f));
		} finally {
			f.getFileSourceFactory().disConnect();
		}
	}

	@Test
	void settingsRoundTripAndCopiesAreTheSameFileSystem() throws IOException {
		Properties p = factory.getConnectProperties();
		VfsSftpFileSourceFactory other = new VfsSftpFileSourceFactory();
		other.setConnectionProperties(p);
		assertEquals(p, other.getConnectProperties());
		assertTrue(factory.isSameFileSystem(other));
		assertEquals(factory.createFileSource("/hello.txt"), other.createFileSource("/hello.txt"));

		FileSourceFactory copy = factory.createThreadSafeCopy();
		try {
			assertTrue(copy instanceof VfsSftpFileSourceFactory);
			assertTrue(factory.isSameFileSystem(copy));
			assertEquals("hello", read(copy.createFileSource("/hello.txt")));
		} finally {
			copy.disConnect();
		}

		other.setUser("someoneelse");
		assertFalse(factory.isSameFileSystem(other));
		other.setUser(VfsSftpTestServer.USER);
		other.setPort(server.port() + 1);
		assertFalse(factory.isSameFileSystem(other));
	}

	@Test
	void settingsAreParleyFilesSftps() {
		VfsSftpFileSourceFactory f = new VfsSftpFileSourceFactory();
		Properties p = f.getConnectProperties();
		for (String key : new String[] { "host", "port", "user", "auth", "password", "identityFile", "privateKey", "knownHosts",
				"strictHostKeyChecking", "connectTimeout" }) {
			assertTrue(p.containsKey(key), key);
		}
		assertEquals("22", p.getProperty("port"));
		assertEquals("yes", p.getProperty("strictHostKeyChecking"));
		assertEquals("Password", p.getProperty("auth"));
		assertTrue(f.isSecretProperty("password"));
		assertTrue(f.isSecretProperty("privateKey"));
		assertFalse(f.isSecretProperty("knownHosts"));
		assertEquals(10, f.getConnectionSettings().size());
		assertThrows(IllegalArgumentException.class, () -> f.setStrictHostKeyChecking("maybe"));
	}

	@Test
	void currentDirectoryIsThisFactorysOwn() throws IOException {
		VfsSftpFileSourceFactory f = server.connect();
		try {
			assertEquals("/", f.getCurrentDirectory().getAbsolutePath());
			FileSource d = f.createFileSource("/cwd");
			assertTrue(d.mkdir());
			f.setCurrentDirectory(d);
			assertEquals("/cwd/x.txt", f.createFileSource("x.txt").getAbsolutePath());
			assertEquals("/", factory.getCurrentDirectory().getAbsolutePath(), "another factory's is unchanged");
			assertTrue(d.delete());
		} finally {
			f.disConnect();
		}
	}

	@Test
	void aFileWorksAfterItsFactoryReconnects() throws IOException {
		VfsSftpFileSourceFactory f = server.connect();
		try {
			FileSource hello = f.createFileSource("/hello.txt");
			assertEquals("hello", read(hello));
			f.disConnect();
			assertFalse(f.isConnected());
			assertEquals("hello", read(hello), "reconnects");
			assertTrue(f.isConnected());
		} finally {
			f.disConnect();
		}
	}

	@Test
	void whoAmIIsTheConnectedUser() {
		assertEquals(VfsSftpTestServer.USER, factory.whoAmI().getName());
	}

	@Test
	void linksCantBeMade() {
		assertThrows(UnsupportedOperationException.class, () -> factory.createSymbolicLink(
				factory.createFileSource("/l"), factory.createFileSource("/hello.txt")));
		assertThrows(UnsupportedOperationException.class, () -> factory.createLink(
				factory.createFileSource("/l"), factory.createFileSource("/hello.txt")));
	}

	@Test
	void aWrongPasswordFailsTheConnect() {
		VfsSftpFileSourceFactory f = new VfsSftpFileSourceFactory();
		Properties p = server.properties();
		p.setProperty("password", "wrong");
		f.setConnectionProperties(p);
		assertThrows(IOException.class, f::connect);
		assertFalse(f.isConnected());
	}

	// ------------------------------------------------------------------ host keys (Q5)

	private static VfsSftpFileSourceFactory checking(Path knownHosts) {
		VfsSftpFileSourceFactory f = new VfsSftpFileSourceFactory();
		Properties p = server.properties();
		p.setProperty("strictHostKeyChecking", "yes");
		p.setProperty("knownHosts", knownHosts.toString());
		f.setConnectionProperties(p);
		return f;
	}

	@Test
	void anUnknownHostKeyIsRefused() throws IOException {
		Path kh = Path.of(FileSourceTestSupport.scratchDir("known_hosts_empty"));
		Files.writeString(kh, "");
		VfsSftpFileSourceFactory f = checking(kh);
		try {
			assertThrows(IOException.class, f::connect);
			assertFalse(f.isConnected());
		} finally {
			f.disConnect();
		}
	}

	@Test
	void aKnownHostKeyIsAccepted() throws IOException {
		Path kh = Path.of(FileSourceTestSupport.scratchDir("known_hosts_server"));
		Files.writeString(kh, server.knownHostsLine() + "\n");
		VfsSftpFileSourceFactory f = checking(kh);
		try {
			assertTrue(f.connect());
			assertNotNull(f.createFileSource("/hello.txt"));
			assertEquals("hello", read(f.createFileSource("/hello.txt")));
		} finally {
			f.disConnect();
		}
	}
}

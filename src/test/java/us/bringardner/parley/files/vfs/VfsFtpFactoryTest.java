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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;

import org.apache.commons.vfs2.FileSystemOptions;
import org.apache.commons.vfs2.provider.ftp.FtpFileSystemConfigBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * The vfsftp and vfsftps factories: their registration and URLs (Q8, Q9), parley-files-ftp's
 * settings, the VFS options they change (DESIGN 10), copies and file systems.
 */
public class VfsFtpFactoryTest {

	private static VfsFtpTestServer server;
	private static VfsFtpFileSourceFactory factory;

	@BeforeAll
	static void start() throws IOException {
		server = VfsFtpTestServer.started();
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
			factory = null;
			if( server != null ) {
				server.stop();
			}
			server = null;
		}
	}

	private static String read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String base() {
		return "://" + VfsFtpTestServer.USER + "@" + VfsFtpTestServer.HOST + ":" + server.port();
	}

	@Test
	void registeredUnderTheirTypeIds() {
		assertTrue(FileSourceFactory.getFileSourceFactory("vfsftp") instanceof VfsFtpFileSourceFactory);
		assertTrue(FileSourceFactory.getFileSourceFactory("vfsftps") instanceof VfsFtpsFileSourceFactory);
		assertTrue(Arrays.asList(FileSourceFactory.getRegisterdFactories()).containsAll(Arrays.asList("vfsftp", "vfsftps")));
	}

	/** Q8: a vfsftp URL opens through parley-files' getFileSource(URL), with no change to it. */
	@Test
	void aVfsUrlOpensThroughGetFileSource() throws IOException {
		FileSource f = FileSourceFactory.getFileSource("vfsftp://" + VfsFtpTestServer.USER + ":" + VfsFtpTestServer.PASSWORD
				+ "@" + VfsFtpTestServer.HOST + ":" + server.port() + "/hello.txt");
		try {
			assertTrue(f instanceof VfsFileSource);
			assertTrue(f.getFileSourceFactory() instanceof VfsFtpFileSourceFactory);
			assertEquals("/hello.txt", f.getAbsolutePath());
			assertEquals("hello", read(f));
		} finally {
			f.getFileSourceFactory().disConnect();
		}
	}

	@Test
	void aVfsUrlOpensAsAJavaNetUrl() throws IOException {
		java.net.URLConnection c = new URL("vfsftp://" + VfsFtpTestServer.USER + ":" + VfsFtpTestServer.PASSWORD + "@"
				+ VfsFtpTestServer.HOST + ":" + server.port() + "/hello.txt").openConnection();
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
		assertEquals("filesource" + base() + "/hello.txt?sourcetype=vfsftp", f.toURL().toString());
		assertEquals("vfsftp" + base() + "/", factory.getURL());
		assertEquals("ftp" + base() + "/hello.txt", ((VfsFileSource) f).getVfsUri());
		assertFalse(f.toURL().toString().contains(VfsFtpTestServer.PASSWORD), "no password");
	}

	/** Explicit TLS goes in the URL as a setting: vfs + VFS's URI would be vfsftps:, implicit TLS. */
	@Test
	void explicitTlsUrlsSaySecure() throws IOException {
		VfsFtpFileSourceFactory f = new VfsFtpFileSourceFactory();
		f.setConnectionProperties(server.properties());
		f.setSecure(true);
		assertEquals("vfsftp" + base() + "/?secure=true", f.getURL());
		assertEquals("ftps", f.vfsScheme());
		VfsFtpFileSourceFactory back = new VfsFtpFileSourceFactory();
		back.setConnectionProperties(new URL(f.getURL()));
		assertTrue(back.isSecure());
		assertTrue(f.isSameFileSystem(back));
		VfsFtpsFileSourceFactory implicit = new VfsFtpsFileSourceFactory();
		implicit.setConnectionProperties(server.properties());
		assertEquals("vfsftps" + base() + "/", implicit.getURL());
		assertFalse(f.isSameFileSystem(implicit), "another type");
	}

	/** A file's filesource: URL opens it again, given the password in the query (no password in the URL). */
	@Test
	void aFileSourceUrlOpensItAgain() throws IOException {
		String url = factory.createFileSource("/hello.txt").toURL().toString() + "&password=" + VfsFtpTestServer.PASSWORD;
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
		VfsFtpFileSourceFactory other = new VfsFtpFileSourceFactory();
		other.setConnectionProperties(p);
		assertEquals(p, other.getConnectProperties());
		assertTrue(factory.isSameFileSystem(other));
		assertEquals(factory.createFileSource("/hello.txt"), other.createFileSource("/hello.txt"));

		FileSourceFactory copy = factory.createThreadSafeCopy();
		try {
			assertTrue(copy instanceof VfsFtpFileSourceFactory);
			assertTrue(factory.isSameFileSystem(copy));
			assertEquals("hello", read(copy.createFileSource("/hello.txt")));
		} finally {
			copy.disConnect();
		}

		other.setUser("anonymous");
		assertFalse(factory.isSameFileSystem(other));
		other.setUser(VfsFtpTestServer.USER);
		other.setPort(server.port() + 1);
		assertFalse(factory.isSameFileSystem(other));
	}

	/** parley-files-ftp's names and defaults, so a saved ftp connection works as a vfsftp one. */
	@Test
	void settingsAreParleyFilesFtps() {
		VfsFtpFileSourceFactory f = new VfsFtpFileSourceFactory();
		Properties p = f.getConnectProperties();
		for (String key : new String[] { "host", "port", "user", "password", "secure", "timeout" }) {
			assertTrue(p.containsKey(key), key);
		}
		assertEquals("21", p.getProperty("port"));
		assertEquals("false", p.getProperty("secure"));
		assertEquals(4000, f.getTimeout());
		assertTrue(f.isSecretProperty("password"));
		assertEquals(6, f.getConnectionSettings().size());

		VfsFtpsFileSourceFactory s = new VfsFtpsFileSourceFactory();
		assertEquals("990", s.getConnectProperties().getProperty("port"));
		assertEquals("true", s.getConnectProperties().getProperty("secure"));
		s.setSecure(false);
		assertTrue(s.isSecure(), "vfsftps is always TLS");
		assertEquals(5, s.getConnectionSettings().size(), "no secure to choose");

		// a saved parley-files-ftp connection, with its settings VFS can't use
		Properties saved = new Properties();
		saved.setProperty("host", "example.com");
		saved.setProperty("account", "acct");
		saved.setProperty("storeAtOffset", "true");
		saved.setProperty("timeout", "9s");
		f.setConnectionProperties(saved);
		assertEquals("example.com", f.getHost());
		assertEquals(9000, f.getTimeout());
	}

	/** The VFS defaults vfsftp changes (DESIGN 10): passive, the server's root, MDTM, aborted transfers. */
	@Test
	void vfsOptionsAreParleyLike() throws IOException {
		VfsFtpFileSourceFactory f = new VfsFtpFileSourceFactory();
		f.setConnectionProperties(server.properties());
		FileSystemOptions o = new FileSystemOptions();
		f.configure(o);
		FtpFileSystemConfigBuilder b = FtpFileSystemConfigBuilder.getInstance();
		assertEquals(Boolean.TRUE, b.getPassiveMode(o));
		assertEquals(Boolean.FALSE, b.getUserDirIsRoot(o));
		assertEquals(Boolean.TRUE, b.getMdtmLastModifiedTime(o));
		assertTrue(b.getTransferAbortedOkReplyCodes(o).contains(426));
		assertEquals(10_000, b.getSoTimeoutDuration(o).toMillis());
	}

	@Test
	void currentDirectoryIsThisFactorysOwn() throws IOException {
		VfsFtpFileSourceFactory f = server.connect();
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
		VfsFtpFileSourceFactory f = server.connect();
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

	/** A directory's time comes from the listing (VFS's MDTM fails on one). */
	@Test
	void aDirectoryHasATime() throws IOException {
		FileSource d = factory.createFileSource("/timed");
		assertTrue(d.mkdir());
		try {
			assertTrue(d.lastModified() > 0, "a directory's time");
			assertTrue(factory.createFileSource("/").lastModified() >= 0, "the root's");
		} finally {
			assertTrue(d.delete());
		}
	}

	@Test
	void whoAmIIsTheConnectedUser() {
		assertEquals(VfsFtpTestServer.USER, factory.whoAmI().getName());
	}

	@Test
	void linksCantBeMade() {
		assertThrows(UnsupportedOperationException.class, () -> factory.createSymbolicLink(
				factory.createFileSource("/l"), factory.createFileSource("/hello.txt")));
	}

	@Test
	void nobodyListeningFailsTheConnect() throws IOException {
		int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		VfsFtpFileSourceFactory f = new VfsFtpFileSourceFactory();
		Properties p = server.properties();
		p.setProperty("port", "" + port);
		f.setConnectionProperties(p);
		assertThrows(IOException.class, f::connect);
		assertFalse(f.isConnected());
	}
}

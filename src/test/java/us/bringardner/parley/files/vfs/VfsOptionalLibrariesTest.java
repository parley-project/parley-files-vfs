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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * JSch, commons-net, commons-compress, httpclient5 and commons-vfs2-jackrabbit2 are optional (README B8, C14, F1, G5, H5): an application that uses only vfsftp has no
 * JSch, and one that uses only vfssftp has no commons-net, yet parley-files' registry creates every
 * factory parley-files-vfs lists. So each factory must load, and say its type id and settings,
 * without the other scheme's library, and the other scheme must work.
 * <p>
 * Each test runs parley-files and parley-files-vfs in a class loader of its own, made from the test
 * class path without one library's jar, over the platform class loader (so nothing leaks in from
 * the test's own). The servers run in the test's loader; only JDK types cross between the two.
 */
public class VfsOptionalLibrariesTest {

	private static VfsSftpTestServer sftp;
	private static VfsFtpTestServer ftp;

	@BeforeAll
	static void start() throws IOException {
		sftp = VfsSftpTestServer.started();
		ftp = VfsFtpTestServer.started();
	}

	@AfterAll
	static void stop() throws IOException {
		try {
			if( sftp != null ) {
				sftp.stop();
			}
		} finally {
			if( ftp != null ) {
				ftp.stop();
			}
		}
	}

	// ------------------------------------------------------------------ the isolated class loader

	/**
	 * A class loader with the test class path less the jars whose name starts with JAR, and the
	 * thread's context class loader set to it (parley's plugin lookup uses that); close() puts the
	 * context class loader back and closes the loader.
	 */
	private static final class Without implements AutoCloseable {

		final URLClassLoader loader;
		private final ClassLoader previous;

		Without(String jar) throws MalformedURLException {
			// surefire puts the real class path here (java.class.path may be its manifest-only jar)
			String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
			List<URL> urls = new ArrayList<>();
			int left = 0;
			for (String entry : cp.split(File.pathSeparator)) {
				if( entry.isEmpty() ) {
					continue;
				}
				File f = new File(entry);
				if( f.getName().startsWith(jar) && f.getName().endsWith(".jar") ) {
					left++;
				} else {
					urls.add(f.toURI().toURL());
				}
			}
			assertTrue(left > 0, jar + " isn't on the test class path, so leaving it out proves nothing: " + cp);
			loader = new URLClassLoader("vfs-without-" + jar, urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
			previous = Thread.currentThread().getContextClassLoader();
			Thread.currentThread().setContextClassLoader(loader);
		}

		/** The class is really missing here. */
		void lacks(String className) {
			assertThrows(ClassNotFoundException.class, () -> Class.forName(className, false, loader), className);
		}

		Object callStatic(String className, String method, Class<?>[] types, Object... args) throws Exception {
			return invoke(Class.forName(className, true, loader).getMethod(method, types), null, args);
		}

		@Override
		public void close() throws IOException {
			Thread.currentThread().setContextClassLoader(previous);
			loader.close();
		}
	}

	private static final String FACTORY = "us.bringardner.parley.files.FileSourceFactory";
	/** How many settings each factory shows (as VfsSftpFactoryTest and VfsFtpFactoryTest check; an archive's: one). */
	private static final int SFTP_SETTINGS = 10;
	private static final int FTP_SETTINGS = 6;
	private static final int FTPS_SETTINGS = 5;

	/** Calls a public method by name, throwing what it throws (not InvocationTargetException). */
	private static Object call(Object target, String method, Class<?>[] types, Object... args) throws Exception {
		return invoke(target.getClass().getMethod(method, types), target, args);
	}

	private static Object call(Object target, String method) throws Exception {
		return call(target, method, new Class<?>[0]);
	}

	private static Object invoke(Method m, Object target, Object... args) throws Exception {
		try {
			return m.invoke(target, args);
		} catch (InvocationTargetException e) {
			Throwable t = e.getCause();
			if( t instanceof Exception ) {
				throw (Exception) t;
			}
			throw (Error) t;
		}
	}

	/** The registry knows TYPE and makes a factory for it whose settings can be read. */
	private static Object registered(Without w, String type, int settings) throws Exception {
		assertEquals(Boolean.TRUE, w.callStatic(FACTORY, "isRegisteredFactory", new Class<?>[] { String.class }, type),
				type + " isn't registered: its factory didn't load");
		Object f = w.callStatic(FACTORY, "getFileSourceFactory", new Class<?>[] { String.class }, type);
		assertNotNull(f, type);
		assertEquals(type, call(f, "getTypeId"));
		assertEquals(settings, ((List<?>) call(f, "getConnectionSettings")).size(), type);
		assertNotNull(call(f, "getConnectProperties"));
		return f;
	}

	/**
	 * Connecting fails with an IOException, not an Error such as NoClassDefFoundError, and leaves the
	 * factory disconnected; asked again, it says the same.
	 */
	private static IOException connectFails(Object factory, Properties p) throws Exception {
		call(factory, "setConnectionProperties", new Class<?>[] { Properties.class }, p);
		try {
			call(factory, "connect");
			fail("connected without its library");
			return null;
		} catch (IOException e) {
			assertEquals(Boolean.FALSE, call(factory, "isConnected"));
			IOException again = assertThrows(IOException.class, () -> call(factory, "connect"), "a second connect");
			assertEquals(e.getMessage(), again.getMessage());
			return e;
		} catch (Error e) {
			throw new AssertionError("connect threw " + e + " instead of an IOException", e);
		} finally {
			call(factory, "disConnect");
		}
	}

	/** Connects with P, writes a file and reads it back, deletes it and disconnects. */
	private static void works(Object factory, Properties p, String name) throws Exception {
		call(factory, "setConnectionProperties", new Class<?>[] { Properties.class }, p);
		try {
			assertEquals(Boolean.TRUE, call(factory, "connect"));
			Object file = call(factory, "createFileSource", new Class<?>[] { String.class }, "/" + name);
			try (OutputStream out = (OutputStream) call(file, "getOutputStream")) {
				out.write(name.getBytes(StandardCharsets.UTF_8));
			}
			try (InputStream in = (InputStream) call(file, "getInputStream")) {
				assertEquals(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
			assertEquals(Boolean.TRUE, call(file, "delete"));
		} finally {
			call(factory, "disConnect");
		}
	}

	// ------------------------------------------------------------------ the tests

	private static Object newFactory(Without w, String type) throws Exception {
		return w.callStatic(FACTORY, "getFileSourceFactory", new Class<?>[] { String.class }, type);
	}

	/** An application with vfsftp only: vfssftp loads but can't connect; vfsftp works. */
	@Test
	void withoutJSch() throws Exception {
		try (Without w = new Without("jsch-")) {
			w.lacks("com.jcraft.jsch.JSch");
			registered(w, "vfsftp", FTP_SETTINGS);
			registered(w, "vfsftps", FTPS_SETTINGS);
			IOException e = connectFails(registered(w, "vfssftp", SFTP_SETTINGS), sftp.properties());
			assertEquals("vfssftp needs JSch (com.github.mwiede:jsch) on the class path (com.jcraft.jsch.JSch is missing)", e.getMessage());
			works(newFactory(w, "vfsftp"), ftp.properties(), "no-jsch.txt");
		}
	}

	/** An application with vfssftp only: vfsftp and vfsftps load but can't connect; vfssftp works. */
	@Test
	void withoutCommonsNet() throws Exception {
		try (Without w = new Without("commons-net-")) {
			w.lacks("org.apache.commons.net.ftp.FTPClient");
			registered(w, "vfssftp", SFTP_SETTINGS);
			registered(w, "vfsftps", FTPS_SETTINGS);
			IOException e = connectFails(registered(w, "vfsftp", FTP_SETTINGS), ftp.properties());
			assertEquals("vfsftp needs commons-net (commons-net:commons-net) on the class path"
					+ " (org.apache.commons.net.ftp.FTPClient is missing)", e.getMessage());
			Properties tls = ftp.properties();
			tls.setProperty("port", "990");
			e = connectFails(newFactory(w, "vfsftps"), tls);
			assertTrue(e.getMessage().startsWith("vfsftps needs commons-net"), e.getMessage());
			works(newFactory(w, "vfssftp"), sftp.properties(), "no-commons-net.txt");
		}
	}

	/**
	 * An application without commons-compress: vfstar, vfstgz, vfstbz2 and vfsbz2 load but can't
	 * connect, saying which jar to add; vfszip and vfsgz, which need nothing, work.
	 */
	@Test
	void withoutCommonsCompress() throws Exception {
		java.nio.file.Path dir = java.nio.file.Paths.get(us.bringardner.parley.files.test.FileSourceTestSupport.scratchDir("vfs-no-compress"));
		java.nio.file.Files.createDirectories(dir);
		java.nio.file.Path zip = TestArchives.write("zip", dir.resolve("t.zip"));
		java.nio.file.Path gz = TestArchives.compressed("gz", dir.resolve("t.txt.gz"), "gz");
		try (Without w = new Without("commons-compress-")) {
			w.lacks("org.apache.commons.compress.archivers.tar.TarArchiveInputStream");
			for (String type : new String[] { "vfstar", "vfstgz", "vfstbz2", "vfsbz2" }) {
				Properties p = new Properties();
				p.setProperty("archive", dir.resolve("t." + type.substring(3)).toString());
				IOException e = connectFails(registered(w, type, 1), p);
				assertTrue(e.getMessage().startsWith(type + " needs commons-compress (org.apache.commons:commons-compress) on the class path"),
						e.getMessage());
			}
			for (String[] each : new String[][] { { "vfszip", zip.toString(), "/a.txt", "hello" }, { "vfsgz", gz.toString(), "/t.txt", "gz" } }) {
				Object f = registered(w, each[0], 1);
				Properties p = new Properties();
				p.setProperty("archive", each[1]);
				call(f, "setConnectionProperties", new Class<?>[] { Properties.class }, p);
				try {
					assertEquals(Boolean.TRUE, call(f, "connect"));
					Object file = call(f, "createFileSource", new Class<?>[] { String.class }, each[2]);
					try (InputStream in = (InputStream) call(file, "getInputStream")) {
						assertEquals(each[3], new String(in.readAllBytes(), StandardCharsets.UTF_8));
					}
				} finally {
					call(f, "disConnect");
				}
			}
		}
	}

	/** An application without httpclient5: vfshttp and vfshttps load but can't connect, saying which jar to add. */
	@Test
	void withoutHttpClient5() throws Exception {
		try (Without w = new Without("httpclient5-")) {
			w.lacks("org.apache.hc.client5.http.classic.HttpClient");
			for (String type : new String[] { "vfshttp", "vfshttps" }) {
				Properties p = new Properties();
				p.setProperty("host", "127.0.0.1");
				IOException e = connectFails(registered(w, type, 5), p);
				assertEquals(type + " needs httpclient5 (org.apache.httpcomponents.client5:httpclient5) on the class path"
						+ " (org.apache.hc.client5.http.classic.HttpClient is missing)", e.getMessage());
			}
			works(newFactory(w, "vfsftp"), ftp.properties(), "no-httpclient5.txt");
		}
	}

	/** An application without commons-vfs2-jackrabbit2: vfswebdav and vfswebdavs load but can't connect, saying which jar to add. */
	@Test
	void withoutJackrabbit2() throws Exception {
		try (Without w = new Without("commons-vfs2-jackrabbit2-")) {
			w.lacks("org.apache.commons.vfs2.provider.webdav4.Webdav4FileProvider");
			for (String type : new String[] { "vfswebdav", "vfswebdavs" }) {
				Properties p = new Properties();
				p.setProperty("host", "127.0.0.1");
				IOException e = connectFails(registered(w, type, 5), p);
				assertEquals(type + " needs commons-vfs2-jackrabbit2 (org.apache.commons:commons-vfs2-jackrabbit2) on the class path"
						+ " (org.apache.commons.vfs2.provider.webdav4.Webdav4FileProvider is missing)", e.getMessage());
			}
			works(newFactory(w, "vfsftp"), ftp.properties(), "no-jackrabbit2.txt");
		}
	}
}

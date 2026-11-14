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
import java.net.URI;
import java.net.URL;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.LogLevel;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * vfshdfs without a cluster (docs/DESIGN.md, 18): its registration, settings and URLs, and, on the
 * normal test class path (no commons-vfs2-hdfs), a connect that says which library to add. The
 * cluster tests are {@link TestVfsHdfs}, run only with -Dparley.test.hdfs.
 */
public class VfsHdfsFactoryTest {

	/** The cluster given for the tests (hdfs://namenode:8020/dir), or null. */
	static String cluster() {
		String ret = System.getProperty("parley.test.hdfs");
		return ret == null || ret.trim().isEmpty() ? null : ret.trim();
	}

	/** A factory for the cluster's name node, from the URL given. */
	static VfsHdfsFileSourceFactory forCluster(String url) {
		URI u = URI.create(url);
		VfsHdfsFileSourceFactory f = new VfsHdfsFileSourceFactory();
		f.setLogLevel(LogLevel.ERROR);
		f.setHost(u.getHost());
		f.setPort(u.getPort());
		return f;
	}

	@Test
	void registered() {
		assertTrue(FileSourceFactory.getFileSourceFactory("vfshdfs") instanceof VfsHdfsFileSourceFactory);
	}

	@Test
	void settingsAndUrls() throws IOException {
		VfsHdfsFileSourceFactory f = new VfsHdfsFileSourceFactory();
		f.setConnectionProperties(new URL("vfshdfs://NameNode:9000/user/tony/a.txt"));
		assertEquals("NameNode", f.getHost());
		assertEquals(9000, f.getPort());
		assertEquals("vfshdfs://namenode:9000/", f.getURL());
		assertEquals("filesource://namenode:9000/user/tony/a.txt?sourcetype=vfshdfs".replace("namenode", "NameNode"),
				f.createFileSource("/user/tony/a.txt").toURL().toString());
		assertEquals(8020, new VfsHdfsFileSourceFactory().getPort());
		assertEquals(3, f.getConnectionSettings().size());
		f.setConfig("/etc/hadoop/hdfs-site.xml");
		VfsHdfsFileSourceFactory back = new VfsHdfsFileSourceFactory();
		back.setConnectionProperties(f.getConnectProperties());
		assertEquals("/etc/hadoop/hdfs-site.xml", back.getConfig());
		assertTrue(f.isSameFileSystem(back));
		assertTrue(f.createFileSource("/a").toURL().toString().endsWith("&config=%2Fetc%2Fhadoop%2Fhdfs-site.xml"));
		VfsHdfsFileSourceFactory other = new VfsHdfsFileSourceFactory();
		other.setHost("namenode");
		assertFalse(f.isSameFileSystem(other), "another port");
	}

	/** On the normal test class path there is no HDFS provider: connect says what to add. */
	@Test
	void withoutTheLibraryConnectSaysWhatToAdd() {
		if( cluster() != null ) {
			return;
		}
		VfsHdfsFileSourceFactory f = new VfsHdfsFileSourceFactory();
		f.setLogLevel(LogLevel.ERROR);
		f.setHost("127.0.0.1");
		IOException e = assertThrows(IOException.class, f::connect);
		assertEquals("vfshdfs needs commons-vfs2-hdfs (org.apache.commons:commons-vfs2-hdfs) and Hadoop's client"
				+ " (org.apache.hadoop:hadoop-client) on the class path (org.apache.commons.vfs2.provider.hdfs.HdfsFileProvider is missing)",
				e.getMessage());
		assertFalse(f.isConnected());
	}

	/** With a cluster given: its URL opens a FileSource. */
	@Test
	void aClusterUrlOpens() throws IOException {
		String c = cluster();
		org.junit.jupiter.api.Assumptions.assumeTrue(c != null, "no cluster given (-Dparley.test.hdfs=hdfs://namenode:8020/dir)");
		FileSource dir = FileSourceFactory.getFileSource("vfs" + c);
		try {
			assertTrue(dir.getFileSourceFactory() instanceof VfsHdfsFileSourceFactory);
			assertEquals(URI.create(c).getPath().replaceAll("/+$", ""), dir.getAbsolutePath());
		} finally {
			dir.getFileSourceFactory().disConnect();
		}
	}

	private static boolean libraryPresent() {
		try {
			Class.forName("org.apache.commons.vfs2.provider.hdfs.HdfsFileProvider");
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	/**
	 * With the library (the hdfs profile): a missing configuration file says so; a present one is
	 * given to VFS (by reflection), and a name node nobody listens on fails the connect with the reason.
	 */
	@Test
	void withTheLibrary() throws IOException {
		org.junit.jupiter.api.Assumptions.assumeTrue(libraryPresent(), "no commons-vfs2-hdfs (the hdfs profile)");
		VfsHdfsFileSourceFactory f = new VfsHdfsFileSourceFactory();
		f.setLogLevel(LogLevel.ERROR);
		f.setHost("127.0.0.1");
		try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
			f.setPort(s.getLocalPort());
		}
		java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("parley-vfs-hdfs");
		f.setConfig(dir.resolve("none.xml").toString());
		IOException e = assertThrows(IOException.class, f::connect);
		assertEquals("vfshdfs: the Hadoop configuration file " + dir.resolve("none.xml") + " isn't there", e.getMessage());
		java.nio.file.Path conf = java.nio.file.Files.write(dir.resolve("core-site.xml"),
				"<configuration><property><name>ipc.client.connect.max.retries</name><value>0</value></property></configuration>"
						.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		f.setConfig(conf.toString());
		e = assertThrows(IOException.class, f::connect);
		assertTrue(e.getMessage().startsWith(f.getURL() + ": java.net.ConnectException"), e.getMessage());
		assertFalse(f.isConnected());
	}
}

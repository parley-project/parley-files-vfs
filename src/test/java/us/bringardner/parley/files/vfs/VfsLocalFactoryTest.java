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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.LogLevel;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.test.FileSourceTestSupport;

/**
 * vfsfile, vfstmp, vfsram and vfsres themselves (docs/DESIGN.md, 14): their settings, URLs, the
 * root a vfsfile factory can't leave, the file systems vfsram and vfstmp share by name, and vfsres
 * reading the class path, read only. Every factory a test makes is disconnected after it.
 */
public class VfsLocalFactoryTest {

	private final List<FileSourceFactory> made = new ArrayList<>();

	@AfterEach
	void disconnectAll() throws IOException {
		for (FileSourceFactory f : made) {
			f.disConnect();
		}
		made.clear();
	}

	private <T extends VfsFileSourceFactory> T connected(T f, Properties p) throws IOException {
		f.setLogLevel(LogLevel.ERROR);
		if( p != null ) {
			f.setConnectionProperties(p);
		}
		made.add(f);
		assertTrue(f.connect(), "can't connect " + f.getTypeId());
		return f;
	}

	private static Properties props(String key, String value) {
		Properties p = new Properties();
		p.setProperty(key, value);
		return p;
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

	private static Path newDir(String name) throws IOException {
		Path ret = Paths.get(FileSourceTestSupport.scratchDir(name));
		Files.createDirectories(ret);
		return ret;
	}

	@Test
	void registeredUnderTheirTypeIds() {
		assertTrue(FileSourceFactory.getFileSourceFactory("vfsfile") instanceof VfsFileFileSourceFactory);
		assertTrue(FileSourceFactory.getFileSourceFactory("vfstmp") instanceof VfsTmpFileSourceFactory);
		assertTrue(FileSourceFactory.getFileSourceFactory("vfsram") instanceof VfsRamFileSourceFactory);
		assertTrue(FileSourceFactory.getFileSourceFactory("vfsres") instanceof VfsResFileSourceFactory);
	}

	// ------------------------------------------------------------------ vfsfile

	/** DESIGN 14.1: paths are from the root, and ".." at the root stays there. */
	@Test
	void vfsfilePathsAreFromItsRootAndCantLeaveIt() throws IOException {
		Path home = newDir("vfs-local-root");
		Files.write(home.resolve("in.txt"), "in".getBytes(StandardCharsets.UTF_8));
		Files.write(home.getParent().resolve("outside-vfs-local-root.txt"), "out".getBytes(StandardCharsets.UTF_8));
		VfsFileFileSourceFactory f = connected(new VfsFileFileSourceFactory(),
				props(VfsFileFileSourceFactory.PROP_ROOT, home.toString()));
		assertEquals("in", read(f.createFileSource("/in.txt")));
		FileSource up = f.createFileSource("/../outside-vfs-local-root.txt");
		assertEquals("/outside-vfs-local-root.txt", up.getAbsolutePath());
		assertFalse(up.exists(), "nothing above the root");
		FileSource made = f.createFileSource("/sub/new.txt");
		assertTrue(made.getParentFile().mkdir());
		write(made, "new");
		assertEquals("new", new String(Files.readAllBytes(home.resolve("sub").resolve("new.txt")), StandardCharsets.UTF_8));
		assertArrayEquals(new String[] { "in.txt", "sub" }, sorted(f.createFileSource("/").list()));
	}

	private static String[] sorted(String[] names) {
		Arrays.sort(names);
		return names;
	}

	@Test
	void vfsfileWithoutARootServesTheWholeDisk() throws IOException {
		Path home = newDir("vfs-local-whole");
		Files.write(home.resolve("a.txt"), "a".getBytes(StandardCharsets.UTF_8));
		VfsFileFileSourceFactory f = connected(new VfsFileFileSourceFactory(), null);
		assertEquals(VfsFileFileSourceFactory.defaultRoot(), f.getRoot());
		if( "/".equals(f.getRoot()) ) {
			assertEquals("a", read(f.createFileSource(home.resolve("a.txt").toString())));
		}
	}

	@Test
	void vfsfileRootMustBeAFolder() throws IOException {
		Path home = newDir("vfs-local-notafolder");
		Path file = home.resolve("plain.txt");
		Files.write(file, "x".getBytes(StandardCharsets.UTF_8));
		for (Path bad : new Path[] { file, home.resolve("missing") }) {
			VfsFileFileSourceFactory f = new VfsFileFileSourceFactory();
			f.setLogLevel(LogLevel.ERROR);
			f.setRoot(bad.toString());
			made.add(f);
			IOException e = assertThrows(IOException.class, f::connect);
			assertTrue(e.getMessage().contains(bad.toString()), e.getMessage());
			assertFalse(f.isConnected());
		}
	}

	@Test
	void vfsfileUrls() throws IOException {
		Path home = newDir("vfs-local-urls");
		Files.write(home.resolve("u.txt"), "url".getBytes(StandardCharsets.UTF_8));
		VfsFileFileSourceFactory f = connected(new VfsFileFileSourceFactory(),
				props(VfsFileFileSourceFactory.PROP_ROOT, home.toString()));
		String rootUri = home.toAbsolutePath().normalize().toUri().toString();
		assertEquals("vfs" + rootUri, f.getURL());
		FileSource u = f.createFileSource("/u.txt");
		assertEquals(rootUri + "u.txt", ((VfsFileSource) u).getVfsUri());
		String fsUrl = u.toURL().toString();
		assertTrue(fsUrl.startsWith("filesource:/u.txt?sourcetype=vfsfile&root="), fsUrl);
		FileSource again = FileSourceFactory.getFileSource(fsUrl);
		made.add(again.getFileSourceFactory());
		assertEquals("url", read(again));
		assertTrue(f.isSameFileSystem(again.getFileSourceFactory()));
		// VFS's own URL with vfs in front: the disk's path, from the whole disk
		if( "/".equals(VfsFileFileSourceFactory.defaultRoot()) ) {
			FileSource byVfsUrl = FileSourceFactory.getFileSource("vfs" + rootUri + "u.txt");
			made.add(byVfsUrl.getFileSourceFactory());
			assertEquals("url", read(byVfsUrl));
			java.net.URLConnection c = new URL("vfs" + rootUri + "u.txt").openConnection();
			try (InputStream in = c.getInputStream()) {
				assertEquals("url", new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
		}
	}

	@Test
	void vfsfileSettingsRoundTrip() throws IOException {
		Path home = newDir("vfs-local-settings");
		VfsFileFileSourceFactory f = new VfsFileFileSourceFactory();
		f.setConnectionProperties(props(VfsFileFileSourceFactory.PROP_ROOT, home.toString()));
		VfsFileFileSourceFactory back = new VfsFileFileSourceFactory();
		back.setConnectionProperties(f.getConnectProperties());
		assertEquals(home.toString(), back.getRoot());
		assertTrue(f.isSameFileSystem(back));
		assertFalse(f.isSameFileSystem(new VfsFileFileSourceFactory()), "another root");
		assertEquals(1, f.getConnectionSettings().size());
		FileSourceFactory copy = connected(f, null).createThreadSafeCopy();
		made.add(copy);
		assertTrue(f.isSameFileSystem(copy));
	}

	// ------------------------------------------------------------------ vfsram and vfstmp

	/** DESIGN 14.2: the same name is the same file system, for factories and their copies. */
	@Test
	void ramAndTmpAreSharedByName() throws IOException {
		for (VfsNamedFileSourceFactory a : new VfsNamedFileSourceFactory[] { new VfsRamFileSourceFactory(),
				new VfsTmpFileSourceFactory() }) {
			String type = a.getTypeId();
			connected(a, props(VfsNamedFileSourceFactory.PROP_NAME, "shared-" + type));
			write(a.createFileSource("/s.txt"), "shared");
			FileSourceFactory copy = a.createThreadSafeCopy();
			made.add(copy);
			assertTrue(copy.connect());
			assertEquals("shared", read(copy.createFileSource("/s.txt")), type + ": a copy sees the same files");
			assertTrue(a.isSameFileSystem(copy));
			VfsNamedFileSourceFactory other = connected(newOf(a), props(VfsNamedFileSourceFactory.PROP_NAME, "other-" + type));
			assertFalse(other.createFileSource("/s.txt").exists(), type + ": another name, another file system");
			assertFalse(a.isSameFileSystem(other));
			assertEquals(2, SharedManagers.users(a.sharedKey()));
			copy.disConnect();
			assertEquals(1, SharedManagers.users(a.sharedKey()));
			assertEquals("shared", read(a.createFileSource("/s.txt")), type + ": still there while one is connected");
		}
	}

	private static VfsNamedFileSourceFactory newOf(VfsNamedFileSourceFactory f) {
		return f instanceof VfsRamFileSourceFactory ? new VfsRamFileSourceFactory() : new VfsTmpFileSourceFactory();
	}

	/** DESIGN 14.2: the file system lasts until the last factory with its name disconnects. */
	@Test
	void ramAndTmpAreGoneWhenTheLastDisconnects() throws IOException {
		for (VfsNamedFileSourceFactory a : new VfsNamedFileSourceFactory[] { new VfsRamFileSourceFactory(),
				new VfsTmpFileSourceFactory() }) {
			String name = "gone-" + a.getTypeId();
			connected(a, props(VfsNamedFileSourceFactory.PROP_NAME, name));
			FileSource f = a.createFileSource("/g.txt");
			write(f, "g");
			Path local = a instanceof VfsTmpFileSourceFactory ? LocalBits.path(((VfsFileSource) f).fo(), a) : null;
			if( local != null ) {
				assertTrue(Files.exists(local), "vfstmp's file is on the disk: " + local);
			}
			a.disConnect();
			assertEquals(0, SharedManagers.users(a.sharedKey()));
			if( local != null ) {
				assertFalse(Files.exists(local), "vfstmp's files are deleted with its last factory: " + local);
			}
			VfsNamedFileSourceFactory b = connected(newOf(a), props(VfsNamedFileSourceFactory.PROP_NAME, name));
			assertFalse(b.createFileSource("/g.txt").exists(), a.getTypeId() + ": a new file system");
		}
	}

	/**
	 * README A20: a manager's temporary files are in a folder of its own, deleted when it closes, never
	 * in VFS's shared java.io.tmpdir/vfs_cache (which another user may own).
	 */
	@Test
	void eachManagerHasATemporaryFolderOfItsOwn() throws Exception {
		VfsTmpFileSourceFactory a = connected(new VfsTmpFileSourceFactory(), props(VfsNamedFileSourceFactory.PROP_NAME, "own-temp"));
		java.lang.reflect.Field mf = VfsFileSourceFactory.class.getDeclaredField("manager");
		mf.setAccessible(true);
		Path temp = ((VfsManager) mf.get(a)).tempDir();
		assertTrue(Files.isDirectory(temp));
		assertFalse(temp.toString().contains("vfs_cache" + java.io.File.separator + "tmp_"), temp.toString());
		FileSource f = a.createFileSource("/t.txt");
		write(f, "t");
		Path local = LocalBits.path(((VfsFileSource) f).fo(), a);
		assertTrue(local.startsWith(temp), local + " is under " + temp);
		a.disConnect();
		assertFalse(Files.exists(temp), "deleted with its manager");
	}

	@Test
	void ramAndTmpUrls() throws IOException {
		VfsRamFileSourceFactory def = new VfsRamFileSourceFactory();
		assertEquals("vfsram:///", def.getURL());
		VfsRamFileSourceFactory named = connected(new VfsRamFileSourceFactory(), props(VfsNamedFileSourceFactory.PROP_NAME, "url test"));
		assertEquals("vfsram:///?name=url%20test", named.getURL());
		write(named.createFileSource("/r.txt"), "ram");
		FileSource byUrl = FileSourceFactory.getFileSource("vfsram:///r.txt?name=url%20test");
		made.add(byUrl.getFileSourceFactory());
		assertEquals("ram", read(byUrl));
		String fsUrl = named.createFileSource("/r.txt").toURL().toString();
		assertEquals("filesource:/r.txt?sourcetype=vfsram&name=url%20test", fsUrl);
		FileSource again = FileSourceFactory.getFileSource(fsUrl);
		made.add(again.getFileSourceFactory());
		assertEquals("ram", read(again));
		assertEquals("vfstmp:///", new VfsTmpFileSourceFactory().getURL());
	}

	// ------------------------------------------------------------------ vfsres

	/** DESIGN 14.4: /a/b is the resource a/b, wherever on the class path it is. */
	@Test
	void vfsresReadsTheClassPath() throws IOException {
		VfsResFileSourceFactory f = connected(new VfsResFileSourceFactory(), null);
		FileSource xml = f.createFileSource("/org/apache/commons/vfs2/impl/providers.xml");
		assertTrue(xml.exists() && xml.isFile());
		assertTrue(read(xml).contains("<providers>"));
		assertTrue(xml.length() > 0);
		FileSource pkg = xml.getParentFile();
		assertTrue(pkg.isDirectory());
		assertTrue(Arrays.asList(pkg.list()).contains("providers.xml"));
		FileSource listed = null;
		for (FileSource each : pkg.listFiles()) {
			if( each.getName().equals("providers.xml") ) {
				listed = each;
			}
		}
		assertNotNull(listed);
		assertEquals("/org/apache/commons/vfs2/impl/providers.xml", listed.getAbsolutePath());
		FileSource missing = f.createFileSource("/no/such/resource.txt");
		assertFalse(missing.exists());
		FileSource root = f.createFileSource("/");
		assertTrue(root.isDirectory());
		assertEquals(0, root.listFiles().length, "the class path can't be listed");
	}

	/** README A14: a write on a read-only file system says so. */
	@Test
	void vfsresIsReadOnly() throws IOException {
		VfsResFileSourceFactory f = connected(new VfsResFileSourceFactory(), null);
		FileSource xml = f.createFileSource("/org/apache/commons/vfs2/impl/providers.xml");
		for (IOException e : new IOException[] { assertThrows(IOException.class, () -> xml.getOutputStream().close()),
				assertThrows(IOException.class, () -> f.createFileSource("/new.txt").createNewFile()),
				assertThrows(IOException.class, () -> f.createFileSource("/newdir").mkdir()),
				assertThrows(IOException.class, xml::delete) }) {
			assertTrue(e.getMessage().contains("read only"), e.getMessage());
		}
		assertTrue(xml.exists(), "still there");
	}

	@Test
	void vfsresUrls() throws IOException {
		assertEquals("vfsres:/", new VfsResFileSourceFactory().getURL());
		FileSource byUrl = FileSourceFactory.getFileSource("vfsres:/org/apache/commons/vfs2/impl/providers.xml");
		made.add(byUrl.getFileSourceFactory());
		assertTrue(byUrl.getFileSourceFactory() instanceof VfsResFileSourceFactory);
		assertTrue(read(byUrl).contains("<providers>"));
		assertTrue(new VfsResFileSourceFactory().isSameFileSystem(new VfsResFileSourceFactory()), "there is one class path");
		assertEquals(0, new VfsResFileSourceFactory().getConnectionSettings().size());
	}
}

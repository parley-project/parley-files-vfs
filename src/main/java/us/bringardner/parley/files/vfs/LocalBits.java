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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import org.apache.commons.vfs2.FileObject;

import us.bringardner.parley.core.BaseObject;

/**
 * The permission bits of a file VFS keeps on the local disk (vfsfile, vfstmp), read and set with
 * java.nio's POSIX attributes, as parley-files' local FileSource does: VFS's local provider gives
 * only what the user can do (docs/DESIGN.md, 14.3). Null where the disk has no POSIX bits (Windows)
 * or the file can't be found; the caller then falls back to VFS.
 */
final class LocalBits {

	/** VFS's protected LocalFile.getLocalFile(), for a tmp: file, whose URI no java.nio provider takes. */
	private static volatile Method localFile;
	private static volatile boolean hookFailed;

	private static final PosixFilePermission[] ORDER = {
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
			PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
			PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE };

	private LocalBits() {
	}

	/** The local file FO is, or null if it can't be found (logged once through LOG). */
	static Path path(FileObject fo, BaseObject log) {
		if( "file".equals(fo.getName().getScheme()) ) {
			try {
				return fo.getPath();
			} catch (RuntimeException e) {
				return null;
			}
		}
		if( hookFailed ) {
			return null;
		}
		try {
			Method m = localFile;
			if( m == null ) {
				m = find(fo.getClass());
				m.setAccessible(true);
				localFile = m;
			}
			Object f = m.invoke(fo);
			return f instanceof File ? ((File) f).toPath() : null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			hookFailed = true;
			log.logError("Can't find the local file of " + fo.getClass().getName()
					+ "; owner, group and other permissions read as false: " + e);
			return null;
		}
	}

	private static Method find(Class<?> cls) throws NoSuchMethodException {
		for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredMethod("getLocalFile");
			} catch (NoSuchMethodException e) {
				// try the superclass
			}
		}
		throw new NoSuchMethodException(cls.getName() + ".getLocalFile()");
	}

	/** The nine bits of P, or null without POSIX attributes. */
	static Integer mode(Path p) {
		if( p == null ) {
			return null;
		}
		try {
			Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
			int ret = 0;
			for (PosixFilePermission each : ORDER) {
				ret = (ret << 1) | (perms.contains(each) ? 1 : 0);
			}
			return ret;
		} catch (IOException | UnsupportedOperationException e) {
			return null;
		}
	}

	/**
	 * Sets and clears bits of P (nine bits: 0400 is the owner's read), the others as they are.
	 * @return true if done, null without POSIX attributes (use VFS's setters)
	 * @throws IOException if they can't be set
	 */
	static Boolean change(Path p, int set, int clear) throws IOException {
		Integer mode = mode(p);
		if( mode == null ) {
			return null;
		}
		int want = (mode | set) & ~clear & 0777;
		Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
		for (int i = 0; i < ORDER.length; i++) {
			if( (want & (0400 >> i)) != 0 ) {
				perms.add(ORDER[i]);
			}
		}
		Files.setPosixFilePermissions(p, perms);
		return true;
	}
}

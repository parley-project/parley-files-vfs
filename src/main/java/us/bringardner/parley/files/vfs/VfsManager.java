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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import org.apache.commons.vfs2.impl.DefaultFileReplicator;
import org.apache.commons.vfs2.impl.StandardFileSystemManager;

/**
 * A VFS manager whose temporary files (vfstmp's folder, and the copies VFS makes of an archive
 * inside an archive or on a server) are in a folder of its own, made in java.io.tmpdir and deleted
 * when the manager closes (docs/DESIGN.md, 19).
 * <p>
 * VFS left to itself puts every manager's in one fixed folder, java.io.tmpdir/vfs_cache: the first
 * user to make it owns it, and another user's vfstmp and nested archives then fail ("Could not
 * create folder", "Could not replicate").
 */
final class VfsManager extends StandardFileSystemManager {

	private final Path tempDir;

	VfsManager() throws IOException {
		// VFS finds its providers.xml beside the manager's class, which is in another package here
		setConfiguration(StandardFileSystemManager.class.getResource("providers.xml"));
		tempDir = Files.createTempDirectory("parley-vfs-");
	}

	/** @return the folder this manager's temporary files are in */
	Path tempDir() {
		return tempDir;
	}

	@Override
	protected DefaultFileReplicator createDefaultFileReplicator() {
		return new DefaultFileReplicator(new File(tempDir.toFile(), "vfs_cache"));
	}

	/** Closes the manager, then deletes its temporary folder and whatever is left in it. */
	@Override
	public void close() {
		try {
			super.close();
		} finally {
			deleteTree(tempDir);
		}
	}

	private static void deleteTree(Path dir) {
		if( !Files.exists(dir) ) {
			return;
		}
		try {
			Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
					Files.deleteIfExists(d);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			// left for the system's temporary-file cleaning
		}
	}
}

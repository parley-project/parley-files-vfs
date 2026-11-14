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

import org.apache.commons.vfs2.RandomAccessContent;

import us.bringardner.parley.files.AbstractRandomAccessIoController;

/**
 * Random access in chunks over VFS's {@code RandomAccessContent}: a chunk is read with a seek and a
 * read, written with a seek and a write, and the length set with VFS's setLength. Writing needs a
 * file system with random access writing, which no remote VFS scheme has (docs/DESIGN.md, 2.1).
 */
final class VfsRandomAccessIoController extends AbstractRandomAccessIoController {

	/** Bytes read or written in one go. */
	static final int CHUNK_SIZE = 64 * 1024;

	private final RandomAccessContent rac;
	private boolean closed;

	VfsRandomAccessIoController(VfsFileSource file, RandomAccessContent rac) throws IOException {
		super(file);
		this.rac = rac;
	}

	@Override
	protected Chunk readChunkForPos(long pos) throws IOException {
		Chunk ret = new Chunk();
		long len = length();
		if( len == 0 || pos >= len ) {
			ret.size = 0;
			ret.data = new byte[CHUNK_SIZE];
			ret.start = len;
			ret.isNew = true;
		} else {
			ret.start = (pos / CHUNK_SIZE) * CHUNK_SIZE;
			int n = (int) Math.min(CHUNK_SIZE, len - ret.start);
			ret.data = new byte[n];
			rac.seek(ret.start);
			rac.readFully(ret.data, 0, n);
			ret.size = n;
		}
		return ret;
	}

	@Override
	protected void writeChunk(Chunk chunk) throws IOException {
		rac.seek(chunk.start);
		rac.write(chunk.data, 0, chunk.data.length);
	}

	@Override
	protected void setLength0(long newLength) throws IOException {
		rac.setLength(newLength);
	}

	@Override
	public void close() throws Exception {
		if( closed ) {
			return;
		}
		closed = true;
		try {
			super.close();
		} finally {
			rac.close();
		}
	}
}

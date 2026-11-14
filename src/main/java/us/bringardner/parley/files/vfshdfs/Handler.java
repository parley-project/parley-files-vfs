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
package us.bringardner.parley.files.vfshdfs;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

import us.bringardner.parley.files.vfs.VfsUrlConnection;

/**
 * The {@code vfshdfs:} URL scheme ({@code vfshdfs://namenode:8020/user/tony/a.txt}): VFS's hdfs URL with {@code vfs} in front. The
 * JDK finds it through parley-files' {@code java.protocol.handler.pkgs} entry, and
 * {@code FileSourceFactory.getFileSource(URL)} opens it with the {@code vfshdfs} factory, as does
 * {@code openConnection()} ({@link VfsUrlConnection}).
 */
public class Handler extends URLStreamHandler {

	@Override
	protected URLConnection openConnection(URL url) throws IOException {
		return new VfsUrlConnection(url);
	}
}

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

/**
 * A WebDAV server over HTTPS (type id {@value #FACTORY_ID}, VFS's webdav4s scheme): vfswebdav with TLS,
 * port 443 by default, the server's certificate checked as Parley checks it. Add
 * {@code org.apache.commons:commons-vfs2-jackrabbit2} to use it.
 */
public class VfsWebdavsFileSourceFactory extends VfsWebdavFileSourceFactory {

	private static final long serialVersionUID = 1L;

	public static final String FACTORY_ID = TYPE_ID_PREFIX + "webdavs";

	@Override
	public String getTypeId() {
		return FACTORY_ID;
	}

	@Override
	protected boolean tls() {
		return true;
	}
}

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

import org.apache.commons.vfs2.UserAuthenticationData;
import org.apache.commons.vfs2.UserAuthenticator;

/**
 * Hands VFS the user and password when it opens a connection. The password setting may be a
 * reference (a keychain entry, say): it is resolved here, when VFS asks, through
 * {@code FileSourceFactory.secret}, and given to VFS as a {@code char[]}, which VFS clears when the
 * login is done. So no password sits in a String in the file system's options, as it would with
 * VFS's {@code StaticUserAuthenticator}.
 */
final class VfsAuthenticator implements UserAuthenticator {

	private final VfsFileSourceFactory factory;

	VfsAuthenticator(VfsFileSourceFactory factory) {
		this.factory = factory;
	}

	@Override
	public UserAuthenticationData requestAuthentication(UserAuthenticationData.Type[] types) {
		UserAuthenticationData ret = new UserAuthenticationData();
		for (UserAuthenticationData.Type t : types) {
			if( UserAuthenticationData.USERNAME.equals(t) ) {
				String user = factory.userName();
				if( user != null ) {
					ret.setData(t, user.toCharArray());
				}
			} else if( UserAuthenticationData.PASSWORD.equals(t) ) {
				String pw = factory.passwordValue();
				if( pw != null && !pw.isEmpty() ) {
					try {
						ret.setData(t, factory.resolveSecret("password", pw));
					} catch (IOException e) {
						// VFS's interface can't throw: the login fails without a password, and the log says why
						factory.logError("Can't resolve the password for " + factory.getURL() + ": " + e.getMessage());
					}
				}
			}
		}
		return ret;
	}
}

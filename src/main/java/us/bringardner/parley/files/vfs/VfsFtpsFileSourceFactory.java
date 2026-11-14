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
 * FTP over implicit TLS through VFS's ftps provider (type id {@value #FACTORY_ID}): TLS from the
 * first byte, on port 990 unless another is given, as parley-files-ftp's ftps. Add
 * {@code commons-net:commons-net} to use it.
 * <p>
 * The settings are vfsftp's without {@code secure}; the data connections are protected (PROT P)
 * and the server's certificate checked as for vfsftp with TLS ({@link VfsTrustManager}).
 */
public class VfsFtpsFileSourceFactory extends VfsFtpFileSourceFactory {

	private static final long serialVersionUID = 1L;

	public static final String FACTORY_ID = TYPE_ID_PREFIX + "ftps";

	@Override
	public String getTypeId() {
		return FACTORY_ID;
	}

	/** Always: a vfsftps connection is TLS from the start, whatever secure says. */
	@Override
	protected boolean implicitTls() {
		return true;
	}
}

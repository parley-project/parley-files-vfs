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
import java.net.URI;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

import us.bringardner.parley.files.test.AbstractTestClass;

/**
 * The shared FileSource suite against vfshdfs, on the cluster given with
 * -Dparley.test.hdfs=hdfs://namenode:8020/a/dir (the tests work under that folder, which must be
 * writable by Hadoop's user); skipped when none is given (docs/DESIGN.md, 18).
 */
public class TestVfsHdfs extends AbstractTestClass {

	@BeforeAll
	public static void setUpBeforeAll() throws IOException {
		String c = VfsHdfsFactoryTest.cluster();
		Assumptions.assumeTrue(c != null, "no cluster given (-Dparley.test.hdfs=hdfs://namenode:8020/dir)");
		localTestFileDirPath = testFilesDir(TestVfsHdfs.class);
		localCacheDirPath = scratchDir("TestVfsHdfs");
		remoteTestFileDirPath = URI.create(c).getPath().replaceAll("/+$", "") + "/TestFiles";
		VfsHdfsFileSourceFactory f = VfsHdfsFactoryTest.forCluster(c);
		if( !f.connect() ) {
			throw new IOException("can't connect to " + c);
		}
		factory = f;
	}
}

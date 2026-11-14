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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.core.SecureBaseObject;

/** VfsTrustManager's host name check (RFC 6125) and where it takes its trust from. */
public class VfsTrustManagerTest {

	private static Path dir;
	private static X509Certificate wildcard;
	private static X509Certificate cnOnly;

	@BeforeAll
	static void certificates() throws Exception {
		dir = Files.createTempDirectory("parley-files-vfs-tm");
		wildcard = VfsFtpTlsTest.certificate(VfsFtpTlsTest.keyStore(dir, "wildcard", "CN=ignored.test",
				"dns:*.example.com,dns:files.test,ip:10.0.0.1,ip:::1"));
		cnOnly = VfsFtpTlsTest.certificate(VfsFtpTlsTest.keyStore(dir, "cn", "OU=x, CN=host.test", null));
	}

	@AfterAll
	static void clean() throws Exception {
		if( dir != null ) {
			try (var files = Files.list(dir)) {
				for (Path f : (Iterable<Path>) files::iterator) {
					Files.deleteIfExists(f);
				}
			}
			Files.deleteIfExists(dir);
		}
	}

	@Test
	void namesAndWildcards() throws Exception {
		assertTrue(VfsTrustManager.matches(wildcard, "files.test"));
		assertTrue(VfsTrustManager.matches(wildcard, "FILES.test."), "case and a trailing dot");
		assertTrue(VfsTrustManager.matches(wildcard, "a.example.com"));
		assertFalse(VfsTrustManager.matches(wildcard, "example.com"), "* is one whole label");
		assertFalse(VfsTrustManager.matches(wildcard, "a.b.example.com"), "* is one label only");
		assertFalse(VfsTrustManager.matches(wildcard, "ignored.test"), "the CN isn't used when there are DNS names");
		assertFalse(VfsTrustManager.matches(wildcard, "other.test"));
	}

	@Test
	void addresses() throws Exception {
		assertTrue(VfsTrustManager.matches(wildcard, "10.0.0.1"));
		assertTrue(VfsTrustManager.matches(wildcard, "[::1]"));
		assertTrue(VfsTrustManager.matches(wildcard, "0:0:0:0:0:0:0:1"));
		assertFalse(VfsTrustManager.matches(wildcard, "10.0.0.2"));
	}

	@Test
	void theCommonNameWhenThereAreNoDnsNames() throws Exception {
		assertTrue(VfsTrustManager.matches(cnOnly, "host.test"));
		assertFalse(VfsTrustManager.matches(cnOnly, "x"));
	}

	/** The application's trust managers decide the chain (here: trust anything), the host name still checked. */
	@Test
	void theApplicationsTrustThenTheHost() throws Exception {
		TrustManager[] saved = SecureBaseObject.getDefaultTrustManagers();
		SecureBaseObject.setDefaultTrustManagers(new TrustManager[] { new X509TrustManager() {
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType) {
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) {
			}

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		} });
		try {
			new VfsTrustManager("files.test").checkServerTrusted(new X509Certificate[] { wildcard }, "RSA");
			assertThrows(CertificateException.class,
					() -> new VfsTrustManager("other.test").checkServerTrusted(new X509Certificate[] { wildcard }, "RSA"));
		} finally {
			SecureBaseObject.setDefaultTrustManagers(saved);
		}
		// the JVM's trust store doesn't know the test certificate
		assertThrows(CertificateException.class,
				() -> new VfsTrustManager("files.test").checkServerTrusted(new X509Certificate[] { wildcard }, "RSA"));
	}
}

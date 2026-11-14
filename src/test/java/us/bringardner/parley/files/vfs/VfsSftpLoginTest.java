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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.files.FileSource;

/**
 * Logging in to vfssftp without a password: with a key file (OpenSSH format), a pasted key (PEM,
 * PKCS#8) and an ssh-agent (auth Agent, as parley-files-sftp). Each logs in with a key alone: the
 * password setting is left out, or wrong, so a login that works was a key's.
 * <p>
 * The agent tests use the running agent (SSH_AUTH_SOCK, or the Windows OpenSSH agent) and let the
 * test server accept the keys it holds; they are skipped when there is no agent or it holds no key.
 */
public class VfsSftpLoginTest {

	private static VfsSftpTestServer server;
	/** Where the key files are written; deleted after the class. */
	private static Path keyDir;
	/** A key the server accepts, and one it doesn't. */
	private static KeyPair accepted;
	private static KeyPair refused;

	@BeforeAll
	static void start() throws IOException, GeneralSecurityException {
		keyDir = Files.createTempDirectory("vfs-sftp-keys");
		accepted = ecKey();
		refused = ecKey();
		server = VfsSftpTestServer.started();
		server.authorize(accepted.getPublic());
	}

	@AfterAll
	static void stop() throws IOException {
		try {
			if( server != null ) {
				server.stop();
			}
		} finally {
			if( keyDir != null ) {
				try (java.util.stream.Stream<Path> files = Files.list(keyDir)) {
					for (Path p : (Iterable<Path>) files::iterator) {
						Files.delete(p);
					}
				}
				Files.delete(keyDir);
			}
		}
	}

	private static KeyPair ecKey() throws GeneralSecurityException {
		KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
		g.initialize(new ECGenParameterSpec("secp256r1"));
		return g.generateKeyPair();
	}

	private static KeyPair rsaKey() throws GeneralSecurityException {
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		return g.generateKeyPair();
	}

	/** The key as ssh-keygen writes it today ("BEGIN OPENSSH PRIVATE KEY"), unencrypted. */
	private static String openSsh(KeyPair key) throws IOException, GeneralSecurityException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(key, "vfs-test", null, out);
		return out.toString(StandardCharsets.US_ASCII);
	}

	/**
	 * The key in PEM, PKCS#8 ("BEGIN PRIVATE KEY"), unencrypted. For RSA keys only: Java writes an
	 * EC key's PKCS#8 without its public key, which JSch needs (ssh-keygen's has it).
	 */
	private static String pem(KeyPair key) {
		return "-----BEGIN PRIVATE KEY-----\n"
				+ Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getPrivate().getEncoded())
				+ "\n-----END PRIVATE KEY-----\n";
	}

	private static Path keyFile(String name, String text) throws IOException {
		return Files.write(keyDir.resolve(name), text.getBytes(StandardCharsets.US_ASCII));
	}

	/** The server's settings with no password, logging in as AUTH. */
	private static Properties props(String auth) {
		Properties p = server.properties();
		p.setProperty(VfsSftpFileSourceFactory.PROP_PASSWORD, "");
		p.setProperty(VfsSftpFileSourceFactory.PROP_AUTH, auth);
		return p;
	}

	/** Connects with P, writes a file and reads it back, and disconnects. */
	private static void logsInAndWorks(Properties p, String name) throws IOException {
		VfsSftpFileSourceFactory f = new VfsSftpFileSourceFactory();
		f.setConnectionProperties(p);
		try {
			assertTrue(f.connect(), "didn't connect");
			FileSource file = f.createFileSource("/" + name);
			try (OutputStream out = file.getOutputStream()) {
				out.write(name.getBytes(StandardCharsets.UTF_8));
			}
			try (InputStream in = file.getInputStream()) {
				assertEquals(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
			assertTrue(file.delete());
		} finally {
			f.disConnect();
		}
	}

	/** Connecting with P fails; the factory is left disconnected. */
	private static IOException refused(Properties p) throws IOException {
		VfsSftpFileSourceFactory f = new VfsSftpFileSourceFactory();
		f.setConnectionProperties(p);
		try {
			IOException e = assertThrows(IOException.class, f::connect);
			assertFalse(f.isConnected());
			return e;
		} finally {
			f.disConnect();
		}
	}

	// ------------------------------------------------------------------ keys

	@Test
	void aKeyFileLogsIn() throws Exception {
		Properties p = props(VfsSftpFileSourceFactory.AUTH_KEY_FILE);
		p.setProperty(VfsSftpFileSourceFactory.PROP_PRIVATE_KEY_FILE_NAME, keyFile("ec-openssh", openSsh(accepted)).toString());
		logsInAndWorks(p, "key-file.txt");
	}

	@Test
	void aPastedKeyLogsIn() throws Exception {
		KeyPair rsa = rsaKey();
		server.authorize(rsa.getPublic());
		Properties p = props(VfsSftpFileSourceFactory.AUTH_KEY);
		p.setProperty(VfsSftpFileSourceFactory.PROP_PRIVATE_KEY, pem(rsa));
		logsInAndWorks(p, "pasted-key.txt");
	}

	@Test
	void aPemKeyFileLogsIn() throws Exception {
		KeyPair rsa = rsaKey();
		server.authorize(rsa.getPublic());
		Properties p = props(VfsSftpFileSourceFactory.AUTH_KEY_FILE);
		p.setProperty(VfsSftpFileSourceFactory.PROP_PRIVATE_KEY_FILE_NAME, keyFile("rsa-pem", pem(rsa)).toString());
		logsInAndWorks(p, "pem-key-file.txt");
	}

	/** A key login tries only the key: the right password, also set, isn't tried. */
	@Test
	void aKeyTheServerDoesntKnowIsRefusedAndNoPasswordIsTried() throws Exception {
		Properties p = props(VfsSftpFileSourceFactory.AUTH_KEY_FILE);
		p.setProperty(VfsSftpFileSourceFactory.PROP_PRIVATE_KEY_FILE_NAME, keyFile("refused", openSsh(refused)).toString());
		p.setProperty(VfsSftpFileSourceFactory.PROP_PASSWORD, VfsSftpTestServer.PASSWORD);
		refused(p);
	}

	@Test
	void aMissingKeyFileSaysWhichFile() throws IOException {
		Properties p = props(VfsSftpFileSourceFactory.AUTH_KEY_FILE);
		String missing = keyDir.resolve("not-there").toString();
		p.setProperty(VfsSftpFileSourceFactory.PROP_PRIVATE_KEY_FILE_NAME, missing);
		IOException e = refused(p);
		assertTrue(String.valueOf(e.getMessage()).contains("not-there"), e.getMessage());
	}

	// ------------------------------------------------------------------ ssh-agent

	@Test
	void agentIsAChoiceAndRoundTrips() {
		VfsSftpFileSourceFactory f = new VfsSftpFileSourceFactory();
		ConnectionSetting auth = f.getConnectionSettings().stream()
				.filter(s -> s.key().equals(VfsSftpFileSourceFactory.PROP_AUTH)).findFirst().orElseThrow();
		assertEquals(List.of("Password", "Private Key File", "Private Key", "Agent"), auth.choices());

		Properties p = new Properties();
		p.setProperty(VfsSftpFileSourceFactory.PROP_AUTH, "agent");
		f.setConnectionProperties(p);
		assertEquals(VfsSftpFileSourceFactory.AUTH_AGENT, f.getAuth());
		assertEquals(VfsSftpFileSourceFactory.AUTH_AGENT, f.getConnectProperties().getProperty(VfsSftpFileSourceFactory.PROP_AUTH));
	}

	/** The keys the running ssh-agent holds, as SSH public key blobs; skips the test if there are none. */
	private static List<byte[]> agentKeys() {
		com.jcraft.jsch.AgentConnector c;
		try {
			c = VfsSftpFileSourceFactory.Agent.connector();
		} catch (IOException e) {
			assumeTrue(false, "no ssh-agent here: " + e.getMessage());
			return List.of();
		}
		Vector<com.jcraft.jsch.Identity> ids = new com.jcraft.jsch.AgentIdentityRepository(c).getIdentities();
		assumeTrue(!ids.isEmpty(), "the ssh-agent holds no keys");
		List<byte[]> ret = new java.util.ArrayList<>();
		for (com.jcraft.jsch.Identity id : ids) {
			ret.add(id.getPublicKeyBlob());
		}
		return ret;
	}

	/** Logs in with the agent's keys alone: the password set is wrong, and no key is given. */
	@Test
	void theAgentLogsIn() throws IOException {
		for (byte[] key : agentKeys()) {
			server.authorize(key);
		}
		Properties p = props(VfsSftpFileSourceFactory.AUTH_AGENT);
		p.setProperty(VfsSftpFileSourceFactory.PROP_PASSWORD, "not-the-password");
		logsInAndWorks(p, "agent.txt");
	}

	@Test
	void withNoAgentRunningAgentLoginSaysSo() throws IOException {
		assumeTrue(System.getenv("SSH_AUTH_SOCK") == null && !System.getProperty("os.name", "").startsWith("Windows"),
				"an ssh-agent is set (SSH_AUTH_SOCK), or Windows");
		IOException e = refused(props(VfsSftpFileSourceFactory.AUTH_AGENT));
		assertTrue(e.getMessage().contains("SSH_AUTH_SOCK"), e.getMessage());
	}
}

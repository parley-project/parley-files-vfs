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

import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

import us.bringardner.parley.core.SecureBaseObject;
import us.bringardner.parley.core.util.AddressMatcher;

/**
 * The server certificate check for VFS's ftps, as Parley's own FTP client makes it: the chain is
 * trusted by {@link SecureBaseObject#getDefaultTrustManagers()} when an application has set them
 * (parley-files-viewer's certificate dialog does), else by the JVM's trust store; and the
 * certificate must be for the host the factory connects to.
 * <p>
 * VFS's own default (commons-net's validating trust manager) only checks that the certificate is
 * in date, so it accepts any self-signed certificate for any host, and commons-net checks no host
 * name unless asked, which VFS can't do (docs/DESIGN.md, 10.3).
 * <p>
 * The host name is checked against the host the factory was given, not the one each socket names:
 * an FTP data connection goes to the address the server's PASV reply gives, and must present the
 * same server's certificate.
 */
final class VfsTrustManager extends X509ExtendedTrustManager {

	private final String host;
	private final X509TrustManager chain;

	/**
	 * @param host the host the certificate must be for: a name, or an address literal; null to
	 *        check the chain only (vfshttp, whose client checks the host of each connection itself,
	 *        a redirect's too: docs/DESIGN.md, 16.1)
	 * @throws GeneralSecurityException if the JVM's trust store can't be loaded
	 */
	VfsTrustManager(String host) throws GeneralSecurityException {
		this.host = host == null ? null : normalize(host);
		this.chain = chainChecker();
	}

	/** The application's trust managers' first X509 one, else the JVM's. */
	private static X509TrustManager chainChecker() throws GeneralSecurityException {
		TrustManager[] set = SecureBaseObject.getDefaultTrustManagers();
		X509TrustManager ret = first(set);
		if( ret != null ) {
			return ret;
		}
		TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		f.init((KeyStore) null);
		ret = first(f.getTrustManagers());
		if( ret == null ) {
			throw new GeneralSecurityException("The JVM has no X509 trust manager");
		}
		return ret;
	}

	private static X509TrustManager first(TrustManager[] tms) {
		if( tms != null ) {
			for (TrustManager tm : tms) {
				if( tm instanceof X509TrustManager ) {
					return (X509TrustManager) tm;
				}
			}
		}
		return null;
	}

	private static String normalize(String host) {
		String h = host == null ? "" : host.trim();
		if( h.startsWith("[") && h.endsWith("]") ) {
			h = h.substring(1, h.length() - 1);
		}
		if( h.endsWith(".") ) {
			h = h.substring(0, h.length() - 1);
		}
		return h.toLowerCase(Locale.ROOT);
	}

	// ------------------------------------------------------------------ server side: what VFS calls

	@Override
	public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
		chain.checkServerTrusted(certs, authType);
		checkHost(certs);
	}

	@Override
	public void checkServerTrusted(X509Certificate[] certs, String authType, Socket socket) throws CertificateException {
		if( chain instanceof X509ExtendedTrustManager ) {
			((X509ExtendedTrustManager) chain).checkServerTrusted(certs, authType, socket);
		} else {
			chain.checkServerTrusted(certs, authType);
		}
		checkHost(certs);
	}

	@Override
	public void checkServerTrusted(X509Certificate[] certs, String authType, SSLEngine engine) throws CertificateException {
		if( chain instanceof X509ExtendedTrustManager ) {
			((X509ExtendedTrustManager) chain).checkServerTrusted(certs, authType, engine);
		} else {
			chain.checkServerTrusted(certs, authType);
		}
		checkHost(certs);
	}

	// ------------------------------------------------------------------ client side: never used here

	@Override
	public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {
		throw new CertificateException("A client's certificate isn't checked here");
	}

	@Override
	public void checkClientTrusted(X509Certificate[] certs, String authType, Socket socket) throws CertificateException {
		checkClientTrusted(certs, authType);
	}

	@Override
	public void checkClientTrusted(X509Certificate[] certs, String authType, SSLEngine engine) throws CertificateException {
		checkClientTrusted(certs, authType);
	}

	@Override
	public X509Certificate[] getAcceptedIssuers() {
		return chain.getAcceptedIssuers();
	}

	// ------------------------------------------------------------------ the host name (RFC 6125)

	private void checkHost(X509Certificate[] certs) throws CertificateException {
		if( certs == null || certs.length == 0 ) {
			throw new CertificateException("The server sent no certificate");
		}
		if( host == null ) {
			// the chain only: the caller checks the host
			return;
		}
		if( !matches(certs[0], host) ) {
			throw new CertificateException("The server's certificate isn't for " + host + " (it names "
					+ names(certs[0]) + ")");
		}
	}

	/**
	 * Whether CERT is for HOST: an address literal against the certificate's IP addresses; a
	 * name against its DNS names (a "*" standing for the whole first label only), or its subject's
	 * common name when it has no DNS names, as the JDK's HTTPS check does.
	 */
	static boolean matches(X509Certificate cert, String host) throws CertificateException {
		String h = normalize(host);
		if( h.isEmpty() ) {
			return false;
		}
		if( AddressMatcher.isAddressLiteral(h) ) {
			InetAddress want = address(h);
			for (String ip : alternativeNames(cert, 7)) {
				if( want != null && want.equals(address(ip)) ) {
					return true;
				}
			}
			return false;
		}
		List<String> dns = alternativeNames(cert, 2);
		if( dns.isEmpty() ) {
			String cn = commonName(cert);
			return cn != null && nameMatches(normalize(cn), h);
		}
		for (String name : dns) {
			if( nameMatches(normalize(name), h) ) {
				return true;
			}
		}
		return false;
	}

	/** An exact name, or "*.rest" matching one label in front of rest (rest having at least two labels). */
	private static boolean nameMatches(String pattern, String host) {
		if( pattern.equals(host) ) {
			return true;
		}
		if( !pattern.startsWith("*.") ) {
			return false;
		}
		String rest = pattern.substring(1); // ".example.com"
		if( rest.indexOf('.', 1) < 0 || rest.indexOf('*') >= 0 ) {
			return false;
		}
		int dot = host.indexOf('.');
		return dot > 0 && host.substring(dot).equals(rest);
	}

	private static InetAddress address(String literal) {
		try {
			// a literal: no lookup is made
			return AddressMatcher.isAddressLiteral(literal) ? InetAddress.getByName(literal) : null;
		} catch (UnknownHostException e) {
			return null;
		}
	}

	private static List<String> alternativeNames(X509Certificate cert, int type) throws CertificateParsingException {
		List<String> ret = new ArrayList<>();
		Collection<List<?>> all = cert.getSubjectAlternativeNames();
		if( all != null ) {
			for (List<?> entry : all) {
				if( entry.size() >= 2 && entry.get(0) instanceof Integer && (Integer) entry.get(0) == type
						&& entry.get(1) instanceof String ) {
					ret.add((String) entry.get(1));
				}
			}
		}
		return ret;
	}

	private static String commonName(X509Certificate cert) {
		try {
			String ret = null;
			// getRdns() runs from the least specific to the most specific: the last CN is the one
			for (Rdn rdn : new LdapName(cert.getSubjectX500Principal().getName()).getRdns()) {
				if( "CN".equalsIgnoreCase(rdn.getType()) ) {
					ret = String.valueOf(rdn.getValue());
				}
			}
			return ret;
		} catch (InvalidNameException e) {
			return null;
		}
	}

	private static String names(X509Certificate cert) {
		try {
			List<String> ret = new ArrayList<>(alternativeNames(cert, 2));
			ret.addAll(alternativeNames(cert, 7));
			if( ret.isEmpty() ) {
				String cn = commonName(cert);
				return cn == null ? "no host" : cn;
			}
			return String.join(", ", ret);
		} catch (CertificateParsingException e) {
			return "names that can't be read";
		}
	}
}

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

import org.apache.commons.logging.Log;

import us.bringardner.parley.core.BaseObject;

/**
 * VFS's own logging (a commons-logging {@code Log}, which a VFS manager hands its providers) sent
 * through the factory, so it goes where Parley's logging goes (BaseObject, then System.Logger) and
 * the factory's log level applies to it. Trace is logged as debug, fatal as error.
 */
final class VfsLog implements Log {

	private final BaseObject owner;

	VfsLog(BaseObject owner) {
		this.owner = owner;
	}

	private static String text(Object message) {
		return String.valueOf(message);
	}

	@Override
	public void debug(Object message) {
		if( owner.isDebugEnabled() ) {
			owner.logDebug(text(message));
		}
	}

	@Override
	public void debug(Object message, Throwable t) {
		if( owner.isDebugEnabled() ) {
			owner.logDebug(text(message), t);
		}
	}

	@Override
	public void error(Object message) {
		owner.logError(text(message));
	}

	@Override
	public void error(Object message, Throwable t) {
		owner.logError(text(message), t);
	}

	@Override
	public void fatal(Object message) {
		owner.logError(text(message));
	}

	@Override
	public void fatal(Object message, Throwable t) {
		owner.logError(text(message), t);
	}

	@Override
	public void info(Object message) {
		if( owner.isInfoEnabled() ) {
			owner.logInfo(text(message));
		}
	}

	@Override
	public void info(Object message, Throwable t) {
		if( owner.isInfoEnabled() ) {
			owner.logInfo(text(message), t);
		}
	}

	@Override
	public boolean isDebugEnabled() {
		return owner.isDebugEnabled();
	}

	@Override
	public boolean isErrorEnabled() {
		return owner.isErrorEnabled();
	}

	@Override
	public boolean isFatalEnabled() {
		return owner.isErrorEnabled();
	}

	@Override
	public boolean isInfoEnabled() {
		return owner.isInfoEnabled();
	}

	@Override
	public boolean isTraceEnabled() {
		return owner.isDebugEnabled();
	}

	@Override
	public boolean isWarnEnabled() {
		return owner.isWarnEnabled();
	}

	@Override
	public void trace(Object message) {
		debug(message);
	}

	@Override
	public void trace(Object message, Throwable t) {
		debug(message, t);
	}

	@Override
	public void warn(Object message) {
		if( owner.isWarnEnabled() ) {
			owner.logWarn(text(message));
		}
	}

	@Override
	public void warn(Object message, Throwable t) {
		if( owner.isWarnEnabled() ) {
			owner.logWarn(text(message), t);
		}
	}
}

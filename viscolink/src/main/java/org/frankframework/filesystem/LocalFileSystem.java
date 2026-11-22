/*
   Copyright 2019-2026 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
// ============================================================================
// TEMPORARY CLASSPATH OVERRIDE -- NOT VISCOSIETY CODE
//
// This is org.frankframework.filesystem.LocalFileSystem exactly as built into
// the consumed Frank!Framework release 10.3.0 (the file in
// frankframework-filesystem:10.3.0:sources, sha256
// efd00d5154fad99bc64bfa62bf6c770b14fd6e9cb2b19252394d575dd39c1be5, pinned by
// the check-localfilesystem-upstream execution in the pom), plus the patch of
// frankframework/frankframework#12075, plus VISCO_OVERRIDE. Only Lombok is
// written out here, because viscolink does not run Lombok's annotation
// processor.
//
// WHY. root is documented as the boundary that all folders and files are
// relative to, but upstream's toFile does not enforce it: an absolute filename
// skips the root (the !result.isAbsolute() guard), and a relative one is
// resolved against the root with no check on the result, so .. escapes.
// FileSystemActor.determineFilename falls back to the input message when
// neither the filename attribute nor the filename parameter is set (and
// determineDestination does the same for move, copy and rename), so a
// message-derived filename reaches arbitrary paths. Proved end to end on an
// unmodified frankframework/frankframework:10.3.0 over an ApiListener into a
// LocalFileSystemPipe with root="/opt/frank/sandbox": reading /etc/passwd,
// creating files in /tmp, overwriting a file outside the root with attacker
// content and deleting a file outside the root all returned HTTP 200.
// Reported as frankframework/frankframework#12074.
//
// HOW THIS DIFFERS FROM #12075. The upstream PR is opt-in: it ships
// application.security.filesystem.allowPathsOutsideRoot as true in the
// framework's AppConstants.properties, so upstream behaviour is unchanged.
// ViscoLink wants the safe behaviour by default, so this copy keeps the same
// property name (the code stays comparable, and removal is a pure deletion)
// but relies on the Java field default being the SAFE value: the
// AppConstants.getBoolean below defaults to false and nothing in this WAR
// ships a permissive entry. viscolink's DeploymentSpecifics.properties sets
// it to false explicitly -- that entry is load-bearing, see the comment
// there. A -D system property still wins, by design: PropertyLoader checks
// system properties before the properties files, which is the documented
// escape hatch.
//
// Deliberately NOT a Frank!Doc attribute: there is no public setter, so the
// framework's shipped FrankConfig XSD stays correct and a configuration
// cannot turn containment off. The package-private setter is for tests only.
//
// Symlinks BELOW the root are deliberately not followed; see
// toRealPathIfPossible.
//
// It lives in the WAR because WEB-INF/classes takes precedence over
// WEB-INF/lib by servlet spec (staged there by the stage-localfilesystem-
// override execution in the pom, because archiveClasses=true would otherwise
// bundle target/classes as a JAR in WEB-INF/lib, where it would NOT shadow
// frankframework-filesystem.jar). WEB-INF/classes precedence only exists in a
// WAR, so com.viscosiety.filesystem.LocalFileSystemOverrideCheck refuses to
// start when the loaded class has no VISCO_OVERRIDE field.
//
// MUST track the consumed version, never frankframework master. On every F!F
// version bump the pom's hash gate fails; re-derive this file from that
// version's sources and re-apply the patch. DELETE it (and the pom execution,
// the startup check, the DeploymentSpecifics entry and the VISCO-OVERRIDES.md
// row) once ViscoLink is on an F!F release containing #12075 -- noting that
// release ships the property as true, so the DeploymentSpecifics entry must
// stay even then, or go to false upstream first.
// ============================================================================
package org.frankframework.filesystem;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

import org.frankframework.core.DestinationType;
import org.frankframework.doc.Default;
import org.frankframework.stream.Message;
import org.frankframework.stream.PathMessage;
import org.frankframework.util.AppConstants;
import org.frankframework.util.CloseUtils;
import org.frankframework.util.MessageUtils;
import org.frankframework.util.StreamUtil;
import org.frankframework.util.XmlEncodingUtils;

/**
 * {@link IWritableFileSystem FileSystem} representation of the local filesystem.
 *
 * @author Gerrit van Brakel
 *
 */
@DestinationType(DestinationType.Type.FILE_SYSTEM)
public class LocalFileSystem extends AbstractFileSystem<Path> implements IWritableFileSystem<Path>, ISupportsCustomFileAttributes<Path> {
	/** Written out because viscolink does not run Lombok's annotation processor; upstream has {@code @Log4j2}. */
	private static final Logger log = LogManager.getLogger(LocalFileSystem.class);

	/**
	 * Marker the {@link com.viscosiety.filesystem.LocalFileSystemOverrideCheck} startup assertion reads reflectively:
	 * proof that this class, and not the one in frankframework-filesystem.jar, is the one that got loaded.
	 */
	public static final String VISCO_OVERRIDE = "containment-v1";

	public static final String ORIGINAL_LAST_MODIFIED_TIME_ATTRIBUTE = "originalLastModifiedTime";

	private boolean createRootFolder = false;

	private static final String FILE_DELIMITER = "/";

	/**
	 * When true, filenames that resolve outside {@link #setRoot(String)} are accepted. Upstream ships this as true in
	 * the framework's AppConstants.properties for backwards compatibility; ViscoLink does not, so the field default
	 * below (false) applies and viscolink's DeploymentSpecifics.properties states it explicitly. This is the only
	 * intentional behavioural difference from frankframework/frankframework#12075.
	 */
	public static final String ALLOW_PATHS_OUTSIDE_ROOT_KEY = "application.security.filesystem.allowPathsOutsideRoot";

	private boolean allowPathsOutsideRoot = AppConstants.getInstance().getBoolean(ALLOW_PATHS_OUTSIDE_ROOT_KEY, false);

	/**
	 * Path to the folder that serves as the root of this virtual filesystem. All specifications of folders or files are relative to this root.
	 * When the root is left unspecified, absolute paths to files and folders can be used
	 */
	@SuppressWarnings({ "NullAway.Init", "java:S2637" })
	private String root;

	@Override
	public void configure() {
		if (!allowPathsOutsideRoot && StringUtils.isEmpty(getRoot())) {
			log.warn("[{}] is disabled but no root is configured, so no containment can be enforced", ALLOW_PATHS_OUTSIDE_ROOT_KEY);
		}
	}

	@Override
	public void open() throws FileSystemException {
		if (createRootFolder && root != null && !Files.exists(Paths.get(root))) {
			createFolder(root);
		}

		super.open();
	}

	@Override
	public @NonNull Path toFile(@Nullable String filename) throws FileSystemException {
		return toFile(null, filename);
	}

	@Override
	public @NonNull Path toFile(@Nullable String folder, @Nullable String filename) throws FileSystemException {
		if (filename == null) {
			filename="";
		}

		if (StringUtils.isNotEmpty(folder) && !(filename.contains(FILE_DELIMITER) || filename.contains("\\"))) {
			filename = folder + FILE_DELIMITER + filename;
		}

		Path result = Paths.get(filename);

		if (StringUtils.isNotEmpty(getRoot()) && !result.isAbsolute()) {
			// Filename will always contain the folder, if it's passed as parameter or within the filename.
			// We need to make sure that the root is not prepended if it's already part of the filename.
			// Use normalized Path-based comparison to avoid false positives (for example root x/y vs filename x/y2/file).
			if (!result.normalize().startsWith(Paths.get(getRoot()).normalize())) {
				return verifyWithinRoot(filename, Paths.get(getRoot()).resolve(filename));
			}

			filename = result.toString();
		}

		if (StringUtils.isEmpty(filename)) {
			throw new FileSystemException("no filesystem-root, file or folder specified");
		}

		return verifyWithinRoot(filename, result);
	}

	/**
	 * Checks that {@code resolved} stays within the configured root. Neither an absolute filename nor a parent
	 * traversal is contained by {@link #setRoot(String)} on its own, so the check has to be made on the result.
	 * Only logs while {@value #ALLOW_PATHS_OUTSIDE_ROOT_KEY} is true, to keep existing configurations working.
	 */
	private @NonNull Path verifyWithinRoot(String filename, @NonNull Path resolved) throws FileSystemException {
		if (StringUtils.isEmpty(getRoot())) {
			return resolved;
		}

		Path rootPath = Paths.get(getRoot()).toAbsolutePath().normalize();
		if (resolved.toAbsolutePath().normalize().startsWith(rootPath)) {
			return resolved;
		}

		// A lexical comparison reports a false positive when root and result express the same location through
		// different symlinks, for example /var versus /private/var. Only pay for the syscalls in that case.
		if (toRealPathIfPossible(resolved).startsWith(toRealPathIfPossible(rootPath))) {
			return resolved;
		}

		if (!allowPathsOutsideRoot) {
			throw new FileSystemException("path [" + filename + "] is outside the root [" + getRoot() + "] of this filesystem");
		}

		log.warn("path [{}] resolves outside the root [{}] of this filesystem, and will be rejected once [{}] is false", filename, getRoot(), ALLOW_PATHS_OUTSIDE_ROOT_KEY);
		return resolved;
	}

	/**
	 * Resolves symlinks as far as the path exists, so that a root and a filename reached through different symlinks
	 * still compare equal. The part that does not exist yet, which is the normal case for a file about to be written,
	 * is appended afterwards. Symlinks below the root are therefore not followed, by design.
	 */
	private static @NonNull Path toRealPathIfPossible(@NonNull Path path) {
		Path existing = path.toAbsolutePath().normalize();
		Path remainder = Paths.get("");

		while (existing != null && !Files.exists(existing)) {
			Path name = existing.getFileName();
			if (name != null) {
				remainder = name.resolve(remainder);
			}
			existing = existing.getParent();
		}

		if (existing == null) {
			return path.toAbsolutePath().normalize();
		}

		try {
			return existing.toRealPath().resolve(remainder).normalize();
		} catch (IOException e) {
			log.debug("cannot determine the real path of [{}]", existing, e);
			return path.toAbsolutePath().normalize();
		}
	}

	@NonNull
	@Override
	public DirectoryStream<Path> list(Path folder, @NonNull TypeFilter filter) throws FileSystemException {
		if (folder == null) {
			folder = toFile(null);
		}
		if (!folderExists(folder)) {
			throw new FolderNotFoundException("Cannot list files in ["+folder+"], no such folder found");
		}
		DirectoryStream.Filter<Path> directoryStreamFilter = switch (filter) {
			case FILES_ONLY -> file -> !Files.isDirectory(file);
			case FOLDERS_ONLY -> Files::isDirectory;
			case FILES_AND_FOLDERS -> file -> true;
		};
		DirectoryStream<Path> paths = null;
		try {
			paths = Files.newDirectoryStream(folder, directoryStreamFilter);
			return paths;
		} catch (IOException e) {
			CloseUtils.closeSilently(paths);
			throw new FileSystemException("Cannot list files in ["+folder+"]", e);
		}
	}

	@Override
	public boolean exists(Path f) {
		return Files.exists(f);
	}

	@Override
	public void createFile(@NonNull Path file, @Nullable InputStream content) throws IOException {
		try (OutputStream out = Files.newOutputStream(file)) {
			StreamUtil.streamToStream(content, out);
		}
	}

	@Override
	public void createFile(@NonNull Path file, @Nullable InputStream contents, @NonNull Map<String, String> customFileAttributes) {
		try {
			// Create the file first
			createFile(file, contents);

			// Then add the custom attributes
			addCustomFileAttributes(file, customFileAttributes);

		} catch (Exception e) {
			throw ExceptionUtils.asRuntimeException(e);
		}
	}

	private static void addCustomFileAttributes(@NonNull Path file, @NonNull Map<String, String> customFileAttributes) throws IOException {
		if (customFileAttributes.isEmpty()) {
			return;
		}
		if (!Files.getFileStore(file).supportsFileAttributeView(UserDefinedFileAttributeView.class)) {
			log.warn("Custom file attributes not supported by FileStore [{}]", Files.getFileStore(file));
			return;
		}

		// We need to restore the original file modified time, because we use it to append to the filename in some configurations of the DirectoryListener.
		FileTime lastModifiedTime = Files.getLastModifiedTime(file);
		UserDefinedFileAttributeView userDefinedAttributes = getUserDefinedAttributes(file);

		// Cannot do this with Java Stream operation because of the IOException thrown by write operation
		for (Map.Entry<String, String> entry : customFileAttributes.entrySet()) {
			userDefinedAttributes.write(entry.getKey(), Charset.defaultCharset().encode(entry.getValue()));
		}

		// Writing the attributes changes the lastModifiedTime, but FS listener functionality depends on that value.
		// So we store it in another attribute and try to restore the original value.
		if (!hasAttribute(userDefinedAttributes, ORIGINAL_LAST_MODIFIED_TIME_ATTRIBUTE)) {
			userDefinedAttributes.write(ORIGINAL_LAST_MODIFIED_TIME_ATTRIBUTE, Charset.defaultCharset().encode(String.valueOf(lastModifiedTime.toMillis())));
		}
		try {
			Files.setLastModifiedTime(file, lastModifiedTime);
		} catch (IOException e) {
			// If this fails, ignore. Can be a Docker related permission-issue that shouldn't affect functionality.
			log.trace(() -> "Cannot set last modified time for [%s]".formatted(file), e);
		}
	}

	private static UserDefinedFileAttributeView getUserDefinedAttributes(@NonNull Path file) {
		return Files.getFileAttributeView(file, UserDefinedFileAttributeView.class);
	}

	private static boolean hasAttribute(@NonNull UserDefinedFileAttributeView userDefinedAttributes, String attributeName) throws IOException {
		return userDefinedAttributes.list().stream().anyMatch(attributeName::equals);
	}

	private @NonNull String readAttribute(@NonNull Path file, @NonNull UserDefinedFileAttributeView userDefinedAttributes, @NonNull String attributeName) throws IOException {
		ByteBuffer bfr = ByteBuffer.allocate(userDefinedAttributes.size(attributeName));
		userDefinedAttributes.read(attributeName, bfr);
		return readAttributeValue(file, attributeName, bfr);
	}

	@Override
	public void appendFile(@NonNull Path f, @Nullable InputStream content) throws IOException {
		try (OutputStream out = Files.newOutputStream(f, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
			StreamUtil.streamToStream(content, out);
		}
	}

	@Override
	public Message readFile(Path f, String charset) throws FileSystemException {
		if (!Files.exists(f)) {
			throw new org.frankframework.filesystem.FileNotFoundException("Cannot find file ["+f+"].");
		}
		return new PathMessage(f, FileSystemUtils.getContext(this, f, charset));
	}

	@Override
	public void deleteFile(Path f) throws FileSystemException {
		try {
			Files.delete(f);
		} catch (FileNotFoundException e) {
			throw new org.frankframework.filesystem.FileNotFoundException("Cannot find file [" + f + "] to delete", e);
		} catch (IOException e) {
			throw new FileSystemException("Could not delete file [" + getCanonicalNameOrErrorMessage(f) + "]: " + e.getMessage());
		}
	}

	@Override
	public boolean isFolder(Path f) {
		return Files.isDirectory(f);
	}

	@Override
	public boolean folderExists(String folder) throws FileSystemException {
		return isFolder(toFile(folder));
	}

	public boolean folderExists(Path folder) {
		return isFolder(folder);
	}

	@Override
	public void createFolder(String folder) throws FileSystemException {
		if (folderExists(folder)) {
			throw new FolderAlreadyExistsException("Create folder for [" + folder + "] has failed. Directory already exists.");
		}
		try {
			Files.createDirectories(toFile(folder, null));
		} catch (IOException e) {
			throw new FileSystemException("Cannot create folder ["+ folder +"]", e);
		}
	}

	@Override
	public void removeFolder(String folder, boolean removeNonEmptyFolder) throws FileSystemException {
		if (!folderExists(folder)) {
			throw new FolderNotFoundException("Remove directory for [" + folder + "] has failed. Directory does not exist.");
		}
		try {
			if(removeNonEmptyFolder) {
				try (Stream<Path> directoryStream = Files.walk(toFile(folder))) {
					directoryStream.sorted(Comparator.reverseOrder())
					.map(Path::toFile)
					.forEach(File::delete);
				}
			} else {
				Files.delete(toFile(folder));
			}
		} catch (IOException e) {
			throw new FileSystemException("Cannot remove folder ["+ folder +"]",e);
		}
	}

	@Override
	public Path renameFile(@NonNull Path source, @NonNull Path destination) throws FileSystemException {
		return renameFile(source, destination, Map.of());
	}

	@Override
	public Path renameFile(@NonNull Path source, @NonNull Path destination, @NonNull Map<String, String> customFileAttributes) throws FileSystemException {
		try {
			Path result = Files.move(source, destination);
			addCustomFileAttributes(result, customFileAttributes);
			return result;
		} catch (FileNotFoundException e) {
			throw new org.frankframework.filesystem.FileNotFoundException(e);
		} catch (IOException e) {
			throw new FileSystemException("Cannot rename file ["+ source +"] to ["+ destination +"]", e);
		}
	}

	@Override
	public Path moveFile(Path f, String destinationFolder, boolean createFolder) throws FileSystemException {
		return moveFile(f, destinationFolder, createFolder, Map.of());
	}

	@Override
	public Path moveFile(@NonNull Path f, @NonNull String destinationFolder, boolean createFolder, @NonNull Map<String, String> customFileAttributes) throws FileSystemException {
		if(createFolder && !folderExists(destinationFolder)) {
			try {
				Files.createDirectories(toFile(destinationFolder));
			} catch (IOException e) {
				throw new FileSystemException("Cannot create folder ["+ destinationFolder +"]", e);
			}
		}
		try {
			Path result = Files.move(f, toFile(destinationFolder, getName(f)));
			addCustomFileAttributes(result, customFileAttributes);
			return result;
		} catch (FileNotFoundException e) {
			throw new org.frankframework.filesystem.FileNotFoundException(e);
		} catch (IOException e) {
			throw new FileSystemException("Cannot move file ["+ f +"] to ["+ destinationFolder+"]", e);
		}
	}

	@Override
	public Path copyFile(Path f, String destinationFolder, boolean createFolder) throws FileSystemException {
		return copyFile(f, destinationFolder, createFolder, Map.of());
	}

	@Override
	public Path copyFile(@NonNull Path f, @NonNull String destinationFolder, boolean createFolder, @NonNull Map<String, String> customFileAttributes) throws FileSystemException {
		if(createFolder && !folderExists(destinationFolder)) {
			try {
				Files.createDirectories(toFile(destinationFolder));
			} catch (IOException e) {
				throw new FileSystemException("Cannot create folder ["+ destinationFolder +"]", e);
			}
		}
		Path target = toFile(destinationFolder, getName(f));
		try {
			Files.copy(f, target);
			addCustomFileAttributes(target, customFileAttributes);
		} catch (FileNotFoundException e) {
			throw new org.frankframework.filesystem.FileNotFoundException(e);
		} catch (IOException e) {
			throw new FileSystemException("Cannot copy file ["+ f +"] to ["+ destinationFolder+"]", e);
		}
		return target;
	}

	@Override
	public long getFileSize(Path f) throws FileSystemException {
		try {
			return Files.size(f);
		} catch (FileNotFoundException e) {
			throw new org.frankframework.filesystem.FileNotFoundException(e);
		} catch (IOException e) {
			throw new FileSystemException(e);
		}
	}

	@Override
	public @Nullable String getName(@NonNull Path f) {
		if(f.getFileName() != null) {
			return f.getFileName().toString();
		}
		return null;
	}

	@Override
	public String getParentFolder(@NonNull Path f) throws FileSystemException {
		return getCanonicalName(f.getParent());
	}

	@Override
	public @NonNull String getCanonicalName(@NonNull Path f) throws FileSystemException {
		try {
			return f.toFile().getCanonicalPath();
		} catch (IOException e) {
			throw new FileSystemException(e);
		}
	}

	@Override
	public Date getModificationTime(Path f) throws FileSystemException {
		try {
			// If the original LastModifiedTime is stored in a user-defined attribute, return that value
			if (Files.getFileStore(f).supportsFileAttributeView(UserDefinedFileAttributeView.class)) {
				UserDefinedFileAttributeView userDefinedAttributes = getUserDefinedAttributes(f);
				if (hasAttribute(userDefinedAttributes, ORIGINAL_LAST_MODIFIED_TIME_ATTRIBUTE)) {
					String lastModifiedTime = readAttribute(f, userDefinedAttributes, ORIGINAL_LAST_MODIFIED_TIME_ATTRIBUTE);
					return new Date(Long.parseLong(lastModifiedTime));
				}
			}

			return new Date(Files.getLastModifiedTime(f).toMillis());
		} catch (IOException e) {
			throw new FileSystemException(e);
		}
	}

	@Override
	public Map<String, Object> getAdditionalFileProperties(Path file) throws FileSystemException {
		try {
			if (!Files.exists(file)) return Map.of();
			UserDefinedFileAttributeView userDefinedAttributes = getUserDefinedAttributes(file);
			List<String> attributeNames = userDefinedAttributes.list();
			if (attributeNames == null || attributeNames.isEmpty()) return Map.of();
			String attrSpec = "user:" + String.join(",", attributeNames);
			Map<String, Object> result = new LinkedHashMap<>();
			Files.readAttributes(file, attrSpec)
					.forEach((name, value) -> result.put(name, readAttributeValue(file, name, value)));
			return result;
		} catch (UnsupportedOperationException e) {
			return Map.of();
		} catch (IOException e) {
			throw new FileSystemException(e);
		}
	}

	private @NonNull String readAttributeValue(@NonNull Path file, @NonNull String name, @NonNull Object attributeValue) {
		try {
			if (attributeValue instanceof byte[] bytes) {
				return bytesToString(bytes);
			} else if (attributeValue instanceof ByteBuffer buffer) {
				return bytesToString(buffer.array());
			} else {
				return attributeValue.toString();
			}
		} catch (Exception e) {
			log.warn(() -> "Error parsing file attribute [%s] on file [%s]:".formatted(name, file), e);
			return "<attribute not readable>";
		}
	}

	@NonNull
	private static String bytesToString(byte @NonNull [] bytes) {
		CharsetDetector detector = new CharsetDetector();
		detector.setText(bytes);
		CharsetMatch match = detector.detect();
		Charset charset;
		if (match.getConfidence() >= MessageUtils.CHARSET_CONFIDENCE_LEVEL) {
			charset = Charset.forName(match.getName());
		} else {
			// If confidence was low, use system default charset
			charset = Charset.defaultCharset();
		}
		return Objects.requireNonNull(XmlEncodingUtils.replaceNonValidXmlCharacters(new String(bytes, charset), '?', false, true));
	}

	@Nullable
	@Override
	public String getCustomFileAttribute(@NonNull Path file, @NonNull String name) throws FileSystemException {
		Map<String, Object> additionalFileProperties = getAdditionalFileProperties(file);
		return Objects.toString(additionalFileProperties.get(name), null);
	}

	@Override
	public String getPhysicalDestinationName() {
		return "root ["+(getRoot()==null?"":getRoot())+"]";
	}

	/**
	 * Whether the LocalFileSystem tries to create the root folder if it doesn't exist yet.
	 * @param createRootFolder
	 */
	@Default("false")
	public void setCreateRootFolder(boolean createRootFolder) {
		this.createRootFolder = createRootFolder;
	}

	/** Lombok's {@code @Getter} on {@code createRootFolder}, written out. */
	public boolean isCreateRootFolder() {
		return createRootFolder;
	}

	/** Lombok's {@code @Getter} on {@code root}, written out. */
	public String getRoot() {
		return root;
	}

	/**
	 * Path to the folder that serves as the root of this virtual filesystem. All specifications of folders or files are relative to this root.
	 * When the root is left unspecified, absolute paths to files and folders can be used.
	 * <p>
	 * Lombok's {@code @Setter} on {@code root}, written out. The Frank!Doc attribute itself comes from the framework's
	 * own XSD, which this copy does not change.
	 */
	public void setRoot(String root) {
		this.root = root;
	}

	/** Lombok's {@code @Setter(AccessLevel.PACKAGE)} on {@code allowPathsOutsideRoot}, written out. For tests. */
	void setAllowPathsOutsideRoot(boolean allowPathsOutsideRoot) {
		this.allowPathsOutsideRoot = allowPathsOutsideRoot;
	}
}

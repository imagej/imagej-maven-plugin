/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2012 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, and Max Planck
 * Institute of Molecular Cell Biology and Genetics.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 * The views and conclusions contained in the software and documentation are
 * those of the authors and should not be interpreted as representing official
 * policies, either expressed or implied, of any organization.
 * #L%
 */

package imagej.maven;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactCollector;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.tree.DependencyNode;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilder;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilderException;
import org.apache.maven.shared.dependency.tree.traversal.CollectingDependencyNodeVisitor;
import org.codehaus.plexus.interpolation.EnvarBasedValueSource;
import org.codehaus.plexus.interpolation.ObjectBasedValueSource;
import org.codehaus.plexus.interpolation.PrefixAwareRecursionInterceptor;
import org.codehaus.plexus.interpolation.PrefixedValueSourceWrapper;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.RecursionInterceptor;
import org.codehaus.plexus.interpolation.RegexBasedInterpolator;
import org.codehaus.plexus.util.FileUtils;

/**
 * Copies .jar artifacts and their dependencies into an ImageJ.app/ directory
 * structure.
 * 
 * @author Johannes Schindelin
 * @goal copy-jars
 * @phase install
 */
public class CopyJarsMojo extends AbstractMojo {

	/**
	 * The name of the property pointing to the ImageJ.app/ directory.
	 * <p>
	 * If no property of that name exists, or if it is not a directory, no .jar
	 * files are copied.
	 * </p>
	 * 
	 * @parameter default-value="imagej.app.directory"
	 */
	private String imagejDirectoryProperty;

	/**
	 * Whether to delete other versions when copying the files.
	 * <p>
	 * When copying a file and its dependencies to an ImageJ.app/ directory and
	 * there are other versions of the same file, we can warn or delete those
	 * other versions.
	 * </p>
	 * 
	 * @parameter default-value="false" expression="${delete.other.versions}"
	 */
	private boolean deleteOtherVersions;

	/**
	 * @parameter expression="${project}"
	 * @required
	 * @readonly
	 */
	private MavenProject project;

	/**
	 * Session
	 * 
	 * @parameter expression="${session}
	 */
	private MavenSession session;

	/**
	 * List of Remote Repositories used by the resolver
	 * 
	 * @parameter expression="${project.remoteArtifactRepositories}"
	 * @readonly
	 * @required
	 */
	protected List<ArtifactRepository> remoteRepositories;

	/**
	 * Location of the local repository.
	 * 
	 * @parameter expression="${localRepository}"
	 * @readonly
	 * @required
	 */
	protected ArtifactRepository localRepository;

	/**
	 * @component role="org.apache.maven.artifact.metadata.ArtifactMetadataSource"
	 *            hint="maven"
	 * @required
	 * @readonly
	 */
	private ArtifactMetadataSource artifactMetadataSource;

	/**
	 * @component role="org.apache.maven.artifact.resolver.ArtifactCollector"
	 * @required
	 * @readonly
	 */
	private ArtifactCollector artifactCollector;

	/**
	 * @component
	 * @required
	 * @readonly
	 */
	private DependencyTreeBuilder treeBuilder;

	/**
	 * Used to look up Artifacts in the remote repository.
	 * 
	 * @component
	 * @required
	 * @readonly
	 */
	protected ArtifactFactory artifactFactory;

	/**
	 * Used to look up Artifacts in the remote repository.
	 * 
	 * @component
	 * @required
	 * @readonly
	 */
	protected ArtifactResolver artifactResolver;

	private File imagejDirectory;

	@SuppressWarnings("unchecked")
	public void execute() throws MojoExecutionException {
		if (imagejDirectoryProperty == null) {
			getLog()
				.info(
					"No property name for the ImageJ.app/ directory location was specified; Skipping");
			return;
		}
		String path = System.getProperty(imagejDirectoryProperty);
		if (path == null) path =
			project.getProperties().getProperty(imagejDirectoryProperty);
		if (path == null) {
			if (hasIJ1Dependency()) getLog().info(
				"Property '" + imagejDirectoryProperty + "' unset; Skipping copy-jars");
			return;
		}
		final String interpolated = interpolate(path);
		imagejDirectory = new File(interpolated);
		if (!imagejDirectory.isDirectory()) {
			getLog().warn(
				"'" + imagejDirectory + "'" +
					(interpolated.equals(path) ? "" : " (" + path + ")") +
					" is not an ImageJ.app/ directory; Skipping copy-jars");
			return;
		}

		try {
			ArtifactFilter artifactFilter =
				new ScopeArtifactFilter(Artifact.SCOPE_COMPILE);
			DependencyNode rootNode =
				treeBuilder.buildDependencyTree(project, localRepository,
					artifactFactory, artifactMetadataSource, artifactFilter,
					artifactCollector);

			CollectingDependencyNodeVisitor visitor =
				new CollectingDependencyNodeVisitor();
			rootNode.accept(visitor);

			for (final DependencyNode dependencyNode : (List<DependencyNode>) visitor
				.getNodes())
			{
				if (dependencyNode.getState() == DependencyNode.INCLUDED) {
					final Artifact artifact = dependencyNode.getArtifact();
					final String scope = artifact.getScope();
					if (scope != null && !scope.equals(Artifact.SCOPE_COMPILE) &&
						!scope.equals(Artifact.SCOPE_RUNTIME)) continue;
					try {
						installArtifact(artifact, false);
					}
					catch (Exception e) {
						throw new MojoExecutionException("Could not copy " + artifact +
							" to " + imagejDirectory, e);
					}
				}
			}
		}
		catch (DependencyTreeBuilderException e) {
			throw new MojoExecutionException("Could not get the dependencies for " +
				project.getArtifactId(), e);
		}
	}

	private boolean hasIJ1Dependency() {
		@SuppressWarnings("unchecked")
		final List<Dependency> dependencies = project.getDependencies();
		for (final Dependency dependency : dependencies) {
			final String artifactId = dependency.getArtifactId();
			if ("ij".equals(artifactId) || "imagej".equals(artifactId)) return true;
		}
		return false;
	}

	private String interpolate(final String original)
		throws MojoExecutionException
	{
		if (original == null || original.indexOf("${") < 0) return original;
		try {
			RegexBasedInterpolator interpolator = new RegexBasedInterpolator();

			interpolator.addValueSource(new EnvarBasedValueSource());
			interpolator.addValueSource(new PropertiesBasedValueSource(System
				.getProperties()));

			List<String> synonymPrefixes = new ArrayList<String>();
			synonymPrefixes.add("project.");
			synonymPrefixes.add("pom.");

			PrefixedValueSourceWrapper modelWrapper =
				new PrefixedValueSourceWrapper(new ObjectBasedValueSource(project
					.getModel()), synonymPrefixes, true);
			interpolator.addValueSource(modelWrapper);

			PrefixedValueSourceWrapper pomPropertyWrapper =
				new PrefixedValueSourceWrapper(new PropertiesBasedValueSource(project
					.getModel().getProperties()), synonymPrefixes, true);
			interpolator.addValueSource(pomPropertyWrapper);

			interpolator.addValueSource(new PropertiesBasedValueSource(session
				.getExecutionProperties()));

			RecursionInterceptor recursionInterceptor =
				new PrefixAwareRecursionInterceptor(synonymPrefixes, true);
			return interpolator.interpolate(original, recursionInterceptor);
		}
		catch (Exception e) {
			throw new MojoExecutionException("Could not interpolate '" + original +
				"'", e);
		}
	}

	private void installArtifact(final Artifact artifact, boolean force)
		throws ArtifactResolutionException, ArtifactNotFoundException, IOException
	{
		artifactResolver.resolve(artifact, remoteRepositories, localRepository);

		if (!"jar".equals(artifact.getType())) return;

		final File source = artifact.getFile();
		final File targetDirectory;
		if ("loci".equals(artifact.getGroupId()) &&
			(source.getName().startsWith("scifio-4.4.") || source.getName()
				.startsWith("jai_imageio-4.4.")))
		{
			targetDirectory = new File(imagejDirectory, "jars/bio-formats");
		}
		else {
			targetDirectory =
				new File(imagejDirectory, isIJ1Plugin(source) ? "plugins" : "jars");
		}
		final String fileName =
			"Fiji_Updater".equals(artifact.getArtifactId()) ? artifact
				.getArtifactId() +
				".jar" : source.getName();
		final File target = new File(targetDirectory, fileName);

		if (!force && target.exists() &&
			target.lastModified() > source.lastModified())
		{
			getLog().info("Dependency " + fileName + " is already there; skipping");
		}
		else {
			getLog().info("Copying " + fileName + " to " + targetDirectory);
			FileUtils.copyFile(source, target);
		}

		final Collection<File> otherVersions = getEncroachingVersions(target);
		if (otherVersions != null && !otherVersions.isEmpty()) {
			for (final File file : otherVersions) {
				if (!deleteOtherVersions) {
					getLog().warn(
						"Possibly incompatible version exists: " + file.getName());
				}
				else if (file.delete()) {
					getLog().info("Deleted overridden " + file.getName());
				}
				else {
					getLog().warn("Could not delete overridden " + file.getName());
				}
			}
		}

	}

	private static boolean isIJ1Plugin(final File file) {
		final String name = file.getName();
		if (name.indexOf('_') < 0 || !file.exists()) return false;
		if (file.isDirectory()) return new File(file,
			"src/main/resources/plugins.config").exists();
		if (name.endsWith(".jar")) try {
			final JarFile jar = new JarFile(file);
			for (JarEntry entry : Collections.list(jar.entries()))
				if (entry.getName().equals("plugins.config")) {
					jar.close();
					return true;
				}
			jar.close();
		}
		catch (Throwable t) {
			// obviously not a plugin...
		}
		return false;
	}

	private final static Pattern versionPattern = Pattern.compile("(.+?)"
		+ "(-\\d+(\\.\\d+|\\d{7})+[a-z]?\\d?(-[A-Za-z0-9.]+?|\\.GA)*?)?"
		+ "((-(swing|swt|sources|javadoc))?(\\.jar(-[a-z]*)?))");
	private final static int PREFIX_INDEX = 1;
	private final static int SUFFIX_INDEX = 5;

	private static Collection<File> getEncroachingVersions(final File file) {
		final Matcher matcher = versionPattern.matcher(file.getName());
		if (!matcher.matches()) return null;

		final String prefix = matcher.group(PREFIX_INDEX);
		final String suffix = matcher.group(SUFFIX_INDEX);
		final File parent = file.getParentFile();
		final File directory =
			parent != null ? parent : file.getAbsoluteFile().getParentFile();
		if (directory == null) return null;
		final File[] candidates = directory.listFiles(new FilenameFilter() {

			public boolean accept(File dir, String name) {
				if (!name.startsWith(prefix)) return false;
				final Matcher matcher = versionPattern.matcher(name);
				return matcher.matches() &&
					prefix.equals(matcher.group(PREFIX_INDEX)) &&
					suffix.equals(matcher.group(SUFFIX_INDEX));
			}
		});
		if (candidates == null || candidates.length == (file.exists() ? 1 : 0)) return null;

		final Collection<File> result = new ArrayList<File>();
		for (final File candidate : candidates) {
			if (!candidate.equals(file)) result.add(candidate);
		}
		return result;
	}
}

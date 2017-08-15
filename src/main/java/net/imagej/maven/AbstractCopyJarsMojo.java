/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2012 - 2016 Board of Regents of the University of
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
 * #L%
 */

package net.imagej.maven;

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
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.interpolation.EnvarBasedValueSource;
import org.codehaus.plexus.interpolation.ObjectBasedValueSource;
import org.codehaus.plexus.interpolation.PrefixAwareRecursionInterceptor;
import org.codehaus.plexus.interpolation.PrefixedValueSourceWrapper;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.RecursionInterceptor;
import org.codehaus.plexus.interpolation.RegexBasedInterpolator;
import org.codehaus.plexus.util.FileUtils;

/**
 * Base class for mojos to copy .jar artifacts and their dependencies into an
 * ImageJ.app/ directory structure.
 * 
 * @author Johannes Schindelin
 */
public abstract class AbstractCopyJarsMojo extends AbstractMojo {

	public static final String imagejDirectoryProperty = "imagej.app.directory";
	public static final String imagejSubdirectoryProperty = "imagej.app.subdirectory";
	public static final String deleteOtherVersionsProperty = "delete.other.versions";

	protected boolean hasIJ1Dependency(final MavenProject project) {
		@SuppressWarnings("unchecked")
		final List<Dependency> dependencies = project.getDependencies();
		for (final Dependency dependency : dependencies) {
			final String artifactId = dependency.getArtifactId();
			if ("ij".equals(artifactId) || "imagej".equals(artifactId)) return true;
		}
		return false;
	}

	protected String interpolate(final String original,
		final MavenProject project, final MavenSession session)
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

			if (project != null) {
				PrefixedValueSourceWrapper modelWrapper =
					new PrefixedValueSourceWrapper(new ObjectBasedValueSource(project
						.getModel()), synonymPrefixes, true);
				interpolator.addValueSource(modelWrapper);

				PrefixedValueSourceWrapper pomPropertyWrapper =
					new PrefixedValueSourceWrapper(new PropertiesBasedValueSource(project
						.getModel().getProperties()), synonymPrefixes, true);
				interpolator.addValueSource(pomPropertyWrapper);
			}

			if (session != null) {
				interpolator.addValueSource(new PropertiesBasedValueSource(session
					.getExecutionProperties()));
			}

			RecursionInterceptor recursionInterceptor =
				new PrefixAwareRecursionInterceptor(synonymPrefixes, true);
			return interpolator.interpolate(original, recursionInterceptor);
		}
		catch (Exception e) {
			throw new MojoExecutionException("Could not interpolate '" + original +
				"'", e);
		}
	}

	protected void installArtifact(final Artifact artifact,
		final File imagejDirectory, final boolean force,
		final boolean deleteOtherVersions) throws IOException
	{
		installArtifact(artifact, imagejDirectory, "", force, deleteOtherVersions);
	}

	protected void installArtifact(final Artifact artifact,
		final File imagejDirectory, final String subdirectory, final boolean force,
		final boolean deleteOtherVersions) throws IOException
	{
		if (!"jar".equals(artifact.getType())) return;

		final File source = artifact.getFile();
		final File targetDirectory;

		if (subdirectory != null && !subdirectory.equals("")) {
			targetDirectory = new File(imagejDirectory, subdirectory);
		} else if (isIJ1Plugin(source)) {
			targetDirectory = new File(imagejDirectory, "plugins");
		}
		else if ("ome".equals(artifact.getGroupId()) ||
			("loci".equals(artifact.getGroupId()) && (source.getName().startsWith(
				"scifio-4.4.") || source.getName().startsWith("jai_imageio-4.4."))))
		{
			targetDirectory = new File(imagejDirectory, "jars/bio-formats");
		}
		else {
			targetDirectory = new File(imagejDirectory, "jars");
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
		+ "((-(swing|swt|sources|javadoc|native|linux-x86|linux-x86_64|macosx-x86_64|windows-x86|windows-x86_64|android-arm|android-x86))?(\\.jar(-[a-z]*)?))");
	private final static int PREFIX_INDEX = 1;
	private final static int SUFFIX_INDEX = 5;

	private static Collection<File> getEncroachingVersions(final File file) {
		// TODO reuse FileUtils.stripFilenameVersion logic
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

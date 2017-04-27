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
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryFactory;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.artifact.resolver.AbstractArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

/**
 * Downloads .jar artifacts and their dependencies into an ImageJ.app/ directory
 * structure.
 * 
 * @author Johannes Schindelin
 */
public class InstallArtifactMojo extends AbstractCopyJarsMojo {

	/**
	 * The name of the property pointing to the ImageJ.app/ directory.
	 * <p>
	 * If no property of that name exists, or if it is not a directory, no .jar
	 * files are copied.
	 * </p>
	 * 
	 * @parameter property="imagej.app.directory"
	 * @required
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
	 * @parameter property="delete.other.versions"
	 */
	private String deleteOtherVersionsProperty;

	/**
	 * Comma-separated list of Remote Repositories used by the resolver
	 * <p>
	 * Example:
	 * {@code -DremoteRepositories=imagej::default::http://maven.imagej.net/content/groups/public}
	 * .
	 * </p>
	 * 
	 * @parameter property="remoteRepositories"
	 * @readonly
	 */
	private String remoteRepositories;

	/**
	 * Location of the local repository.
	 * 
	 * @parameter property="localRepository"
	 * @readonly
	 */
	private ArtifactRepository localRepository;

	/**
	 * Used to look up Artifacts in the remote repository.
	 * 
	 * @component
	 * @required
	 * @readonly
	 */
	private ArtifactFactory artifactFactory;

	/**
	 * Used to look up Artifacts in the remote repository.
	 * 
	 * @component
	 * @required
	 * @readonly
	 */
	private ArtifactResolver artifactResolver;

	/**
	 * @component
	 * @readonly
	 */
	private ArtifactRepositoryFactory artifactRepositoryFactory;

	/**
	 * Map that contains the layouts.
	 * 
	 * @component role=
	 *            "org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout"
	 */
	private Map<String, ArtifactRepositoryLayout> repositoryLayouts;

	/**
	 * @component
	 * @readonly
	 */
	private ArtifactMetadataSource source;

	/**
	 * The groupId of the artifact to download. Ignored if {@link #artifact} is
	 * used.
	 * 
	 * @parameter property="groupId"
	 */
	private String groupId;

	/**
	 * The artifactId of the artifact to download. Ignored if {@link #artifact} is
	 * used.
	 * 
	 * @parameter property="artifactId"
	 */
	private String artifactId;

	/**
	 * The version of the artifact to download. Ignored if {@link #artifact} is
	 * used.
	 * 
	 * @parameter property="version"
	 */
	private String version;

	/**
	 * A string of the form groupId:artifactId:version[:packaging][:classifier].
	 * 
	 * @parameter property="artifact"
	 */
	private String artifact;

	/**
	 * Whether to force overwriting files.
	 * 
	 * @parameter property="force"
	 */
	private boolean force;

	/**
	 * @parameter property="project.remoteArtifactRepositories"
	 * @required
	 * @readonly
	 */
	private List<ArtifactRepository> projectRemoteRepositories;

	public void execute() throws MojoExecutionException, MojoFailureException {
		if (imagejDirectoryProperty == null) {
			imagejDirectoryProperty = System.getProperty("imagejDirectoryProperty");
		}
		if (imagejDirectoryProperty == null) {
			throw new MojoExecutionException(
				"The 'imagej.app.directory' property is unset!");
		}
		File imagejDirectory = new File(imagejDirectoryProperty);
		if (!imagejDirectory.isDirectory() && !imagejDirectory.mkdirs()) {
			throw new MojoFailureException("Could not make directory: " +
				imagejDirectory);
		}

		if (deleteOtherVersionsProperty == null) {
			deleteOtherVersionsProperty = System.getProperty("deleteOtherVersionsProperty");
		}
		final boolean deleteOtherVersions =
			deleteOtherVersionsProperty != null &&
				deleteOtherVersionsProperty.matches("(?i)true||\\+?[1-9][0-9]*");

		if (artifactId == null && artifact == null) {
			throw new MojoFailureException(
				"No artifact specified (e.g. by -Dartifact=net.imagej:ij:1.48p)");
		}
		if (artifact != null) {
			String[] tokens = artifact.split(":");
			if (tokens.length != 3) {
				throw new MojoFailureException(
					"Invalid artifact, you must specify groupId:artifactId:version " +
						artifact);
			}
			groupId = tokens[0];
			artifactId = tokens[1];
			version = tokens[2];
		}

		final Artifact toDownload =
			artifactFactory.createBuildArtifact(groupId, artifactId, version, "jar");
		final Artifact pomToDownload =
			artifactFactory.createBuildArtifact(groupId, artifactId, version, "pom");

		final ArtifactRepositoryLayout layout =
			(ArtifactRepositoryLayout) repositoryLayouts.get("default");

		if (layout == null) {
			throw new MojoFailureException("default", "Invalid repository layout",
				"Invalid repository layout: default");
		}

		final ArtifactRepositoryPolicy policy =
			new ArtifactRepositoryPolicy(true,
				ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS,
				ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN);

		final List<ArtifactRepository> remoteRepositoriesList =
			new ArrayList<ArtifactRepository>();

		if (projectRemoteRepositories != null) {
			remoteRepositoriesList.addAll(projectRemoteRepositories);
		}

		final ArtifactRepository imagej =
			artifactRepositoryFactory
				.createArtifactRepository("imagej",
					"http://maven.imagej.net/content/groups/public", layout, policy,
					policy);
		remoteRepositoriesList.add(imagej);

		if (remoteRepositories != null) {
			String[] repos = remoteRepositories.split(",");
			int count = 1;
			for (String repo : repos) {
				final String id = "dummy" + count++;
				final ArtifactRepository repository =
					artifactRepositoryFactory.createArtifactRepository(id, repo, layout,
						policy, policy);

				remoteRepositoriesList.add(repository);
			}
		}

		try {
			final Set<Artifact> set = new HashSet<Artifact>();
			set.add(toDownload);
			final ArtifactResolutionResult result =
				artifactResolver.resolveTransitively(set, pomToDownload,
					remoteRepositoriesList, localRepository, source);
			@SuppressWarnings("unchecked")
			final Set<Artifact> artifacts = (Set<Artifact>) result.getArtifacts();
			for (final Artifact artifact : artifacts)
				try {
					final String scope = artifact == null ? null : artifact.getScope();
					if (artifact.isOptional()) continue;
					if (Artifact.SCOPE_SYSTEM.equals(scope) ||
						Artifact.SCOPE_PROVIDED.equals(scope) ||
						Artifact.SCOPE_TEST.equals(scope)) continue;
					installArtifact(artifact, imagejDirectory, force,
						deleteOtherVersions, artifactResolver, remoteRepositoriesList,
						localRepository);
				}
				catch (IOException e) {
					throw new MojoExecutionException("Couldn't download artifact " +
						artifact + ": " + e.getMessage(), e);
				}
		}
		catch (AbstractArtifactResolutionException e) {
			throw new MojoExecutionException("Couldn't download artifact: " +
				e.getMessage(), e);
		}
	}
}
